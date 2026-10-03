package com.local.kakaoautosender;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class Prefs {
    static final String FILE = "kakao_auto_sender";
    static final String KEY_ROOM = "room";
    static final String KEY_MESSAGE = "message";
    static final String KEY_INTERVAL_MIN = "interval_min";
    static final String KEY_MAX_PER_DAY = "max_per_day";
    static final String KEY_ACTIVE = "active";
    static final String KEY_NEXT_AT = "next_at";
    static final String KEY_COUNT_DATE = "count_date";
    static final String KEY_COUNT = "count";
    static final String KEY_RECENT_LABELS = "recent_labels";
    static final String KEY_LAST_STATUS = "last_status";
    static final String KEY_LAST_STATUS_AT = "last_status_at";

    private Prefs() {}

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static void addRecentLabel(Context c, String label) {
        if (label == null) return;
        label = label.trim();
        if (label.isEmpty()) return;
        Set<String> old = p(c).getStringSet(KEY_RECENT_LABELS, Collections.emptySet());
        Set<String> copy = new HashSet<>(old);
        copy.add(label);
        p(c).edit().putStringSet(KEY_RECENT_LABELS, copy).apply();
    }

    static ArrayList<String> recentLabels(Context c) {
        ArrayList<String> list = new ArrayList<>(p(c).getStringSet(KEY_RECENT_LABELS, Collections.emptySet()));
        Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
        return list;
    }

    static int getTodayCount(Context c) {
        SharedPreferences p = p(c);
        String today = LocalDate.now().toString();
        String saved = p.getString(KEY_COUNT_DATE, "");
        if (!today.equals(saved)) {
            p.edit().putString(KEY_COUNT_DATE, today).putInt(KEY_COUNT, 0).apply();
            return 0;
        }
        return p.getInt(KEY_COUNT, 0);
    }

    static int incrementTodayCount(Context c) {
        int next = getTodayCount(c) + 1;
        p(c).edit().putString(KEY_COUNT_DATE, LocalDate.now().toString()).putInt(KEY_COUNT, next).apply();
        return next;
    }

    static void setStatus(Context c, String status) {
        p(c).edit().putString(KEY_LAST_STATUS, status).putLong(KEY_LAST_STATUS_AT, System.currentTimeMillis()).apply();
    }
}
