package com.local.kakaovoiceroom;

import java.util.Locale;

final class VoiceRoomAudioPolicy {
    enum State { ON, OFF, UNKNOWN }

    private VoiceRoomAudioPolicy() {}

    static State speakerState(String rawLabel) {
        String s = normalize(rawLabel);
        if (containsAny(s,
                "스피커 켜기", "소리 켜기", "오디오 켜기", "스피커 음소거 해제", "소리 음소거 해제")) {
            return State.OFF;
        }
        if (containsAny(s,
                "스피커 끄기", "소리 끄기", "오디오 끄기", "스피커 음소거", "소리 음소거")) {
            return State.ON;
        }
        return State.UNKNOWN;
    }

    static State micState(String rawLabel) {
        String s = normalize(rawLabel);
        if (containsAny(s,
                "마이크 켜기", "마이크 음소거 해제", "마이크 음소거 풀기")) {
            return State.OFF;
        }
        if (containsAny(s,
                "마이크 끄기", "마이크 음소거", "마이크 음소거하기")) {
            return State.ON;
        }
        return State.UNKNOWN;
    }

    static boolean shouldToggleToProtect(State state) {
        return state == State.ON;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.KOREA).replaceAll("\\s+", " ");
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }
}