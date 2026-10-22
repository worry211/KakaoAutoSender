package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import java.lang.reflect.Field;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 35})
public class PanelOffTest {
  Context c;

  private void set(String name, Object value) throws Exception {
    Field f = PhotoTourController.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(null, value);
  }

  @Before
  public void setup() {
    c = RuntimeEnvironment.getApplication();
    PhotoTourController.stop(c, "reset");
    PanelOffManager.disconnect(c);
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
  }

  @After
  public void cleanup() {
    PhotoTourController.stop(c, "cleanup");
    PanelOffManager.end("cleanup");
  }

  @Test
  public void acceptsOnlyNumericPortNotShellOrRemoteInput() {
    assertEquals(37123, PanelOffManager.port("37123"));
    assertEquals(0, PanelOffManager.port("127.0.0.1:37123"));
    assertEquals(0, PanelOffManager.port("1;echo"));
    assertEquals(0, PanelOffManager.port("65536"));
    assertEquals(0, PanelOffManager.port("0"));
    assertEquals(0, PanelOffManager.port(null));
  }

  @Test
  public void shellClasspathIsSingleQuoted() {
    assertEquals("'/data/app/base.apk'", PanelOffManager.quote("/data/app/base.apk"));
    assertEquals("'a'\"'\"'b'", PanelOffManager.quote("a'b"));
  }

  @Test
  public void disconnectedModeCannotBeginOrTrial() {
    assertFalse(PanelOffManager.begin(c, "run").isEmpty());
    assertFalse(PanelOffManager.trial(c).isEmpty());
    assertFalse(PanelOffManager.healthy("run"));
  }

  @Test
  public void noPanelLeaseStopsBeforeClaimingPhoto() throws Exception {
    PhotoTourQueue q = new PhotoTourQueue(true);
    MultiRoomStore.Profile p = new MultiRoomStore.Profile("room");
    q.jobs.add(
        new PhotoTourQueue.Job(p, new RoomMediaStore.Media("content://a", "image/png", "a"), 1));
    set("app", c);
    set("queue", q);
    set("phase", PhotoTourController.Phase.WAIT_DUE);
    set("panelOff", true);
    PhotoTourController.tick(c, q.run);
    assertFalse(PhotoTourController.active());
    assertNull(q.inFlight);
  }

  @Test
  public void physicalPowerButtonStopsPairedModeInsteadOfWaitingOrWaking() throws Exception {
    PhotoTourQueue q = new PhotoTourQueue(true);
    set("app", c);
    set("queue", q);
    set("phase", PhotoTourController.Phase.WAIT_DUE);
    set("panelOff", true);
    Shadows.shadowOf((PowerManager) c.getSystemService(Context.POWER_SERVICE))
        .setIsInteractive(false);
    PhotoTourController.screenChanged(c);
    assertFalse(PhotoTourController.active());
  }

  @Test
  public void newIdentityRemainsInNoBackupStorageAndMatchesCertificate() throws Exception {
    LocalAdb first = new LocalAdb(c), second = new LocalAdb(c);
    assertArrayEquals(first.getPrivateKey().getEncoded(), second.getPrivateKey().getEncoded());
    assertArrayEquals(first.getCertificate().getEncoded(), second.getCertificate().getEncoded());
    first.getCertificate().verify(first.getCertificate().getPublicKey());
    assertTrue(new java.io.File(c.getNoBackupFilesDir(), "local-adb/identity.pk8").isFile());
    assertEquals("127.0.0.1", first.getHostAddress());
  }
}
