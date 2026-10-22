package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
public class BulkPhotoSettingsTest {
  private Context c;
  private final RoomMediaStore.Media shared =
      new RoomMediaStore.Media("content://photos/shared", "image/png", "shared.png");

  @Before
  public void prepare() {
    c = RuntimeEnvironment.getApplication();
    Prefs.p(c).edit().clear().commit();
    c.getSharedPreferences("room_media_v1", 0).edit().clear().commit();
    KakaoNotificationListener.clearRuntimeAndBindings(c);
    for (String name : new String[] {"a", "b", "c"}) {
      MultiRoomStore.Profile p = new MultiRoomStore.Profile(name);
      p.message = "message-" + name;
      p.todayCount = 2;
      p.failureStreak = 1;
      p.lastSuccessAt = 1234;
      p.executionId = "execution-" + name;
      MultiRoomStore.upsert(c, p);
      RoomMediaStore.set(c, name, "content://photos/" + name, "image/jpeg", name + ".jpg");
    }
  }

  private MultiRoomStore.BulkPatch photoPatch() {
    MultiRoomStore.BulkPatch p = new MultiRoomStore.BulkPatch();
    p.applyMedia = true;
    p.media = shared;
    return p;
  }

  @Test
  public void sharedPhotoMessageAndScheduleApplyToSelectedRoomsTogether() {
    MultiRoomStore.BulkPatch patch = photoPatch();
    patch.applyMessage = true;
    patch.message = "shared message";
    patch.applySchedule = true;
    patch.intervalMinutes = 15;
    assertEquals(
        2, MultiRoomStore.applyBulkPatch(c, Arrays.asList("a", "b", "a", "missing"), patch));
    for (String name : new String[] {"a", "b"}) {
      assertEquals(shared.uri, RoomMediaStore.get(c, name).uri);
      MultiRoomStore.Profile p = MultiRoomStore.get(c, name);
      assertEquals("shared message", p.message);
      assertEquals(15, p.intervalMinutes);
      assertEquals(2, p.todayCount);
      assertEquals(1, p.failureStreak);
      assertEquals(1234, p.lastSuccessAt);
      assertEquals("execution-" + name, p.executionId);
    }
    assertEquals("content://photos/c", RoomMediaStore.get(c, "c").uri);
    assertEquals("message-c", MultiRoomStore.get(c, "c").message);
    assertTrue(Prefs.p(c).contains(MultiRoomStore.KEY_PROFILES));
    assertTrue(Prefs.p(c).contains(RoomMediaStore.KEY_OVERRIDES));
  }

  @Test
  public void sameUriIsKeptUntilLastRoomRemovesIt() {
    MultiRoomStore.applyBulkPatch(c, Arrays.asList("a", "b"), photoPatch());
    RoomMediaStore.clear(c, "a");
    assertTrue(RoomMediaStore.isReferenced(c, shared.uri));
    assertFalse(RoomMediaStore.releaseIfUnused(c, shared.uri));
    assertEquals(shared.uri, RoomMediaStore.get(c, "b").uri);
    RoomMediaStore.clear(c, "b");
    assertFalse(RoomMediaStore.isReferenced(c, shared.uri));
  }

  @Test
  public void unselectedLegacyPhotoSurvivesAndSharedLegacyGrantIsNotReleased() {
    Prefs.p(c).edit().remove(RoomMediaStore.KEY_OVERRIDES).commit();
    c.getSharedPreferences("room_media_v1", 0)
        .edit()
        .putString("a|uri", shared.uri)
        .putString("a|mime", shared.mime)
        .putString("b|uri", shared.uri)
        .putString("b|mime", shared.mime)
        .commit();
    RoomMediaStore.clear(c, "a");
    assertFalse(RoomMediaStore.get(c, "a").hasImage());
    assertEquals(shared.uri, RoomMediaStore.get(c, "b").uri);
    assertTrue(RoomMediaStore.isReferenced(c, shared.uri));
    assertFalse(RoomMediaStore.releaseIfUnused(c, shared.uri));
  }

  @Test
  public void uncheckedPhotoDoesNotChangeAndClearDoesNotResurrectLegacyPhoto() {
    MultiRoomStore.BulkPatch unchanged = new MultiRoomStore.BulkPatch();
    unchanged.applyMessage = true;
    unchanged.message = "updated";
    MultiRoomStore.applyBulkPatch(c, Collections.singletonList("a"), unchanged);
    assertEquals("content://photos/a", RoomMediaStore.get(c, "a").uri);
    c.getSharedPreferences("room_media_v1", 0)
        .edit()
        .putString("a|uri", "content://legacy/old")
        .commit();
    MultiRoomStore.BulkPatch clear = photoPatch();
    clear.media = new RoomMediaStore.Media("", "", "");
    MultiRoomStore.applyBulkPatch(c, Collections.singletonList("a"), clear);
    assertFalse(RoomMediaStore.get(c, "a").hasImage());
    assertEquals("updated", MultiRoomStore.get(c, "a").message);
    assertEquals("content://photos/b", RoomMediaStore.get(c, "b").uri);
  }

  @Test
  public void malformedPhotoCannotPartiallyChangeMessagesOrSchedules() {
    MultiRoomStore.BulkPatch invalid = photoPatch();
    invalid.media = new RoomMediaStore.Media("file:///private.png", "image/png", "invalid");
    invalid.applyMessage = true;
    invalid.message = "must not save";
    assertThrows(
        IllegalArgumentException.class,
        () -> MultiRoomStore.applyBulkPatch(c, Arrays.asList("a", "b"), invalid));
    assertEquals("message-a", MultiRoomStore.get(c, "a").message);
    assertEquals("content://photos/a", RoomMediaStore.get(c, "a").uri);
  }

