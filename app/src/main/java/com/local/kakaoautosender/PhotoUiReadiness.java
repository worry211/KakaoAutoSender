package com.local.kakaoautosender;

/** Current availability, deliberately separate from historical send/learning outcomes. */
final class PhotoUiReadiness {
  final boolean canLearn, canSchedule;
  final String message;

  private PhotoUiReadiness(boolean learn, boolean schedule, String message) {
    canLearn = learn;
    canSchedule = schedule;
    this.message = message;
  }

  static PhotoUiReadiness evaluate(
      boolean connected,
      PhotoUiWorkflow.Phase phase,
      PhotoUiWorkflow.Anchor learned,
      PhotoUiWorkflow.Anchor current) {
    if (!connected) return new PhotoUiReadiness(false, false, "사진 전송 보조 접근성이 꺼져 있습니다.");
    if (phase == PhotoUiWorkflow.Phase.LEARNING
        || phase == PhotoUiWorkflow.Phase.LEARN_RETURN
        || phase == PhotoUiWorkflow.Phase.AUTO_PREVIEW
        || phase == PhotoUiWorkflow.Phase.AUTO_RETURN)
      return new PhotoUiReadiness(
          false,
          false,
          phase == PhotoUiWorkflow.Phase.LEARNING
              ? "학습 중 · 카카오톡 사진 화면에서 전송하거나 취소하세요."
              : "전송 동작과 원래 채팅방 복귀를 확인하고 있습니다.");
    if (current == null)
      return new PhotoUiReadiness(
          false, false, "현재 채팅방의 제목·입력창을 확인할 수 없습니다. 채팅방과 메시지 입력창이 보이도록 확인하세요.");
    if (phase == PhotoUiWorkflow.Phase.READY && learned != null && learned.same(current))
      return new PhotoUiReadiness(true, true, "현재 방의 사진 전송 준비 완료 · 시험 또는 예약을 시작할 수 있습니다.");
    return new PhotoUiReadiness(
        true, false, "현재 방 확인됨 · 사진 전송 준비를 한 번 진행하세요. 준비 과정도 사진 한 장을 전송합니다.");
  }
}
