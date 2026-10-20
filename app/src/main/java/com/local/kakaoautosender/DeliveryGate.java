package com.local.kakaoautosender;

import android.content.Context;

/** Synchronizes the final PendingIntent acceptance with global stop, lockout, and APK integrity. */
final class DeliveryGate {
  static final Object LOCK = new Object();
  private static final ThreadLocal<Boolean> SCHEDULED = new ThreadLocal<>();

  static void scheduled(boolean value) {
    if (value) SCHEDULED.set(true);
    else SCHEDULED.remove();
  }

  static boolean allowed(Context c) {
    if (!AppIntegrity.isAuthentic(c)) {
      AppIntegrity.trip(c);
      return false;
    }
    return LicenseManager.isUsable(c)
        && (!Boolean.TRUE.equals(SCHEDULED.get())
            || Prefs.p(c).getBoolean(Prefs.KEY_ACTIVE, false));
  }

  static void stop(Context c) {
    synchronized (LOCK) {
      PhotoKeyboardService.stopLoop(c, null, "전체 중지로 사진 예약을 취소했습니다.");
      PhotoKeyboardService.cancelPending();
      Prefs.p(c).edit().putBoolean(Prefs.KEY_ACTIVE, false).commit();
      SendScheduler.cancel(c);
    }
  }
}
