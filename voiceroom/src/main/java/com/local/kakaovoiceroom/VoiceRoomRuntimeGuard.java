package com.local.kakaovoiceroom;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
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
 * Fail-closed rules:
 * - never accepts/promotes a speaker request;
 * - never uses coordinates, profile names or generic "취소" for request decisions;
 * - requires a scoped managed VoiceRoom plus explicit request context and reject action;
 * - does not blindly toggle icon-only audio/request controls;
 * - when room identity or control semantics are ambiguous, records status and does nothing.
 */
final class VoiceRoomRuntimeGuard {
    private static final String KAKAO_PACKAGE = "com.kakao.talk";
    private static final long SCAN_DEBOUNCE_MS = 300L;
    private static final long ACTION_COOLDOWN_MS = 900L;
    private static final String PREFS = "voiceroom_runtime_guard";
    private static final String KEY_REJECTED = "speaker_requests_rejected";
    private static final String KEY_REQUEST_TOGGLES = "speaker_request_toggles_disabled";
    private static final String KEY_AUDIO_FIXES = "passive_audio_fixes";
    private static final String KEY_AMBIGUOUS = "ambiguous_requests_seen";
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
            VoiceRoomStore.Room globalScope = uniqueRoomAcrossWindows(roots, eligible);
            boolean eventMentionsRequest = eventTextMentionsRequest(event);

            for (AccessibilityNodeInfo root : roots) {
                if (root == null) continue;
                VoiceRoomStore.Room room = scopeRoom(root, eligible);
                if (room == null) room = globalScope;

                boolean requestContext = containsRequestContext(root) || eventMentionsRequest;
                if (requestContext) {
                    if (room == null) {
                        increment(service, KEY_AMBIGUOUS);
                        VoiceRoomStore.setLastStatus(service,
                                "스피커 요청 감지 · 관리 보룸 대상을 확정하지 못해 안전하게 자동 거절 보류");
                        return;
                    }
                    // Current requests are rejected before touching any global preference toggle.
                    if (tryRejectSpeakerRequest(service, root, room, now)) return;
                }

                // A proven action-labelled global request toggle is the cleanest prevention.
                if (room != null && tryDisableSpeakerRequestToggle(service, root, room, now)) return;

                // Re-apply only semantically explicit audio protection between scheduled checks.
                if (room != null && tryProtectExplicitAudio(service, root, room, now)) return;
            }

