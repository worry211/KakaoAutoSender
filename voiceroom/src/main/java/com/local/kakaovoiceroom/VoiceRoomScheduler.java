package com.local.kakaovoiceroom;

import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

final class VoiceRoomScheduler {
    static final String EXTRA_ROOM_ID = "room_id";
    static final String EXTRA_MANUAL = "manual_check";
    private static final int REQUEST_CODE = 24041;

    private VoiceRoomScheduler() {}

    static void scheduleNext(Context context) {
        long now = System.currentTimeMillis();
        boolean managerActive = VoiceRoomStore.managerActive(context);
        VoiceRoomStore.Room room = managerActive
                ? VoiceRoomStore.earliestDue(context, now)
                : VoiceRoomStore.manualCheckDue(context, now);
        if (room == null) {
            cancel(context);
            return;
        }
        boolean manual = !managerActive && "CHECK_DUE".equals(room.status);
        long at = room.nextCheckAt <= 0L
                ? now + 5_000L
                : Math.max(now + 2_000L, room.nextCheckAt);
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        PendingIntent pending = pendingIntent(
                context, room.id, manual, PendingIntent.FLAG_UPDATE_CURRENT);
        if (Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
        } else {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
        }
    }

    static void scheduleRetry(Context context, String roomId, long delayMs) {
        VoiceRoomStore.Room room = VoiceRoomStore.get(context, roomId);
        if (room == null) return;
        room.nextCheckAt = System.currentTimeMillis() + Math.max(30_000L, delayMs);
        VoiceRoomStore.update(context, room);
        scheduleNext(context);
    }

    static void cancel(Context context) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        PendingIntent automatic = pendingIntent(context, "", false, PendingIntent.FLAG_NO_CREATE);
        if (automatic != null) alarm.cancel(automatic);
        PendingIntent manual = pendingIntent(context, "", true, PendingIntent.FLAG_NO_CREATE);
        if (manual != null) alarm.cancel(manual);
    }

    static boolean canExact(Context context) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return alarm != null && alarm.canScheduleExactAlarms();
    }

    private static PendingIntent pendingIntent(
            Context context, String roomId, boolean manual, int mode) {
        Intent intent = new Intent(context, WakeActivity.class);
        intent.putExtra(EXTRA_ROOM_ID, roomId == null ? "" : roomId);
        intent.putExtra(EXTRA_MANUAL, manual);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        int flags = mode | PendingIntent.FLAG_IMMUTABLE;

        Bundle options = null;
        if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions activityOptions = ActivityOptions.makeBasic();
            activityOptions.setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            options = activityOptions.toBundle();
        }
        int requestCode = manual ? REQUEST_CODE + 1 : REQUEST_CODE;
        return PendingIntent.getActivity(context, requestCode, intent, flags, options);
    }
}
