package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputContentInfo;
import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
public class PhotoLoopSessionTest {
  private Context context;
  private EditorInfo editor;
  private PhotoLoopSession loop;
  private MultiRoomStore.Profile profile;
  private Connection connection;
  private final RoomMediaStore.Media media =
      new RoomMediaStore.Media("content://photos/selected", "image/png", "selected.png");

  private class Connection extends BaseInputConnection {
    int calls;
    boolean accepted = true, crash;

    Connection() {
      super(new View(context), true);
    }

    @Override
    public boolean commitContent(InputContentInfo content, int flags, Bundle extras) {
      calls++;
      assertEquals(media.uri, content.getContentUri().toString());
      if (crash) throw new IllegalStateException("unknown remote outcome");
      return accepted;
    }
  }

  @Before
  public void prepare() {
    context = RuntimeEnvironment.getApplication();
    Prefs.p(context).edit().clear().commit();
    context.getSharedPreferences("room_media_v1", 0).edit().clear().commit();
    context.getSharedPreferences("photo_loop_v1", 0).edit().clear().commit();
    Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 4);
    context
        .getSharedPreferences("entitlement_v2", 0)
        .edit()
        .clear()
        .putString("state", "ACTIVE")
        .putLong("validated_elapsed", SystemClock.elapsedRealtime())
        .putInt("validated_boot", 4)
        .putLong("server_time", 100000)
        .putLong("expiry", 0)
        .putLong("grace", 600)
        .commit();
    Shadows.shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
        .setIsInteractive(true);
    Shadows.shadowOf((KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE))
        .setIsDeviceLocked(false);
    profile = new MultiRoomStore.Profile("source");
    profile.intervalMinutes = 1;
    profile.enabled = false;
    profile.dailyLimit = 3;
    MultiRoomStore.upsert(context, profile);
    RoomMediaStore.set(context, profile.room, media.uri, media.mime, media.name);
    editor = new EditorInfo();
    editor.packageName = KakaoNotificationListener.KAKAO_PACKAGE;
    editor.inputType = InputType.TYPE_CLASS_TEXT;
    editor.contentMimeTypes = new String[] {"image/*"};
    connection = new Connection();
    loop = new PhotoLoopSession();
  }

  private PhotoLoopSession.Plan arm(boolean once, boolean off) {
    assertEquals(
        "",
        loop.arm(
            context,
            profile,
            media,
            editor,
            connection,
            once,
            off,
            true,
            true,
            System.currentTimeMillis()));
    return loop.current();
  }

  private void deliver(PhotoLoopSession.Plan plan) {
    assertTrue(loop.reserve(context, plan, plan.run, plan.next, plan.next));
    assertEquals("", loop.deliver(context, plan, editor, connection, plan.next));
  }

  @Test
  public void quickTestUsesTenSecondsWithoutChangingSavedScheduleAndCannotRepeat() {
    long now = System.currentTimeMillis();
    assertEquals(
        "", loop.armQuickTest(context, profile, media, editor, connection, true, true, now));
    PhotoLoopSession.Plan test = loop.current();
    assertEquals(now + 10_000L, test.next);
    assertTrue(test.once);
    assertFalse(test.screenOff);
    assertEquals(1, MultiRoomStore.get(context, profile.room).intervalMinutes);
    assertFalse(loop.reserve(context, test, test.run, test.next, now));
    deliver(test);
    assertTrue(loop.completeUi(context, test, editor, new Connection(), now + 11_000L));
    assertFalse(loop.active());
    assertEquals(1, connection.calls);
    assertEquals(1, PhotoLoopStore.attemptsToday(context, profile.room));
  }

  @Test
  public void quickTestRequiresRecipientAndButtonLearningAndRespectsDailyLimit() {
    long now = System.currentTimeMillis();
    assertFalse(
        loop.armQuickTest(context, profile, media, editor, connection, false, true, now).isEmpty());
    assertFalse(
        loop.armQuickTest(context, profile, media, editor, connection, true, false, now).isEmpty());
    assertFalse(loop.active());
    profile.dailyLimit = 1;
    MultiRoomStore.upsert(context, profile);
    assertEquals(
        "", loop.armQuickTest(context, profile, media, editor, connection, true, true, now));
    deliver(loop.current());
    loop.stop(context, "test stop");
    assertFalse(
        loop.armQuickTest(context, profile, media, editor, connection, true, true, now).isEmpty());
    assertEquals(1, connection.calls);
  }

  @Test
  public void reservationIsFutureBoundAndDuplicateAlarmCannotReplayOccurrence() {
    PhotoLoopSession.Plan plan = arm(false, false);
    long due = plan.next;
    assertFalse(loop.reserve(context, plan, plan.run, due, due - 1));
    assertFalse(loop.reserve(context, plan, "stale-run", due, due));
    assertTrue(loop.reserve(context, plan, plan.run, due, due));
    assertFalse(loop.reserve(context, plan, plan.run, due, due));
    assertEquals("", loop.deliver(context, plan, editor, connection, due));
    assertFalse(loop.reserve(context, plan, plan.run, due, due));
    assertTrue(plan.uiPending);
    assertEquals(1, connection.calls);
    assertEquals(1, PhotoLoopStore.attemptsToday(context, profile.room));
    assertEquals(0, MultiRoomStore.get(context, profile.room).todayCount);
  }

  @Test
  public void nextOccurrenceRequiresFinalButtonAndReturnThenUsesFreshConnection() {
    PhotoLoopSession.Plan plan = arm(false, false);
    long first = plan.next;
    deliver(plan);
    assertFalse(loop.completeUi(context, plan, editor, null, first));
    Connection returned = new Connection();
    assertTrue(loop.completeUi(context, plan, editor, returned, first + 1000));
    assertSame(returned, plan.connection);
    assertEquals(first + 61000, plan.next);
    assertFalse(plan.uiPending);
    assertFalse(loop.reserve(context, plan, plan.run, first, first + 90000));
    assertTrue(loop.reserve(context, plan, plan.run, plan.next, plan.next));
    assertEquals("", loop.deliver(context, plan, editor, returned, plan.next));
    assertEquals(1, connection.calls);
    assertEquals(1, returned.calls);
  }

  @Test
  public void onceStopsOnlyAfterConfirmedUiRoundtrip() {
    PhotoLoopSession.Plan plan = arm(true, false);
    deliver(plan);
    assertTrue(loop.active());
    assertTrue(loop.completeUi(context, plan, editor, new Connection(), plan.next + 1));
    assertFalse(loop.active());
    assertFalse(PhotoLoopStore.recordedRunning(context));
    assertFalse(loop.completeUi(context, plan, editor, connection, plan.next + 1));
  }

  @Test
  public void learnedButtonAndRecipientConfirmationAreMandatory() {
    assertFalse(
        loop.arm(
                context,
                profile,
                media,
                editor,
                connection,
                false,
                false,
                false,
                true,
                System.currentTimeMillis())
            .isEmpty());
    assertFalse(
        loop.arm(
                context,
                profile,
                media,
                editor,
                connection,
                false,
                false,
                true,
                false,
                System.currentTimeMillis())
            .isEmpty());
    assertFalse(loop.active());
  }

  @Test
  public void rejectionOrAmbiguousCommitConsumesQuotaAndStopsInsteadOfRetrying() {
    connection.accepted = false;
    PhotoLoopSession.Plan rejected = arm(false, false);
    assertTrue(loop.reserve(context, rejected, rejected.run, rejected.next, rejected.next));
    assertFalse(loop.deliver(context, rejected, editor, connection, rejected.next).isEmpty());
    assertFalse(loop.active());
    assertEquals(1, connection.calls);
    connection.accepted = true;
    connection.crash = true;
    PhotoLoopSession.Plan uncertain = arm(false, false);
    assertTrue(loop.reserve(context, uncertain, uncertain.run, uncertain.next, uncertain.next));
    assertFalse(loop.deliver(context, uncertain, editor, connection, uncertain.next).isEmpty());
    assertFalse(loop.reserve(context, uncertain, uncertain.run, uncertain.next, uncertain.next));
    assertEquals(2, connection.calls);
    assertEquals(2, PhotoLoopStore.attemptsToday(context, profile.room));
  }

  @Test
  public void quotaPersistsAcrossStopAndNewLoopAndWaitsUntilNextDay() {
    profile.dailyLimit = 1;
    MultiRoomStore.upsert(context, profile);
    PhotoLoopSession.Plan plan = arm(false, false);
    long due = plan.next;
    deliver(plan);
    assertTrue(loop.completeUi(context, plan, editor, connection, due + 1));
    assertTrue(plan.next > due + 60_000);
    loop.stop(context, "stop");
    assertFalse(
        loop.arm(
                context,
                profile,
                media,
                editor,
                connection,
                false,
                false,
                true,
                true,
                System.currentTimeMillis())
            .isEmpty());
    assertEquals(1, PhotoLoopStore.attemptsToday(context, profile.room));
  }

  @Test
  public void staleInputChangedSettingsAndPhotoAreBlocked() {
    PhotoLoopSession.Plan plan = arm(false, false);
    assertFalse(loop.preflight(context, plan, editor, new Connection()).isEmpty());
    profile.intervalMinutes = 10;
    MultiRoomStore.upsert(context, profile);
    assertFalse(loop.preflight(context, plan, editor, connection).isEmpty());
    profile.intervalMinutes = 1;
    MultiRoomStore.upsert(context, profile);
    RoomMediaStore.clear(context, profile.room);
    assertFalse(loop.preflight(context, plan, editor, connection).isEmpty());
    assertEquals(0, connection.calls);
  }

  @Test
  public void notificationScheduleConflictIsRejectedAtStartAndBeforeSend() {
    profile.enabled = true;
    MultiRoomStore.upsert(context, profile);
    Prefs.p(context).edit().putBoolean(Prefs.KEY_ACTIVE, true).commit();
    assertFalse(
        loop.arm(
                context,
                profile,
                media,
                editor,
                connection,
                false,
                false,
                true,
                true,
                System.currentTimeMillis())
            .isEmpty());
    Prefs.p(context).edit().putBoolean(Prefs.KEY_ACTIVE, false).commit();
    PhotoLoopSession.Plan plan = arm(false, false);
    Prefs.p(context).edit().putBoolean(Prefs.KEY_ACTIVE, true).commit();
    assertFalse(loop.preflight(context, plan, editor, connection).isEmpty());
  }

  @Test
  public void offscreenOptionOnlyRelaxesInteractiveCheckNeverReconnectsAnOldEditor() {
    PhotoLoopSession.Plan plan = arm(false, false);
    Shadows.shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
        .setIsInteractive(false);
    assertFalse(loop.preflight(context, plan, editor, connection).isEmpty());
    loop.stop(context, "stop");
    Shadows.shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
        .setIsInteractive(true);
    plan = arm(false, true);
    Shadows.shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
        .setIsInteractive(false);
    assertEquals("", loop.preflight(context, plan, editor, connection));
    assertFalse(loop.preflight(context, plan, editor, null).isEmpty());
    assertFalse(loop.preflight(context, plan, editor, new Connection()).isEmpty());
    // This is a policy test, not evidence Android/Kakao allow a real screen-off call.
  }

  @Test
  public void licenseRevocationBeforeFinalUiButtonStopsUiGateAndFurtherOccurrence() {
    PhotoLoopSession.Plan plan = arm(false, false);
    deliver(plan);
    context.getSharedPreferences("entitlement_v2", 0).edit().putString("state", "REVOKED").commit();
    assertFalse(loop.uiGate(context, plan).isEmpty());
    assertFalse(loop.completeUi(context, plan, editor, connection, plan.next + 1));
    assertFalse(loop.active());
    assertEquals(1, connection.calls);
  }

  @Test
  public void durableInFlightCannotBeReservedAgainByRestartedEngine() {
    PhotoLoopSession.Plan plan = arm(false, false);
    assertTrue(PhotoLoopStore.reserve(context, plan.run, plan.next, profile.dailyLimit));
    assertFalse(PhotoLoopStore.reserve(context, plan.run, plan.next, profile.dailyLimit));
    PhotoLoopSession restarted = new PhotoLoopSession();
    assertFalse(restarted.active());
    assertNull(restarted.current());
    assertTrue(PhotoLoopStore.summary(context).contains("다시 시작"));
  }

  @Test
  public void serviceGlobalStopImmediatelyDisarmsLiveLoop() throws Exception {
    var controller = Robolectric.buildService(PhotoKeyboardService.class).create();
    try {
      Field field = PhotoKeyboardService.class.getDeclaredField("loop");
      field.setAccessible(true);
      PhotoLoopSession live = (PhotoLoopSession) field.get(controller.get());
      assertEquals(
          "",
          live.arm(
              context,
              profile,
              media,
              editor,
              connection,
              false,
              false,
              true,
              true,
              System.currentTimeMillis()));
      assertTrue(PhotoKeyboardService.hasActiveLoop());
      DeliveryGate.stop(context);
      assertFalse(live.active());
      assertFalse(PhotoLoopStore.recordedRunning(context));
    } finally {
      controller.destroy();
    }
  }
}
