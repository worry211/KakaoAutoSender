package com.local.kakaoautosender;

import android.content.Context;

/** Route capability, never an assertion of delivery or a substitute for the final sender gate. */
final class RoomSendReadiness {
  enum Kind {
    EMPTY,
    CONNECTION,
    TEXT_READY,
    PHOTO_READY,
    PHOTO_BLOCKED,
    PHOTO_INVALID
  }

  final Kind kind;
  final String label, detail;
  final boolean ready, blockedPhoto;

  private RoomSendReadiness(
      Kind kind, String label, String detail, boolean ready, boolean blocked) {
    this.kind = kind;
    this.label = label;
    this.detail = detail;
    this.ready = ready;
    blockedPhoto = blocked;
  }

  static RoomSendReadiness get(Context c, MultiRoomStore.Profile profile) {
    return get(c, profile.room, profile.message, RoomMediaStore.get(c, profile.room));
  }

  static RoomSendReadiness get(Context c, String room, String message, RoomMediaStore.Media media) {
    boolean photo = media != null && media.hasImage();
    boolean text = message != null && !message.trim().isEmpty();
    if (!photo && !text) return result(Kind.EMPTY, "내용 없음", "문구 또는 사진을 선택하세요.", false, false);
    if (photo
        && (!"content".equals(android.net.Uri.parse(media.uri).getScheme())
            || !media.mime.matches("image/[a-z0-9][a-z0-9.+_-]*")))
      return result(Kind.PHOTO_INVALID, "사진 확인 필요", "사진을 다시 선택하세요. 자동전송을 시작할 수 없습니다.", false, true);
    KakaoNotificationListener.ReplyTarget target = KakaoNotificationListener.findTarget(room);
    if (target == null || !target.verified || !RoomRouting.sameTitle(room, target.label))
      return result(
          Kind.CONNECTION,
          "연결 확인 필요",
          photo ? "사진 저장됨 · 이 방의 새 알림을 연결해야 자동전송 지원을 확인할 수 있습니다." : "이 방의 새 알림을 연결하세요.",
          false,
          false);
    java.util.ArrayList<String> aliases = Prefs.aliasesForIdentity(c, target.stableIdentityKeys);
    if (aliases.size() > 1 || (aliases.size() == 1 && !RoomRouting.sameTitle(aliases.get(0), room)))
      return result(Kind.CONNECTION, "방 연결 확인 필요", "방 연결 정보가 겹칩니다. 대상 방을 다시 연결하세요.", false, false);
    if (!photo)
      return KakaoMessageSender.freeFormInputs(target.remoteInputs).length > 0
          ? result(Kind.TEXT_READY, "문자 자동전송 준비", "문자 입력 연결이 확인됐습니다.", true, false)
          : result(Kind.CONNECTION, "문자 입력 없음", "현재 알림은 문자 답장을 지원하지 않습니다.", false, false);
    if (PhotoReplyAction.select(target.photoActions, media.mime, false) == null)
      return result(
          Kind.PHOTO_BLOCKED,
          "사진 자동전송 미지원",
          "현재 알림 연결은 사진을 받지 않습니다. 메인 화면의 ‘사진 여러 방 전송’에서 방 최초 준비와 순회 전송을 사용할 수 있습니다. 화면 OFF 전송을 뜻하지 않습니다.",
          false,
          true);
    if (PhotoReplyAction.select(target.photoActions, media.mime, text) == null)
      return result(
          Kind.PHOTO_BLOCKED,
          "사진+문구 자동전송 미지원",
          "이 연결은 사진과 문구를 함께 받지 않습니다. 문구를 지우거나 직접 보내기를 이용하세요.",
          false,
          true);
    return result(
        Kind.PHOTO_READY, "사진 요청 가능 · 수신 미확인", "사진 입력이 확인됐습니다. 테스트 후 실제 도착을 확인하세요.", true, false);
  }

  private static RoomSendReadiness result(Kind k, String l, String d, boolean r, boolean b) {
    return new RoomSendReadiness(k, l, d, r, b);
  }
}
