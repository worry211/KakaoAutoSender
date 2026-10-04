package com.local.kakaoautosender;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class KakaoMessageSender {
    private static volatile String lastError = "";

    private KakaoMessageSender() {}

    static boolean send(Context context, String room, String message, RoomMediaStore.Media media) {
        if (!LicenseManager.isUsable(context)) {
            lastError = LicenseManager.verifyStored(context).message;
            return false;
        }
        if (media == null || !media.hasImage()) {
            return sendTextOnly(context, room, message);
        }

        if (!media.mime.toLowerCase(Locale.ROOT).startsWith("image/") || media.mime.contains("*")) {
            lastError = "사진 형식을 확인할 수 없습니다. 사진을 다시 선택하세요.";
            return false;
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
        if (!validTarget(context, requested, target)) return false;

        Uri uri;
        try {
            uri = Uri.parse(media.uri);
            if (!"content".equals(uri.getScheme())) throw new IllegalArgumentException("SAF content URI required");
        } catch (Throwable t) {
            lastError = "선택한 사진 주소가 유효하지 않음";
            return false;
        }

        // Fail closed before invoking Kakao. A persisted URI may point to a deleted/moved file or
        // may have lost its SAF grant after provider changes. Never report success or fall back to
        // text-only when the configured image cannot actually be opened now.
        try (android.os.ParcelFileDescriptor descriptor =
                     context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (descriptor == null) throw new FileNotFoundException("photo unavailable");
        } catch (SecurityException e) {
            lastError = "사진 읽기 권한이 만료되었습니다. 사진을 다시 선택해 주세요.";
            return false;
        } catch (FileNotFoundException e) {
            lastError = "선택한 사진을 찾을 수 없습니다. 삭제·이동 여부를 확인하고 다시 선택해 주세요.";
            return false;
        } catch (Throwable t) {
            lastError = "선택한 사진을 읽을 수 없습니다. 사진을 다시 선택해 주세요.";
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
            lastError = "현재 카카오톡 알림 답장 방식에서는 이 방에 사진 자동전송을 지원하지 않습니다.";
            return false;
        }

        RemoteInput[] textInputs = freeFormInputs(target.remoteInputs);
        if (!message.trim().isEmpty() && textInputs.length == 0) {
            lastError = "현재 카카오톡 알림 답장 방식에서는 이 방에 텍스트 자동전송을 지원하지 않습니다.";
            return false;
        }

        try {
            Intent fillIn = new Intent();
            if (!message.trim().isEmpty()) {
                Bundle textResults = new Bundle();
                for (RemoteInput input : textInputs) {
                    textResults.putCharSequence(input.getResultKey(), message);
                }
                RemoteInput.addResultsToIntent(textInputs, fillIn, textResults);
            }

            Map<String, Uri> data = new HashMap<>();
            data.put(acceptedMime, uri);
            RemoteInput.addDataResultToIntent(dataInput, fillIn, data);
            fillIn.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.grantUriPermission(KakaoNotificationListener.KAKAO_PACKAGE, uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            synchronized (DeliveryGate.LOCK) {
                if (!DeliveryGate.allowed(context)) {
                    lastError = "전송 중단 · 라이선스 또는 자동전송 상태를 확인하세요.";
                    return false;
                }
                target.pendingIntent.send(context, 0, fillIn);
            }
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

    private static boolean sendTextOnly(Context context, String room, String message) {
        lastError = "";
        String requested = room == null ? "" : room.trim();
        if (requested.isEmpty()) {
            lastError = "대상 방이 비어 있음";
            return false;
        }
        if (message == null || message.trim().isEmpty()) {
            lastError = "보낼 메시지가 비어 있음";
            return false;
        }

        KakaoNotificationListener.requestRefresh();
        KakaoNotificationListener.ReplyTarget target = findTarget(requested);
        if (!validTarget(context, requested, target)) return false;

        RemoteInput[] textInputs = freeFormInputs(target.remoteInputs);
        if (textInputs.length == 0) {
            lastError = "현재 카카오톡 알림 답장 방식에서는 이 방에 텍스트 자동전송을 지원하지 않습니다.";
            return false;
        }

        try {
            Bundle results = new Bundle();
            for (RemoteInput input : textInputs) {
                results.putCharSequence(input.getResultKey(), message);
            }
            Intent fillIn = new Intent();
            RemoteInput.addResultsToIntent(textInputs, fillIn, results);
            synchronized (DeliveryGate.LOCK) {
                if (!DeliveryGate.allowed(context)) {
                    lastError = "전송 중단 · 라이선스 또는 자동전송 상태를 확인하세요.";
                    return false;
                }
                target.pendingIntent.send(context, 0, fillIn);
            }
            lastError = "";
            return true;
        } catch (PendingIntent.CanceledException e) {
            lastError = "카카오 답장 세션 만료됨 · 새 알림에서 다시 연결 필요";
            return false;
        } catch (Throwable t) {
            lastError = "전송 오류: " + t.getClass().getSimpleName();
            return false;
        }
    }

    private static boolean validTarget(
            Context context, String requested, KakaoNotificationListener.ReplyTarget target) {
        if (target == null || !target.verified) {
            lastError = "확인된 실시간 답장 세션 없음 · 대상 방 알림을 다시 연결해줘";
            return false;
        }
        if (!same(requested, target.label)) {
            lastError = "안전 차단: 선택 방과 검증 세션 이름이 다름";
            return false;
        }
        ArrayList<String> aliases = Prefs.aliasesForIdentity(context, target.stableIdentityKeys);
        if (aliases.size() > 1 || (aliases.size() == 1 && !same(aliases.get(0), requested))) {
            lastError = "안전 차단: 저장된 방 식별자 충돌 감지";
            return false;
        }
        if (target.remoteInputs.length == 0 || target.pendingIntent == null) {
            lastError = "카카오 답장 입력 정보가 없음";
            return false;
        }
        return true;
    }

    static RemoteInput[] freeFormInputs(RemoteInput[] inputs) {
        if (inputs == null || inputs.length == 0) return new RemoteInput[0];
        ArrayList<RemoteInput> result = new ArrayList<>();
        for (RemoteInput input : inputs) {
            if (input != null && input.getAllowFreeFormInput()) result.add(input);
        }
        return result.toArray(new RemoteInput[0]);
    }

    static String lastError() {
        return lastError == null ? "" : lastError;
    }

    private static KakaoNotificationListener.ReplyTarget findTarget(String room) {
        return KakaoNotificationListener.findTarget(room);
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
        return RoomRouting.sameTitle(a, b);
    }
}
