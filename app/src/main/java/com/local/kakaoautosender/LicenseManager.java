package com.local.kakaoautosender;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

final class LicenseManager {
    private static final String PREF_LICENSE = "license_text_v1";
    private static final String PREF_INSTALL_ID = "license_install_id_v1";
    private static final String PREF_MAX_WALL_TIME = "license_max_wall_time_v1";
    private static final long CLOCK_ROLLBACK_TOLERANCE_MS = 5 * 60_000L;
    private static final String PUBLIC_KEY_B64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZJeBNrx2xGcHMKrJqOtsOWivO4A0g9Ln6qfrUECWCG9tSfSlCbHFK5k6D1y7eZVDvA0RxOnpC0B2IOuKSM+f5g==";

    static final class Verification {
        final boolean valid;
        final String message;
        final String licenseId;
        final String customer;
        final long expiresAtSeconds;

        Verification(boolean valid, String message, String licenseId, String customer, long expiresAtSeconds) {
            this.valid = valid;
            this.message = message == null ? "" : message;
            this.licenseId = licenseId == null ? "" : licenseId;
            this.customer = customer == null ? "" : customer;
            this.expiresAtSeconds = expiresAtSeconds;
        }

        String expiryLabel() {
            if (!valid) return "인증 안 됨";
            if (expiresAtSeconds <= 0L) return "영구 라이선스";
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
                    .format(new Date(expiresAtSeconds * 1000L)) + " 만료";
        }
    }

    private LicenseManager() {}

    static String deviceCode(Context context) {
        try {
            SharedPreferences prefs = Prefs.p(context);
            String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null || androidId.trim().isEmpty()) {
                androidId = prefs.getString(PREF_INSTALL_ID, "");
                if (androidId == null || androidId.trim().isEmpty()) {
                    androidId = UUID.randomUUID().toString();
                    prefs.edit().putString(PREF_INSTALL_ID, androidId).apply();
                }
            }
            String raw = "KAS-DEVICE-v1|" + context.getPackageName() + "|" + androidId.trim();
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder compact = new StringBuilder();
            for (int i = 0; i < 10; i++) compact.append(String.format(Locale.ROOT, "%02X", digest[i]));
            String s = compact.toString();
            return s.substring(0, 5) + "-" + s.substring(5, 10) + "-" + s.substring(10, 15) + "-" + s.substring(15, 20);
        } catch (Throwable t) {
            return "DEVICE-CODE-ERROR";
        }
    }

    static Verification activate(Context context, String licenseText) {
        Verification result = verifyText(context, licenseText, true);
        if (result.valid) {
            Prefs.p(context).edit().putString(PREF_LICENSE, licenseText.trim()).apply();
            Prefs.setStatus(context, "라이선스 인증 완료 · " + result.expiryLabel());
        }
        return result;
    }

    static Verification verifyStored(Context context) {
        String license = Prefs.p(context).getString(PREF_LICENSE, "");
        return verifyText(context, license, true);
    }

    static boolean isUsable(Context context) {
        return verifyStored(context).valid;
    }

    static void clear(Context context) {
        Prefs.p(context).edit().remove(PREF_LICENSE).apply();
    }

    static String shortStatus(Context context) {
        Verification v = verifyStored(context);
        return v.valid ? v.expiryLabel() : v.message;
    }

    private static Verification verifyText(Context context, String text, boolean updateClock) {
        if (text == null || text.trim().isEmpty()) return invalid("라이선스 키를 입력해줘.");
        try {
            String[] parts = text.trim().split("\\.");
            if (parts.length != 3 || !"KAS1".equals(parts[0])) return invalid("라이선스 형식이 올바르지 않아.");

            byte[] payload = decodeUrl(parts[1]);
            byte[] signatureBytes = decodeUrl(parts[2]);
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey());
            verifier.update(payload);
            if (!verifier.verify(signatureBytes)) return invalid("서명이 올바르지 않은 라이선스야.");

            JSONObject o = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            if (o.optInt("v", 0) != 1) return invalid("지원하지 않는 라이선스 버전이야.");

            String licenseId = o.optString("licenseId", "");
            String customer = o.optString("customer", "");
            String expectedDevice = normalizeDevice(deviceCode(context));
            String licensedDevice = normalizeDevice(o.optString("device", ""));
            if (!expectedDevice.equals(licensedDevice)) {
                return invalid("이 라이선스는 이 휴대폰용이 아니야.");
            }

            long nowMs = System.currentTimeMillis();
            long nowSec = nowMs / 1000L;
            long issuedAt = o.optLong("issuedAt", 0L);
            long expiresAt = o.optLong("expiresAt", 0L);
            if (issuedAt <= 0L || issuedAt > nowSec + 24 * 60 * 60L) return invalid("발급 시간이 올바르지 않아.");
            if (expiresAt > 0L && nowSec > expiresAt) return invalid("라이선스가 만료됐어.");

            SharedPreferences prefs = Prefs.p(context);
            long maxSeen = prefs.getLong(PREF_MAX_WALL_TIME, 0L);
            if (maxSeen > 0L && nowMs + CLOCK_ROLLBACK_TOLERANCE_MS < maxSeen) {
                return invalid("휴대폰 시간이 이전으로 크게 변경돼 인증을 확인할 수 없어.");
            }
            if (updateClock && nowMs > maxSeen) prefs.edit().putLong(PREF_MAX_WALL_TIME, nowMs).apply();

            return new Verification(true, "인증됨", licenseId, customer, expiresAt);
        } catch (Throwable t) {
            return invalid("라이선스를 읽을 수 없어.");
        }
    }

    private static Verification invalid(String message) {
        return new Verification(false, message, "", "", 0L);
    }

    private static String normalizeDevice(String value) {
        if (value == null) return "";
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static byte[] decodeUrl(String s) {
        return Base64.decode(s, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static PublicKey publicKey() throws Exception {
        byte[] der = Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT);
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
    }
}
