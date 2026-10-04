package com.local.kakaoautosender;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null) return;
    String action = intent.getAction();
    boolean restoreEvent =
        Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
    if (!restoreEvent) return;

    Prefs.ensureLabelSchema(context);
    MultiRoomStore.ensureMigrated(context);

    PendingResult pending = goAsync();
    Context app = context.getApplicationContext();
    LicenseManager.checkAsync(
        app,
        v -> {
          try {
            if (!v.valid) {
              DeliveryGate.stop(app);
              return;
            }
            if (Prefs.p(app).getBoolean(Prefs.KEY_ACTIVE, false)) {
              MultiRoomStore.repairNextTimesAfterRestore(app);
              SendScheduler.scheduleNext(app);
              KakaoNotificationListener.requestReconnect(app);
              Prefs.setStatus(
                  app,
                  Intent.ACTION_BOOT_COMPLETED.equals(action)
                      ? "재부팅 후 다중방 자동전송 예약 복구 · 방 세션 복구 대기"
                      : "앱 업데이트 후 다중방 예약 복구 · 카카오 세션 재연결 요청");
            } else {
              SendScheduler.cancel(app);
            }
          } finally {
            pending.finish();
          }
        });
  }
}
