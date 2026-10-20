package com.local.kakaoautosender;

import android.content.ClipDescription;
import android.content.Context;
import android.net.Uri;
import android.os.PowerManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import java.util.UUID;

/** An explicitly armed schedule belongs to one live input connection, never just a room title. */
final class PhotoLoopSession {
  static final class Plan {
    final String run = UUID.randomUUID().toString();
    final MultiRoomStore.Profile source;
    final RoomMediaStore.Media media;
    InputConnection connection;
    final boolean once, screenOff;
    long next;
    boolean inFlight;
    boolean uiPending;

    Plan(
        MultiRoomStore.Profile source,
        RoomMediaStore.Media media,
        InputConnection connection,
        boolean once,
        boolean screenOff,
        long now) {
      this.source = source.copy();
      this.media = media;
      this.connection = connection;
      this.once = once;
      this.screenOff = screenOff;
      next = MultiRoomStore.computeNextAt(source, now);
    }
  }

  private Plan plan;

  boolean active() {
    synchronized (DeliveryGate.LOCK) {
      return plan != null;
    }
  }

  Plan current() {
    synchronized (DeliveryGate.LOCK) {
      return plan;
    }
  }

  String arm(
      Context c,
      MultiRoomStore.Profile source,
      RoomMediaStore.Media media,
      EditorInfo editor,
      InputConnection connection,
      boolean once,
      boolean screenOff,
      boolean recipientConfirmed,
      boolean directDeliveryConfirmed,
      long now) {
    return armInternal(
        c,
        source,
        media,
        editor,
        connection,
        once,
        screenOff,
        recipientConfirmed,
        directDeliveryConfirmed,
        now,
        false);
  }

  String armQuickTest(
      Context c,
      MultiRoomStore.Profile source,
      RoomMediaStore.Media media,
      EditorInfo editor,
      InputConnection connection,
      boolean recipientConfirmed,
      boolean directDeliveryConfirmed,
      long now) {
    return armInternal(
        c,
        source,
        media,
        editor,
        connection,
        true,
        false,
        recipientConfirmed,
        directDeliveryConfirmed,
        now,
        true);
  }

  private String armInternal(
      Context c,
      MultiRoomStore.Profile source,
      RoomMediaStore.Media media,
      EditorInfo editor,
      InputConnection connection,
      boolean once,
      boolean screenOff,
      boolean recipientConfirmed,
      boolean directDeliveryConfirmed,
      long now,
      boolean quickTest) {
    synchronized (DeliveryGate.LOCK) {
      if (plan != null) return "사진 예약이 이미 실행 중입니다. 먼저 중지하세요.";
      if (!recipientConfirmed || !directDeliveryConfirmed) return "현재 방 확인과 사진 전송 버튼 학습을 먼저 완료하세요.";
      if (!DeliveryGate.allowed(c) || !PhotoKeyboardSession.unlocked(c))
        return "화면 잠금과 라이선스를 확인하세요.";
      if (source == null || connection == null) return "방 설정 또는 입력 연결이 없습니다.";
      if (Prefs.p(c).getBoolean(Prefs.KEY_ACTIVE, false) && source.enabled)
        return "중복 예약을 막으려면 앱에서 이 방의 기존 자동전송을 먼저 끄세요.";
      if (!PhotoKeyboardSession.sameMedia(RoomMediaStore.get(c, source.room), media)
          || !"content".equals(Uri.parse(media.uri).getScheme())) return "사진을 다시 선택하세요.";
      String rejection = PhotoKeyboardSession.rejection(editor, media.mime);
      if (!rejection.isEmpty()) return rejection;
      if (source.dailyLimit > 0
          && PhotoLoopStore.attemptsToday(c, source.room) >= source.dailyLimit)
        return "오늘 사진 요청 한도에 도달했습니다.";
      Plan candidate = new Plan(source, media, connection, once, screenOff, now);
      if (quickTest) candidate.next = now + 10_000L;
      PhotoLoopStore.arm(c, candidate.run, source.room, candidate.next);
      plan = candidate;
      return "";
    }
  }

  String preflight(Context c, Plan expected, EditorInfo editor, InputConnection connection) {
    synchronized (DeliveryGate.LOCK) {
      if (expected == null || plan != expected) return "사진 예약이 취소되었거나 바뀌었습니다.";
      if (!DeliveryGate.allowed(c)) return "라이선스 또는 앱 인증으로 사진 예약 중지";
      if (connection == null || connection != expected.connection)
        return "카카오톡 입력 연결이 바뀌어 사진 예약 중지";
      String rejection = PhotoKeyboardSession.rejection(editor, expected.media.mime);
      if (!rejection.isEmpty()) return rejection;
      PowerManager power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
      if (power == null) return "화면 상태를 확인할 수 없어 사진 예약 중지";
      if (!power.isInteractive()) {
        if (!expected.screenOff) return "화면이 꺼져 사진 예약 중지";
        // This does not unlock the phone or bypass the system. An inactive InputConnection
        // may reject commitContent; only this still-bound, unchanged connection is attempted.
      } else if (!PhotoKeyboardSession.unlocked(c)) return "휴대폰이 잠겨 사진 예약 중지";
      MultiRoomStore.Profile saved = MultiRoomStore.get(c, expected.source.room);
      if (saved == null
          || !sameSettings(saved, expected.source)
          || !PhotoKeyboardSession.sameMedia(
              RoomMediaStore.get(c, expected.source.room), expected.media))
        return "사진 또는 방 일정이 바뀌어 사진 예약 중지";
      if (Prefs.p(c).getBoolean(Prefs.KEY_ACTIVE, false) && saved.enabled)
        return "같은 방의 기존 자동전송이 켜져 사진 키보드 예약 중지";
      return "";
    }
  }

