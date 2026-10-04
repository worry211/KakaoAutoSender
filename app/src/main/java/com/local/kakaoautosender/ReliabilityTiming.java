package com.local.kakaoautosender;

import android.content.Context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Timing policy for stable multi-room delivery.
 *
 * Interval schedules keep the user's configured minute interval as the base and
 * add a small deterministic 3-10 second offset per cycle. The offset is derived
 * from the room route and base timestamp so repeated scheduler scans do not keep
 * pushing the same run farther into the future.
 *
 * Cross-room dispatch spacing is random 2-5 seconds and is applied by
 * SendAlarmReceiver between separate alarm invocations. This avoids holding a
 * BroadcastReceiver open for a long batch while still preventing reply actions
 * from firing back-to-back.
 */
final class ReliabilityTiming {
    static final long INTERVAL_JITTER_MIN_MS = 3_000L;
    static final long INTERVAL_JITTER_MAX_MS = 10_000L;
    static final long ROOM_GAP_MIN_MS = 2_000L;
    static final long ROOM_GAP_MAX_MS = 5_000L;
    static final long RETRY_GAP_MIN_MS = 650L;
    static final long RETRY_GAP_MAX_MS = 1_250L;
    private static final long DUE_TOLERANCE_MS = 750L;

    private ReliabilityTiming() {}

    static long effectiveDueAt(MultiRoomStore.Profile profile) {
        if (profile == null || profile.nextAt <= 0L) return 0L;
        if (profile.fixedTimes()) return profile.nextAt;
        return profile.nextAt + intervalJitterMillis(profile);
    }

    static long nextEffectiveDueAt(Context context) {
        long min = Long.MAX_VALUE;
        for (MultiRoomStore.Profile p : MultiRoomStore.list(context)) {
            if (!isRunnable(context, p)) continue;
            long due = effectiveDueAt(p);
            if (due > 0L) min = Math.min(min, due);
        }
        return min == Long.MAX_VALUE ? 0L : min;
    }

    static ArrayList<MultiRoomStore.Profile> due(Context context, long now) {
        ArrayList<MultiRoomStore.Profile> result = new ArrayList<>();
        for (MultiRoomStore.Profile p : MultiRoomStore.list(context)) {
            if (!isRunnable(context, p)) continue;
            long effective = effectiveDueAt(p);
            if (effective > 0L && effective <= now + DUE_TOLERANCE_MS) result.add(p);
        }
        result.sort(Comparator.comparingLong(ReliabilityTiming::effectiveDueAt));
        return result;
    }

    static long intervalJitterMillis(MultiRoomStore.Profile profile) {
        if (profile == null || profile.fixedTimes() || profile.nextAt <= 0L) return 0L;
        long mixed = 0x9E3779B97F4A7C15L;
        String route = profile.room == null ? "" : profile.room;
        for (int i = 0; i < route.length(); i++) {
            mixed ^= route.charAt(i);
            mixed *= 0x100000001B3L;
            mixed ^= (mixed >>> 29);
        }
        mixed ^= profile.nextAt;
        mixed ^= (mixed >>> 33);
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= (mixed >>> 33);
        long span = INTERVAL_JITTER_MAX_MS - INTERVAL_JITTER_MIN_MS + 1L;
        long offset = Math.floorMod(mixed, span);
        return INTERVAL_JITTER_MIN_MS + offset;
    }

    static long intervalJitterMillis(MultiRoomStore.Profile profile, long baseTimestamp) {
        if (profile == null) return 0L;
        MultiRoomStore.Profile copy = profile.copy();
        copy.nextAt = baseTimestamp;
        return intervalJitterMillis(copy);
    }

    static long roomGapMillis() {
        return ThreadLocalRandom.current().nextLong(ROOM_GAP_MIN_MS, ROOM_GAP_MAX_MS + 1L);
    }

    static long retryGapMillis() {
        return ThreadLocalRandom.current().nextLong(RETRY_GAP_MIN_MS, RETRY_GAP_MAX_MS + 1L);
    }

    static int secondsCeil(long millis) {
        return (int) Math.max(1L, (millis + 999L) / 1_000L);
    }

    private static boolean isRunnable(Context context, MultiRoomStore.Profile p) {
        return p != null && p.enabled && RoomMediaStore.hasPayload(context, p);
    }
}
