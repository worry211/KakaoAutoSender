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

        // 버전 업 직후에는 예전의 잘못된 방 연결/활성 상태를 먼저 폐기한다.
        Prefs.ensureLabelSchema(context);

        if (Prefs.p(context).getBoolean(Prefs.KEY_ACTIVE, false)) {
            SendScheduler.scheduleFromNow(context);
            KakaoNotificationListener.requestReconnect(context);
            Prefs.setStatus(context,
                    Intent.ACTION_BOOT_COMPLETED.equals(action)
                            ? "재부팅 후 자동전송 예약 복구됨 · 검증 세션 복구 대기"
                            : "앱 업데이트 후 자동전송 예약 복구됨 · 검증 세션 재연결 요청");
        } else {
            SendScheduler.cancel(context);
        }
    }
}
