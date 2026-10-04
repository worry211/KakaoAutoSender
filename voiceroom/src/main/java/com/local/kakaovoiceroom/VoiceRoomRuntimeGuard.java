package com.local.kakaovoiceroom;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Event-driven guard that remains active between the 48-hour health checks.
 *
 * It is deliberately conservative: it never accepts/promotes a speaker request, never uses
 * coordinate-only fallbacks for request decisions, and refuses to act when several managed rooms
 * make the request's room identity ambiguous. It may still keep explicit mic/speaker controls muted
 * when Kakao exposes an unambiguous action label.
 */
final class VoiceRoomRuntimeGuard {
    private static final String KAKAO_PACKAGE = "com.kakao.talk";
    private static final long SCAN_DEBOUNCE_MS = 300L;
    private static final long ACTION_COOLDOWN_MS = 900L;
    private static final String PREFS = "voiceroom_runtime_guard";
    private static final String KEY_REJECTED = "speaker_requests_rejected";
    private static final String KEY_REQUEST_TOGGLES = "speaker_request_toggles_disabled";
    private static final String KEY_AUDIO_FIXES = "passive_audio_fixes";
    private static final String KEY_LAST_ACTION_AT = "last_action_at";

    private static long lastScanAt;
    private static long lastActionAt;

    private VoiceRoomRuntimeGuard() {}

    static void onKakaoEvent(AccessibilityService service, AccessibilityEvent event) {
        if (service == null || event == null || !VoiceRoomStore.managerActive(service)) return;
        if (!VoiceRoomStore.pendingRoomId(service).isEmpty()) return;

        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_DEBOUNCE_MS) return;
        lastScanAt = now;

        List<VoiceRoomStore.Room> eligible = eligibleRooms(service);
        if (eligible.isEmpty()) return;

