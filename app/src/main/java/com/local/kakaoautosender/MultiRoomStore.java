package com.local.kakaoautosender;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

final class MultiRoomStore {
    static final String KEY_PROFILES = "multi_room_profiles_v1";
    private static final String KEY_MIGRATED = "multi_room_migrated_v1";

    static final String MODE_INTERVAL = "interval";
    static final String MODE_TIMES = "times";

    static final class Profile {
        String room;           // internal routing alias used by KakaoNotificationListener
        String actualRoomName; // visible Kakao conversation name
        String displayName;    // legacy/private label; kept for backwards compatibility
        String message;
        String scheduleMode;
        int intervalMinutes;
        String dailyTimes;
        int dailyLimit;        // 0 = unlimited
        boolean enabled;
        long nextAt;
        String countDate;
        int todayCount;
        int failureStreak;
        long lastSuccessAt;
        String lastStatus;
        String executionId;
        String completedExecutionId;

        Profile(String room) {
            this.room = safe(room).trim();
            this.actualRoomName = this.room;
            this.displayName = this.room;
            this.message = "";
            this.scheduleMode = MODE_INTERVAL;
            this.intervalMinutes = 60;
            this.dailyTimes = "09:00";
            this.dailyLimit = 8;
            this.enabled = true;
            this.nextAt = 0L;
            this.countDate = LocalDate.now().toString();
            this.todayCount = 0;
            this.failureStreak = 0;
            this.lastSuccessAt = 0L;
            this.lastStatus = "아직 전송 기록 없음";
            this.executionId = "";
            this.completedExecutionId = "";
        }

        Profile copy() {
            Profile p = new Profile(room);
            p.actualRoomName = actualRoomName;
            p.displayName = displayName;
            p.message = message;
            p.scheduleMode = scheduleMode;
            p.intervalMinutes = intervalMinutes;
            p.dailyTimes = dailyTimes;
            p.dailyLimit = dailyLimit;
            p.enabled = enabled;
            p.nextAt = nextAt;
            p.countDate = countDate;
            p.todayCount = todayCount;
            p.failureStreak = failureStreak;
            p.lastSuccessAt = lastSuccessAt;
            p.lastStatus = lastStatus;
            p.executionId = executionId;
            p.completedExecutionId = completedExecutionId;
            return p;
        }

        boolean unlimited() { return dailyLimit <= 0; }
        boolean fixedTimes() { return MODE_TIMES.equals(scheduleMode); }

        String title() {
            String actual = safe(actualRoomName).trim();
            if (!actual.isEmpty()) return actual;
            String legacy = safe(displayName).trim();
            return legacy.isEmpty() ? room : legacy;
        }
    }


    static final class BulkPatch {
        boolean applyMessage;
        String message = "";
        boolean applySchedule;
        String scheduleMode = MODE_INTERVAL;
        int intervalMinutes = 60;
        String dailyTimes = "09:00";
        boolean applyDailyLimit;
        int dailyLimit = 8;
        boolean applyEnabled;
        boolean enabled = true;
    }

    private MultiRoomStore() {}

    static synchronized void ensureMigrated(Context context) {
        SharedPreferences prefs = Prefs.p(context);
        if (prefs.getBoolean(KEY_MIGRATED, false)) return;

        ArrayList<Profile> profiles = readRaw(context);
        if (profiles.isEmpty()) {
            String legacyRoom = prefs.getString(Prefs.KEY_ROOM, "");
            if (legacyRoom != null && !legacyRoom.trim().isEmpty()) {
                Profile p = new Profile(legacyRoom.trim());
                p.actualRoomName = legacyRoom.trim();
                p.message = safe(prefs.getString(Prefs.KEY_MESSAGE, ""));
                p.intervalMinutes = Math.max(SendScheduler.MIN_INTERVAL_MINUTES,
                        prefs.getInt(Prefs.KEY_INTERVAL_MIN, 60));
                p.dailyLimit = Math.max(1, prefs.getInt(Prefs.KEY_MAX_PER_DAY, 8));
                p.enabled = true;
                p.nextAt = prefs.getLong(Prefs.KEY_NEXT_AT, 0L);
                p.todayCount = Prefs.getTodayCount(context);
                p.countDate = LocalDate.now().toString();
                p.failureStreak = prefs.getInt(Prefs.KEY_FAILURE_STREAK, 0);
                p.lastSuccessAt = prefs.getLong(Prefs.KEY_LAST_SUCCESS_AT, 0L);
                p.lastStatus = safe(prefs.getString(Prefs.KEY_LAST_STATUS, "기존 설정에서 가져옴"));
                profiles.add(p);
                writeRaw(context, profiles);
                Prefs.appendLog(context, "다중방 마이그레이션: 기존 단일 방 설정 보존 · " + p.title());
            }
        }
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply();
    }

