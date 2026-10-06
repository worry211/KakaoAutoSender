package com.local.kakaoautosender;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class RoomDashboardPolicyTest {
    @Test
    public void filtersRespectEnabledPausedConnectionAndErrorStates() {
        MultiRoomStore.Profile p = new MultiRoomStore.Profile("route-a");
        p.enabled = true;
        p.failureStreak = 0;

        assertTrue(RoomDashboardPolicy.matches(p, RoomDashboardPolicy.FILTER_ALL, true, true));
        assertTrue(RoomDashboardPolicy.matches(p, RoomDashboardPolicy.FILTER_ENABLED, true, true));
        assertFalse(RoomDashboardPolicy.matches(p, RoomDashboardPolicy.FILTER_PAUSED, true, true));
        assertFalse(RoomDashboardPolicy.matches(p, RoomDashboardPolicy.FILTER_NEEDS_CONNECTION, true, true));

        assertTrue(RoomDashboardPolicy.matches(p, RoomDashboardPolicy.FILTER_NEEDS_CONNECTION, false, true));
        p.failureStreak = 2;
        assertTrue(RoomDashboardPolicy.matches(p, RoomDashboardPolicy.FILTER_ERROR, true, true));
        p.enabled = false;
        assertTrue(RoomDashboardPolicy.matches(p, RoomDashboardPolicy.FILTER_PAUSED, true, true));
    }

    @Test
    public void nextSortPutsScheduledRoomsFirstAndUnscheduledLast() {
        MultiRoomStore.Profile late = new MultiRoomStore.Profile("late");
        late.actualRoomName = "나";
        late.nextAt = 3000L;
        MultiRoomStore.Profile early = new MultiRoomStore.Profile("early");
        early.actualRoomName = "가";
        early.nextAt = 1000L;
        MultiRoomStore.Profile none = new MultiRoomStore.Profile("none");
        none.actualRoomName = "다";
        none.nextAt = 0L;

        java.util.ArrayList<MultiRoomStore.Profile> sorted = RoomDashboardPolicy.sorted(
                Arrays.asList(late, none, early), RoomDashboardPolicy.SORT_NEXT);
        assertEquals("early", sorted.get(0).room);
        assertEquals("late", sorted.get(1).room);
        assertEquals("none", sorted.get(2).room);
    }

    @Test
    public void statusSortPrioritizesErrorsThenEnabledThenPaused() {
        MultiRoomStore.Profile paused = new MultiRoomStore.Profile("paused");
        paused.enabled = false;
        MultiRoomStore.Profile enabled = new MultiRoomStore.Profile("enabled");
        enabled.enabled = true;
        MultiRoomStore.Profile error = new MultiRoomStore.Profile("error");
        error.enabled = true;
        error.failureStreak = 1;

        java.util.ArrayList<MultiRoomStore.Profile> sorted = RoomDashboardPolicy.sorted(
                Arrays.asList(paused, enabled, error), RoomDashboardPolicy.SORT_STATUS);
        assertEquals("error", sorted.get(0).room);
        assertEquals("enabled", sorted.get(1).room);
        assertEquals("paused", sorted.get(2).room);
    }
}
