package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
public class PhotoTourRouteTest {
  private Context c;
  private KakaoNotificationListener.ReplyTarget target;
  private PendingIntent open;

  @After
  public void cleanup() {
    PhotoTourController.stop(c, "test cleanup");
  }

  private void controller(String name, Object value) throws Exception {
    Field f = PhotoTourController.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(null, value);
  }

  @SuppressWarnings("unchecked")
  private void put(String field, String key, Object value) throws Exception {
    Field f = KakaoNotificationListener.class.getDeclaredField(field);
    f.setAccessible(true);
    ((Map<String, Object>) f.get(null)).put(key, value);
  }

  @Before
  public void setup() throws Exception {
    c = RuntimeEnvironment.getApplication();
    Prefs.p(c).edit().clear().commit();
    KakaoNotificationListener.clearLiveTargets();
    ArrayList<String> ids = new ArrayList<>(Collections.singletonList("kakao-shortcut:a"));
    Prefs.bindIdentity(c, "a", ids);
    Prefs.markRoomConfirmed(c, "a");
    open =
        PendingIntent.getActivity(
            c, 193, new Intent("test-open-room"), PendingIntent.FLAG_IMMUTABLE);
    Shadows.shadowOf(open).setCreatorPackage(KakaoNotificationListener.KAKAO_PACKAGE);
    target =
        new KakaoNotificationListener.ReplyTarget(
            "a",
            null,
            new RemoteInput[0],
            100,
            100,
            "token",
            null,
            null,
            "a",
            100,
            "test",
            ids,
            true,
            new PhotoReplyAction[0],
            open);
    put("sessions", "a", target);
    put("recentTargets", "token", target);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void duplicateRoomConfirmationCannotSkipNextRegistration() throws Exception {
    android.content.pm.PackageInfo info = new android.content.pm.PackageInfo();
    info.packageName = KakaoNotificationListener.KAKAO_PACKAGE;
    info.versionCode = 7;
    Shadows.shadowOf(c.getPackageManager()).installPackage(info);
    PhotoUiWorkflow.Anchor anchor = new PhotoUiWorkflow.Anchor(1, "editor", "header");
    controller("app", c);
    controller("queue", null);
    controller("alias", "a");
    controller("route", target);
    controller("registrationAnchor", anchor);
    controller("phase", PhotoTourController.Phase.REGISTER_READY);
    Field f = PhotoTourController.class.getDeclaredField("registrations");
    f.setAccessible(true);
    ArrayDeque<String> remaining = (ArrayDeque<String>) f.get(null);
    remaining.clear();
    remaining.add("a");
    remaining.add("b");
    PhotoUiWorkflow.Button template =
        new PhotoUiWorkflow.Button(2, "photo.Activity", "layout", "send", "Button", "전송");
    PhotoTourController.learned(anchor, template);
    PhotoTourController.learned(anchor, template);
    assertEquals(1, remaining.size());
    assertEquals("b", remaining.peek());
    assertNotNull(PhotoTourBindings.get(c, "a"));
  }

  @Test
  public void rebindingRoomAfterNavigationBlocksCommitAndFinalClick() throws Exception {
    android.content.pm.PackageInfo info = new android.content.pm.PackageInfo();
    info.packageName = KakaoNotificationListener.KAKAO_PACKAGE;
    info.versionCode = 7;
    Shadows.shadowOf(c.getPackageManager()).installPackage(info);
    PhotoTourBindings.Binding b =
        new PhotoTourBindings.Binding(
            "a",
            new PhotoUiWorkflow.Anchor(1, "editor", "header"),
            target.stableIdentityKeys,
            7,
            new PhotoUiWorkflow.Button(2, "photo.Activity", "layout", "send", "Button", "전송"));
    assertEquals("", PhotoTourBindings.save(c, b));
    PhotoTourQueue q = new PhotoTourQueue(true);
    PhotoTourQueue.Job job =
        new PhotoTourQueue.Job(
            new MultiRoomStore.Profile("a"),
            new RoomMediaStore.Media("content://photos/a", "image/png", "a"),
            1);
    q.jobs.add(job);
    q.claim(1);
    controller("app", c);
    controller("queue", q);
    controller("job", job);
    controller("alias", "a");
    controller("route", target);
    controller("loopRun", "loop");
    controller("phase", PhotoTourController.Phase.SENDING);
    assertTrue(PhotoTourController.finalGate("loop"));
    assertFalse(PhotoTourController.finalGate("old"));
    Prefs.bindIdentity(c, "b", target.stableIdentityKeys);
    assertFalse(PhotoTourController.finalGate("loop"));
  }

  @Test
  public void currentVerifiedRoomAcceptsOnlyKakaoCreatedOpenAction() {
    assertEquals("", PhotoTourController.routeError(c, "a", target));
    Shadows.shadowOf(open).setCreatorPackage("other.app");
    assertFalse(PhotoTourController.routeError(c, "a", target).isEmpty());
  }

  @Test
  public void removedNotificationCannotNavigateUsingStaleSavedAction() {
    KakaoNotificationListener.invalidateNotification("token", 100);
    assertFalse(PhotoTourController.routeError(c, "a", target).isEmpty());
  }

  @Test
  public void confirmationAndIdentityConflictBlockNavigation() {
    Prefs.clearRoomConfirmed(c, "a");
    assertFalse(PhotoTourController.routeError(c, "a", target).isEmpty());
    Prefs.markRoomConfirmed(c, "a");
    Prefs.bindIdentity(c, "b", target.stableIdentityKeys);
    assertFalse(PhotoTourController.routeError(c, "a", target).isEmpty());
  }

  @Test
  public void verifiedCopyPreservesOpeningActionWithoutUsingReplyAction() {
    assertSame(open, target.verifiedAs("a").openIntent);
    assertNull(target.verifiedAs("a").pendingIntent);
  }
}
