package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import java.lang.reflect.Field;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
public class PhotoKeyboardSessionTest {
  private Context context;
  private PhotoKeyboardSession session;
  private EditorInfo editor;
  private RecordingConnection connection;
  private final RoomMediaStore.Media media =
      new RoomMediaStore.Media("content://photos/test.png", "image/png", "test.png");

  private class RecordingConnection extends BaseInputConnection {
    int calls;
    int flags;
    InputContentInfo delivered;
    boolean accepted = true;
    boolean throwsAfterAccept;

    RecordingConnection() {
      super(new View(context), true);
    }

    @Override
    public boolean commitContent(InputContentInfo content, int flags, Bundle options) {
      calls++;
      delivered = content;
      this.flags = flags;
      if (throwsAfterAccept) throw new IllegalStateException("unknown remote result");
      return accepted;
    }
  }

  @Before
  public void prepare() {
    context = RuntimeEnvironment.getApplication();
    Prefs.p(context).edit().clear().commit();
    context.getSharedPreferences("room_media_v1", 0).edit().clear().commit();
    context.getSharedPreferences("photo_keyboard_diagnostics", 0).edit().clear().commit();
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
    MultiRoomStore.upsert(context, new MultiRoomStore.Profile("test-room"));
    RoomMediaStore.set(context, "test-room", media.uri, media.mime, media.name);
    session = new PhotoKeyboardSession();
    session.invalidate(true);
    editor = new EditorInfo();
    editor.packageName = KakaoNotificationListener.KAKAO_PACKAGE;
    editor.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
    editor.contentMimeTypes = new String[] {"image/*"};
    connection = new RecordingConnection();
  }

  @Test
  public void newVisibleEditorSelectionRecoversAfterKakaoClosesWithoutRevivingOldTicket() {
    session.invalidate(true);
    PhotoKeyboardSession.Ticket old = session.select("test-room", media);
    session.invalidate(false);
    assertNull(session.select("test-room", media));
    PhotoKeyboardSession.Ticket fresh =
        session.selectInCurrentEditor("test-room", media, editor, connection, true);
    assertNotNull(fresh);
    assertTrue(session.current(fresh));
    assertFalse(session.current(old));
    assertFalse(session.commit(context, old, editor, connection, true).startsWith("사진 전달 요청"));
    assertEquals(0, connection.calls);
    assertTrue(session.commit(context, fresh, editor, connection, true).startsWith("사진 전달 요청"));
    assertEquals(1, connection.calls);
  }

  @Test
  public void hiddenDisconnectedOrForeignEditorCannotRestartSelection() {
    session.invalidate(false);
    assertNull(session.selectInCurrentEditor("test-room", media, editor, connection, false));
    assertNull(session.selectInCurrentEditor("test-room", media, editor, null, true));
    editor.packageName = "other.app";
    assertNull(session.selectInCurrentEditor("test-room", media, editor, connection, true));
    assertEquals(0, connection.calls);
  }

  private PhotoKeyboardSession.Ticket select() {
    return session.select("test-room", media);
  }

  private String commit(PhotoKeyboardSession.Ticket ticket) {
    return session.commit(context, ticket, editor, connection, true);
  }

  @Test
  public void deliversOneContentRequestWithTemporaryReadFlagAndNoSuccessCount() {
    PhotoKeyboardSession.Ticket ticket = select();
    assertTrue(commit(ticket).contains("전달 요청"));
    assertEquals(1, connection.calls);
    assertEquals(Uri.parse(media.uri), connection.delivered.getContentUri());
    assertTrue(connection.delivered.getDescription().hasMimeType("image/png"));
    assertEquals(InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, connection.flags);
    assertNull(connection.delivered.getLinkUri());
    assertEquals(0, MultiRoomStore.get(context, "test-room").todayCount);
    assertFalse(commit(ticket).contains("전달 요청"));
    assertEquals(1, connection.calls);
  }

  @Test
  public void rejectingAndAmbiguousResponsesNeverRetrySameTicket() {
    connection.accepted = false;
    PhotoKeyboardSession.Ticket rejected = select();
    assertTrue(commit(rejected).contains("거절"));
    commit(rejected);
    assertEquals(1, connection.calls);
    connection.throwsAfterAccept = true;
    PhotoKeyboardSession.Ticket uncertain = select();
    assertTrue(commit(uncertain).contains("중복"));
    commit(uncertain);
    assertEquals(2, connection.calls);
    assertEquals(0, MultiRoomStore.get(context, "test-room").todayCount);
  }

  @Test
  public void editorRestartHiddenAndReplacedSelectionsCannotUseOldTicket() {
    PhotoKeyboardSession.Ticket beforeRestart = select();
    session.invalidate(true);
    commit(beforeRestart);
    PhotoKeyboardSession.Ticket first = select();
    PhotoKeyboardSession.Ticket replacement = select();
    commit(first);
    session.invalidate(false);
    commit(replacement);
    assertNull(select());
    assertEquals(0, connection.calls);
  }

  @Test
  public void confirmationExpiresAndRequiresNewSelection() {
    PhotoKeyboardSession.Ticket ticket = select();
    ShadowSystemClock.advanceBy(Duration.ofMillis(PhotoKeyboardSession.CONFIRM_WINDOW_MS));
    assertFalse(session.current(ticket));
    commit(ticket);
    assertEquals(0, connection.calls);
  }

  @Test
  public void recipientConfirmationIsMandatoryAndConsumesTicket() {
    PhotoKeyboardSession.Ticket ticket = select();
    session.commit(context, ticket, editor, connection, false);
    commit(ticket);
    assertEquals(0, connection.calls);
  }