  String uiGate(Context c, Plan expected) {
    synchronized (DeliveryGate.LOCK) {
      if (plan != expected || expected == null || !expected.uiPending) return "활성 사진 요청 없음";
      if (!DeliveryGate.allowed(c)) return "라이선스 또는 앱 인증으로 중지";
      PowerManager power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
      if (power == null
          || (!power.isInteractive() && !expected.screenOff)
          || (power.isInteractive() && !PhotoKeyboardSession.unlocked(c))) return "화면 또는 잠금으로 중지";
      MultiRoomStore.Profile saved = MultiRoomStore.get(c, expected.source.room);
      if (saved == null
          || !sameSettings(saved, expected.source)
          || !PhotoKeyboardSession.sameMedia(RoomMediaStore.get(c, saved.room), expected.media))
        return "설정 변경으로 중지";
      if (Prefs.p(c).getBoolean(Prefs.KEY_ACTIVE, false) && saved.enabled) return "중복 예약으로 중지";
      return "";
    }
  }

  boolean reserve(Context c, Plan expected, String run, long when, long now) {
    synchronized (DeliveryGate.LOCK) {
      if (plan != expected
          || expected == null
          || expected.inFlight
          || expected.uiPending
          || !expected.run.equals(run)
          || expected.next != when
          || now < when) return false;
      if (!PhotoLoopStore.reserve(c, run, when, expected.source.dailyLimit)) {
        stop(c, "사진 요청 한도 또는 예약 기록을 확인할 수 없어 중지");
        return false;
      }
      expected.inFlight = true;
      return true;
    }
  }

  String deliver(
      Context c, Plan expected, EditorInfo editor, InputConnection connection, long now) {
    synchronized (DeliveryGate.LOCK) {
      String rejection = preflight(c, expected, editor, connection);
      if (!rejection.isEmpty()) return rejection;
      if (!expected.inFlight) return "예약되지 않은 사진 요청 차단";
      // Consume this occurrence before the remote call; never replay an uncertain result.
      expected.inFlight = false;
      expected.uiPending = true;
      try {
        boolean accepted =
            connection.commitContent(
                new InputContentInfo(
                    Uri.parse(expected.media.uri),
                    new ClipDescription("예약한 사진", new String[] {expected.media.mime}),
                    null),
                InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null);
        if (!accepted) {
          stop(c, "카카오톡이 사진 입력을 거절해 예약 중지 · 재시도 없음");
          return "사진 입력 거절";
        }
        if (plan != expected) return "입력 연결 변경으로 중지되었습니다. 도착 여부를 확인하세요.";
        PhotoLoopStore.accepted(c, expected.run, 0);
        return "";
      } catch (RuntimeException error) {
        stop(c, "사진 요청 응답 불명확 · 중복 방지를 위해 예약 중지");
        return "사진 요청 응답 불명확";
      }
    }
  }

  boolean completeUi(
      Context c, Plan expected, EditorInfo editor, InputConnection connection, long now) {
    synchronized (DeliveryGate.LOCK) {
      if (plan != expected
          || expected == null
          || !expected.uiPending
          || connection == null
          || !PhotoKeyboardSession.rejection(editor, expected.media.mime).isEmpty()) return false;
      if (!uiGate(c, expected).isEmpty()) {
        stop(c, "사진 전송 후 설정·잠금·라이선스 변경으로 중지");
        return false;
      }
      expected.connection = connection;
      expected.uiPending = false;
      expected.next =
          expected.once
              ? 0
              : (expected.source.dailyLimit > 0
                      && PhotoLoopStore.attemptsToday(c, expected.source.room)
                          >= expected.source.dailyLimit
                  ? MultiRoomStore.nextAfterDailyLimit(expected.source, now)
                  : MultiRoomStore.computeNextAt(expected.source, now));
      PhotoLoopStore.accepted(c, expected.run, expected.next);
      if (expected.once) stop(c, "사진 1회 예약 전송 버튼 실행 · 실제 도착을 확인하세요.");
      return true;
    }
  }

  void stop(Context c, String reason) {
    synchronized (DeliveryGate.LOCK) {
      plan = null;
      PhotoLoopStore.stop(c, reason);
    }
  }

  static boolean sameSettings(MultiRoomStore.Profile a, MultiRoomStore.Profile b) {
    return a.room.equals(b.room)
        && a.actualRoomName.equals(b.actualRoomName)
        && a.scheduleMode.equals(b.scheduleMode)
        && a.intervalMinutes == b.intervalMinutes
        && a.dailyTimes.equals(b.dailyTimes)
        && a.dailyLimit == b.dailyLimit
        && a.enabled == b.enabled
        && a.message.equals(b.message);
  }
}
