package com.local.kakaoautosender;

import android.content.Context;

/** Explicitly copied local diagnostics; no message text, image URI, pairing code or license key. */
final class PhotoSupportReport {
  static String build(Context c) {
    android.app.NotificationManager notices =
        (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    return "KakaoMacro "
        + BuildConfig.VERSION_NAME
        + "\nAndroid "
        + android.os.Build.VERSION.RELEASE
        + " / SDK "
        + android.os.Build.VERSION.SDK_INT
        + "\n기기: "
        + android.os.Build.MANUFACTURER
        + " "
        + android.os.Build.MODEL
        + "\n라이선스 현재 사용 가능: "
        + LicenseManager.isUsable(c)
        + "\n사진 키보드 현재 선택: "
        + PhotoTourController.keyboardSelected(c)
        + "\n사진 보조 연결: "
        + PhotoUiService.connected()
        + "\n정확한 알람: "
        + SendScheduler.canUseExact(c)
        + "\n알림 표시: "
        + (notices != null && notices.areNotificationsEnabled())
        + "\n패널 자체 연결: "
        + PanelOffManager.connected()
        + "\n"
        + PanelOffManager.status()
        + "\n현재 사진 UI: "
        + PhotoUiService.status()
        + "\n순회: "
        + PhotoTourController.status(c)
        + "\n단일 방: "
        + PhotoLoopStore.summary(c)
        + "\n"
        + PhotoKeyboardDiagnostics.summary(c);
  }
}
