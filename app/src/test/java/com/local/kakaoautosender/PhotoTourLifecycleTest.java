package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
public class PhotoTourLifecycleTest {
  private Context c;
  private PhotoTourQueue q;

  private void set(String name, Object value) throws Exception {
    Field f = PhotoTourController.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(null, value);
  }

  private Object get(String name) throws Exception {
    Field f = PhotoTourController.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(null);
  }

  @Before
  public void setup() throws Exception {
    c = RuntimeEnvironment.getApplication();
    PhotoTourController.stop(c, "reset");
    Prefs.p(c).edit().clear().commit();
    Settings.Global.putInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, 4);
    c.getSharedPreferences("entitlement_v2", 0)
        .edit()
        .clear()
        .putString("state", "ACTIVE")
        .putLong("validated_elapsed", SystemClock.elapsedRealtime())
        .putInt("validated_boot", 4)
        .putLong("server_time", 100000)
        .putLong("expiry", 0)
        .putLong("grace", 600)
        .commit();
    Shadows.shadowOf((PowerManager) c.getSystemService(Context.POWER_SERVICE))
        .setIsInteractive(true);
    Shadows.shadowOf((KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE))
        .setIsDeviceLocked(false);
    q = new PhotoTourQueue(true);
    MultiRoomStore.Profile p = new MultiRoomStore.Profile("a");
    q.jobs.add(
        new PhotoTourQueue.Job(
            p,
            new RoomMediaStore.Media("content://photos/a", "image/png", "a"),
            System.currentTimeMillis() + 60000));
    set("app", c);
    set("queue", q);
    set("phase", PhotoTourController.Phase.WAIT_DUE);
  }

  @After
  public void stop() {
    PhotoTourController.stop(c, "test cleanup");
  }

  @Test
  public void oldRegistrationStopCannotCancelNewRegistration() throws Exception {
    set("queue", null);
    set("phase", PhotoTourController.Phase.REGISTER_READY);
    set("registrationRun", "new-registration");
    PhotoTourController.stopRun(c, "old-registration");
    assertTrue(PhotoTourController.active());
    PhotoTourController.stopRun(c, "new-registration");
    assertFalse(PhotoTourController.active());
  }

  @Test
  public void oldAlarmAndOldStopCannotAffectNewTour() {
    PhotoTourController.tick(c, "old");
    PhotoTourController.stopRun(c, "old");
    assertTrue(PhotoTourController.active());
    assertNull(q.inFlight);
    PhotoTourController.stopRun(c, q.run);
    assertFalse(PhotoTourController.active());
  }

  @Test
  public void screenOffPausesWithoutClaimAndScreenOnReschedulesOnly() throws Exception {
    Shadows.shadowOf((PowerManager) c.getSystemService(Context.POWER_SERVICE))
        .setIsInteractive(false);
    PhotoTourController.tick(c, q.run);
    assertEquals(PhotoTourController.Phase.SCREEN_WAIT, get("phase"));
    assertNull(q.inFlight);
    Shadows.shadowOf((PowerManager) c.getSystemService(Context.POWER_SERVICE))
        .setIsInteractive(true);
    PhotoTourController.screenChanged(c);
    assertEquals(PhotoTourController.Phase.WAIT_DUE, get("phase"));
    assertNull(q.inFlight);
  }

  @Test
  public void screenOffDuringSendingStopsWithoutReplayingUncertainPhoto() throws Exception {
    set("phase", PhotoTourController.Phase.SENDING);
    q.claim(System.currentTimeMillis() + 60000);
    Shadows.shadowOf((PowerManager) c.getSystemService(Context.POWER_SERVICE))
        .setIsInteractive(false);
    PhotoTourController.screenChanged(c);
    assertFalse(PhotoTourController.active());
    assertTrue(q.stopped);
    assertNull(q.claim(Long.MAX_VALUE));
  }

  @Test
  public void processLossClearsOrphanNotificationWithoutRestoringJobs() throws Exception {
    Prefs.p(c).edit().putBoolean("photo_tour_running", true).commit();
    set("queue", null);
    set("phase", PhotoTourController.Phase.IDLE);
    PhotoTourController.tick(c, q.run);
    assertFalse(PhotoTourController.active());
    assertFalse(Prefs.p(c).getBoolean("photo_tour_running", true));
  }

  @Test
  public void backgroundLicenseStopIsSerializedOnMainThread() throws Exception {
    Thread worker = new Thread(() -> PhotoTourController.stop(c, "license stop"));
    worker.start();
    worker.join();
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    assertFalse(PhotoTourController.active());
    assertTrue(q.stopped);
  }

  @Test
  public void globalStopClearsTourAndDoesNotRestoreOnLateCompletion() {
    PhotoTourController.stop(c, "stop");
    PhotoTourController.completed("a", "old-loop");
    assertFalse(PhotoTourController.active());
    assertTrue(q.stopped);
  }

  @Test
  public void openingAnIntermediateRoomWaitsWithoutBindingSendingOrAbortingTheTour()
      throws Exception {
    android.content.pm.PackageInfo info = new android.content.pm.PackageInfo();
    info.packageName = KakaoNotificationListener.KAKAO_PACKAGE;
    info.versionCode = 7;
    Shadows.shadowOf(c.getPackageManager()).installPackage(info);
    PhotoTourBindings.Binding binding =
        new PhotoTourBindings.Binding(
            "a",
            new PhotoUiWorkflow.Anchor(1, "editor", "expected-room"),
            new java.util.ArrayList<>(java.util.Collections.singletonList("identity-a")),
            7,
            new PhotoUiWorkflow.Button(2, "photo.Activity", "layout", "send", "Button", "전송"));
    assertEquals("", PhotoTourBindings.save(c, binding));
    set("phase", PhotoTourController.Phase.ROUTE_OPEN);
    set("alias", "a");
    set("before", null);
    set("windowObserved", true);
    set("panelOff", false);
    PhotoTourController.observeChat(new PhotoUiWorkflow.Anchor(3, "editor", "previous-room"));
    assertEquals(PhotoTourController.Phase.ROUTE_OPEN, get("phase"));
    assertTrue(PhotoTourController.active());
    assertFalse(q.stopped);
    assertNull(q.inFlight);
  }
}
