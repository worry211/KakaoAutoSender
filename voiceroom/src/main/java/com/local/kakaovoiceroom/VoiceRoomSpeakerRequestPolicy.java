package com.local.kakaovoiceroom;

import java.util.Locale;

/**
 * Pure text policy for host-side speaker-request protection.
 *
 * The policy intentionally fails closed: generic words such as "요청", "취소", or a person's
 * name never authorize a click. Only explicit speaker-request context plus an explicit reject
 * action is actionable. Likewise, a global request toggle is changed only when its label clearly
 * describes the action that disables incoming requests.
 */
final class VoiceRoomSpeakerRequestPolicy {
    enum ToggleState { ACCEPTING, BLOCKED, UNKNOWN }

    private VoiceRoomSpeakerRequestPolicy() {}

    static boolean isRequestContext(String raw) {
        String s = normalize(raw);
        return containsAny(s,
                "스피커 요청", "스피커 신청", "스피커로 참여 요청", "스피커 참여 요청",
                "스피커로 참여 신청", "스피커 참여 신청", "스피치 요청", "발언 요청");
    }

    static boolean isRejectAction(String raw) {
        String s = normalize(raw);
        if (s.isEmpty()) return false;
        return containsAny(s,
                "스피커 요청 거절", "스피커 요청 거부", "스피커 신청 거절", "스피커 신청 거부",
                "스피커로 참여 요청 거절", "스피커로 참여 요청 거부",
                "스피치 요청 거절", "스피치 요청 거부", "발언 요청 거절", "발언 요청 거부")
                || "거절".equals(s) || "거부".equals(s);
    }

    static boolean isAcceptAction(String raw) {
        String s = normalize(raw);
        if (s.isEmpty()) return false;
        return containsAny(s,
                "스피커 요청 수락", "스피커 요청 승인", "스피커 신청 수락", "스피커 신청 승인",
                "스피커로 참여", "스피커로 전환", "스피커 승격", "요청 수락", "요청 승인")
                || "수락".equals(s) || "승인".equals(s);
    }

    /**
     * Interprets action-oriented accessibility labels.
     * ACCEPTING means incoming requests are currently allowed and the control explicitly offers
     * an action to disable/block them. BLOCKED means the control explicitly offers an action to
     * re-enable/receive requests, which proves requests are currently blocked.
     */
    static ToggleState requestToggleState(String raw) {
        String s = normalize(raw);
        if (!containsAny(s, "스피커 요청", "스피커 신청", "스피치 요청", "발언 요청")) {
            return ToggleState.UNKNOWN;
        }

        if (containsAny(s,
                "요청 끄기", "요청 받지 않기", "요청 차단", "요청 비활성화",
                "신청 끄기", "신청 받지 않기", "신청 차단", "신청 비활성화")) {
            return ToggleState.ACCEPTING;
        }
        if (containsAny(s,
                "요청 켜기", "요청 받기", "요청 허용", "요청 활성화",
                "신청 켜기", "신청 받기", "신청 허용", "신청 활성화")) {
            return ToggleState.BLOCKED;
        }
        return ToggleState.UNKNOWN;
    }

    static boolean shouldDisableRequests(ToggleState state) {
        return state == ToggleState.ACCEPTING;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.KOREA).replaceAll("\\s+", " ");
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }
}