    static synchronized ArrayList<Profile> list(Context context) {
        ensureMigrated(context);
        ArrayList<Profile> profiles = readRaw(context);
        boolean changed = false;
        for (Profile p : profiles) changed |= normalizeDailyCount(p);
        profiles.sort(Comparator
                .comparing((Profile p) -> !p.enabled)
                .thenComparing(p -> normalize(p.title())));
        if (changed) writeRaw(context, profiles);
        ArrayList<Profile> result = new ArrayList<>();
        for (Profile p : profiles) result.add(p.copy());
        return result;
    }

    static synchronized Profile get(Context context, String room) {
        if (room == null || room.trim().isEmpty()) return null;
        String wanted = normalize(room);
        for (Profile p : list(context)) {
            if (normalize(p.room).equals(wanted)) return p;
        }
        return null;
    }

    static synchronized ArrayList<Profile> findByActualName(Context context, String actualRoomName) {
        ArrayList<Profile> result = new ArrayList<>();
        String wanted = normalize(actualRoomName);
        if (wanted.isEmpty()) return result;
        for (Profile p : list(context)) {
            if (normalize(p.actualRoomName).equals(wanted)) result.add(p);
        }
        return result;
    }

    static synchronized int sameTitleCount(Context context, String actualRoomName) {
        return findByActualName(context, actualRoomName).size();
    }

    static synchronized void upsert(Context context, Profile profile) {
        if (profile == null || safe(profile.room).trim().isEmpty()) return;
        ensureMigrated(context);
        Profile incoming = sanitize(profile.copy());
        ArrayList<Profile> profiles = readRaw(context);
        String wanted = normalize(incoming.room);
        boolean replaced = false;
        for (int i = 0; i < profiles.size(); i++) {
            if (normalize(profiles.get(i).room).equals(wanted)) {
                profiles.set(i, incoming);
                replaced = true;
                break;
            }
        }
        if (!replaced) profiles.add(incoming);
        writeRaw(context, profiles);
    }


    static synchronized int applyBulkPatch(Context context, List<String> rooms, BulkPatch patch) {
        if (patch == null || rooms == null || rooms.isEmpty()) return 0;
        ensureMigrated(context);
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String room : rooms) {
            String normalized = normalize(room);
            if (!normalized.isEmpty()) targets.add(normalized);
        }
        if (targets.isEmpty()) return 0;

