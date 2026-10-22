package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

/** Start with an actual notification callback, not an already verified session fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
public class NotificationConnectionFlowTest {
  private Context c;
  private KakaoNotificationListener listener;
  private PendingIntent open;

  @Before
  public void setup() {
    c = RuntimeEnvironment.getApplication();
    Prefs.p(c).edit().clear().commit();
    KakaoNotificationListener.clearLiveTargets();
    android.content.pm.PackageInfo info = new android.content.pm.PackageInfo();
    info.packageName = KakaoNotificationListener.KAKAO_PACKAGE;
    info.versionCode = 7;
    Shadows.shadowOf(c.getPackageManager()).installPackage(info);
    listener = Robolectric.buildService(KakaoNotificationListener.class).create().get();
    open =
        PendingIntent.getActivity(c, 721, new Intent("fixture-room"), PendingIntent.FLAG_IMMUTABLE);
    Shadows.shadowOf(open).setCreatorPackage(KakaoNotificationListener.KAKAO_PACKAGE);
  }

  @After
  public void cleanup() {
    KakaoNotificationListener.clearLiveTargets();
    listener.onDestroy();
  }

  private StatusBarNotification post(boolean reply, boolean summary, String shortcut) {
    Notification.Builder b =
        new Notification.Builder(c, "fixture")
            .setContentTitle("보낸 사람")
            .setContentText("시험 메시지")
            .setContentIntent(open)
            .setShortcutId(shortcut)
            .setGroup("fixture")
            .setGroupSummary(summary);
    android.os.Bundle extras = new android.os.Bundle();
    extras.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, "실제 오픈채팅 방");
    b.addExtras(extras);
    if (reply)
      b.addAction(
          new Notification.Action.Builder(null, "답장", open)
              .addRemoteInput(new RemoteInput.Builder("reply").build())
              .build());
    StatusBarNotification sbn =
        new StatusBarNotification(
            KakaoNotificationListener.KAKAO_PACKAGE,
            KakaoNotificationListener.KAKAO_PACKAGE,
            721,
            "fixture",
            Process.myUid(),
            0,
            0,
            b.build(),
            Process.myUserHandle(),
            100);
    listener.onNotificationPosted(sbn);
    return sbn;
  }

  private String connect() {
    var candidates = KakaoNotificationListener.candidateSessionEntries();
    assertEquals(1, candidates.size());
    assertEquals("실제 오픈채팅 방", candidates.get(0).suggestedRoom);
    String alias = "route-internal-id";
    assertTrue(KakaoNotificationListener.bindRecentTokenToRoom(c, candidates.get(0).token, alias));
    MultiRoomStore.Profile p = new MultiRoomStore.Profile(alias);
    p.actualRoomName = candidates.get(0).suggestedRoom;
    MultiRoomStore.upsert(c, p);
    return alias;
  }

  @Test
  public void connectingThenOpeningChatRetainsPhotoRouteBeforeVisitingPhotoSettings() {
    var sbn = post(true, false, "fixture-conversation");
    String room = connect();
    listener.onNotificationRemoved(sbn);
    assertNull(KakaoNotificationListener.findTarget(room));
    var route = PhotoRoomRoutes.get(c, room);
    assertNotNull(route);
    assertEquals("", PhotoRoomRoutes.error(c, room, route));
    assertSame(open, route.openIntent);
    assertEquals("실제 오픈채팅 방", MultiRoomStore.get(c, room).title());
  }

  @Test
  public void openingOnlyNotificationCanConnectPhotosWithoutAdvertisingTextReply() {
    var sbn = post(false, false, "fixture-conversation");
    String room = connect();
    assertFalse(RoomSendReadiness.get(c, room, "text", RoomMediaStore.get(c, room)).ready);
    assertNull(KakaoNotificationListener.findTarget(room).pendingIntent);
    listener.onNotificationRemoved(sbn);
    assertNotNull(PhotoRoomRoutes.get(c, room));
  }

  @Test
  public void openingOnlySummaryAndMissingIdentityAreNotOfferedAsRooms() {
    post(false, true, "fixture-conversation");
    assertTrue(KakaoNotificationListener.candidateSessionEntries().isEmpty());
    post(false, false, null);
    assertTrue(KakaoNotificationListener.candidateSessionEntries().isEmpty());
  }

  @Test
  public void foreignOpeningActionCannotBecomePhotoRoom() {
    Shadows.shadowOf(open).setCreatorPackage("other.app");
    post(false, false, "fixture-conversation");
    assertTrue(KakaoNotificationListener.candidateSessionEntries().isEmpty());
  }

  @Test
  public void unbindingDropsCachedRouteEvenIfRoomProfileIsStillPresent() {
    var sbn = post(true, false, "fixture-conversation");
    String room = connect();
    listener.onNotificationRemoved(sbn);
    assertNotNull(PhotoRoomRoutes.get(c, room));
    KakaoNotificationListener.unbindRoom(c, room);
    assertNull(PhotoRoomRoutes.get(c, room));
  }

  @Test
  public void reconnectUsesExistingIdentityEvenWhenTwoRoomsHaveTheSameTitle() {
    post(true, false, "first-conversation");
    String room = connect();
    post(true, false, "second-conversation");
    var p = MultiRoomStore.get(c, room);
    assertTrue(KakaoNotificationListener.candidatesForRoom(c, p).isEmpty());
    var unrelated = KakaoNotificationListener.candidateSessionEntries().get(0);
    assertFalse(KakaoNotificationListener.bindRecentTokenToRoom(c, unrelated.token, room));
    assertEquals(1, Prefs.bindingCount(c));
    post(true, false, "first-conversation");
    assertEquals(1, KakaoNotificationListener.candidatesForRoom(c, p).size());
  }

  @Test
  public void permissionRecognizesBothComponentFormsWithoutSubstringMatches() {
    var component = new android.content.ComponentName(c, KakaoNotificationListener.class);
    android.provider.Settings.Secure.putString(
        c.getContentResolver(), "enabled_notification_listeners", component.flattenToShortString());
    assertTrue(KakaoNotificationListener.notificationAccessEnabled(c));
    android.provider.Settings.Secure.putString(
        c.getContentResolver(), "enabled_notification_listeners", component.flattenToString());
    assertTrue(KakaoNotificationListener.notificationAccessEnabled(c));
    android.provider.Settings.Secure.putString(
        c.getContentResolver(),
        "enabled_notification_listeners",
        component.flattenToString() + "Other");
    assertFalse(KakaoNotificationListener.notificationAccessEnabled(c));
  }
}
