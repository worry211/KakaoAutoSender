package com.local.kakaoautosender;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean restoreEvent = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!restoreEvent) return;

        Prefs.ensureLabelSchema(context);
        MultiRoomStore.ensureMigrated(context);

        if (!LicenseManager.isUsable(context)) {
            Prefs.p(context).edit().putBoolean(Prefs.KEY_ACTIVE, false).apply();
            SendScheduler.cancel(context);
            Prefs.setStatus(context, "라이선스 인증 필요 · 재부팅 후 자동전송 복구 안 함");
            return;
        }

        if (Prefs.p(context).getBoolean(Prefs.KEY_ACTIVE, false)) {
            MultiRoomStore.repairNextTimesAfterRestore(context);
            SendScheduler.scheduleNext(context);
            KakaoNotificationListener.requestReconnect(context);
            Prefs.setStatus(context,
                    Intent.ACTION_BOOT_COMPLETED.equals(action)
                            ? "재부팅 후 다중방 자동전송 예약 복구 · 방 세션 복구 대기"
                            : "앱 업데이트 후 다중방 예약 복구 · 카카오 세션 재연결 요청");
        } else {
            SendScheduler.cancel(context);
        }
    }
}
