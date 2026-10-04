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

        if (boot || updated) recoverInterruptedPending(context, boot ? "재부팅" : "앱 업데이트");

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

    private void recoverInterruptedPending(Context context, String reason) {
        String pendingId = VoiceRoomStore.pendingRoomId(context);
        boolean probe = VoiceRoomStore.isProbePending(context);
        boolean manual = VoiceRoomStore.isManualPending(context);
        VoiceRoomStore.clearPending(context);
        if (pendingId.isEmpty()) return;

        VoiceRoomStore.Room room = VoiceRoomStore.get(context, pendingId);
        if (room == null) return;
        room.stageStartedAt = 0L;
        if (probe) {
            room.status = "PROBE_ERROR";
            room.lastError = reason + "으로 안전 점검이 중단됨";
        } else if (manual) {
            room.status = "MANUAL_ERROR";
            room.lastError = reason + "으로 실제 점검이 중단됨 · 자동으로 다시 실행하지 않음";
        } else {
            room.status = "CHECK_DUE";
            room.lastError = reason + "으로 이전 자동관리 작업이 중단됨 · 자동 재시도 예정";
            room.nextCheckAt = System.currentTimeMillis() + 15_000L;
        }
        VoiceRoomStore.update(context, room);
    }
}
