package com.local.kakaoautosender;

import android.app.KeyguardManager;
import android.content.ClipDescription;
import android.content.Context;
import android.net.Uri;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import java.util.Locale;

/** One explicit user gesture, bound to one visible editor generation. Never retries a commit. */
final class PhotoKeyboardSession {
  static final long CONFIRM_WINDOW_MS = 120_000L;
  private long generation;
  private long nextTicket;
  private boolean visible;
  private Ticket pending;

  static final class Ticket {
    final long generation, number, deadline;
    final String room;
    final RoomMediaStore.Media media;

    Ticket(long generation, long number, String room, RoomMediaStore.Media media) {
      this.generation = generation;
      this.number = number;
      this.room = room;
      this.media = media;
      deadline = SystemClock.elapsedRealtime() + CONFIRM_WINDOW_MS;
    }
  }

  void invalidate(boolean nowVisible) {
    synchronized (DeliveryGate.LOCK) {
      generation++;
      visible = nowVisible;
      pending = null;
    }
  }

  Ticket select(String room, RoomMediaStore.Media media) {
    synchronized (DeliveryGate.LOCK) {
      if (!visible) return null;
      pending = new Ticket(generation, ++nextTicket, room, media);
      return pending;
    }
  }

  Ticket selectInCurrentEditor(
      String room,
      RoomMediaStore.Media media,
      EditorInfo editor,
      InputConnection connection,
      boolean viewShown) {
    synchronized (DeliveryGate.LOCK) {
      if (!viewShown
          || connection == null
          || media == null
          || !rejection(editor, media.mime).isEmpty()) return null;
      // A fresh user selection may follow a finished/reopened editor. Never revive its old ticket.
      invalidate(true);
      return select(room, media);
    }
  }

  boolean current(Ticket ticket) {
    synchronized (DeliveryGate.LOCK) {
      return visible
          && ticket != null
          && pending == ticket
          && ticket.generation == generation
          && SystemClock.elapsedRealtime() < ticket.deadline;
    }
  }

  String commit(
      Context context,
      Ticket ticket,
      EditorInfo editor,
      InputConnection connection,
      boolean recipientConfirmed) {
    synchronized (DeliveryGate.LOCK) {
      if (!current(ticket)) return "전송 확인이 만료되었습니다. 저장한 사진을 다시 준비하세요.";
      // Consume before calling any external code, including a rejecting or throwing editor.
      pending = null;
      if (!recipientConfirmed) return "현재 카카오톡 방을 먼저 확인하세요.";
      if (!DeliveryGate.allowed(context)) return "라이선스 또는 앱 인증을 확인하세요.";
      if (!unlocked(context)) return "화면을 켜고 잠금을 해제한 뒤 다시 선택하세요.";
      String rejection = rejection(editor, ticket.media.mime);
      if (!rejection.isEmpty()) return rejection;
      MultiRoomStore.Profile profile = MultiRoomStore.get(context, ticket.room);
      RoomMediaStore.Media saved = RoomMediaStore.get(context, ticket.room);
      if (profile == null || !sameMedia(saved, ticket.media))
        return "사진 또는 방 설정이 바뀌었습니다. 다시 선택하세요.";
      if (!"content".equals(Uri.parse(ticket.media.uri).getScheme()))
        return "사진 주소가 유효하지 않습니다. 앱에서 사진을 다시 선택하세요.";
      if (connection == null) return "카카오톡 입력 연결이 끊겼습니다. 입력창을 다시 누르세요.";
      try {
        InputContentInfo content =
            new InputContentInfo(
                Uri.parse(ticket.media.uri),
                new ClipDescription("선택한 사진", new String[] {ticket.media.mime}),
                null);
        boolean accepted =
            connection.commitContent(
                content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null);
        return accepted
            ? "사진 전달 요청을 보냈습니다. 카카오톡에서 미리보기와 실제 도착을 확인하세요."
            : "카카오톡이 사진 입력을 거절했습니다. 자동 재시도하지 않았습니다.";
      } catch (RuntimeException error) {
        return "사진 입력 응답을 확인하지 못했습니다. 중복 전송을 막기 위해 재시도하지 않았습니다.";
      }
    }
  }

  static boolean sameMedia(RoomMediaStore.Media a, RoomMediaStore.Media b) {
    return a != null && b != null && a.uri.equals(b.uri) && a.mime.equals(b.mime);
  }

  static boolean unlocked(Context context) {
    PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
    return power != null && power.isInteractive() && keyguard != null && !keyguard.isDeviceLocked();
  }

  static String rejection(EditorInfo editor, String mime) {
    if (editor == null || !KakaoNotificationListener.KAKAO_PACKAGE.equals(editor.packageName))
      return "카카오톡 채팅방의 메시지 입력창에서 사용하세요.";
    int kind = editor.inputType & InputType.TYPE_MASK_CLASS;
    int variation = editor.inputType & InputType.TYPE_MASK_VARIATION;
    if (kind != InputType.TYPE_CLASS_TEXT
        || variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
        || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
      return "이 입력창에서는 사진을 전달하지 않습니다. 채팅 메시지 입력창을 누르세요.";
    String actual = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
    if (!actual.matches("image/[a-z0-9][a-z0-9.+_-]*")) return "사진 형식을 확인할 수 없습니다.";
    for (String accepted : mimeTypes(editor))
      if (accepted != null && KakaoMessageSender.matchesMime(accepted, actual)) return "";
    return "현재 카카오톡 입력창이 이 사진 형식의 키보드 입력을 지원하지 않습니다.";
  }

  static String[] mimeTypes(EditorInfo editor) {
    if (editor == null) return new String[0];
    if (editor.contentMimeTypes != null && editor.contentMimeTypes.length > 0)
      return editor.contentMimeTypes.clone();
    if (editor.extras != null) {
      // AndroidX/support-v13 compatibility uses the same platform commitContent on API 25+.
      for (String key :
          new String[] {
            "androidx.core.view.inputmethod.EditorInfoCompat.CONTENT_MIME_TYPES",
            "android.support.v13.view.inputmethod.EditorInfoCompat.CONTENT_MIME_TYPES"
          }) {
        Object value = editor.extras.get(key);
        if (value instanceof String[]) return ((String[]) value).clone();
      }
    }
    return new String[0];
  }
}
