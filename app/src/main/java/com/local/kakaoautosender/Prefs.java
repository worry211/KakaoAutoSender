package com.local.kakaoautosender;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    static final String KEY_LAST_SUCCESS_AT = "last_success_at";
    static final String KEY_FAILURE_STREAK = "failure_streak";
    static final String KEY_EVENT_LOG = "event_log";

    private static final String KEY_LABEL_SCHEMA_VERSION = "label_schema_version";
    private static final int LABEL_SCHEMA_VERSION = 3;
    private static final String BINDING_PREFIX = "binding.";
    private static final int MAX_LOG_CHARS = 12000;

    private Prefs() {}

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static void ensureLabelSchema(Context c) {
        SharedPreferences p = p(c);
        if (p.getInt(KEY_LABEL_SCHEMA_VERSION, 0) >= LABEL_SCHEMA_VERSION) return;
        p.edit()
                .remove(KEY_RECENT_LABELS)
                .putInt(KEY_LABEL_SCHEMA_VERSION, LABEL_SCHEMA_VERSION)
                .apply();
    }

    static void addRecentLabel(Context c, String label) {
        ensureLabelSchema(c);
        if (label == null) return;
        label = label.trim();
        if (label.isEmpty()) return;
        Set<String> old = p(c).getStringSet(KEY_RECENT_LABELS, Collections.emptySet());
        Set<String> copy = new HashSet<>(old);
        copy.add(label);
        p(c).edit().putStringSet(KEY_RECENT_LABELS, copy).apply();
    }

    static ArrayList<String> recentLabels(Context c) {
        ensureLabelSchema(c);
        ArrayList<String> list = new ArrayList<>(p(c).getStringSet(KEY_RECENT_LABELS, Collections.emptySet()));
        Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
        return list;
    }

    static void bindIdentity(Context c, String alias, List<String> identityKeys) {
        if (alias == null || identityKeys == null) return;
        alias = alias.trim();
        if (alias.isEmpty()) return;
        SharedPreferences.Editor e = p(c).edit();
        int added = 0;
        for (String key : identityKeys) {
            if (key == null || key.trim().isEmpty()) continue;
            e.putString(BINDING_PREFIX + digest(key), alias);
            added++;
        }
        if (added > 0) {
            e.apply();
            addRecentLabel(c, alias);
            appendLog(c, "연결 규칙 저장: " + alias + " (식별자 " + added + "개)");
        }
    }

    static String aliasForIdentity(Context c, List<String> identityKeys) {
        if (identityKeys == null) return null;
        SharedPreferences p = p(c);
        for (String key : identityKeys) {
            if (key == null || key.trim().isEmpty()) continue;
            String alias = p.getString(BINDING_PREFIX + digest(key), null);
            if (alias != null && !alias.trim().isEmpty()) return alias.trim();
        }
        return null;
    }

    static boolean hasBindingForAlias(Context c, String alias) {
        if (alias == null || alias.trim().isEmpty()) return false;
        String wanted = alias.trim();
        for (Map.Entry<String, ?> entry : p(c).getAll().entrySet()) {
            if (!entry.getKey().startsWith(BINDING_PREFIX)) continue;
            Object value = entry.getValue();
            if (value instanceof String && wanted.equalsIgnoreCase(((String) value).trim())) return true;
        }
        return false;
    }

    static int bindingCount(Context c) {
        int count = 0;
        for (String key : p(c).getAll().keySet()) {
            if (key.startsWith(BINDING_PREFIX)) count++;
        }
        return count;
    }

    static void clearBindingsAndLabels(Context c) {
        SharedPreferences prefs = p(c);
        SharedPreferences.Editor e = prefs.edit().remove(KEY_RECENT_LABELS);
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(BINDING_PREFIX)) e.remove(key);
        }
        e.apply();
        appendLog(c, "저장된 방 연결 규칙 초기화");
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
        p(c).edit()
                .putString(KEY_COUNT_DATE, LocalDate.now().toString())
                .putInt(KEY_COUNT, next)
                .putLong(KEY_LAST_SUCCESS_AT, System.currentTimeMillis())
                .putInt(KEY_FAILURE_STREAK, 0)
                .apply();
        return next;
    }

    static void markManualSuccess(Context c) {
        p(c).edit()
                .putLong(KEY_LAST_SUCCESS_AT, System.currentTimeMillis())
                .putInt(KEY_FAILURE_STREAK, 0)
                .apply();
    }

    static int recordFailure(Context c) {
        int next = p(c).getInt(KEY_FAILURE_STREAK, 0) + 1;
        p(c).edit().putInt(KEY_FAILURE_STREAK, next).apply();
        return next;
    }

    static void setStatus(Context c, String status) {
        if (status == null) status = "";
        p(c).edit()
                .putString(KEY_LAST_STATUS, status)
                .putLong(KEY_LAST_STATUS_AT, System.currentTimeMillis())
                .apply();
        appendLog(c, status);
    }

    static void appendLog(Context c, String line) {
        if (line == null || line.trim().isEmpty()) return;
        String safe = line.replace('\n', ' ').replace('\r', ' ').trim();
        String old = p(c).getString(KEY_EVENT_LOG, "");
        String next = System.currentTimeMillis() + "|" + safe + "\n" + old;
        if (next.length() > MAX_LOG_CHARS) next = next.substring(0, MAX_LOG_CHARS);
        p(c).edit().putString(KEY_EVENT_LOG, next).apply();
    }

    static String recentLog(Context c, int maxLines) {
        String raw = p(c).getString(KEY_EVENT_LOG, "");
        if (raw == null || raw.isEmpty()) return "기록 없음";
        String[] lines = raw.split("\\n");
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            int sep = line.indexOf('|');
            if (sep >= 0 && sep + 1 < line.length()) line = line.substring(sep + 1);
            if (used > 0) sb.append("\n");
            sb.append("• ").append(line);
            used++;
            if (used >= Math.max(1, maxLines)) break;
        }
        return sb.length() == 0 ? "기록 없음" : sb.toString();
    }

    private static String digest(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
