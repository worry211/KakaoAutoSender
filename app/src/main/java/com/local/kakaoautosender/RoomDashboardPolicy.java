package com.local.kakaoautosender;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class RoomDashboardPolicy {
    static final String FILTER_ALL = "all";
    static final String FILTER_ENABLED = "enabled";
    static final String FILTER_PAUSED = "paused";
    static final String FILTER_NEEDS_CONNECTION = "needs_connection";
    static final String FILTER_ERROR = "error";

    static final String SORT_STATUS = "status";
    static final String SORT_NEXT = "next";
    static final String SORT_NAME = "name";

    private RoomDashboardPolicy() {}

    static boolean matches(MultiRoomStore.Profile p, String filter, boolean live, boolean stored) {
        if (p == null) return false;
        if (FILTER_ENABLED.equals(filter)) return p.enabled;
        if (FILTER_PAUSED.equals(filter)) return !p.enabled;
        if (FILTER_NEEDS_CONNECTION.equals(filter)) return p.enabled && (!live || !stored);
        if (FILTER_ERROR.equals(filter)) return p.failureStreak > 0;
        return true;
    }

    static ArrayList<MultiRoomStore.Profile> sorted(List<MultiRoomStore.Profile> input, String sort) {
        ArrayList<MultiRoomStore.Profile> result = new ArrayList<>();
        if (input != null) {
            for (MultiRoomStore.Profile p : input) if (p != null) result.add(p.copy());
        }
        Comparator<MultiRoomStore.Profile> comparator;
        if (SORT_NEXT.equals(sort)) {
            comparator = Comparator
                    .comparingLong((MultiRoomStore.Profile p) -> p.nextAt <= 0 ? Long.MAX_VALUE : p.nextAt)
                    .thenComparing(p -> RoomRouting.normalizeTitle(p.title()));
        } else if (SORT_NAME.equals(sort)) {
            comparator = Comparator.comparing(p -> RoomRouting.normalizeTitle(p.title()));
        } else {
            comparator = Comparator
                    .comparingInt(RoomDashboardPolicy::statusRank)
                    .thenComparing(p -> RoomRouting.normalizeTitle(p.title()));
        }
        result.sort(comparator);
        return result;
    }

    private static int statusRank(MultiRoomStore.Profile p) {
        if (p.failureStreak > 0) return 0;
        if (p.enabled) return 1;
        return 2;
    }
}
