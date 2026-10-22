package com.local.kakaoautosender;

import android.content.Context;
import java.util.Collection;

/** The UI and actual start use the same checks, before acquiring physical panel control. */
final class PhotoTourReadiness {
  static String roomError(Context c, String room, boolean requirePrepared) {
    MultiRoomStore.Profile p = MultiRoomStore.get(c, room);
    if (p == null) return "방이 삭제되었습니다.";
    if (!RoomMediaStore.get(c, room).hasImage()) return "공통 사진·시간 설정에서 사진을 선택하세요.";
    KakaoNotificationListener.ReplyTarget target = PhotoRoomRoutes.get(c, room);
    String route = PhotoRoomRoutes.error(c, room, target);
    if (!route.isEmpty()) return route;
    PhotoTourBindings.Binding binding = PhotoTourBindings.get(c, room);
    if (requirePrepared
        && (binding == null || !PhotoTourBindings.identityMatches(c, binding, target)))
      return "방 최초 준비 또는 변경된 방 연결을 다시 확인하세요.";
    if (requirePrepared && p.enabled && Prefs.p(c).getBoolean(Prefs.KEY_ACTIVE, false))
      return "이 사진 방의 기존 자동전송 사용을 끄세요.";
    return "";
  }

  static String firstRoomError(Context c, Collection<String> rooms, boolean prepared) {
    if (rooms.isEmpty()) return "전송할 방을 선택하세요.";
    for (String room : rooms) {
      String error = roomError(c, room, prepared);
      MultiRoomStore.Profile p = MultiRoomStore.get(c, room);
      if (!error.isEmpty()) return (p == null ? room : p.title()) + ": " + error;
    }
    return "";
  }

  static String summary(Context c, Collection<String> rooms, boolean panelOff) {
    if (PhotoTourController.active()) return "진행 중 · 중지 후 설정을 변경할 수 있습니다.";
    if (PhotoKeyboardService.hasActiveLoop()) return "키보드의 단일 방 사진 예약을 먼저 중지하세요.";
    if (PanelOffManager.engaged()) return "실행 중인 화면 끄기 시험 또는 작업을 먼저 중지하세요.";
    if (!DeliveryGate.allowed(c)) return "앱에서 라이선스 연결 상태를 확인하세요.";
    if (!PhotoKeyboardSession.unlocked(c)) return "화면을 켜고 잠금을 해제한 뒤 준비하세요.";
    String error = firstRoomError(c, rooms, true);
    if (!error.isEmpty()) return error;
    if (!PhotoUiService.connected()) return "키보드·접근성 설정에서 사진 전송 보조를 켜세요.";
    if (!PhotoTourController.keyboardSelected(c)) return "사진 키보드를 현재 키보드로 선택하세요.";
    if (panelOff && !PanelOffManager.connected()) return "화면 끄기 최초 설정에서 휴대폰 자체 연결을 완료하세요.";
    return rooms.size()
        + "개 방 시작 준비됨 · 실행 때 다시 확인합니다."
        + (SendScheduler.canUseExact(c) ? "" : "\n정확한 알람 권한이 없어 요청이 늦어질 수 있습니다.");
  }
}
