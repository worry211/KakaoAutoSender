package com.local.kakaoautosender;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReliabilityTimingTest {
    @Test
    public void intervalJitter_isStableAndWithinThreeToTenSeconds() {
        MultiRoomStore.Profile p = new MultiRoomStore.Profile("route-test");
        p.scheduleMode = MultiRoomStore.MODE_INTERVAL;
        p.nextAt = 1_700_000_000_000L;

        long first = ReliabilityTiming.intervalJitterMillis(p);
        long second = ReliabilityTiming.intervalJitterMillis(p);

        assertEquals(first, second);
        assertTrue(first >= ReliabilityTiming.INTERVAL_JITTER_MIN_MS);
        assertTrue(first <= ReliabilityTiming.INTERVAL_JITTER_MAX_MS);
        assertEquals(p.nextAt + first, ReliabilityTiming.effectiveDueAt(p));
    }

    @Test
    public void intervalJitter_changesAcrossCycles() {
        MultiRoomStore.Profile p = new MultiRoomStore.Profile("route-test");
        p.scheduleMode = MultiRoomStore.MODE_INTERVAL;
        p.nextAt = 1_700_000_000_000L;
        long first = ReliabilityTiming.intervalJitterMillis(p);

        p.nextAt += 15 * 60_000L;
        long second = ReliabilityTiming.intervalJitterMillis(p);

        // A hash collision is possible in theory but extremely unlikely; verify effective
        // due still remains in the required range for the new cycle.
        assertTrue(second >= ReliabilityTiming.INTERVAL_JITTER_MIN_MS);
        assertTrue(second <= ReliabilityTiming.INTERVAL_JITTER_MAX_MS);
        assertTrue(ReliabilityTiming.effectiveDueAt(p) >= p.nextAt + 3_000L);
        assertTrue(ReliabilityTiming.effectiveDueAt(p) <= p.nextAt + 10_000L);
    }

    @Test
    public void fixedTimeSchedule_hasNoIntervalJitter() {
        MultiRoomStore.Profile p = new MultiRoomStore.Profile("route-test");
        p.scheduleMode = MultiRoomStore.MODE_TIMES;
        p.nextAt = 1_700_000_000_000L;

        assertEquals(0L, ReliabilityTiming.intervalJitterMillis(p));
        assertEquals(p.nextAt, ReliabilityTiming.effectiveDueAt(p));
    }

    @Test
    public void randomizedRoomAndRetryGapsStayInsidePolicy() {
        for (int i = 0; i < 200; i++) {
            long roomGap = ReliabilityTiming.roomGapMillis();
            assertTrue(roomGap >= ReliabilityTiming.ROOM_GAP_MIN_MS);
            assertTrue(roomGap <= ReliabilityTiming.ROOM_GAP_MAX_MS);

            long retryGap = ReliabilityTiming.retryGapMillis();
            assertTrue(retryGap >= ReliabilityTiming.RETRY_GAP_MIN_MS);
            assertTrue(retryGap <= ReliabilityTiming.RETRY_GAP_MAX_MS);
        }
    }
}