            if (eventMentionsRequest && globalScope == null) {
                increment(service, KEY_AMBIGUOUS);
                VoiceRoomStore.setLastStatus(service,
                        "스피커 요청 알림 감지 · 안전한 거절 버튼/방 연결 증거가 없어 자동 조작하지 않음");
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

    static int ambiguousRequestCount(Context context) {
        return prefs(context).getInt(KEY_AMBIGUOUS, 0);
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

    private static VoiceRoomStore.Room uniqueRoomAcrossWindows(
            List<AccessibilityNodeInfo> roots, List<VoiceRoomStore.Room> eligible) {
        VoiceRoomStore.Room unique = null;
        for (VoiceRoomStore.Room room : eligible) {
            boolean seen = false;
            for (AccessibilityNodeInfo root : roots) {
                if (containsRoomTitle(root, room.title)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) continue;
            if (unique != null && !unique.id.equals(room.id)) return null;
            unique = room;
        }
        return unique;
    }

    private static VoiceRoomStore.Room scopeRoom(
            AccessibilityNodeInfo root, List<VoiceRoomStore.Room> eligible) {
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
            String control = label(clickable);
            VoiceRoomSpeakerRequestPolicy.ToggleState state =
                    VoiceRoomSpeakerRequestPolicy.requestToggleState(control);
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
        AccessibilityNodeInfo reject = findRejectInsideRequestContainer(root);
        if (reject == null || !canAct(now)) return false;
        if (!click(reject)) return false;

        markAction(now);
        increment(context, KEY_REJECTED);
        VoiceRoomStore.setLastStatus(context,
                room.title + " · 스피커 요청 자동 거절 · 누적 " + rejectedCount(context) + "회");
        return true;
    }

    /**
     * Locates a reject button inside the smallest visible subtree that also contains explicit
     * speaker-request context. A root-wide unrelated "거절" button is therefore not enough.
     */
    private static AccessibilityNodeInfo findRejectInsideRequestContainer(AccessibilityNodeInfo root) {
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        long rootArea = Math.max(1L, (long) rootBounds.width() * rootBounds.height());
        AccessibilityNodeInfo best = null;
        long bestContainerArea = Long.MAX_VALUE;

        for (AccessibilityNodeInfo contextNode : allNodes(root)) {
            if (!VoiceRoomSpeakerRequestPolicy.isRequestContext(label(contextNode))) continue;
            AccessibilityNodeInfo container = contextNode;
            for (int depth = 0; depth < 6 && container != null; depth++) {
                Rect cb = new Rect();
                container.getBoundsInScreen(cb);
                long area = cb.isEmpty() ? Long.MAX_VALUE : (long) cb.width() * cb.height();
                // Root-sized containers are too broad for a generic exact "거절" action.
                if (area < rootArea * 9L / 10L) {
                    AccessibilityNodeInfo candidate = explicitRejectInSubtree(container);
                    if (candidate != null && area < bestContainerArea) {
                        bestContainerArea = area;
                        best = candidate;
                    }
                }
                container = container.getParent();
            }
        }
        return best;
    }

    private static AccessibilityNodeInfo explicitRejectInSubtree(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo best = null;
        long bestArea = Long.MAX_VALUE;
        for (AccessibilityNodeInfo node : allNodes(root)) {
            String nodeLabel = label(node);
            if (!VoiceRoomSpeakerRequestPolicy.isRejectAction(nodeLabel)
                    || VoiceRoomSpeakerRequestPolicy.isAcceptAction(nodeLabel)) continue;
            AccessibilityNodeInfo clickable = clickableAncestorWithin(node, root);
            if (clickable == null || !clickable.isVisibleToUser() || !clickable.isEnabled()) continue;
            Rect b = new Rect();
            clickable.getBoundsInScreen(b);
            if (b.isEmpty()) continue;
            long area = (long) b.width() * b.height();
            if (area < bestArea) {
                bestArea = area;
                best = clickable;
            }
        }
        return best;
    }

    private static AccessibilityNodeInfo clickableAncestorWithin(
            AccessibilityNodeInfo node, AccessibilityNodeInfo boundary) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 6 && current != null; i++) {
            if (current.isClickable()) return current;
            if (current.equals(boundary)) break;
            current = current.getParent();
        }
        return null;
    }

    private static boolean tryProtectExplicitAudio(
            Context context, AccessibilityNodeInfo root, VoiceRoomStore.Room room, long now) {
        Set<AccessibilityNodeInfo> visited = new HashSet<>();
        for (AccessibilityNodeInfo node : allNodes(root)) {
            AccessibilityNodeInfo clickable = clickableAncestor(node);
            if (clickable == null || !visited.add(clickable)
                    || !clickable.isVisibleToUser() || !clickable.isEnabled()) continue;
            String control = label(clickable);
            VoiceRoomAudioPolicy.State mic = VoiceRoomAudioPolicy.micState(control);
            VoiceRoomAudioPolicy.State speaker = VoiceRoomAudioPolicy.speakerState(control);

            // A parent/container that simultaneously describes both controls is too broad to click.
            if (mic != VoiceRoomAudioPolicy.State.UNKNOWN
                    && speaker != VoiceRoomAudioPolicy.State.UNKNOWN) continue;

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

    private static boolean eventTextMentionsRequest(AccessibilityEvent event) {
        try {
            for (CharSequence value : event.getText()) {
                if (VoiceRoomSpeakerRequestPolicy.isRequestContext(text(value))) return true;
            }
            return VoiceRoomSpeakerRequestPolicy.isRequestContext(text(event.getContentDescription()));
        } catch (Exception ignored) {
            return false;
        }
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
