package com.local.kakaoautosender;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Single commercial entitlement service. No KAS1 verifier or offline bypass exists. */
final class LicenseManager {
  private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final java.util.concurrent.locks.ReentrantLock REQUEST_LOCK =
      new java.util.concurrent.locks.ReentrantLock();
  private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
  private static final long FOREGROUND_DEADLINE_MS = 12_000L;
  private static final int CONNECT_TIMEOUT_MS = 3_000;
  private static final int READ_TIMEOUT_MS = 3_500;

  interface Callback {
    void done(Verification v);
  }

  static final class Verification {
    final boolean valid;
    final String message, licenseId, state;
    final long expiresAtSeconds;

    Verification(boolean valid, String state, String message, String id, long expiry) {
      this.valid = valid;
      this.state = state;
      this.message = message;
      licenseId = id;
      expiresAtSeconds = expiry;
    }

    String expiryLabel() {
      if (!valid) return message;
      if (expiresAtSeconds <= 0) return "영구 라이선스";
      return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.KOREA)
              .format(new Date(expiresAtSeconds * 1000L))
          + " 만료";
    }
  }

  private LicenseManager() {}

  private static SharedPreferences p(Context c) {
    return c.getSharedPreferences("entitlement_v2", Context.MODE_PRIVATE);
  }

  private static int boot(Context c) {
    return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
  }

  static Verification verifyStored(Context c) {
    SharedPreferences p = p(c);
    String state = p.getString("state", "INVALID");
    long expiry = p.getLong("expiry", 0), elapsed = SystemClock.elapsedRealtime();
    boolean valid =
        EntitlementPolicy.usable(
            state,
            elapsed,
            p.getLong("validated_elapsed", -1),
            boot(c),
            p.getInt("validated_boot", -2),
            p.getLong("server_time", 0),
            expiry,
            p.getLong("grace", 600));
    boolean leaseEnded = "ACTIVE".equals(state) && !valid;
    if (leaseEnded)
      state =
          expiry > 0
                  && p.getInt("validated_boot", -2) == boot(c)
                  && p.getLong("server_time", 0)
                          + Math.max(0, elapsed - p.getLong("validated_elapsed", elapsed)) / 1000
                      >= expiry
              ? "EXPIRED"
              : "NETWORK";
    return new Verification(
        valid,
        state,
        valid
            ? "라이선스 정상"
            : leaseEnded
                ? EntitlementPolicy.message(state)
                : p.getString("message", EntitlementPolicy.message(state)),
        p.getString("license_id", ""),
        expiry);
  }

  static boolean isUsable(Context c) {
    return verifyStored(c).valid;
  }

  static String shortStatus(Context c) {
    Verification v = verifyStored(c);
    if (!v.valid) return v.message;
    long remaining = v.expiresAtSeconds - estimatedServerTime(c);
    if (v.expiresAtSeconds > 0 && remaining < 86400) return "라이선스가 24시간 이내 만료됩니다.";
    if (v.expiresAtSeconds > 0 && remaining < 7 * 86400)
      return "라이선스가 " + Math.max(1, (remaining + 86399) / 86400) + "일 후 만료됩니다.";
    return "정상 · " + v.expiryLabel();
  }

  static long heartbeatMillis(Context c) {
    return Math.max(30, Math.min(300, p(c).getLong("heartbeat", 60))) * 1000L;
  }

  static long estimatedServerTime(Context c) {
    SharedPreferences p = p(c);
    if (p.getInt("time_boot", -2) == boot(c) && p.getLong("time_elapsed", -1) >= 0)
      return p.getLong("time_server", 0)
          + Math.max(0, SystemClock.elapsedRealtime() - p.getLong("time_elapsed", 0)) / 1000;
    return System.currentTimeMillis() / 1000;
  }

  static void checkAsync(Context context, Callback callback) {
    Context app = context.getApplicationContext();
    NETWORK.execute(
        () -> {
          Verification v = validate(app);
          MAIN.post(() -> callback.done(v));
        });
  }

  static void activateAsync(Context context, String key, Callback callback) {
    Context app = context.getApplicationContext();
    NETWORK.execute(
        () -> {
          Verification v;
          DEADLINE.set(SystemClock.elapsedRealtime() + FOREGROUND_DEADLINE_MS);
          try {
            JSONObject b =
                body()
                    .put("key", key.trim().toUpperCase(java.util.Locale.ROOT))
                    .put("public_key", InstallIdentity.publicKey());
            JSONObject r = activateWithRecovery(app, b);
            if ("ACTIVE".equals(r.optString("state"))) {
              accept(app, r);
              v = verifyStored(app);
            } else {
              String state = r.optString("state", "INVALID");
              v = new Verification(false, state, EntitlementPolicy.message(state), "", 0);
            }
          } catch (Exception e) {
            v = activationNetworkFailure(app, e);
          } finally {
            DEADLINE.remove();
          }
          final Verification result = v;
          MAIN.post(() -> callback.done(result));
        });
  }

  /**
   * Makes one-time activation robust without turning the redeem key into a replayable credential.
   * If an activation response is lost, installation-key recovery is attempted first. Only when the
   * server confirms this installation has not claimed a license do we send the activation once more.
   */
  private static JSONObject activateWithRecovery(Context c, JSONObject body) throws Exception {
    try {
      JSONObject first = request(c, "/api/v1/activate", body, "");
      if ("ALREADY_USED".equals(first.optString("state"))) {
        JSONObject recovered = retryRecover(c, 2);
        if ("ACTIVE".equals(recovered.optString("state"))) return recovered;
      }
      return first;
    } catch (IOException uncertainActivation) {
      JSONObject recovered = null;
      try {
        recovered = retryRecover(c, 2);
        if ("ACTIVE".equals(recovered.optString("state"))) return recovered;
      } catch (Exception ignoredRecovery) {
        if (!canRetry()) throw uncertainActivation;
      }

      String recoveredState = recovered == null ? "" : recovered.optString("state", "");
      if (!recoveredState.isEmpty()
          && !"NOT_FOUND".equals(recoveredState)
          && !"INVALID".equals(recoveredState)) {
        return recovered;
      }
      if (!canRetry()) throw uncertainActivation;
      pauseBeforeRetry(0);

      JSONObject second = request(c, "/api/v1/activate", body, "");
      if ("ALREADY_USED".equals(second.optString("state"))) {
        JSONObject finalRecovery = retryRecover(c, 2);
        if ("ACTIVE".equals(finalRecovery.optString("state"))) return finalRecovery;
      }
      return second;
    }
  }

  private static Verification activationNetworkFailure(Context c, Exception error) {
    p(c).edit().putString("last_error", networkErrorCode(error)).apply();
    Verification cached = verifyStored(c);
    if (cached.valid) return cached;
    return new Verification(
        false,
        "NETWORK",
        EntitlementPolicy.message("NETWORK"),
        cached.licenseId,
        cached.expiresAtSeconds);
  }

  static Verification validate(Context c) {
    if (Looper.myLooper() == Looper.getMainLooper())
      throw new IllegalStateException("Network entitlement checks require worker thread");
    boolean acquired = false;
    try {
      acquired = REQUEST_LOCK.tryLock(750, java.util.concurrent.TimeUnit.MILLISECONDS);
      if (!acquired) return verifyStored(c);
      DEADLINE.set(SystemClock.elapsedRealtime() + FOREGROUND_DEADLINE_MS);
      return validateInternal(c);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return verifyStored(c);
    } finally {
      DEADLINE.remove();
      if (acquired) REQUEST_LOCK.unlock();
    }
  }

  private static Verification validateInternal(Context c) {
    if (Looper.myLooper() == Looper.getMainLooper())
      throw new IllegalStateException("Network entitlement checks require worker thread");
    try {
      String[] tokens = InstallIdentity.tokens(c);
      JSONObject r;
      if (tokens[0].isEmpty()) {
        r = retryRecover(c, 2);
      } else {
        r = retryRequest(c, "/api/v1/heartbeat", body(), tokens[0], 2);
        if ("ACCESS_EXPIRED".equals(r.optString("state"))) {
          try {
            r = request(c, "/api/v1/session/refresh", body(), tokens[1]);
          } catch (IOException refreshResponseLost) {
            // Refresh rotation may already have committed. Recover by installation key instead of
            // replaying an old refresh token.
            r = retryRecover(c, 2);
          }
        }
        if ("INVALID".equals(r.optString("state"))) r = retryRecover(c, 2);
      }
      String state = r.getString("state");
      if ("ACTIVE".equals(state)) {
        accept(c, r);
        return verifyStored(c);
      }
      if (isTemporary(state)) throw new IOException("temporary:" + state);
      lockout(c, state, r.optString("message", ""));
      return verifyStored(c);
    } catch (Exception e) {
      return networkFailure(c, e);
    }
  }

  private static Verification networkFailure(Context c, Exception error) {
    String detail = networkErrorCode(error);
    p(c).edit().putString("last_error", detail).apply();
    Verification cached = verifyStored(c);
    if (cached.valid) return cached;

    String storedState = p(c).getString("state", "INVALID");
    if ("ACTIVE".equals(storedState)) {
      // Fail closed without destroying the last authoritative ACTIVE state or encrypted session.
      // A later successful heartbeat/recover can restore access without forcing reactivation.
      DeliveryGate.stop(c);
      Prefs.setStatus(c, "라이선스 서버 연결 대기 · 자동전송 안전 중지");
      return new Verification(
          false,
          "NETWORK",
          EntitlementPolicy.message("NETWORK"),
          p(c).getString("license_id", ""),
          p(c).getLong("expiry", 0));
    }
    return cached;
  }

  private static String networkErrorCode(Exception e) {
    if (e == null) return "NETWORK";
    String message = e.getMessage();
    if (message == null || message.trim().isEmpty()) return "NETWORK";
    String clean = message.replace('\n', ' ').replace('\r', ' ').trim();
    if (clean.length() > 48) clean = clean.substring(0, 48);
    return "NETWORK:" + clean;
  }

  private static JSONObject retryRecover(Context c, int attempts) throws Exception {
    Exception last = null;
    for (int i = 0; i < attempts; i++) {
      try {
        return recover(c);
      } catch (Exception e) {
        last = e;
        if (!canRetry() || i + 1 >= attempts) throw e;
        pauseBeforeRetry(i);
      }
    }
    throw last == null ? new IOException("recover") : last;
  }

  private static JSONObject retryRequest(
      Context c, String path, JSONObject b, String token, int attempts) throws Exception {
    Exception last = null;
    for (int i = 0; i < attempts; i++) {
      try {
        return request(c, path, b, token);
      } catch (Exception e) {
        last = e;
        if (!canRetry() || i + 1 >= attempts) throw e;
        pauseBeforeRetry(i);
      }
    }
    throw last == null ? new IOException("request") : last;
  }

  private static boolean canRetry() {
    Long deadline = DEADLINE.get();
    return deadline == null || deadline - SystemClock.elapsedRealtime() > 1200L;
  }

  private static void pauseBeforeRetry(int attempt) throws InterruptedException {
    SystemClock.sleep(180L + attempt * 220L);
    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
  }

  private static JSONObject recover(Context c) throws Exception {
    return request(
        c, "/api/v1/session/recover", body().put("public_key", InstallIdentity.publicKey()), "");
  }

  private static JSONObject body() throws Exception {
    return new JSONObject().put("app_version", BuildConfig.VERSION_CODE);
  }

  private static boolean isTemporary(String s) {
    return "SERVER_ERROR".equals(s)
        || "RATE_LIMITED".equals(s)
        || "REPLAY".equals(s)
        || "INVALID_PROOF".equals(s);
  }

  private static JSONObject request(Context c, String path, JSONObject b, String token)
      throws Exception {
    return request(c, path, b, token, true);
  }

  private static JSONObject request(
      Context c, String path, JSONObject b, String token, boolean retryClock) throws Exception {
    String base = BuildConfig.API_BASE_URL;
    if (!base.startsWith("https://") || base.endsWith("/"))
      throw new IOException("endpoint");
    String raw = b.toString(), ts = Long.toString(estimatedServerTime(c));
    String nonce = UUID.randomUUID().toString().replace("-", "");
    String canonical =
        "KM1\nPOST\n"
            + path
            + "\n"
            + ts
            + "\n"
            + nonce
            + "\n"
            + InstallIdentity.sha(raw)
            + "\n"
            + InstallIdentity.sha(token);
    HttpsURLConnection conn = (HttpsURLConnection) new URL(base + path).openConnection();
    long remaining = DEADLINE.get() == null ? FOREGROUND_DEADLINE_MS
        : DEADLINE.get() - SystemClock.elapsedRealtime();
    if (remaining < 150) throw new IOException("deadline");
    int connectTimeout = (int) Math.max(150, Math.min(CONNECT_TIMEOUT_MS, remaining / 2));
    int readTimeout = (int) Math.max(150, Math.min(READ_TIMEOUT_MS, remaining - connectTimeout));
    conn.setConnectTimeout(connectTimeout);
    conn.setReadTimeout(readTimeout);
    conn.setInstanceFollowRedirects(false);
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setRequestProperty("Accept", "application/json");
    conn.setRequestProperty("X-Install-Time", ts);
    conn.setRequestProperty("X-Install-Nonce", nonce);
    conn.setRequestProperty("X-Install-Signature", InstallIdentity.sign(canonical));
    if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
    try {
      byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
      conn.setFixedLengthStreamingMode(bytes.length);
      try (java.io.OutputStream out = conn.getOutputStream()) {
        out.write(bytes);
      }
      int status = conn.getResponseCode();
      if (status >= 500) throw new IOException("server:" + status);
      if (status >= 300 && status < 400) throw new IOException("redirect");
      InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
      if (stream == null) throw new IOException("body");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (InputStream in = stream) {
        byte[] chunk = new byte[1024];
        int n;
        while ((n = in.read(chunk)) != -1) {
          if (out.size() + n > 16384) throw new IOException("body_size");
          out.write(chunk, 0, n);
        }
      }
      JSONObject r = new JSONObject(out.toString("UTF-8"));
      if (r.has("server_time"))
        p(c).edit()
            .putLong("time_server", r.getLong("server_time"))
            .putLong("time_elapsed", SystemClock.elapsedRealtime())
            .putInt("time_boot", boot(c))
            .commit();
      if (retryClock && "INVALID_PROOF".equals(r.optString("state")) && r.has("server_time")) {
        conn.disconnect();
        return request(c, path, b, token, false);
      }
      return r;
    } finally {
      conn.disconnect();
    }
  }

  private static void accept(Context c, JSONObject r) throws Exception {
    long expiry = r.isNull("expires_at") ? 0 : r.getLong("expires_at");
    if (r.has("access_token"))
      InstallIdentity.saveTokens(c, r.getString("access_token"), r.getString("refresh_token"));
    if (!p(c).edit()
        .putString("state", "ACTIVE")
        .putString("message", "라이선스 정상")
        .putString("license_id", r.getString("license_id"))
        .putLong("expiry", expiry)
        .putLong("server_time", r.getLong("server_time"))
        .putLong("validated_elapsed", SystemClock.elapsedRealtime())
        .putInt("validated_boot", boot(c))
        .putLong("grace", Math.max(0, Math.min(600, r.getLong("grace_seconds"))))
        .putLong("heartbeat", Math.max(30, Math.min(300, r.getLong("heartbeat_seconds"))))
        .putLong("latest_version", r.optLong("latest_version", BuildConfig.VERSION_CODE))
        .putString("download_url", r.optString("download_url", ""))
        .putString("last_error", "")
        .commit()) throw new IOException("storage");
    c.sendBroadcast(
        new Intent(KakaoNotificationListener.ACTION_SESSIONS_UPDATED)
            .setPackage(c.getPackageName()));
  }

  static void lockout(Context c, String state, String serverMessage) {
    String message = EntitlementPolicy.message(state);
    if ("MAINTENANCE".equals(state) && !serverMessage.isEmpty()) message += "\n" + serverMessage;
    synchronized (DeliveryGate.LOCK) {
      p(c).edit()
          .putString("state", state)
          .putString("message", message)
          .putString("last_error", state)
          .remove("validated_elapsed")
          .commit();
      DeliveryGate.stop(c);
    }
    // Clear access; retain encrypted installation refresh credential for renewal/resume.
    String[] tokens = InstallIdentity.tokens(c);
    try {
      InstallIdentity.saveTokens(c, "", tokens[1]);
    } catch (Exception ignored) {
      p(c).edit().remove("tokens").commit();
    }
    Prefs.setStatus(c, message);
    Context app = c.getApplicationContext();
    MAIN.post(
        () -> {
          if (app instanceof KakaoMacroApplication) ((KakaoMacroApplication) app).routeLockout();
        });
  }

  static void runAuthorized(Activity a, Runnable action) {
    checkAsync(
        a,
        v -> {
          if (a.isFinishing()) return;
          if (v.valid && isUsable(a)) action.run();
          else route(a);
        });
  }

  static void route(Activity a) {
    Intent i = new Intent(a, LicenseActivity.class);
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    a.startActivity(i);
    a.finish();
  }

  static String diagnostic(Context c) {
    SharedPreferences p = p(c);
    return "앱 버전: "
        + BuildConfig.VERSION_NAME
        + "\n지원 코드: "
        + p.getString("license_id", "미등록")
        + "\n상태: "
        + p.getString("state", "INVALID")
        + "\n마지막 서버 확인 (UTC epoch): "
        + p.getLong("server_time", 0)
        + "\n오류: "
        + p.getString("last_error", "");
  }

  static String updateNotice(Context c) {
    return p(c).getLong("latest_version", 0) > BuildConfig.VERSION_CODE
        ? "새 앱 버전이 있습니다. 판매자에게 업데이트를 문의하세요."
        : "";
  }
}