  @SuppressWarnings("unchecked")
  private void bind(String room, boolean photos, boolean caption) throws Exception {
    RemoteInput text = new RemoteInput.Builder("text").build();
    RemoteInput image =
        new RemoteInput.Builder("photo")
            .setAllowFreeFormInput(false)
            .setAllowDataType("image/*", true)
            .build();
    PendingIntent intent =
        PendingIntent.getBroadcast(
            c, room.hashCode(), new Intent("test-" + room), PendingIntent.FLAG_IMMUTABLE);
    RemoteInput[] inputs =
        photos
            ? (caption ? new RemoteInput[] {text, image} : new RemoteInput[] {image})
            : new RemoteInput[] {text};
    KakaoNotificationListener.ReplyTarget target =
        new KakaoNotificationListener.ReplyTarget(
                "",
                intent,
                inputs,
                0,
                10,
                "key-" + room,
                "",
                "",
                room,
                3,
                "test",
                new ArrayList<>(),
                false)
            .verifiedAs(room);
    for (String name : new String[] {"sessions", "recentTargets"}) {
      Field field = KakaoNotificationListener.class.getDeclaredField(name);
      field.setAccessible(true);
      ((Map<String, KakaoNotificationListener.ReplyTarget>) field.get(null))
          .put(name.equals("sessions") ? room : "key-" + room, target);
    }
  }

  @Test
  public void unsupportedPhotoRoomsPauseWithoutChangingSupportedOrUnselectedRooms()
      throws Exception {
    bind("a", false, true);
    bind("b", true, true);
    MultiRoomStore.BulkPatch patch = photoPatch();
    patch.pauseUnsupportedPhotos = true;
    MultiRoomStore.applyBulkPatch(c, Arrays.asList("a", "b"), patch);
    assertFalse(MultiRoomStore.get(c, "a").enabled);
    assertTrue(MultiRoomStore.get(c, "b").enabled);
    assertTrue(MultiRoomStore.get(c, "c").enabled);
    assertEquals(shared.uri, RoomMediaStore.get(c, "a").uri);
    assertTrue(RoomSendReadiness.get(c, MultiRoomStore.get(c, "a")).blockedPhoto);
    assertEquals(
        RoomSendReadiness.Kind.PHOTO_READY,
        RoomSendReadiness.get(c, MultiRoomStore.get(c, "b")).kind);
  }

  @Test
  public void connectionAloneDoesNotMakePhotoOrCaptionReady() throws Exception {
    bind("a", false, true);
    assertEquals(
        RoomSendReadiness.Kind.TEXT_READY, RoomSendReadiness.get(c, "a", "hello", null).kind);
    assertTrue(RoomSendReadiness.get(c, "a", "hello", shared).blockedPhoto);
    bind("b", true, false);
    assertTrue(RoomSendReadiness.get(c, "b", "caption", shared).blockedPhoto);
    assertEquals(
        RoomSendReadiness.Kind.PHOTO_READY, RoomSendReadiness.get(c, "b", "", shared).kind);
    assertEquals(
        RoomSendReadiness.Kind.CONNECTION, RoomSendReadiness.get(c, "unbound", "", shared).kind);
  }

  @Test
  public void photoOnlyPayloadReschedulesOnBulkSetAndStopsOnBulkClear() {
    MultiRoomStore.Profile p = MultiRoomStore.get(c, "a");
    p.message = "";
    MultiRoomStore.upsert(c, p);
    Prefs.p(c).edit().putBoolean(Prefs.KEY_ACTIVE, true).commit();
    MultiRoomStore.applyBulkPatch(c, Collections.singletonList("a"), photoPatch());
    assertTrue(MultiRoomStore.get(c, "a").nextAt > System.currentTimeMillis());
    MultiRoomStore.BulkPatch clear = photoPatch();
    clear.media = new RoomMediaStore.Media("", "", "");
    MultiRoomStore.applyBulkPatch(c, Collections.singletonList("a"), clear);
    assertEquals(0, MultiRoomStore.get(c, "a").nextAt);
  }

  @Test
  public void stagingPhotoChangesDoesNotMutateCachedOrStoredPhotos() {
    String staged = RoomMediaStore.withChanges(c, Arrays.asList("a", "b"), shared);
    assertTrue(staged.contains("shared.png"));
    assertEquals("content://photos/a", RoomMediaStore.get(c, "a").uri);
    assertEquals("content://photos/b", RoomMediaStore.get(c, "b").uri);
  }

  @Test
  public void preferenceObserversSeePhotoAndMessageChangesTogether() {
    ArrayList<Boolean> consistent = new ArrayList<>();
    android.content.SharedPreferences.OnSharedPreferenceChangeListener observer =
        (prefs, key) -> {
          if (MultiRoomStore.KEY_PROFILES.equals(key) || RoomMediaStore.KEY_OVERRIDES.equals(key))
            consistent.add(
                "new message".equals(MultiRoomStore.get(c, "a").message)
                    && shared.uri.equals(RoomMediaStore.get(c, "a").uri)
                    && shared.uri.equals(RoomMediaStore.get(c, "b").uri));
        };
    Prefs.p(c).registerOnSharedPreferenceChangeListener(observer);
    try {
      MultiRoomStore.BulkPatch patch = photoPatch();
      patch.applyMessage = true;
      patch.message = "new message";
      MultiRoomStore.applyBulkPatch(c, Arrays.asList("a", "b"), patch);
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
      assertFalse(consistent.isEmpty());
      assertFalse(consistent.contains(false));
    } finally {
      Prefs.p(c).unregisterOnSharedPreferenceChangeListener(observer);
    }
  }
}
