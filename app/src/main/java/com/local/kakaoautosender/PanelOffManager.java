package com.local.kakaoautosender;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.github.muntashirakon.adb.AdbStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Explicit local ADB opt-in. All blocking IO stays off main; no shell command UI or remote host.
 */
final class PanelOffManager {
  private static final Handler main = new Handler(Looper.getMainLooper());
  private static final java.util.concurrent.ExecutorService setup =
      Executors.newSingleThreadExecutor();
  private static final ScheduledExecutorService io = Executors.newScheduledThreadPool(3);
  private static volatile LocalAdb adb;
  private static volatile boolean linked, busy;
  private static volatile String status = "무선 연결 필요 · 휴대폰에서 최초 페어링을 진행하세요.";
  private static volatile Session current;
  private static long generation;
  private static long restoreUntil;

  private static final class Session {
    final String owner, token = UUID.randomUUID().toString();
    final PanelLease lease = new PanelLease(token);
    final Context app;
    final long started = SystemClock.elapsedRealtime();
    volatile AdbStream stream;
    volatile OutputStream output;
    volatile boolean offRequested, closed;
    long lastLicenseCheck = SystemClock.elapsedRealtime();
    boolean checkingLicense;

    Session(Context c, String owner) {
      app = c.getApplicationContext();
      this.owner = owner;
    }
  }

  static String status() {
    return status;
  }

  static boolean connected() {
    return linked && !busy;
  }

  static boolean engaged() {
    return current != null;
  }

