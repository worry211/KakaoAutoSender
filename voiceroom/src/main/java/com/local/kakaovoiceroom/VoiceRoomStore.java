package com.local.kakaovoiceroom;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class VoiceRoomStore {
    static final long VOICE_ROOM_LIFETIME_MS = 48L * 60L * 60L * 1000L;
    static final long PRECHECK_MS = 5L * 60L * 1000L;
    static final long UNKNOWN_ACTIVE_RECHECK_MS = 30L * 60L * 1000L;
    static final long ERROR_RETRY_MS = 5L * 60L * 1000L;
    static final long PENDING_TIMEOUT_MS = 90L * 1000L;
    static final String MODE_AUTO = "AUTO";
    static final String MODE_PROBE = "PROBE";
    static final String MODE_MANUAL = "MANUAL";
    static final String ENTRY_UNKNOWN = "UNKNOWN";
    static final String ENTRY_DEEPLINK = "DEEPLINK";
    static final String ENTRY_LAUNCHER = "LAUNCHER";

    private static final String PREFS = "voiceroom_manager";
    private static final String KEY_ROOMS = "rooms_json";
    private static final String KEY_ACTIVE = "manager_active";
    private static final String KEY_PENDING_ROOM = "pending_room";
    private static final String KEY_PENDING_AT = "pending_at";
    private static final String KEY_PENDING_MODE = "pending_mode";
    private static final String KEY_PENDING_ENTRY = "pending_entry";
    private static final String KEY_LAST_STATUS = "last_status";

    private VoiceRoomStore() {}

    static final class Room {
        String id;
        String title;
        String roomUrl;
        boolean enabled;
        boolean safeProbePassed;
        boolean liveCheckPassed;
        long verifiedAt;
        long startedAt;
        long nextCheckAt;
        int failures;
        String status;
        String lastError;
        long stageStartedAt;
        String lastDiagnostic;

        Room copy() {
            Room r = new Room();
            r.id = id;
            r.title = title;
            r.roomUrl = roomUrl;
            r.enabled = enabled;
            r.safeProbePassed = safeProbePassed;
            r.liveCheckPassed = liveCheckPassed;
            r.verifiedAt = verifiedAt;
            r.startedAt = startedAt;
            r.nextCheckAt = nextCheckAt;
            r.failures = failures;
            r.status = status;
            r.lastError = lastError;
            r.stageStartedAt = stageStartedAt;
            r.lastDiagnostic = lastDiagnostic;
            return r;
        }
    }

    private static SharedPreferences p(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized List<Room> list(Context context) {
        ArrayList<Room> out = new ArrayList<>();
        String raw = p(context).getString(KEY_ROOMS, "[]");
        try {
            JSONArray a = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Room r = fromJson(o);
                if (r.id != null && !r.id.isEmpty() && r.title != null && !r.title.isEmpty()) out.add(r);
            }
        } catch (Exception ignored) {}
        out.sort(Comparator.comparingLong(r -> r.nextCheckAt <= 0 ? Long.MAX_VALUE : r.nextCheckAt));
        return out;
    }

    static synchronized Room get(Context context, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Room room : list(context)) if (id.equals(room.id)) return room;
        return null;
    }

    static synchronized Room add(Context context, String title, String roomUrl) {
        Room room = new Room();
        room.id = UUID.randomUUID().toString();
        room.title = title == null ? "" : title.trim();
        room.roomUrl = roomUrl == null ? "" : roomUrl.trim();
        room.enabled = true;
        room.safeProbePassed = false;
        room.liveCheckPassed = false;
        room.verifiedAt = 0L;
        room.startedAt = 0L;
        room.nextCheckAt = 0L;
        room.failures = 0;
        room.status = "NEW";
        room.lastError = "";
        room.stageStartedAt = 0L;
        room.lastDiagnostic = "";
        List<Room> rooms = list(context);
        rooms.add(room);
        save(context, rooms);
        return room;
    }

    static synchronized void update(Context context, Room updated) {
        if (updated == null || updated.id == null) return;
        List<Room> rooms = list(context);
        boolean replaced = false;
        for (int i = 0; i < rooms.size(); i++) {
            if (updated.id.equals(rooms.get(i).id)) {
                rooms.set(i, updated.copy());
                replaced = true;
                break;
            }
        }
        if (!replaced) rooms.add(updated.copy());
        save(context, rooms);
    }

    static synchronized void remove(Context context, String id) {
        List<Room> rooms = list(context);
        rooms.removeIf(room -> id != null && id.equals(room.id));
        save(context, rooms);
        if (id != null && id.equals(pendingRoomId(context))) clearPending(context);
    }

    static synchronized Room earliestDue(Context context, long now) {
        Room best = null;
        for (Room room : list(context)) {
            if (!room.enabled || !room.liveCheckPassed) continue;
            long due = room.nextCheckAt <= 0 ? now : room.nextCheckAt;
            if (best == null || due < (best.nextCheckAt <= 0 ? now : best.nextCheckAt)) best = room;
        }
        return best;
    }

    static boolean managerActive(Context context) {
        return p(context).getBoolean(KEY_ACTIVE, false);
    }

    static void setManagerActive(Context context, boolean value) {
        p(context).edit().putBoolean(KEY_ACTIVE, value).apply();
    }

    static void setPending(Context context, String roomId) {
        setPending(context, roomId, MODE_AUTO, ENTRY_UNKNOWN);
    }

    static void setPending(Context context, String roomId, String mode) {
        setPending(context, roomId, mode, ENTRY_UNKNOWN);
    }

    static void setPending(Context context, String roomId, String mode, String entry) {
        p(context).edit()
                .putString(KEY_PENDING_ROOM, roomId == null ? "" : roomId)
                .putLong(KEY_PENDING_AT, System.currentTimeMillis())
                .putString(KEY_PENDING_MODE, normalizeMode(mode))
                .putString(KEY_PENDING_ENTRY, normalizeEntry(entry))
                .apply();
    }

    static void updatePendingEntry(Context context, String entry) {
        if (pendingRoomId(context).isEmpty()) return;
        p(context).edit().putString(KEY_PENDING_ENTRY, normalizeEntry(entry)).apply();
    }

    static String pendingRoomId(Context context) {
        String value = p(context).getString(KEY_PENDING_ROOM, "");
        return value == null ? "" : value;
    }

    static long pendingAt(Context context) {
        return p(context).getLong(KEY_PENDING_AT, 0L);
    }

    static String pendingMode(Context context) {
        String value = p(context).getString(KEY_PENDING_MODE, MODE_AUTO);
        return normalizeMode(value);
    }

    static String pendingEntry(Context context) {
        String value = p(context).getString(KEY_PENDING_ENTRY, ENTRY_UNKNOWN);
        return normalizeEntry(value);
    }

    static boolean pendingEnteredByLink(Context context) {
        return ENTRY_DEEPLINK.equals(pendingEntry(context));
    }

    static boolean isProbePending(Context context) {
        return MODE_PROBE.equals(pendingMode(context)) && !pendingRoomId(context).isEmpty();
    }

    static boolean isManualPending(Context context) {
        return MODE_MANUAL.equals(pendingMode(context)) && !pendingRoomId(context).isEmpty();
    }

    static boolean hasFreshPending(Context context) {
        String roomId = pendingRoomId(context);
        if (roomId.isEmpty()) return false;
        long startedAt = pendingAt(context);
        return startedAt > 0L && System.currentTimeMillis() - startedAt < PENDING_TIMEOUT_MS;
    }

    static void clearPending(Context context) {
        p(context).edit()
                .remove(KEY_PENDING_ROOM)
                .remove(KEY_PENDING_AT)
                .remove(KEY_PENDING_MODE)
                .remove(KEY_PENDING_ENTRY)
                .apply();
        AudioGuard.restore(context);
    }

    static void setLastStatus(Context context, String value) {
        p(context).edit().putString(KEY_LAST_STATUS, value == null ? "" : value).apply();
    }

    static String lastStatus(Context context) {
        String value = p(context).getString(KEY_LAST_STATUS, "");
        return value == null ? "" : value;
    }

    private static void save(Context context, List<Room> rooms) {
        JSONArray a = new JSONArray();
        for (Room room : rooms) a.put(toJson(room));
        p(context).edit().putString(KEY_ROOMS, a.toString()).apply();
    }

    private static JSONObject toJson(Room r) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", safe(r.id));
            o.put("title", safe(r.title));
            o.put("roomUrl", safe(r.roomUrl));
            o.put("enabled", r.enabled);
            o.put("safeProbePassed", r.safeProbePassed);
            o.put("liveCheckPassed", r.liveCheckPassed);
            o.put("verifiedAt", r.verifiedAt);
            o.put("startedAt", r.startedAt);
            o.put("nextCheckAt", r.nextCheckAt);
            o.put("failures", r.failures);
            o.put("status", safe(r.status));
            o.put("lastError", safe(r.lastError));
            o.put("stageStartedAt", r.stageStartedAt);
            o.put("lastDiagnostic", safe(r.lastDiagnostic));
        } catch (Exception ignored) {}
        return o;
    }

    private static Room fromJson(JSONObject o) {
        Room r = new Room();
        r.id = o.optString("id", "");
        r.title = o.optString("title", "");
        r.roomUrl = o.optString("roomUrl", "");
        r.enabled = o.optBoolean("enabled", true);
        r.safeProbePassed = o.optBoolean("safeProbePassed", false);
        r.liveCheckPassed = o.optBoolean("liveCheckPassed", false);
        r.verifiedAt = Math.max(0L, o.optLong("verifiedAt", 0L));
        r.startedAt = Math.max(0L, o.optLong("startedAt", 0L));
        r.nextCheckAt = Math.max(0L, o.optLong("nextCheckAt", 0L));
        r.failures = Math.max(0, o.optInt("failures", 0));
        r.status = o.optString("status", "NEW");
        r.lastError = o.optString("lastError", "");
        r.stageStartedAt = Math.max(0L, o.optLong("stageStartedAt", 0L));
        r.lastDiagnostic = o.optString("lastDiagnostic", "");
        return r;
    }

    private static String normalizeMode(String mode) {
        if (MODE_PROBE.equals(mode)) return MODE_PROBE;
        if (MODE_MANUAL.equals(mode)) return MODE_MANUAL;
        return MODE_AUTO;
    }

    private static String normalizeEntry(String entry) {
        if (ENTRY_DEEPLINK.equals(entry)) return ENTRY_DEEPLINK;
        if (ENTRY_LAUNCHER.equals(entry)) return ENTRY_LAUNCHER;
        return ENTRY_UNKNOWN;
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
