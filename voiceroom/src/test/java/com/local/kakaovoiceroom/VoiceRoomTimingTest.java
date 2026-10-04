package com.local.kakaovoiceroom;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VoiceRoomTimingTest {
    @Test
    public void unknownStartUsesPeriodicProbe() {
        long now = 1_000_000L;
        assertEquals(now + VoiceRoomStore.UNKNOWN_ACTIVE_RECHECK_MS,
                VoiceRoomTiming.nextActiveCheck(0L, now));
    }

    @Test
    public void knownStartSchedulesFiveMinutePrecheck() {
        long startedAt = 10_000L;
        long now = startedAt + 2L * 60L * 60L * 1000L;
        long expected = startedAt + VoiceRoomStore.VOICE_ROOM_LIFETIME_MS - VoiceRoomStore.PRECHECK_MS;
        assertEquals(expected, VoiceRoomTiming.nextActiveCheck(startedAt, now));
    }

    @Test
    public void finalWindowPollsAtMostOncePerMinute() {
        long startedAt = 10_000L;
        long expiry = startedAt + VoiceRoomStore.VOICE_ROOM_LIFETIME_MS;
        long now = expiry - 4L * 60L * 1000L;
        assertEquals(now + 60_000L, VoiceRoomTiming.nextActiveCheck(startedAt, now));
    }

    @Test
    public void overdueActiveRoomIsRecheckedInsteadOfAssumedDead() {
        long startedAt = 10_000L;
        long expiry = startedAt + VoiceRoomStore.VOICE_ROOM_LIFETIME_MS;
        long now = expiry + 30_000L;
        assertEquals(now + 60_000L, VoiceRoomTiming.nextActiveCheck(startedAt, now));
    }
}
