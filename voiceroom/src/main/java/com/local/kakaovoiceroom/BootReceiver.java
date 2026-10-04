package com.local.kakaovoiceroom;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        boolean alarmAccess = AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action);
        if (!boot && !updated && !alarmAccess) return;

        if (boot || updated) VoiceRoomStore.clearPending(context);

        if (!VoiceRoomStore.managerActive(context)) {
            VoiceRoomScheduler.cancel(context);
            return;
        }

        if (boot) {
            VoiceRoomStore.setLastStatus(context, "재부팅 후 보이스룸 자동관리 예약 복구");
        } else if (updated) {
            VoiceRoomStore.setLastStatus(context, "앱 업데이트 후 보이스룸 자동관리 예약 복구");
        } else {
            VoiceRoomStore.setLastStatus(context, "정확 알람 권한 변경 확인 · 예약 다시 설정");
        }
        VoiceRoomScheduler.scheduleNext(context);
    }
}
