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
    private static final int REQUEST_CODE = 24041;

    private VoiceRoomScheduler() {}

    static void scheduleNext(Context context) {
        if (!VoiceRoomStore.managerActive(context)) {
            cancel(context);
            return;
        }
        long now = System.currentTimeMillis();
        VoiceRoomStore.Room room = VoiceRoomStore.earliestDue(context, now);
        if (room == null) {
            cancel(context);
            return;
        }
        long at = room.nextCheckAt <= 0L
                ? now + 5_000L
                : Math.max(now + 2_000L, room.nextCheckAt);
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        PendingIntent pending = pendingIntent(context, room.id, PendingIntent.FLAG_UPDATE_CURRENT);
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
        PendingIntent pending = pendingIntent(context, "", PendingIntent.FLAG_NO_CREATE);
        if (pending != null) alarm.cancel(pending);
    }

    static boolean canExact(Context context) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return alarm != null && alarm.canScheduleExactAlarms();
    }

    private static PendingIntent pendingIntent(Context context, String roomId, int mode) {
        Intent intent = new Intent(context, WakeActivity.class);
        intent.putExtra(EXTRA_ROOM_ID, roomId == null ? "" : roomId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        int flags = mode | PendingIntent.FLAG_IMMUTABLE;

        Bundle options = null;
        if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions activityOptions = ActivityOptions.makeBasic();
            activityOptions.setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            options = activityOptions.toBundle();
        }
        return PendingIntent.getActivity(context, REQUEST_CODE, intent, flags, options);
    }
}
