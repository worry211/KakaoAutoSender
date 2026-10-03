package com.local.kakaoautosender;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

final class SendScheduler {
    static final int MIN_INTERVAL_MINUTES = 30;
    // Kept for the non-launcher v0.7 fallback activity so old code still compiles.
    static final int MAX_DAILY_LIMIT = 24;
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

    // Compatibility wrapper for the retained v0.7 activity.
    static void scheduleFromNow(Context c) {
        MultiRoomStore.ensureMigrated(c);
        MultiRoomStore.setAllNextFromNow(c);
        scheduleNext(c);
    }

    static void scheduleAt(Context c, long when) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending(c));
        Prefs.p(c).edit().putLong(Prefs.KEY_NEXT_AT, when).apply();
    }

    static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c));
        Prefs.p(c).edit().putLong(Prefs.KEY_NEXT_AT, 0L).apply();
    }
}
