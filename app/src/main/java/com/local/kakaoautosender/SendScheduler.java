package com.local.kakaoautosender;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class SendScheduler {
    static final int MIN_INTERVAL_MINUTES = 1;
    // Retained for legacy activities that still compile but are no longer the launcher.
    static final int MAX_DAILY_LIMIT = 9999;
    private static final int REQUEST_CODE = 7301;

    private SendScheduler() {}

    static PendingIntent pending(Context c) {
        Intent i = new Intent(c, SendAlarmReceiver.class);
        i.setAction("com.local.kakaoautosender.SEND_ALARM");
        return PendingIntent.getBroadcast(c, REQUEST_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void scheduleNext(Context c) {
        if (!Prefs.p(c).getBoolean(Prefs.KEY_ACTIVE, false)) {
            cancel(c);
            return;
        }
        MultiRoomStore.ensureMigrated(c);
        long when = MultiRoomStore.nextDueAt(c);
        if (when <= 0L) {
            cancel(c);
            return;
        }
        scheduleAt(c, when);
    }

    static void scheduleFromNow(Context c) {
        MultiRoomStore.ensureMigrated(c);
        MultiRoomStore.setAllNextFromNow(c);
        scheduleNext(c);
    }

    static void scheduleAt(Context c, long when) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending(c));
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending(c));
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending(c));
        }
        Prefs.p(c).edit().putLong(Prefs.KEY_NEXT_AT, when).apply();
    }

    static boolean canUseExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c));
        Prefs.p(c).edit().putLong(Prefs.KEY_NEXT_AT, 0L).apply();
    }
}
