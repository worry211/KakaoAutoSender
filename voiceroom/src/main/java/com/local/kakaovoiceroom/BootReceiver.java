package com.local.kakaovoiceroom;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.List;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        boolean alarmAccess = AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action);
        if (!boot && !updated && !alarmAccess) return;

        AudioGuard.recoverIfStale(context);
        if (boot || updated) recoverInterruptedPending(context, boot ? "재부팅" : "앱 업데이트");

        if (!VoiceRoomStore.managerActive(context)) {
            VoiceRoomScheduler.cancel(context);
            return;
        }

        if (boot) {
            // A device reboot tears down the local Kakao audio/session process. Do not trust a
            // previously stored 47h55m alarm after reboot: force a real Kakao UI health check soon.
            forcePostBootRecheck(context);
            VoiceRoomStore.setLastStatus(context,
                    "재부팅 후 보이스룸 실제 상태 재확인 예약 복구");
        } else if (updated) {
            VoiceRoomStore.setLastStatus(context, "앱 업데이트 후 보이스룸 자동관리/요청 보호 복구");
        } else {
            VoiceRoomStore.setLastStatus(context, "정확 알람 권한 변경 확인 · 예약 다시 설정");
        }
        VoiceRoomScheduler.scheduleNext(context);
    }

    private void forcePostBootRecheck(Context context) {
        long now = System.currentTimeMillis();
        List<VoiceRoomStore.Room> rooms = VoiceRoomStore.list(context);
        int offset = 0;
        for (VoiceRoomStore.Room room : rooms) {
            if (!room.enabled || !room.liveCheckPassed) continue;
            room.status = "CHECK_DUE";
            room.stageStartedAt = 0L;
            room.lastError = "재부팅 후 실제 보이스룸 상태 재확인 예정";
            room.nextCheckAt = now + 15_000L + (offset * 5_000L);
            VoiceRoomStore.update(context, room);
            offset += 1;
        }
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
            room.lastError = reason + "으로 실제 점검이 중단됨 · 사용자가 다시 실행해야 함";
        } else if (room.enabled && room.liveCheckPassed) {
            room.status = "CHECK_DUE";
            room.lastError = reason + "으로 이전 자동작업이 중단됨 · 자동 재시도 예정";
            room.nextCheckAt = System.currentTimeMillis() + 15_000L;
        } else {
            room.status = "NEW";
            room.lastError = reason + " 이후 방 검증 상태를 확인해줘";
            room.nextCheckAt = 0L;
        }
        VoiceRoomStore.update(context, room);
    }
}
