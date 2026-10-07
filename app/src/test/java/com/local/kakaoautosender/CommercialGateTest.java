package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommercialGateTest {
  private Context c;

  @Before
  public void prepare() throws Exception {
    c = RuntimeEnvironment.getApplication();
    Prefs.p(c).edit().clear().commit();
    c.getSharedPreferences("entitlement_v2", 0).edit().clear().commit();
    Settings.Global.putInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, 4);
    KakaoNotificationListener.clearRuntimeAndBindings(c);
    Shadows.shadowOf(c.getContentResolver())
        .registerInputStream(
        Uri.parse("content://photo/a"), new ByteArrayInputStream(new byte[] {1, 2, 3}));
    c.getSharedPreferences("entitlement_v2", 0)
        .edit()
        .putString("state", "ACTIVE")
        .putLong("validated_elapsed", SystemClock.elapsedRealtime())
        .putInt("validated_boot", 4)
        .putLong("server_time", 100000)
        .putLong("expiry", 0)
        .putLong("grace", 600)
        .commit();
  }

  @SuppressWarnings("unchecked")
  private void target(String label, boolean verified, String type) throws Exception {
    java.lang.reflect.Field field = KakaoNotificationListener.class.getDeclaredField("sessions");
    field.setAccessible(true);
    Map<String, KakaoNotificationListener.ReplyTarget> sessions =
        (Map<String, KakaoNotificationListener.ReplyTarget>) field.get(null);
    RemoteInput.Builder input = new RemoteInput.Builder("reply");
    if (type != null) input.setAllowDataType(type, true);
    PendingIntent pi =
        PendingIntent.getBroadcast(
            c, 22, new Intent("TEST_REPLY"), PendingIntent.FLAG_UPDATE_CURRENT);
    sessions.put(
        "room-a",
        new KakaoNotificationListener.ReplyTarget(
            label,
            pi,
            new RemoteInput[] {input.build()},
            0,
            0,
            "t",
            "",
            "",
            "room-a",
            3,
            "test",
            new ArrayList<>(),
            verified));
    java.lang.reflect.Field recent =
        KakaoNotificationListener.class.getDeclaredField("recentTargets");
    recent.setAccessible(true);
    ((Map<String, KakaoNotificationListener.ReplyTarget>) recent.get(null))
        .put("t", sessions.get("room-a"));
  }

  @Test
  public void unlicensedManualAndTestSendCannotReachKakao() {
    LicenseManager.lockout(c, "SUSPENDED", "");
    assertFalse(KakaoMessageSender.send(c, "room-a", "text", new RoomMediaStore.Media("", "", "")));
    assertFalse(KakaoNotificationListener.sendToRoom(c, "room-a", "text"));
  }

  @Test
  public void scheduledGateAlsoChecksImmediateGlobalStop() {
    Prefs.p(c).edit().putBoolean(Prefs.KEY_ACTIVE, true).commit();
    DeliveryGate.scheduled(true);
    try {
      assertTrue(DeliveryGate.allowed(c));
      DeliveryGate.stop(c);
      assertFalse(DeliveryGate.allowed(c));
    } finally {
      DeliveryGate.scheduled(false);
    }
  }

  @Test
  public void lockoutCancelsAlarmAndPreservesRoomsMessagesSchedulesAndPhotos() {
    MultiRoomStore.Profile profile = new MultiRoomStore.Profile("room-a");
    profile.message = "private";
    profile.intervalMinutes = 15;
    MultiRoomStore.upsert(c, profile);
    RoomMediaStore.set(c, "room-a", "content://photo/a", "image/png", "private.png");
    Prefs.bindIdentity(
        c, "room-a", new ArrayList<>(java.util.Collections.singletonList("kakao-shortcut:a")));
    Prefs.p(c).edit().putBoolean(Prefs.KEY_ACTIVE, true).commit();
    SendScheduler.scheduleAt(c, System.currentTimeMillis() + 60000);
    String before = Prefs.p(c).getString(MultiRoomStore.KEY_PROFILES, "");
    assertFalse(
        Shadows.shadowOf((AlarmManager) c.getSystemService(Context.ALARM_SERVICE))
            .getScheduledAlarms()
            .isEmpty());
    LicenseManager.lockout(c, "REVOKED", "");
    assertEquals(before, Prefs.p(c).getString(MultiRoomStore.KEY_PROFILES, ""));
    assertEquals("content://photo/a", RoomMediaStore.get(c, "room-a").uri);
    assertTrue(Prefs.hasBindingForAlias(c, "room-a"));
    assertFalse(Prefs.p(c).getBoolean(Prefs.KEY_ACTIVE, true));
    assertTrue(
        Shadows.shadowOf((AlarmManager) c.getSystemService(Context.ALARM_SERVICE))
            .getScheduledAlarms()
            .isEmpty());
  }

  @Test
  public void oldKAS1TextCannotBypassEntitlement() {
    c.getSharedPreferences("entitlement_v2", 0).edit().clear().commit();
    Prefs.p(c).edit().putString("license_text_v1", "KAS1.old.signed").commit();
    assertFalse(LicenseManager.isUsable(c));
  }

  @Test
  public void unknownPhotoMimeDoesNotFallBackToText() throws Exception {
    target("room-a", true, null);
    assertFalse(
        KakaoMessageSender.send(
            c, "room-a", "text", new RoomMediaStore.Media("content://photo/a", "", "image")));
    assertTrue(KakaoMessageSender.lastError().contains("형식"));
  }

  @Test
  public void configuredUnsupportedPhotoDoesNotFallBackToText() throws Exception {
    target("room-a", true, null);
    assertFalse(
        KakaoMessageSender.send(
            c,
            "room-a",
            "text",
            new RoomMediaStore.Media("content://photo/a", "image/png", "image")));
    assertEquals("현재 카카오톡 알림 답장 방식에서는 이 방에 사진 자동전송을 지원하지 않습니다.", KakaoMessageSender.lastError());
  }

  @Test
  public void unreadableConfiguredPhotoFailsClosedBeforeKakaoSend() throws Exception {
    target("room-a", true, "image/png");
    assertFalse(
        KakaoMessageSender.send(
            c,
            "room-a",
            "text",
            new RoomMediaStore.Media("content://photo/missing", "image/png", "missing.png")));
    assertTrue(KakaoMessageSender.lastError().contains("사진"));
    for (Intent i : Shadows.shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents())
      assertNotEquals("TEST_REPLY", i.getAction());
  }

  @Test
  public void wrongRoomLabelFailsClosedForTextAndPhoto() throws Exception {
    target("room-b", true, "image/png");
    assertFalse(KakaoNotificationListener.sendToRoom(c, "room-a", "text"));
    assertFalse(
        KakaoMessageSender.send(
            c,
            "room-a",
            "text",
            new RoomMediaStore.Media("content://photo/a", "image/png", "image")));
    assertTrue(KakaoMessageSender.lastError().contains("안전 차단"));
  }

  @Test
  public void unverifiedTargetFailsClosed() throws Exception {
    target("room-a", false, null);
    assertFalse(KakaoNotificationListener.sendToRoom(c, "room-a", "text"));
  }

  @Test
  public void identityConflictFailsClosed() throws Exception {
    target("room-a", true, null);
    java.lang.reflect.Field f = KakaoNotificationListener.class.getDeclaredField("sessions");
    f.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, KakaoNotificationListener.ReplyTarget> sessions =
        (Map<String, KakaoNotificationListener.ReplyTarget>) f.get(null);
    sessions.get("room-a").stableIdentityKeys.add("kakao-shortcut:conflict");
    Prefs.bindIdentity(
        c,
        "room-b",
        new ArrayList<>(java.util.Collections.singletonList("kakao-shortcut:conflict")));
    assertFalse(KakaoNotificationListener.sendToRoom(c, "room-a", "text"));
  }

  @Test
  public void photoOnlyProfileCanSchedule() {
    MultiRoomStore.Profile p = new MultiRoomStore.Profile("room-a");
    p.message = "";
    RoomMediaStore.set(c, p.room, "content://photo/a", "image/png", "photo");
    MultiRoomStore.upsert(c, p);
    MultiRoomStore.setAllNextFromNow(c);
    assertTrue(MultiRoomStore.nextDueAt(c) > 0);
  }

  @Test
  public void duplicateSuccessDoesNotDoubleIncrementCounter() {
    MultiRoomStore.Profile p = new MultiRoomStore.Profile("room-a");
    p.message = "text";
    p.executionId = "attempt-1";
    MultiRoomStore.upsert(c, p);
    MultiRoomStore.markSuccess(c, p.room, 100, "sent");
    MultiRoomStore.markSuccess(c, p.room, 100, "duplicate");
    assertEquals(1, MultiRoomStore.get(c, p.room).todayCount);
  }

  @Test
  public void repeatedAlarmCannotReserveSameAttemptTwice() {
    MultiRoomStore.Profile p = new MultiRoomStore.Profile("room-a");
    p.message = "text";
    p.nextAt = 100;
    MultiRoomStore.upsert(c, p);
    assertTrue(MultiRoomStore.reserveExecution(c, p, 200));
    assertFalse(MultiRoomStore.reserveExecution(c, p, 200));
    assertFalse(MultiRoomStore.get(c, p.room).executionId.isEmpty());
  }

  @Test
  public void supportedPhotoOnlyUsesDataRemoteInput() throws Exception {
    target("room-a", true, "image/png");
    assertTrue(
        KakaoMessageSender.send(
            c, "room-a", "", new RoomMediaStore.Media("content://photo/a", "image/png", "image")));
    Intent delivered = null;
    for (Intent i : Shadows.shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents())
      if ("TEST_REPLY".equals(i.getAction())) delivered = i;
    assertNotNull(delivered);
    assertEquals(
        Uri.parse("content://photo/a"),
        RemoteInput.getDataResultsFromIntent(delivered, "reply").get("image/png"));
  }

  @Test
  public void diagnosticsContainNoChatNamesMessagesPhotosOrTokens() {
    MultiRoomStore.Profile p = new MultiRoomStore.Profile("private-room");
    p.message = "private-message";
    MultiRoomStore.upsert(c, p);
    RoomMediaStore.set(c, p.room, "content://private/photo", "image/png", "private-photo");
    String d = LicenseManager.diagnostic(c);
    assertFalse(d.contains("private"));
    assertFalse(d.contains("tokens"));
    Prefs.appendLog(c, "전송 완료 private-room private-message content://private/photo");
    assertFalse(Prefs.recentLog(c, 10).contains("private"));
  }

  @Test
  public void removedPhotoTargetNeverFallsBackToText() throws Exception {
    target("room-a", true, "image/png");
    KakaoNotificationListener.invalidateNotification("t", 0);
    assertFalse(
        KakaoMessageSender.send(
            c,
            "room-a",
            "text",
            new RoomMediaStore.Media("content://photo/a", "image/png", "image")));
    for (Intent i : Shadows.shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents())
      assertFalse("TEST_REPLY".equals(i.getAction()));
  }

  private void verifyRedirect(String state) {
    c.getSharedPreferences("entitlement_v2", 0).edit().putString("state", state).commit();
    org.robolectric.android.controller.ActivityController<MainActivityV4> controller =
        org.robolectric.Robolectric.buildActivity(MainActivityV4.class).create().start().resume();
    Intent intent = Shadows.shadowOf(controller.get()).getNextStartedActivity();
    assertNotNull(intent);
    assertEquals(LicenseActivity.class.getName(), intent.getComponent().getClassName());
    assertTrue(controller.get().isFinishing());
    controller.pause().stop().destroy();
  }

  @Test
  public void expiredForegroundRedirect() {
    verifyRedirect("EXPIRED");
  }

  @Test
  public void suspendedForegroundRedirect() {
    verifyRedirect("SUSPENDED");
  }

  @Test
  public void revokedForegroundRedirect() {
    verifyRedirect("REVOKED");
  }

  @Test
  public void deletedForegroundRedirect() {
    verifyRedirect("DELETED");
  }

  @Test
  public void updateRequiredForegroundRedirect() {
    verifyRedirect("UPDATE_REQUIRED");
  }

  @Test
  public void deviceMismatchForegroundRedirect() {
    verifyRedirect("DEVICE_MISMATCH");
  }

  @Test
  public void backgroundReceiverRejectsInvalidLicenseBeforeReplyAction() throws Exception {
    target("room-a", true, null);
    Prefs.p(c).edit().putBoolean(Prefs.KEY_ACTIVE, true).commit();
    c.getSharedPreferences("entitlement_v2", 0).edit().putString("state", "SUSPENDED").commit();
    c.sendBroadcast(
        new Intent(c, SendAlarmReceiver.class).setAction("com.local.kakaoautosender.SEND_ALARM"));
    long deadline = System.nanoTime() + 3_000_000_000L;
    while (Prefs.p(c).getBoolean(Prefs.KEY_ACTIVE, true) && System.nanoTime() < deadline) {
      Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
      Thread.sleep(20);
    }
    assertFalse(Prefs.p(c).getBoolean(Prefs.KEY_ACTIVE, true));
    for (Intent i : Shadows.shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents())
      assertNotEquals("TEST_REPLY", i.getAction());
  }
}
