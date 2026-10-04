package com.local.kakaoautosender;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

public class MultiRoomStoreTest {
    @Test
    public void canonicalTimes_sortsAndDeduplicates() {
        assertEquals("09:00, 13:30, 20:00",
                MultiRoomStore.canonicalTimes("20:00 09:00,13:30,09:00"));
    }

    @Test
    public void canonicalTimes_rejectsInvalidClockValues() {
        assertEquals("07:05", MultiRoomStore.canonicalTimes("25:00, 07:05, 10:99"));
        assertFalse(MultiRoomStore.hasValidTimes("25:00 99:99 nope"));
    }

    @Test
    public void intervalSummary_isHumanReadable() {
        MultiRoomStore.Profile p = new MultiRoomStore.Profile("route-test");
        p.actualRoomName = "테스트방";
        p.intervalMinutes = 60;
        assertEquals("1시간마다", MultiRoomStore.scheduleSummary(p));
        p.intervalMinutes = 5;
        assertEquals("5분마다", MultiRoomStore.scheduleSummary(p));
    }

    @Test
    public void intervalNextRun_respectsConfiguredMinutes() {
        MultiRoomStore.Profile p = new MultiRoomStore.Profile("route-test");
        p.scheduleMode = MultiRoomStore.MODE_INTERVAL;
        p.intervalMinutes = 5;
        long from = 1_700_000_000_000L;
        assertEquals(from + 5 * 60_000L, MultiRoomStore.computeNextAt(p, from));
    }

    @Test
    public void fixedTimeNextRun_usesNextConfiguredTime() {
        ZoneId zone = ZoneId.systemDefault();
        long from = LocalDateTime.of(2026, 1, 5, 10, 0)
                .atZone(zone).toInstant().toEpochMilli();
        long expected = LocalDateTime.of(2026, 1, 5, 11, 30)
                .atZone(zone).toInstant().toEpochMilli();
        MultiRoomStore.Profile p = new MultiRoomStore.Profile("route-test");
        p.scheduleMode = MultiRoomStore.MODE_TIMES;
        p.dailyTimes = "08:00, 11:30, 21:00";
        assertEquals(expected, MultiRoomStore.computeNextAt(p, from));
    }

    @Test
    public void visibleTitle_prefersActualKakaoRoomNameOverInternalRouteAlias() {
        MultiRoomStore.Profile p = new MultiRoomStore.Profile("route-1234");
        p.actualRoomName = "발로란트 종합 소통방";
        p.displayName = "legacy alias";
        assertEquals("발로란트 종합 소통방", p.title());
        assertTrue(p.room.startsWith("route-"));
    }
}
