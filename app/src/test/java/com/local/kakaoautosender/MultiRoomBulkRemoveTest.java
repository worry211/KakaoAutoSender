package com.local.kakaoautosender;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MultiRoomBulkRemoveTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.p(context).edit().clear().commit();
        MultiRoomStore.ensureMigrated(context);
        for (String alias : Arrays.asList("route-a", "route-b", "route-c")) {
            MultiRoomStore.Profile p = new MultiRoomStore.Profile(alias);
            p.actualRoomName = alias;
            MultiRoomStore.upsert(context, p);
        }
    }

    @Test
    public void removeMany_removesOnlyTargetsAndDeduplicatesInput() {
        int removed = MultiRoomStore.removeMany(context,
                Arrays.asList("route-a", "route-a", "route-missing", "route-c"));
        assertEquals(2, removed);
        assertNull(MultiRoomStore.get(context, "route-a"));
        assertNotNull(MultiRoomStore.get(context, "route-b"));
        assertNull(MultiRoomStore.get(context, "route-c"));
        assertEquals(1, MultiRoomStore.list(context).size());
    }
}