        ArrayList<Profile> profiles = readRaw(context);
        boolean active = Prefs.p(context).getBoolean(Prefs.KEY_ACTIVE, false);
        long now = System.currentTimeMillis();
        int changed = 0;
        for (Profile profile : profiles) {
            if (!targets.contains(normalize(profile.room))) continue;
            if (patch.applyMessage) profile.message = safe(patch.message);
            if (patch.applySchedule) {
                if (MODE_TIMES.equals(patch.scheduleMode)) {
                    profile.scheduleMode = MODE_TIMES;
                    String canonical = canonicalTimes(patch.dailyTimes);
                    profile.dailyTimes = canonical.isEmpty() ? "09:00" : canonical;
                } else {
                    profile.scheduleMode = MODE_INTERVAL;
                    profile.intervalMinutes = Math.max(SendScheduler.MIN_INTERVAL_MINUTES, patch.intervalMinutes);
                }
            }
            if (patch.applyDailyLimit) profile.dailyLimit = Math.max(0, patch.dailyLimit);
            if (patch.applyEnabled) profile.enabled = patch.enabled;
            sanitize(profile);
            normalizeDailyCount(profile);
            profile.nextAt = active && isRunnable(context, profile) ? computeNextAt(profile, now) : 0L;
            changed++;
        }
        if (changed > 0) writeRaw(context, profiles);
        return changed;
    }

    static synchronized boolean remove(Context context, String room) {
        if (room == null || room.trim().isEmpty()) return false;
        ensureMigrated(context);
        ArrayList<Profile> profiles = readRaw(context);
        String wanted = normalize(room);
        boolean removed = profiles.removeIf(p -> normalize(p.room).equals(wanted));
        if (removed) writeRaw(context, profiles);
        return removed;
    }

    static synchronized void setAllNextFromNow(Context context) {
        ensureMigrated(context);
        long now = System.currentTimeMillis();
        ArrayList<Profile> profiles = readRaw(context);
        for (Profile p : profiles) {
            sanitize(p);
            normalizeDailyCount(p);
            p.nextAt = isRunnable(context, p) ? computeNextAt(p, now) : 0L;
        }
        writeRaw(context, profiles);
    }

    static synchronized void repairNextTimesAfterRestore(Context context) {
        ensureMigrated(context);
        long now = System.currentTimeMillis();
        ArrayList<Profile> profiles = readRaw(context);
        for (Profile p : profiles) {
            sanitize(p);
            normalizeDailyCount(p);
            if (!isRunnable(context, p)) p.nextAt = 0L;
            else if (p.nextAt <= now) p.nextAt = computeNextAt(p, now);
        }
        writeRaw(context, profiles);
    }

    static synchronized long nextDueAt(Context context) {
        long min = Long.MAX_VALUE;
        for (Profile p : list(context)) {
            if (!isRunnable(context, p) || p.nextAt <= 0L) continue;
            min = Math.min(min, p.nextAt);
        }
        return min == Long.MAX_VALUE ? 0L : min;
    }

    static synchronized ArrayList<Profile> due(Context context, long now) {
        ArrayList<Profile> result = new ArrayList<>();
        for (Profile p : list(context)) {
            if (!isRunnable(context, p)) continue;
            if (p.nextAt > 0L && p.nextAt <= now + 2_000L) result.add(p);
        }
        result.sort(Comparator.comparingLong(a -> a.nextAt));
        return result;
    }

    static long computeNextAt(Profile profile, long fromMillis) {
        Profile p = sanitize(profile.copy());
        if (p.fixedTimes()) {
            List<LocalTime> times = parseTimes(p.dailyTimes);
            if (times.isEmpty()) times = Collections.singletonList(LocalTime.of(9, 0));
            ZoneId zone = ZoneId.systemDefault();
            LocalDateTime from = LocalDateTime.ofInstant(Instant.ofEpochMilli(fromMillis), zone).plusSeconds(2);
            for (LocalTime time : times) {
                LocalDateTime candidate = LocalDateTime.of(from.toLocalDate(), time);
                if (candidate.isAfter(from)) return candidate.atZone(zone).toInstant().toEpochMilli();
            }
            return LocalDateTime.of(from.toLocalDate().plusDays(1), times.get(0))
                    .atZone(zone).toInstant().toEpochMilli();
        }
        return fromMillis + Math.max(SendScheduler.MIN_INTERVAL_MINUTES, p.intervalMinutes) * 60_000L;
    }

    static long nextAfterDailyLimit(Profile profile, long nowMillis) {
        Profile p = sanitize(profile.copy());
        ZoneId zone = ZoneId.systemDefault();
        LocalDate tomorrow = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone).toLocalDate().plusDays(1);
        if (p.fixedTimes()) {
            List<LocalTime> times = parseTimes(p.dailyTimes);
            LocalTime first = times.isEmpty() ? LocalTime.of(9, 0) : times.get(0);
            return LocalDateTime.of(tomorrow, first).atZone(zone).toInstant().toEpochMilli();
        }
        return LocalDateTime.of(tomorrow, LocalTime.of(0, 1)).atZone(zone).toInstant().toEpochMilli()
                + Math.max(SendScheduler.MIN_INTERVAL_MINUTES, p.intervalMinutes) * 60_000L;
    }

    static String scheduleSummary(Profile p) {
        if (p == null) return "-";
        if (p.fixedTimes()) return "매일 " + canonicalTimes(p.dailyTimes);
        int minutes = Math.max(SendScheduler.MIN_INTERVAL_MINUTES, p.intervalMinutes);
        if (minutes % 60 == 0) return (minutes / 60) + "시간마다";
        return minutes + "분마다";
    }

    static String canonicalTimes(String raw) {
        List<LocalTime> times = parseTimes(raw);
        StringBuilder sb = new StringBuilder();
        for (LocalTime t : times) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.format(Locale.ROOT, "%02d:%02d", t.getHour(), t.getMinute()));
        }
        return sb.toString();
    }

    static boolean hasValidTimes(String raw) { return !parseTimes(raw).isEmpty(); }

    static synchronized void markSuccess(Context context, String room, long nextAt, String status) {
        mutate(context, room, p -> {
            if (!p.executionId.isEmpty() && p.executionId.equals(p.completedExecutionId)) return;
            p.completedExecutionId = p.executionId;
            normalizeDailyCount(p);
            p.todayCount++;
            p.failureStreak = 0;
            p.lastSuccessAt = System.currentTimeMillis();
            p.nextAt = nextAt;
            p.lastStatus = safe(status);
        });
    }

    static synchronized boolean reserveExecution(Context context, Profile expected, long nextAt) {
        Profile current = get(context, expected.room);
        if (current == null || !current.enabled || current.nextAt != expected.nextAt
                || !current.message.equals(expected.message)
                || current.intervalMinutes != expected.intervalMinutes
                || !current.dailyTimes.equals(expected.dailyTimes)
                || !current.scheduleMode.equals(expected.scheduleMode)) return false;
        current.nextAt = nextAt;
        current.executionId = java.util.UUID.randomUUID().toString();
        upsert(context, current); // Synchronous commit must succeed before Kakao is touched.
        return true;
    }

    static synchronized void markFailure(Context context, String room, long nextAt, String status) {
        mutate(context, room, p -> {
            normalizeDailyCount(p);
            p.failureStreak++;
            p.nextAt = nextAt;
            p.lastStatus = safe(status);
        });
    }

    static synchronized void markSkippedForLimit(Context context, String room, long nextAt, String status) {
        mutate(context, room, p -> {
            normalizeDailyCount(p);
            p.nextAt = nextAt;
            p.lastStatus = safe(status);
        });
    }

    static synchronized int enabledCount(Context context) {
        int n = 0;
        for (Profile p : list(context)) if (p.enabled) n++;
        return n;
    }

    static synchronized int readyCount(Context context) {
        int n = 0;
        for (Profile p : list(context)) {
            if (p.enabled && RoomMediaStore.hasPayload(context, p) && KakaoNotificationListener.hasLiveSession(p.room)) n++;
        }
        return n;
    }

    private interface Mutation { void apply(Profile p); }

    private static void mutate(Context context, String room, Mutation mutation) {
        if (room == null || room.trim().isEmpty()) return;
        ArrayList<Profile> profiles = readRaw(context);
        String wanted = normalize(room);
        for (Profile p : profiles) {
            if (!normalize(p.room).equals(wanted)) continue;
            mutation.apply(p);
            sanitize(p);
            break;
        }
        writeRaw(context, profiles);
    }

    private static boolean isRunnable(Context context, Profile p) {
        return p != null && p.enabled && RoomMediaStore.hasPayload(context, p);
    }

    private static Profile sanitize(Profile p) {
        p.room = safe(p.room).trim();
        p.actualRoomName = safe(p.actualRoomName).trim();
        if (p.actualRoomName.isEmpty()) p.actualRoomName = p.room;
        p.displayName = safe(p.displayName).trim();
        if (p.displayName.isEmpty()) p.displayName = p.actualRoomName;
        p.message = safe(p.message);
        p.scheduleMode = MODE_TIMES.equals(p.scheduleMode) ? MODE_TIMES : MODE_INTERVAL;
        p.intervalMinutes = Math.max(SendScheduler.MIN_INTERVAL_MINUTES, p.intervalMinutes <= 0 ? 60 : p.intervalMinutes);
        String canonical = canonicalTimes(p.dailyTimes);
        p.dailyTimes = canonical.isEmpty() ? "09:00" : canonical;
        p.dailyLimit = Math.max(0, p.dailyLimit);
        p.countDate = safe(p.countDate);
        p.lastStatus = safe(p.lastStatus);
        if (p.lastStatus.isEmpty()) p.lastStatus = "아직 전송 기록 없음";
        normalizeDailyCount(p);
        return p;
    }

    private static boolean normalizeDailyCount(Profile p) {
        String today = LocalDate.now().toString();
        if (!today.equals(p.countDate)) {
            p.countDate = today;
            p.todayCount = 0;
            return true;
        }
        return false;
    }

    private static List<LocalTime> parseTimes(String raw) {
        LinkedHashSet<LocalTime> unique = new LinkedHashSet<>();
        if (raw != null) {
            String[] parts = raw.trim().split("[,\\n\\s]+");
            for (String part : parts) {
                if (part == null || part.trim().isEmpty()) continue;
                String[] hm = part.trim().split(":");
                if (hm.length != 2) continue;
                try {
                    int h = Integer.parseInt(hm[0]);
                    int m = Integer.parseInt(hm[1]);
                    if (h < 0 || h > 23 || m < 0 || m > 59) continue;
                    unique.add(LocalTime.of(h, m));
                } catch (Exception ignored) {}
            }
        }
        ArrayList<LocalTime> result = new ArrayList<>(unique);
        Collections.sort(result);
        return result;
    }

    private static ArrayList<Profile> readRaw(Context context) {
        ArrayList<Profile> result = new ArrayList<>();
        String raw = Prefs.p(context).getString(KEY_PROFILES, "[]");
        try {
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String room = o.optString("room", "").trim();
                if (room.isEmpty()) continue;
                Profile p = new Profile(room);
                p.actualRoomName = o.optString("actualRoomName", room);
                p.displayName = o.optString("displayName", p.actualRoomName);
                p.message = o.optString("message", "");
                p.scheduleMode = o.optString("scheduleMode", MODE_INTERVAL);
                p.intervalMinutes = o.optInt("intervalMinutes", 60);
                p.dailyTimes = o.optString("dailyTimes", "09:00");
                p.dailyLimit = o.optInt("dailyLimit", 8);
                p.enabled = o.optBoolean("enabled", true);
                p.nextAt = o.optLong("nextAt", 0L);
                p.countDate = o.optString("countDate", LocalDate.now().toString());
                p.todayCount = o.optInt("todayCount", 0);
                p.failureStreak = o.optInt("failureStreak", 0);
                p.lastSuccessAt = o.optLong("lastSuccessAt", 0L);
                p.lastStatus = o.optString("lastStatus", "아직 전송 기록 없음");
                p.executionId = o.optString("executionId", "");
                p.completedExecutionId = o.optString("completedExecutionId", "");
                result.add(sanitize(p));
            }
        } catch (Throwable t) {
            Prefs.appendLog(context, "다중방 설정 읽기 실패: " + t.getClass().getSimpleName());
        }
        return result;
    }

    private static void writeRaw(Context context, ArrayList<Profile> profiles) {
        JSONArray arr = new JSONArray();
        try {
            for (Profile p0 : profiles) {
                Profile p = sanitize(p0.copy());
                if (p.room.isEmpty()) continue;
                JSONObject o = new JSONObject();
                o.put("room", p.room);
                o.put("actualRoomName", p.actualRoomName);
                o.put("displayName", p.displayName);
                o.put("message", p.message);
                o.put("scheduleMode", p.scheduleMode);
                o.put("intervalMinutes", p.intervalMinutes);
                o.put("dailyTimes", p.dailyTimes);
                o.put("dailyLimit", p.dailyLimit);
                o.put("enabled", p.enabled);
                o.put("nextAt", p.nextAt);
                o.put("countDate", p.countDate);
                o.put("todayCount", p.todayCount);
                o.put("failureStreak", p.failureStreak);
                o.put("lastSuccessAt", p.lastSuccessAt);
                o.put("lastStatus", p.lastStatus);
                o.put("executionId", p.executionId);
                o.put("completedExecutionId", p.completedExecutionId);
                arr.put(o);
            }
        if (!Prefs.p(context).edit().putString(KEY_PROFILES, arr.toString()).commit()) {
            throw new IllegalStateException("Schedule state could not be persisted");
        }
        } catch (Throwable t) {
            Prefs.appendLog(context, "다중방 설정 저장 실패: " + t.getClass().getSimpleName());
            throw new IllegalStateException("Schedule persistence failed", t);
        }
    }

    private static String normalize(String s) { return RoomRouting.normalizeTitle(s); }
    private static String safe(String s) { return s == null ? "" : s; }
}