        List<AccessibilityNodeInfo> roots = collectKakaoRoots(service, event);
        try {
            for (AccessibilityNodeInfo root : roots) {
                if (root == null) continue;
                VoiceRoomStore.Room room = scopeRoom(root, eligible);

                // A proven global request toggle is the cleanest protection when Kakao exposes one.
                if (room != null && tryDisableSpeakerRequestToggle(service, root, room, now)) return;

                // Incoming request handling requires both explicit request context and an explicit
                // reject/deny action. There is intentionally no generic "취소" or geometry click.
                boolean requestContext = containsRequestContext(root);
                if (requestContext) {
                    if (room == null) {
                        VoiceRoomStore.setLastStatus(service,
                                "스피커 요청 감지 · 여러 관리 보룸 중 대상 방을 확정하지 못해 자동 거절 보류");
                        return;
                    }
                    if (tryRejectSpeakerRequest(service, root, room, now)) return;
                }

                // Between scheduled checks, re-apply only semantically explicit audio protections.
                // Structural/icon-only fallback is reserved for the foreground verification state
                // machine where a VoiceRoom exit control already proves the surface identity.
                if (room != null && tryProtectExplicitAudio(service, root, room, now)) return;
            }
        } finally {
            for (AccessibilityNodeInfo root : roots) {
                try { if (root != null) root.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    static int rejectedCount(Context context) {
        return prefs(context).getInt(KEY_REJECTED, 0);
    }

    static int requestToggleDisableCount(Context context) {
        return prefs(context).getInt(KEY_REQUEST_TOGGLES, 0);
    }

    static int passiveAudioFixCount(Context context) {
        return prefs(context).getInt(KEY_AUDIO_FIXES, 0);
    }

    static long lastActionAt(Context context) {
        return prefs(context).getLong(KEY_LAST_ACTION_AT, 0L);
    }

    private static List<VoiceRoomStore.Room> eligibleRooms(Context context) {
        ArrayList<VoiceRoomStore.Room> out = new ArrayList<>();
        for (VoiceRoomStore.Room room : VoiceRoomStore.list(context)) {
            if (room.enabled && room.liveCheckPassed) out.add(room);
        }
        return out;
    }

    private static List<AccessibilityNodeInfo> collectKakaoRoots(
            AccessibilityService service, AccessibilityEvent event) {
        ArrayList<AccessibilityNodeInfo> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();

        try {
            for (AccessibilityWindowInfo window : service.getWindows()) {
                if (window == null) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (!isKakaoRoot(root)) {
                    try { if (root != null) root.recycle(); } catch (Exception ignored) {}
                    continue;
                }
                int id = window.getId();
                if (seen.add(id)) out.add(root);
                else try { root.recycle(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        if (out.isEmpty()) {
            try {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (isKakaoRoot(root)) out.add(root);
                else if (root != null) root.recycle();
            } catch (Exception ignored) {}
        }

        if (out.isEmpty()) {
            try {
                AccessibilityNodeInfo source = event.getSource();
                if (isKakaoRoot(source)) out.add(source);
                else if (source != null) source.recycle();
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static boolean isKakaoRoot(AccessibilityNodeInfo root) {
        return root != null && root.getPackageName() != null
                && KAKAO_PACKAGE.contentEquals(root.getPackageName());
    }

    private static VoiceRoomStore.Room scopeRoom(
            AccessibilityNodeInfo root, List<VoiceRoomStore.Room> eligible) {
        if (eligible.size() == 1) return eligible.get(0);
        VoiceRoomStore.Room match = null;
        for (VoiceRoomStore.Room room : eligible) {
            if (!containsRoomTitle(root, room.title)) continue;
            if (match != null && !match.id.equals(room.id)) return null;
            match = room;
        }
        return match;
    }

    private static boolean tryDisableSpeakerRequestToggle(
            Context context, AccessibilityNodeInfo root, VoiceRoomStore.Room room, long now) {
        for (AccessibilityNodeInfo node : allNodes(root)) {
            AccessibilityNodeInfo clickable = clickableAncestor(node);
            if (clickable == null || !clickable.isVisibleToUser() || !clickable.isEnabled()) continue;
            String label = label(clickable);
            VoiceRoomSpeakerRequestPolicy.ToggleState state =
                    VoiceRoomSpeakerRequestPolicy.requestToggleState(label);
            if (!VoiceRoomSpeakerRequestPolicy.shouldDisableRequests(state)) continue;
            if (!canAct(now)) return false;
            if (click(clickable)) {
                markAction(now);
                increment(context, KEY_REQUEST_TOGGLES);
                VoiceRoomStore.setLastStatus(context,
                        room.title + " · 스피커 요청 받기 자동 차단 적용");
                return true;
            }
        }
        return false;
    }

    private static boolean tryRejectSpeakerRequest(
            Context context, AccessibilityNodeInfo root, VoiceRoomStore.Room room, long now) {
        AccessibilityNodeInfo reject = null;
        long bestArea = Long.MAX_VALUE;
        for (AccessibilityNodeInfo node : allNodes(root)) {
            String nodeLabel = label(node);
            if (!VoiceRoomSpeakerRequestPolicy.isRejectAction(nodeLabel)
                    || VoiceRoomSpeakerRequestPolicy.isAcceptAction(nodeLabel)) continue;
            AccessibilityNodeInfo clickable = clickableAncestor(node);
            if (clickable == null || !clickable.isVisibleToUser() || !clickable.isEnabled()) continue;
            android.graphics.Rect b = new android.graphics.Rect();
            clickable.getBoundsInScreen(b);
            if (b.isEmpty()) continue;
            long area = (long) b.width() * b.height();
            if (area < bestArea) {
                bestArea = area;
                reject = clickable;
            }
        }
        if (reject == null || !canAct(now)) return false;
        if (!click(reject)) return false;

        markAction(now);
        increment(context, KEY_REJECTED);
        VoiceRoomStore.setLastStatus(context,
                room.title + " · 스피커 요청 자동 거절 · 누적 " + rejectedCount(context) + "회");
        return true;
    }

    private static boolean tryProtectExplicitAudio(
            Context context, AccessibilityNodeInfo root, VoiceRoomStore.Room room, long now) {
        for (AccessibilityNodeInfo node : allNodes(root)) {
            AccessibilityNodeInfo clickable = clickableAncestor(node);
            if (clickable == null || !clickable.isVisibleToUser() || !clickable.isEnabled()) continue;
            String control = label(clickable);

            VoiceRoomAudioPolicy.State mic = VoiceRoomAudioPolicy.micState(control);
            if (mic == VoiceRoomAudioPolicy.State.ON) {
                if (!canAct(now)) return false;
                if (click(clickable)) {
                    markAction(now);
                    increment(context, KEY_AUDIO_FIXES);
                    room.micMuted = true;
                    room.audioCheckedAt = now;
                    VoiceRoomStore.update(context, room);
                    VoiceRoomStore.setLastStatus(context,
                            room.title + " · 런타임 가드가 마이크를 다시 음소거함");
                    return true;
                }
            }

            VoiceRoomAudioPolicy.State speaker = VoiceRoomAudioPolicy.speakerState(control);
            if (speaker == VoiceRoomAudioPolicy.State.ON) {
                if (!canAct(now)) return false;
                if (click(clickable)) {
                    markAction(now);
                    increment(context, KEY_AUDIO_FIXES);
                    room.speakerMuted = true;
                    room.audioCheckedAt = now;
                    VoiceRoomStore.update(context, room);
                    VoiceRoomStore.setLastStatus(context,
                            room.title + " · 런타임 가드가 스피커를 다시 음소거함");
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsRequestContext(AccessibilityNodeInfo root) {
        for (AccessibilityNodeInfo node : allNodes(root)) {
            if (VoiceRoomSpeakerRequestPolicy.isRequestContext(label(node))) return true;
        }
        return false;
    }

    private static boolean containsRoomTitle(AccessibilityNodeInfo root, String expected) {
        if (expected == null || expected.trim().isEmpty()) return false;
        for (AccessibilityNodeInfo node : allNodes(root)) {
            if (KakaoUiPolicy.roomTitleMatches(expected, text(node.getText()))
                    || KakaoUiPolicy.roomTitleMatches(expected, text(node.getContentDescription()))) {
                return true;
            }
        }
        return false;
    }

    private static List<AccessibilityNodeInfo> allNodes(AccessibilityNodeInfo root) {
        ArrayList<AccessibilityNodeInfo> out = new ArrayList<>();
        if (root == null) return out;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            out.add(node);
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return out;
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 6 && current != null; i++) {
            if (current.isClickable()) return current;
            current = current.getParent();
        }
        return null;
    }

    private static String label(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder out = new StringBuilder();
        append(out, node.getText());
        append(out, node.getContentDescription());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            append(out, child.getText());
            append(out, child.getContentDescription());
        }
        return out.toString().trim();
    }

    private static void append(StringBuilder out, CharSequence value) {
        String s = text(value);
        if (!s.isEmpty()) out.append(' ').append(s);
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static boolean click(AccessibilityNodeInfo node) {
        try {
            return node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean canAct(long now) {
        return now - lastActionAt >= ACTION_COOLDOWN_MS;
    }

    private static void markAction(long now) {
        lastActionAt = now;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void increment(Context context, String key) {
        SharedPreferences p = prefs(context);
        p.edit()
                .putInt(key, p.getInt(key, 0) + 1)
                .putLong(KEY_LAST_ACTION_AT, System.currentTimeMillis())
                .apply();
    }
}
