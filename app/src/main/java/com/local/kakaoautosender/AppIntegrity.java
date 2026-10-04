package com.local.kakaoautosender;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Local anti-tamper guard for the commercial release.
 *
 * <p>The release certificate fingerprint is public information, not a secret. The purpose of this
 * check is to make re-signed/patched APKs fail closed at multiple execution points. It supplements
 * the server-side license system; it does not replace it.
 */
final class AppIntegrity {
  static final String EXPECTED_PACKAGE = "com.local.kakaoautosender";
  private static volatile Context app;
  private static volatile String lastReason = "";

  private AppIntegrity() {}

  static void initialize(Context context) {
    if (context != null) app = context.getApplicationContext();
  }

  static void requireAuthentic() {
    Context c = app;
    if (c != null && !isAuthentic(c)) throw new SecurityException("APK_INTEGRITY");
  }

  static boolean isAuthentic(Context context) {
    if (BuildConfig.DEBUG) return true;
    if (context == null) return fail("NO_CONTEXT");
    if (!EXPECTED_PACKAGE.equals(context.getPackageName())) return fail("PACKAGE_MISMATCH");
    try {
      ApplicationInfo info = context.getApplicationInfo();
      if ((info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) return fail("DEBUGGABLE_RELEASE");
      if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return fail("DEBUGGER_ATTACHED");

      PackageManager pm = context.getPackageManager();
      PackageInfo pi;
      Signature[] signers;
      if (Build.VERSION.SDK_INT >= 28) {
        pi = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (pi.signingInfo == null) return fail("NO_SIGNING_INFO");
        signers =
            pi.signingInfo.hasMultipleSigners()
                ? pi.signingInfo.getApkContentsSigners()
                : pi.signingInfo.getSigningCertificateHistory();
      } else {
        // minSdk is 26. Kept for API 26-27 devices.
        pi = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
        signers = pi.signatures;
      }
      if (signers == null || signers.length == 0) return fail("NO_SIGNER");
      for (Signature signer : signers) {
        if (signer == null) continue;
        String digest = sha256Hex(signer.toByteArray());
        if (certificateMatches(digest)) {
          lastReason = "";
          return true;
        }
      }
      return fail("SIGNER_MISMATCH");
    } catch (Throwable t) {
      return fail("SIGNATURE_CHECK_FAILED");
    }
  }

  static boolean certificateMatches(String digest) {
    return digest != null
        && BuildConfig.EXPECTED_RELEASE_CERT_SHA256.equals(digest.trim().toLowerCase(Locale.ROOT));
  }

  static String reason() {
    return lastReason == null ? "" : lastReason;
  }

  static void trip(Context context) {
    lastReason = lastReason.isEmpty() ? "APK_INTEGRITY" : lastReason;
    try {
      Prefs.p(context).edit().putBoolean(Prefs.KEY_ACTIVE, false).commit();
      SendScheduler.cancel(context);
      Prefs.setStatus(context, "앱 무결성 확인 실패 · 공식 APK를 다시 설치해 주세요.");
    } catch (Throwable ignored) {
    }
  }

  private static boolean fail(String reason) {
    lastReason = reason;
    return false;
  }

  private static String sha256Hex(byte[] value) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
    StringBuilder out = new StringBuilder(digest.length * 2);
    for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
    return out.toString();
  }
}
