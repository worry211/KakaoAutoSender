package com.local.kakaoautosender;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class KakaoMessageSender {
    private static volatile String lastError = "";

    private KakaoMessageSender() {}

    static boolean send(Context context, String room, String message, RoomMediaStore.Media media) {
        if (media == null || !media.hasImage()) {
            boolean ok = KakaoNotificationListener.sendToRoom(context, room, message);
            lastError = ok ? "" : KakaoNotificationListener.lastSendError();
            return ok;
        }

        lastError = "";
        String requested = room == null ? "" : room.trim();
        if (requested.isEmpty()) {
            lastError = "대상 방이 비어 있음";
            return false;
        }
        if (message == null) message = "";

        KakaoNotificationListener.requestRefresh();
        KakaoNotificationListener.ReplyTarget target = findTarget(requested);
        if (target == null || !target.verified) {
            lastError = "확인된 실시간 답장 세션 없음 · 대상 방 알림을 다시 연결해줘";
            return false;
        }
        if (!same(requested, target.label)) {
            lastError = "안전 차단: 선택 방과 검증 세션 이름이 다름";
            return false;
        }

        String mapped = Prefs.aliasForIdentity(context, target.stableIdentityKeys);
        if (mapped != null && !same(mapped, requested)) {
            lastError = "안전 차단: 저장된 방 식별자 충돌 감지";
            return false;
        }
        if (target.remoteInputs.length == 0 || target.pendingIntent == null) {
            lastError = "카카오 답장 입력 정보가 없음";
            return false;
        }

        Uri uri;
        try {
            uri = Uri.parse(media.uri);
            if (uri.getScheme() == null) throw new IllegalArgumentException("missing scheme");
        } catch (Throwable t) {
            lastError = "선택한 사진 주소가 유효하지 않음";
            return false;
        }

        RemoteInput dataInput = null;
        String acceptedMime = null;
        for (RemoteInput input : target.remoteInputs) {
            if (input == null) continue;
            Set<String> types = input.getAllowedDataTypes();
            if (types == null || types.isEmpty()) continue;
            for (String allowed : types) {
                if (matchesMime(allowed, media.mime)) {
                    dataInput = input;
                    acceptedMime = media.mime;
                    break;
                }
            }
            if (dataInput != null) break;
        }

        if (dataInput == null) {
            lastError = "현재 카카오 답장 세션은 사진 첨부를 지원하지 않음 · 텍스트는 보내지 않았어";
            return false;
        }

        try {
            Intent fillIn = new Intent();
            Bundle textResults = new Bundle();
            if (!message.trim().isEmpty()) {
                for (RemoteInput input : target.remoteInputs) {
                    if (input != null && input.getAllowFreeFormInput()) {
                        textResults.putCharSequence(input.getResultKey(), message);
                    }
                }
                if (!textResults.isEmpty()) {
                    RemoteInput.addResultsToIntent(target.remoteInputs, fillIn, textResults);
                }
            }

            Map<String, Uri> data = new HashMap<>();
            data.put(acceptedMime, uri);
            RemoteInput.addDataResultToIntent(dataInput, fillIn, data);
            fillIn.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.grantUriPermission(KakaoNotificationListener.KAKAO_PACKAGE, uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            target.pendingIntent.send(context, 0, fillIn);
            lastError = "";
            return true;
        } catch (PendingIntent.CanceledException e) {
            lastError = "카카오 답장 세션 만료됨 · 새 알림에서 다시 연결 필요";
            return false;
        } catch (SecurityException e) {
            lastError = "사진 읽기 권한이 만료됨 · 사진을 다시 선택해줘";
            return false;
        } catch (Throwable t) {
            lastError = "사진 전송 오류: " + t.getClass().getSimpleName();
            return false;
        }
    }

    static String lastError() {
        return lastError == null ? "" : lastError;
    }

    @SuppressWarnings("unchecked")
    private static KakaoNotificationListener.ReplyTarget findTarget(String room) {
        try {
            Method m = KakaoNotificationListener.class.getDeclaredMethod("findTarget", String.class);
            m.setAccessible(true);
            Object value = m.invoke(null, room);
            return value instanceof KakaoNotificationListener.ReplyTarget
                    ? (KakaoNotificationListener.ReplyTarget) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean matchesMime(String allowed, String actual) {
        String a = allowed == null ? "" : allowed.trim().toLowerCase(Locale.ROOT);
        String b = actual == null ? "" : actual.trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b) || a.equals("*/*")) return true;
        int slash = a.indexOf('/');
        return slash > 0 && a.endsWith("/*") && b.startsWith(a.substring(0, slash + 1));
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }
}
