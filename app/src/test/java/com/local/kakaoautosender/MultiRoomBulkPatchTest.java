package com.local.kakaoautosender;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MultiRoomBulkPatchTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.p(context).edit().clear().commit();
        MultiRoomStore.ensureMigrated(context);

        MultiRoomStore.Profile a = new MultiRoomStore.Profile("route-a");
        a.actualRoomName = "A방";
        a.message = "old-a";
        a.intervalMinutes = 10;
        a.dailyLimit = 5;
        a.enabled = true;
        MultiRoomStore.upsert(context, a);

        MultiRoomStore.Profile b = new MultiRoomStore.Profile("route-b");
        b.actualRoomName = "B방";
        b.message = "old-b";
        b.intervalMinutes = 30;
        b.dailyLimit = 9;
        b.enabled = true;
        MultiRoomStore.upsert(context, b);
    }

    @Test
    public void bulkPatch_updatesOnlySelectedRoomsAndCheckedFields() {
        MultiRoomStore.BulkPatch patch = new MultiRoomStore.BulkPatch();
        patch.applyMessage = true;
        patch.message = "shared-message";
        patch.applyDailyLimit = true;
        patch.dailyLimit = 0;

        assertEquals(1, MultiRoomStore.applyBulkPatch(context, Collections.singletonList("route-a"), patch));

        MultiRoomStore.Profile a = MultiRoomStore.get(context, "route-a");
        MultiRoomStore.Profile b = MultiRoomStore.get(context, "route-b");
        assertEquals("shared-message", a.message);
        assertTrue(a.unlimited());
        assertEquals(10, a.intervalMinutes);

        assertEquals("old-b", b.message);
        assertEquals(9, b.dailyLimit);
        assertEquals(30, b.intervalMinutes);
    }

    @Test
    public void bulkPatch_canChangeScheduleAndEnabledStateInOneWrite() {
        MultiRoomStore.BulkPatch patch = new MultiRoomStore.BulkPatch();
        patch.applySchedule = true;
        patch.scheduleMode = MultiRoomStore.MODE_TIMES;
        patch.dailyTimes = "21:00, 09:00, 21:00";
        patch.applyEnabled = true;
        patch.enabled = false;

        assertEquals(2, MultiRoomStore.applyBulkPatch(context, Arrays.asList("route-a", "route-b"), patch));

        MultiRoomStore.Profile a = MultiRoomStore.get(context, "route-a");
        MultiRoomStore.Profile b = MultiRoomStore.get(context, "route-b");
        assertTrue(a.fixedTimes());
        assertEquals("09:00, 21:00", a.dailyTimes);
        assertFalse(a.enabled);
        assertEquals(0L, a.nextAt);
        assertTrue(b.fixedTimes());
        assertEquals("09:00, 21:00", b.dailyTimes);
        assertFalse(b.enabled);
        assertEquals(0L, b.nextAt);
    }
}
