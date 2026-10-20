package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
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
public class PhotoReplyActionTest {
  private Context context;
  private final Uri photo = Uri.parse("content://photo/test");

  @Before
  public void prepare() {
    context = RuntimeEnvironment.getApplication();
    Prefs.p(context).edit().clear().commit();
    KakaoNotificationListener.clearRuntimeAndBindings(context);
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
    Shadows.shadowOf(context.getContentResolver())
        .registerInputStream(photo, new ByteArrayInputStream(new byte[] {1, 2, 3}));
  }

  private Notification.Action action(String title, String broadcast, boolean text, boolean image) {
    Notification.Action.Builder builder =
        new Notification.Action.Builder(
            0,
            title,
            PendingIntent.getBroadcast(
                context,
                broadcast.hashCode(),
                new Intent(broadcast),
                PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0)));
    if (text) builder.addRemoteInput(new RemoteInput.Builder("text").build());
    if (image)
      builder.addRemoteInput(
          new RemoteInput.Builder("photo")
              .setAllowFreeFormInput(false)
              .setAllowDataType("image/*", true)
              .build());
    return builder.build();
  }

  private Notification notification(Notification.Action... actions) {
    Notification.Builder builder = new Notification.Builder(context, "test");
    for (Notification.Action action : actions) builder.addAction(action);
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private KakaoNotificationListener.ReplyTarget bind(Notification notification) throws Exception {
    Notification.Action primary = KakaoNotificationListener.findReplyAction(notification);
    assertNotNull(primary);
    KakaoNotificationListener.ReplyTarget target =
        new KakaoNotificationListener.ReplyTarget(
                "",
                primary.actionIntent,
                PhotoReplyAction.allInputs(primary),
                0,
                10,
                "key",
                "",
                "",
                "room",
                3,
                "test",
                new ArrayList<>(),
                false,
                PhotoReplyAction.capture(notification, primary))
            .verifiedAs("room");
    for (String name : new String[] {"sessions", "recentTargets"}) {
      Field field = KakaoNotificationListener.class.getDeclaredField(name);
      field.setAccessible(true);
      ((Map<String, KakaoNotificationListener.ReplyTarget>) field.get(null))
          .put(name.equals("sessions") ? "room" : "key", target);
    }
    return target;
  }

  private RoomMediaStore.Media media() {
    return new RoomMediaStore.Media(photo.toString(), "image/png", "test.png");
  }

  private Intent delivered(String action) {
    for (Intent intent :
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents())
      if (action.equals(intent.getAction())) return intent;
    return null;
  }

  @Test
  @Config(sdk = {28, 35})
  public void androidSeparatesDataOnlyInputAndCaptureIncludesIt() throws Exception {
    Notification.Action reply = action("답장", "BOTH", true, true);
    assertEquals(1, reply.getRemoteInputs().length);
    assertEquals(1, reply.getDataOnlyRemoteInputs().length);
    bind(notification(reply));
    assertTrue(KakaoMessageSender.send(context, "room", "caption", media()));
    Intent sent = delivered("BOTH");
    assertNotNull(sent);
    assertEquals(photo, RemoteInput.getDataResultsFromIntent(sent, "photo").get("image/png"));
    assertEquals("caption", RemoteInput.getResultsFromIntent(sent).getCharSequence("text"));
  }

  @Test
  public void separatePhotoReplyUsesItsOwnPendingIntent() throws Exception {
    bind(notification(action("답장", "TEXT", true, false), action("사진 답장", "PHOTO", false, true)));
    assertTrue(KakaoMessageSender.send(context, "room", "", media()));
    assertNull(delivered("TEXT"));
    assertEquals(
        photo, RemoteInput.getDataResultsFromIntent(delivered("PHOTO"), "photo").get("image/png"));
    assertTrue(KakaoMessageSender.send(context, "room", "text remains", null));
    assertEquals(
        "text remains",
        RemoteInput.getResultsFromIntent(delivered("TEXT")).getCharSequence("text"));
  }

  @Test
  public void wearablePhotoReplyRetainsOrdinaryTextAction() throws Exception {
    Notification.Action text = action("답장", "TEXT", true, false);
    Notification notification =
        new Notification.Builder(context, "test")
            .addAction(text)
            .extend(
                new Notification.WearableExtender()
                    .addAction(action("사진 답장", "WEAR_PHOTO", false, true)))
            .build();
    assertSame(text, KakaoNotificationListener.findReplyAction(notification));
    bind(notification);
    assertTrue(KakaoMessageSender.send(context, "room", "", media()));
    assertNotNull(delivered("WEAR_PHOTO"));
    assertNull(delivered("TEXT"));
  }

  @Test
  @Config(sdk = 26)
  public void photoOnlyReplyCanBeCapturedAtMinimumSupportedAndroid() throws Exception {
    Notification.Action reply = action("사진 답장", "PHOTO", false, true);
    assertNull(reply.getRemoteInputs());
    assertEquals(1, reply.getDataOnlyRemoteInputs().length);
    assertNotNull(KakaoNotificationListener.findReplyAction(notification(reply)));
  }

  @Test
  public void captionCannotBeBorrowedFromAnotherAction() throws Exception {
    bind(notification(action("답장", "TEXT", true, false), action("사진 답장", "PHOTO", false, true)));
    assertFalse(KakaoMessageSender.send(context, "room", "caption", media()));
    assertNull(delivered("TEXT"));
    assertNull(delivered("PHOTO"));
  }

  @Test
  public void imageOnlyReplyCanBeBoundButCannotSendText() throws Exception {
    bind(notification(action("사진 답장", "PHOTO", false, true)));
    assertFalse(KakaoNotificationListener.sendToRoom(context, "room", "text"));
    assertNull(delivered("PHOTO"));
    assertTrue(KakaoMessageSender.send(context, "room", "", media()));
  }

  @Test
  public void arbitraryImageActionIsNotTreatedAsPhotoReply() throws Exception {
    bind(notification(action("답장", "TEXT", true, false), action("삭제", "DELETE", false, true)));
    assertFalse(KakaoMessageSender.send(context, "room", "", media()));
    assertNull(delivered("DELETE"));
    assertNull(delivered("TEXT"));
  }

  @Test
  @Config(sdk = 35)
  public void authenticationRequiredPhotoCannotDispatchWhileDeviceLocked() throws Exception {
    Notification.Action protectedReply =
        new Notification.Action.Builder(action("사진 답장", "PHOTO", false, true))
            .setAuthenticationRequired(true)
            .build();
    bind(notification(protectedReply));
    Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(true);
    assertFalse(KakaoMessageSender.send(context, "room", "", media()));
    assertNull(delivered("PHOTO"));
  }

  @Test
  public void removedNotificationCannotUseItsSecondaryPhotoReply() throws Exception {
    bind(notification(action("답장", "TEXT", true, false), action("사진 답장", "PHOTO", false, true)));
    KakaoNotificationListener.invalidateNotification("key", 10);
    assertFalse(KakaoMessageSender.send(context, "room", "", media()));
    assertNull(delivered("PHOTO"));
  }

  @Test
  public void canceledPhotoReplyNeverFallsBackToTextReply() throws Exception {
    KakaoNotificationListener.ReplyTarget target =
        bind(
            notification(action("답장", "TEXT", true, false), action("사진 답장", "PHOTO", false, true)));
    PhotoReplyAction.select(target.photoActions, "image/png", false).pendingIntent.cancel();
    assertFalse(KakaoMessageSender.send(context, "room", "", media()));
    assertNull(delivered("TEXT"));
  }

  @Test
  public void wrongMimeAndLicenseLockPreventPhotoDispatch() throws Exception {
    bind(notification(action("사진 답장", "PHOTO", false, true)));
    assertFalse(
        KakaoMessageSender.send(
            context, "room", "", new RoomMediaStore.Media(photo.toString(), "video/mp4", "video")));
    LicenseManager.lockout(context, "SUSPENDED", "");
    assertFalse(KakaoMessageSender.send(context, "room", "", media()));
    assertNull(delivered("PHOTO"));
  }

  @Test
  public void userShareGrantsOnlyReadAccessAndDoesNotGuessRecipient() throws Exception {
    Intent request = PhotoShareRequest.create(context, media());
    assertEquals(Intent.ACTION_SEND, request.getAction());
    assertEquals("com.kakao.talk", request.getPackage());
    assertEquals(photo, request.getParcelableExtra(Intent.EXTRA_STREAM));
    assertEquals(photo, request.getClipData().getItemAt(0).getUri());
    assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, request.getFlags());
    assertFalse(request.hasExtra(Intent.EXTRA_TEXT));
    assertFalse(request.hasExtra(Intent.EXTRA_SHORTCUT_ID));
    assertNull(delivered("PHOTO"));
  }

  @Test
  public void unreadableShareCannotOpenKakao() throws Exception {
    try {
      PhotoShareRequest.create(
          context, new RoomMediaStore.Media("file:///private/test.png", "image/png", "test"));
      fail("file URI must not be shared");
    } catch (IOException expected) {
    }
    try {
      PhotoShareRequest.create(
          context, new RoomMediaStore.Media("content://photo/missing", "image/png", "test"));
      fail("missing URI must not be shared");
    } catch (IOException expected) {
    }
  }
}