  static int port(String input) {
    if (input == null || !input.matches("[0-9]{1,5}")) return 0;
    try {
      int value = Integer.parseInt(input);
      return value > 0 && value <= 65535 ? value : 0;
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  static String quote(String s) {
    return "'" + s.replace("'", "'\"'\"'") + "'";
  }

  static boolean healthy(String owner) {
    Session s = current;
    return s != null
        && !s.closed
        && s.owner.equals(owner)
        && s.lease.allows(SystemClock.elapsedRealtime());
  }

  static boolean owns(String owner) {
    Session s = current;
    return s != null && s.owner.equals(owner);
  }

  static void connect(Context c, String portText, String code, boolean pairing) {
    int port = port(portText);
    if (Build.VERSION.SDK_INT < 30) {
      status = "이 방식은 Android 11 이상에서 사용할 수 있습니다.";
      return;
    }
    if (busy || engaged() || PhotoTourController.active() || PhotoKeyboardService.hasActiveLoop()) {
      status = "진행 중인 사진 작업 또는 연결 작업을 먼저 중지하세요.";
      return;
    }
    if (!DeliveryGate.allowed(c) || !PhotoKeyboardSession.unlocked(c)) {
      status = "라이선스와 화면 잠금 해제를 확인하세요.";
      return;
    }
    if (port == 0 || (pairing && (code == null || !code.matches("[0-9]{6}")))) {
      status = "포트 숫자와 6자리 페어링 코드를 확인하세요.";
      return;
    }
    busy = true;
    linked = false;
    long expected = ++generation;
    Context app = c.getApplicationContext();
    status = pairing ? "휴대폰 자체 페어링 중…" : "휴대폰 자체 연결 중…";
    setup.execute(
        () -> {
          boolean ok = false;
          try {
            if (adb == null) adb = new LocalAdb(app);
            adb.disconnect();
            ok = pairing ? adb.pair(port, code) : adb.connect(port);
          } catch (Throwable ignored) {
          }
          boolean success = ok;
          main.post(
              () -> {
                if (generation != expected) return;
                busy = false;
                linked = success && !pairing;
                status =
                    success
                        ? (pairing
                            ? "페어링 완료 · 무선 디버깅 기본 화면의 연결 포트로 연결하세요. 페어링 포트와 다릅니다."
                            : "휴대폰 자체 연결 완료 · 먼저 ‘사진 없이 5초 화면 끄기 시험’을 진행할 수 있습니다.")
                        : "연결 실패 · 무선 디버깅과 포트/코드를 확인하세요. 코드는 기기에 저장하지 않았습니다.";
              });
        });
  }

  static String begin(Context c, String owner) {
    if (!connected() || adb == null) return "화면 끄기 설정에서 무선 연결을 먼저 완료하세요.";
    if (current != null) return "화면 끄기 작업이 이미 실행 중입니다.";
    if (SystemClock.elapsedRealtime() < restoreUntil) return "이전 화면 복구를 기다리는 중입니다. 10초 뒤 다시 시작하세요.";
    if (!DeliveryGate.allowed(c) || !PhotoKeyboardSession.unlocked(c)) return "화면 잠금과 라이선스를 확인하세요.";
    Session s = new Session(c, owner);
    current = s;
    status = "화면 패널 제어 연결 중 · 전원 버튼을 누르지 마세요.";
    io.execute(() -> read(s));
    main.postDelayed(() -> monitor(s), 1000L);
    return "";
  }

  static String trial(Context c) {
    if (PhotoTourController.active() || PhotoKeyboardService.hasActiveLoop())
      return "사진 작업을 먼저 중지하세요.";
    String error = begin(c, "trial");
    if (error.isEmpty())
      main.postDelayed(
          () -> {
            Session s = current;
            if (s != null && s.owner.equals("trial")) end("화면 끄기 시험 종료 · 패널 복구 요청됨");
          },
          8000L); // Five seconds after OFF acknowledgement below; also bounds stalled startup.
    return error;
  }

  private static void read(Session s) {
    try {
      String command =
          "CLASSPATH="
              + quote(s.app.getApplicationInfo().sourceDir)
              + " app_process / com.local.kakaoautosender.PanelShell "
              + s.token;
      if (s.closed) return;
      s.stream = adb.openStream("shell:" + command);
      if (s.closed) {
        s.stream.close();
        return;
      }
      s.output = s.stream.openOutputStream();
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(s.stream.openInputStream(), StandardCharsets.UTF_8));
      String line;
      while (!s.closed && (line = reader.readLine()) != null) {
        if (line.length() > 512) break;
        line = line.trim();
        if (line.equals("KMPANEL READY " + s.token) && !s.offRequested) {
          s.offRequested = true;
          write(s, "OFF");
        } else if (line.equals("KMPANEL OFF " + s.token)) {
          boolean first = !s.lease.allows(SystemClock.elapsedRealtime());
          s.lease.acknowledge(s.token, SystemClock.elapsedRealtime(), true);
          if (first)
            main.post(
                () -> {
                  if (current != s || s.closed) return;
                  status = "패널 OFF 명령 응답 확인 · 기기는 잠금 해제 상태로 실행 중";
                  if (s.owner.equals("trial"))
                    main.postDelayed(
                        () -> {
                          if (current == s) end("5초 화면 끄기 시험 종료 · 패널 복구 요청됨");
                        },
                        5000L);
                });
        } else if (line.startsWith("KMPANEL ERROR") || line.contains("RESTORE_FAILED")) break;
      }
    } catch (Throwable ignored) {
    } finally {
      main.post(() -> failed(s, "화면 제어 연결 종료 · 패널 복구 요청 · 전송 결과를 확인하세요."));
    }
  }

  private static void monitor(Session s) {
    if (current != s || s.closed) return;
    if (!DeliveryGate.allowed(s.app)
        || !PhotoKeyboardSession.unlocked(s.app)
        || (!s.owner.equals("trial") && !PhotoTourController.matchesRun(s.owner))) {
      failed(s, "화면/잠금·라이선스·실행 변경으로 화면 끄기와 사진 순회를 중지했습니다.");
      return;
    }
    if (!s.lease.allows(SystemClock.elapsedRealtime())
        && SystemClock.elapsedRealtime() - s.started > 6000L) {
      failed(s, "패널 OFF 응답 또는 연결 유지 확인 실패 · 순회를 중지했습니다.");
      return;
    }
    if (!s.checkingLicense && SystemClock.elapsedRealtime() - s.lastLicenseCheck >= 120000L) {
      s.checkingLicense = true;
      s.lastLicenseCheck = SystemClock.elapsedRealtime();
      LicenseManager.checkAsync(
          s.app,
          result -> {
            s.checkingLicense = false;
            if (current == s && !result.valid && !LicenseManager.isUsable(s.app))
              failed(s, "라이선스 확인 실패로 화면 끄기와 사진 순회 중지");
          });
    }
    if (s.offRequested)
      io.execute(
          () -> {
            try {
              write(s, "PING");
            } catch (Throwable ignored) {
              main.post(() -> failed(s, "화면 제어 응답 중단 · 사진 순회 중지"));
            }
          });
    main.postDelayed(() -> monitor(s), 1000L);
  }

  private static void write(Session s, String command) throws Exception {
    OutputStream out = s.output;
    if (out == null) throw new java.io.IOException("NO_STREAM");
    synchronized (s) {
      if (s.closed && !command.equals("STOP")) return;
      out.write((command + " " + s.token + "\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
    }
  }

  private static void failed(Session s, String reason) {
    if (current != s || s.closed) return;
    boolean tour = !s.owner.equals("trial") && PhotoTourController.matchesRun(s.owner);
    end(reason);
    if (tour) PhotoTourController.stop(s.app, reason);
  }

  static void end(String reason) {
    Session s = current;
    if (s == null) return;
    current = null;
    s.closed = true;
    s.lease.end();
    status = reason;
    restoreUntil = SystemClock.elapsedRealtime() + 9000L;
    io.execute(
        () -> {
          try {
            write(s, "STOP");
          } catch (Throwable ignored) {
          }
        });
    io.schedule(
        () -> {
          try {
            if (s.stream != null) s.stream.close();
          } catch (Throwable ignored) {
          }
        },
        500L,
        TimeUnit.MILLISECONDS);
  }

  static void disconnect(Context c) {
    if (PhotoTourController.active()) PhotoTourController.stop(c, "무선 연결 해제로 사진 순회 중지");
    end("연결 해제 · 패널 복구 요청됨");
    ++generation;
    linked = false;
    busy = false;
    status = "무선 연결을 해제했습니다. Android 설정의 페어링된 기기에서 권한을 삭제할 수 있습니다.";
    setup.execute(
        () -> {
          try {
            if (adb != null) adb.disconnect();
          } catch (Exception ignored) {
          }
        });
  }
}