  @Test
  public void finalGateRechecksLicenseEvenAfterPreview() {
    PhotoKeyboardSession.Ticket ticket = select();
    context.getSharedPreferences("entitlement_v2", 0).edit().putString("state", "REVOKED").commit();
    assertTrue(commit(ticket).contains("인증"));
    assertEquals(0, connection.calls);
  }

  @Test
  public void screenOffOrSecureLockPreventsDelivery() {
    PhotoKeyboardSession.Ticket ticket = select();
    Shadows.shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
        .setIsInteractive(false);
    commit(ticket);
    Shadows.shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
        .setIsInteractive(true);
    Shadows.shadowOf((KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE))
        .setIsDeviceLocked(true);
    commit(select());
    assertEquals(0, connection.calls);
  }

  @Test
  public void changedPhotoMimeOrDeletedProfileBlocksPreparedRequest() {
    PhotoKeyboardSession.Ticket ticket = select();
    RoomMediaStore.set(context, "test-room", media.uri, "image/jpeg", "changed");
    commit(ticket);
    RoomMediaStore.set(context, "test-room", media.uri, media.mime, media.name);
    PhotoKeyboardSession.Ticket deleted = select();
    MultiRoomStore.remove(context, "test-room");
    commit(deleted);
    assertEquals(0, connection.calls);
  }

  @Test
  public void acceptsOnlyConcreteImagesInKakaoTextEditors() {
    assertEquals("", PhotoKeyboardSession.rejection(editor, "image/png"));
    assertFalse(PhotoKeyboardSession.rejection(editor, "image/*").isEmpty());
    assertFalse(PhotoKeyboardSession.rejection(editor, "text/plain").isEmpty());
    editor.packageName = "com.kakao.talk.other";
    assertFalse(PhotoKeyboardSession.rejection(editor, media.mime).isEmpty());
    commit(select());
    editor.packageName = KakaoNotificationListener.KAKAO_PACKAGE;
    editor.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    commit(select());
    editor.inputType = InputType.TYPE_CLASS_NUMBER;
    commit(select());
    editor.inputType = InputType.TYPE_CLASS_TEXT;
    editor.contentMimeTypes = new String[] {"image/gif"};
    commit(select());
    assertEquals(0, connection.calls);
  }

  @Test
  public void supportMetadataFallbackWorksWithoutReadingEditorText() {
    editor.contentMimeTypes = null;
    assertFalse(PhotoKeyboardSession.rejection(editor, media.mime).isEmpty());
    editor.extras = new Bundle();
    editor.extras.putStringArray(
        "androidx.core.view.inputmethod.EditorInfoCompat.CONTENT_MIME_TYPES",
        new String[] {"image/png"});
    assertEquals("", PhotoKeyboardSession.rejection(editor, media.mime));
    editor.extras.putString(
        "androidx.core.view.inputmethod.EditorInfoCompat.CONTENT_MIME_TYPES", "malformed");
    assertFalse(PhotoKeyboardSession.rejection(editor, media.mime).isEmpty());
  }

  @Test
  public void diagnosticsAreScopedAndExpireAcrossBootAndTime() {
    PhotoKeyboardDiagnostics.recordEditor(context, editor);
    assertTrue(PhotoKeyboardDiagnostics.summary(context).contains("image/*"));
    editor.packageName = "private.app";
    editor.contentMimeTypes = new String[] {"image/secret"};
    PhotoKeyboardDiagnostics.recordEditor(context, editor);
    assertFalse(PhotoKeyboardDiagnostics.summary(context).contains("secret"));
    Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 5);
    assertTrue(PhotoKeyboardDiagnostics.summary(context).contains("확인 전"));
    Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 4);
    ShadowSystemClock.advanceBy(Duration.ofMinutes(16));
    assertTrue(PhotoKeyboardDiagnostics.summary(context).contains("확인 전"));
    assertFalse(
        context
            .getSharedPreferences("photo_keyboard_diagnostics", 0)
            .getAll()
            .toString()
            .contains(media.uri));
  }

  @Test
  public void fileUriAndMissingInputConnectionNeverCommit() {
    RoomMediaStore.Media invalid =
        new RoomMediaStore.Media("file:///private.png", media.mime, media.name);
    RoomMediaStore.set(context, "test-room", invalid.uri, invalid.mime, invalid.name);
    commit(session.select("test-room", invalid));
    RoomMediaStore.set(context, "test-room", media.uri, media.mime, media.name);
    session.commit(context, select(), editor, null, true);
    assertEquals(0, connection.calls);
  }

  @Test
  public void serviceCancelsPreparedWorkOnScreenOffAndGlobalStop() throws Exception {
    ServiceController<PhotoKeyboardService> controller =
        Robolectric.buildService(PhotoKeyboardService.class).create();
    try {
      PhotoKeyboardService service = controller.get();
      service.onStartInputView(editor, false);
      Field field = PhotoKeyboardService.class.getDeclaredField("session");
      field.setAccessible(true);
      PhotoKeyboardSession live = (PhotoKeyboardSession) field.get(service);
      PhotoKeyboardSession.Ticket ticket = live.select("test-room", media);
      assertTrue(live.current(ticket));
      context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
      Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
      assertFalse(live.current(ticket));
      service.onStartInputView(editor, true);
      ticket = live.select("test-room", media);
      DeliveryGate.stop(context);
      assertFalse(live.current(ticket));
    } finally {
      controller.destroy();
    }
  }
}
