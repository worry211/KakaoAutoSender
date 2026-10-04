package com.local.kakaoautosender;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Shared fail-closed room identity helpers. No raw Kakao message content is persisted here. */
final class RoomRouting {
    private RoomRouting() {}

    static String normalizeTitle(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\u00a0', ' ')
                .replaceAll("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]", "")
                .trim()
                .replaceAll("\\s+", " ");
        return normalized.toLowerCase(Locale.ROOT);
    }

    static boolean sameTitle(String a, String b) {
        String left = normalizeTitle(a);
        return !left.isEmpty() && left.equals(normalizeTitle(b));
    }

    static String identityFingerprint(List<String> identityKeys) {
        if (identityKeys == null || identityKeys.isEmpty()) return "";
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String key : identityKeys) {
            if (key == null) continue;
            String clean = key.trim();
            if (!clean.isEmpty()) unique.add(clean);
        }
        if (unique.isEmpty()) return "";
        ArrayList<String> sorted = new ArrayList<>(unique);
        Collections.sort(sorted);
        return sha256(String.join("\n", sorted));
    }

    static String runtimeFingerprint(String token) {
        if (token == null || token.trim().isEmpty()) return "";
        return sha256("runtime\n" + token.trim());
    }

    static String shortCode(String fingerprint) {
        if (fingerprint == null) return "";
        String clean = fingerprint.replaceAll("[^0-9a-fA-F]", "").toUpperCase(Locale.ROOT);
        return clean.length() <= 6 ? clean : clean.substring(0, 6);
    }

    static boolean identitiesOverlap(List<String> a, List<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        LinkedHashSet<String> left = new LinkedHashSet<>();
        for (String key : a) if (key != null && !key.trim().isEmpty()) left.add(key.trim());
        for (String key : b) {
            if (key != null && left.contains(key.trim())) return true;
        }
        return false;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
