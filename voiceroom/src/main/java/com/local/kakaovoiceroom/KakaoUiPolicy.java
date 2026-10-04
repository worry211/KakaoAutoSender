package com.local.kakaovoiceroom;

import java.net.URI;
import java.util.Locale;

final class KakaoUiPolicy {
    private static final int VOICE_ROOM_NAME_MAX_CODEPOINTS = 30;

    private KakaoUiPolicy() {}

    static String normalize(String value) {
        if (value == null) return "";
        String s = value.replace('\u00A0', ' ').trim();
        return s.replaceAll("\\s+", " ");
    }

    /** Kakao may expose title + participant count as one accessibility label (e.g. "1 1"). */
    static boolean roomTitleMatches(String expected, String visible) {
        String want = normalize(expected);
        String got = normalize(visible);
        if (want.isEmpty() || got.isEmpty()) return false;
        if (want.equals(got)) return true;
        if (!got.startsWith(want + " ")) return false;
        String suffix = got.substring(want.length()).trim().replace(",", "");
        if (suffix.isEmpty()) return false;
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) return false;
        }
        return true;
    }

    /** Create-sheet names are 1..30 chars; truncate by Unicode code point, never surrogate unit. */
    static String voiceRoomName(String roomTitle) {
        String value = normalize(roomTitle);
        if (value.isEmpty()) value = "보이스룸";
        int count = value.codePointCount(0, value.length());
        if (count <= VOICE_ROOM_NAME_MAX_CODEPOINTS) return value;
        int end = value.offsetByCodePoints(0, VOICE_ROOM_NAME_MAX_CODEPOINTS);
        return value.substring(0, end).trim();
    }

    /** Accept only official HTTPS Open Chat links; reject look-alike hosts and unsafe schemes. */
    static boolean isOpenChatUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();
            return scheme != null
                    && "https".equals(scheme.toLowerCase(Locale.ROOT))
                    && host != null
                    && "open.kakao.com".equals(host.toLowerCase(Locale.ROOT))
                    && path != null
                    && path.length() > 1;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
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
