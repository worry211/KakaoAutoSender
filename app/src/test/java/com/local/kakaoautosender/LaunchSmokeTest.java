package com.local.kakaoautosender;

import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LaunchSmokeTest {
  private <T extends Activity> T setup(Class<T> type) {
    try {
      return Robolectric.buildActivity(type).setup().get();
    } catch (Throwable t) {
      t.printStackTrace(System.err);
      throw t;
    }
  }

  @Test
  public void licenseActivityBuildsAndResumesWithoutCrash() {
    Activity activity = setup(LicenseActivity.class);
    assertNotNull(activity.getWindow().getDecorView());
  }

  @Test
  public void integrityGateBuildsWithoutCrashInDebug() {
    Activity activity = setup(IntegrityGateActivity.class);
    assertNotNull(activity);
  }

  @Test
  public void dashboardBuildsAndResumesWithoutCrash() {
    Activity activity = setup(MainActivityV4.class);
    assertNotNull(activity.getWindow().getDecorView());
  }

  @Test
  public void editorBuildsWithoutCrashWhenRoomExists() {
    android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
    MultiRoomStore.ensureMigrated(context);
    MultiRoomStore.Profile profile = new MultiRoomStore.Profile("route-smoke");
    profile.actualRoomName = "Smoke Room";
    profile.displayName = "Smoke Room";
    MultiRoomStore.upsert(context, profile);

    android.content.Intent intent = new android.content.Intent(context, RoomEditorActivity.class);
    intent.putExtra(RoomEditorActivity.EXTRA_ROOM, "route-smoke");
    try {
      Activity activity = Robolectric.buildActivity(RoomEditorActivity.class, intent).setup().get();
      assertNotNull(activity.getWindow().getDecorView());
    } catch (Throwable t) {
      t.printStackTrace(System.err);
      throw t;
    }
  }
}
