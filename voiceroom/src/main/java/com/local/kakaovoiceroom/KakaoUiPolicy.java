package com.local.kakaovoiceroom;

import java.util.Locale;

final class KakaoUiPolicy {
    private KakaoUiPolicy() {}

    static String normalize(String value) {
        if (value == null) return "";
        String s = value.replace('\u00A0', ' ').trim();
        return s.replaceAll("\\s+", " ");
    }

    /**
     * Kakao can expose the room title and the participant count as one accessibility label
     * (for example "게임방 386" or, for a room literally named "1", "1 1").
     * Accept only an exact title or an exact title followed by a numeric count. This keeps
     * matching strict enough to avoid treating a similarly-prefixed room as the target.
     */
    static boolean roomTitleMatches(String expected, String visible) {
        String want = normalize(expected);
        String got = normalize(visible);
        if (want.isEmpty() || got.isEmpty()) return false;
        if (want.equals(got)) return true;
        if (!got.startsWith(want + " ")) return false;
        String suffix = got.substring(want.length()).trim();
        if (suffix.isEmpty()) return false;
        suffix = suffix.replace(",", "");
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) return false;
        }
        return true;
    }

    static boolean isOpenChatUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("https://open.kakao.com/") || lower.startsWith("http://open.kakao.com/");
    }

    static long retryDelayMs(int failures) {
        int n = Math.max(1, failures);
        if (n == 1) return 60_000L;
        if (n == 2) return 3L * 60_000L;
        if (n == 3) return 10L * 60_000L;
        if (n == 4) return 30L * 60_000L;
        return 60L * 60_000L;
    }
}
