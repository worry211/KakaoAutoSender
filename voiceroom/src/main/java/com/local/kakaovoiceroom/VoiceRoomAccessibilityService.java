package com.local.kakaovoiceroom;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class VoiceRoomAccessibilityService extends AccessibilityService {
    private static final String KAKAO_PACKAGE = "com.kakao.talk";
    private static final long JOB_TIMEOUT_MS = VoiceRoomStore.PENDING_TIMEOUT_MS;
    private static final long STAGE_TIMEOUT_MS = 18_000L;
    private static final long STEP_DEBOUNCE_MS = 300L;
    private static final long FOLLOW_UP_MS = 700L;

    private static final List<String> ROOM_READY_TERMS = Arrays.asList(
            "메시지 입력", "메시지를 입력", "채팅 입력", "메시지 보내기");
    private static final List<String> OPEN_CHAT_CONTEXT_TERMS = Arrays.asList(
            "공유하기", "링크 공유", "QR 코드", "오픈채팅");
    private static final List<String> COMPOSER_ACTION_TERMS = Arrays.asList(
            "+", "추가", "첨부");
    private static final List<String> VOICE_TERMS = Arrays.asList("보이스룸");
    private static final List<String> CREATE_STRONG_TERMS = Arrays.asList(
            "보이스룸 만들기", "보이스룸 시작", "보이스룸 열기", "보이스룸 개설");
    private static final List<String> CREATE_WEAK_TERMS = Arrays.asList("시작하기");
    private static final List<String> CONFIRM_TERMS = Arrays.asList("시작", "만들기", "확인");
    private static final List<String> STRONG_ACTIVE_TERMS = Arrays.asList(
            "보이스룸 종료", "보이스룸 나가기");
    private static final List<String> SPEAKER_TERMS = Arrays.asList("스피커 신청", "스피커");
    private static final List<String> LISTENER_TERMS = Arrays.asList("리스너", "청취자");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastStepAt;
    private String watchedRoomId = "";
    private boolean followUpScheduled;

    private final Runnable timeoutRunnable = new Runnable() {
        @Override public void run() {
            String pending = VoiceRoomStore.pendingRoomId(VoiceRoomAccessibilityService.this);
            if (pending.isEmpty() || !pending.equals(watchedRoomId)) return;
            VoiceRoomStore.Room room = VoiceRoomStore.get(VoiceRoomAccessibilityService.this, pending);
            if (room != null) fail(room, stageError(room.status, "전체 작업 제한시간 초과"));
        }
    };

    private final Runnable followUpRunnable = new Runnable() {
        @Override public void run() {
            followUpScheduled = false;
            if (!VoiceRoomStore.pendingRoomId(VoiceRoomAccessibilityService.this).isEmpty()) {
                stepPendingJob();
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        VoiceRoomStore.setLastStatus(this, "접근성 자동화 연결됨");
        if (!VoiceRoomStore.pendingRoomId(this).isEmpty()) handler.post(this::stepPendingJob);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!KAKAO_PACKAGE.contentEquals(event.getPackageName())) return;
        if (VoiceRoomStore.pendingRoomId(this).isEmpty()) return;
        if (!VoiceRoomStore.managerActive(this) && !VoiceRoomStore.isProbePending(this)) return;
        long now = System.currentTimeMillis();
        if (now - lastStepAt < STEP_DEBOUNCE_MS) return;
        lastStepAt = now;
        stepPendingJob();
    }

    @Override public void onInterrupt() {
        VoiceRoomStore.setLastStatus(this, "접근성 자동화가 일시 중단됨");
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(timeoutRunnable);
        handler.removeCallbacks(followUpRunnable);
        followUpScheduled = false;
        super.onDestroy();
    }

    private void stepPendingJob() {
        String roomId = VoiceRoomStore.pendingRoomId(this);
        if (roomId.isEmpty()) return;
        scheduleWatchdog(roomId);

        boolean probe = VoiceRoomStore.isProbePending(this);
        VoiceRoomStore.Room room = VoiceRoomStore.get(this, roomId);
        if (room == null || (!room.enabled && !probe)
                || (!VoiceRoomStore.managerActive(this) && !probe)) {
            finishPending();
            return;
        }

        long now = System.currentTimeMillis();
        long pendingAt = VoiceRoomStore.pendingAt(this);
        if (pendingAt > 0L && now - pendingAt > JOB_TIMEOUT_MS) {
            fail(room, stageError(room.status, "전체 작업 제한시간 초과"));
            return;
        }

        if (room.stageStartedAt <= 0L) {
            room.stageStartedAt = now;
            VoiceRoomStore.update(this, room);
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            if (stageExpired(room, now)) fail(room, stageError(room.status, "화면 루트 없음"));
            else scheduleFollowUp();
            return;
        }

        try {
            CharSequence rootPackage = root.getPackageName();
            if (rootPackage == null || !KAKAO_PACKAGE.contentEquals(rootPackage)) {
                // Delayed callbacks can fire after the UI returned to this app. Never click
                // anything unless the active root itself belongs to KakaoTalk.
                if (stageExpired(room, now)) fail(room, stageError(room.status, "카카오톡이 전경이 아님"));
                else scheduleFollowUp();
                return;
            }

            saveDiagnostic(room, diagnosticSummary(root, room));

            boolean titleVisible = containsRoomTitle(root, room.title);
            boolean composerVisible = containsAny(root, ROOM_READY_TERMS);
            boolean openChatContext = containsAny(root, OPEN_CHAT_CONTEXT_TERMS);
            boolean trustedLinkEntry = VoiceRoomStore.pendingEnteredByLink(this);
            boolean roomScreen = composerVisible
                    && (titleVisible || (trustedLinkEntry && openChatContext));

            String status = safe(room.status);
            boolean openingRoom = "OPENING_ROOM".equals(status)
                    || "PROBE_OPENING_ROOM".equals(status);
            boolean roomMenu = "ROOM_MENU".equals(status)
                    || "PROBE_ROOM_MENU".equals(status);
            boolean voiceMenu = "VOICE_MENU".equals(status)
                    || "PROBE_VOICE_MENU".equals(status);
            boolean creating = "CREATING".equals(status);
            boolean confirming = "CREATING_CONFIRMING".equals(status);
            boolean modalProgress = roomMenu || voiceMenu || creating || confirming;

            if (stageExpired(room, now)) {
                fail(room, stageError(status, "단계 진행 없음"));
                return;
            }

            if (!roomScreen && !modalProgress) {
                if (openingRoom) {
                    scheduleFollowUp();
                    return;
                }

                AccessibilityNodeInfo roomNode = findRoomTitle(root, room.title);
                if (roomNode != null && clickNodeOrParent(roomNode)) {
                    transition(room,
                            probe ? "PROBE_OPENING_ROOM" : "OPENING_ROOM",
                            room.title + " · 대상 방 진입 중");
                }
                scheduleFollowUp();
                return;
            }

            if (roomScreen && !modalProgress) {
                transition(room,
                        probe ? "PROBE_ROOM_VERIFIED" : "ROOM_VERIFIED",
                        room.title + " · 대상 오픈채팅방 확인 완료");

                if (isActiveVoiceRoom(root)) {
                    if (probe) finishProbe(room, "기존 보이스룸 활성 상태 인식 성공");
                    else markActive(room, now);
                    return;
                }

                if (clickComposerAction(root)) {
                    transition(room,
                            probe ? "PROBE_ROOM_MENU" : "ROOM_MENU",
                            room.title + " · 하단 + 메뉴 확인 중");
                }
                scheduleFollowUp();
                return;
            }

            if (roomMenu) {
                if (isActiveVoiceRoom(root)) {
                    if (probe) finishProbe(room, "기존 보이스룸 활성 상태 인식 성공");
                    else markActive(room, now);
                    return;
                }
                if (clickAny(root, VOICE_TERMS)) {
                    transition(room,
                            probe ? "PROBE_VOICE_MENU" : "VOICE_MENU",
                            room.title + " · 보이스룸 화면 진입");
                }
                scheduleFollowUp();
                return;
            }

            if (voiceMenu) {
                if (isActiveVoiceRoom(root)) {
                    if (probe) finishProbe(room, "기존 보이스룸 활성 상태 인식 성공");
                    else markActive(room, now);
                    return;
                }

                boolean strongCreate = containsAny(root, CREATE_STRONG_TERMS);
                boolean weakCreate = containsAny(root, CREATE_WEAK_TERMS) && containsAny(root, VOICE_TERMS);
                if (strongCreate || weakCreate) {
                    if (probe) {
                        finishProbe(room, "보이스룸 생성 화면까지 안전하게 인식 성공");
                        return;
                    }
                    boolean clicked = strongCreate
                            ? clickAny(root, CREATE_STRONG_TERMS)
                            : clickAny(root, CREATE_WEAK_TERMS);
                    if (clicked) {
                        transition(room, "CREATING", room.title + " · 보이스룸 생성 단계");
                    }
                }
                scheduleFollowUp();
                return;
            }

            if (creating) {
                if (isActiveVoiceRoom(root)) {
                    markActive(room, now);
                    return;
                }
                if (clickAnyExact(root, CONFIRM_TERMS)) {
                    transition(room, "CREATING_CONFIRMING", room.title + " · 생성 확인 후 활성 대기");
                }
                scheduleFollowUp();
                return;
            }

            if (confirming) {
                if (isActiveVoiceRoom(root)) {
                    markActive(room, now);
                    return;
                }
                scheduleFollowUp();
                return;
            }

            scheduleFollowUp();
        } finally {
            root.recycle();
        }
    }

    private void transition(VoiceRoomStore.Room room, String nextStatus, String message) {
        if (!nextStatus.equals(room.status)) room.stageStartedAt = System.currentTimeMillis();
        room.status = nextStatus;
        room.lastError = "";
        VoiceRoomStore.update(this, room);
        VoiceRoomStore.setLastStatus(this, message);
    }

    private boolean stageExpired(VoiceRoomStore.Room room, long now) {
        return room.stageStartedAt > 0L && now - room.stageStartedAt > STAGE_TIMEOUT_MS;
    }

    private String stageError(String status, String detail) {
        String s = safe(status);
        String base;
        if (s.contains("OPENING_KAKAO")) base = "카카오톡은 열렸지만 대상 오픈채팅방을 확인하지 못함";
        else if (s.contains("OPENING_ROOM")) base = "방 선택 후 채팅 화면 진입을 확인하지 못함";
        else if (s.contains("ROOM_VERIFIED") || s.contains("ROOM_MENU")) base = "하단 + 메뉴에서 보이스룸 항목을 찾지 못함";
        else if (s.contains("VOICE_MENU")) base = "보이스룸 화면에서 활성/생성 상태를 확인하지 못함";
        else if (s.contains("CREATING")) base = "보이스룸 생성 후 활성 상태를 확인하지 못함";
        else base = "카카오톡 화면 인식 제한시간 초과";
        return base + " · " + detail;
    }

    private boolean isActiveVoiceRoom(AccessibilityNodeInfo root) {
        if (containsAny(root, STRONG_ACTIVE_TERMS)) return true;
        return containsAny(root, SPEAKER_TERMS) && containsAny(root, LISTENER_TERMS);
    }

    private void finishProbe(VoiceRoomStore.Room room, String message) {
        room.status = "PROBE_OK";
        room.lastError = "";
        room.stageStartedAt = 0L;
        VoiceRoomStore.update(this, room);
        VoiceRoomStore.setLastStatus(this,
                room.title + " · " + message + " · 생성 버튼은 누르지 않음");
        finishPending();
    }

    private void markActive(VoiceRoomStore.Room room, long now) {
        boolean createdByUs = "CREATING".equals(room.status)
                || "CREATING_CONFIRMING".equals(room.status);
        if (createdByUs) room.startedAt = now;
        room.failures = 0;
        room.lastError = "";
        room.stageStartedAt = 0L;
        room.status = room.startedAt <= 0L ? "ACTIVE_UNKNOWN_START" : "ACTIVE";
        room.nextCheckAt = VoiceRoomTiming.nextActiveCheck(room.startedAt, now);
        VoiceRoomStore.update(this, room);
        VoiceRoomStore.setLastStatus(this, room.title + " · 보이스룸 활성 확인");
        finishPending();
    }

    private void fail(VoiceRoomStore.Room room, String error) {
        boolean probe = VoiceRoomStore.isProbePending(this);
        room.stageStartedAt = 0L;
        if (probe) {
            room.status = "PROBE_ERROR";
            room.lastError = error;
        } else {
            room.failures += 1;
            room.status = "ERROR";
            room.lastError = error;
            room.nextCheckAt = System.currentTimeMillis() + KakaoUiPolicy.retryDelayMs(room.failures);
        }
        VoiceRoomStore.update(this, room);
        VoiceRoomStore.setLastStatus(this, room.title + " · " + error);
        finishPending();
    }

    private void finishPending() {
        handler.removeCallbacks(timeoutRunnable);
        handler.removeCallbacks(followUpRunnable);
        followUpScheduled = false;
        watchedRoomId = "";
        VoiceRoomStore.clearPending(this);
        VoiceRoomScheduler.scheduleNext(this);
    }

    private void scheduleWatchdog(String roomId) {
        if (roomId.equals(watchedRoomId)) return;
        handler.removeCallbacks(timeoutRunnable);
        watchedRoomId = roomId;
        long pendingAt = VoiceRoomStore.pendingAt(this);
        long elapsed = pendingAt <= 0L ? 0L : Math.max(0L, System.currentTimeMillis() - pendingAt);
        handler.postDelayed(timeoutRunnable, Math.max(1_000L, JOB_TIMEOUT_MS - elapsed));
    }

    private void scheduleFollowUp() {
        if (followUpScheduled) return;
        followUpScheduled = true;
        handler.postDelayed(followUpRunnable, FOLLOW_UP_MS);
    }

    /** Click only a bottom-left composer/add control, never the top-right room menu. */
    private boolean clickComposerAction(AccessibilityNodeInfo root) {
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int width = Math.max(1, rootBounds.width());
        int height = Math.max(1, rootBounds.height());
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MIN_VALUE;

        for (AccessibilityNodeInfo candidate : findAllContains(root, COMPOSER_ACTION_TERMS)) {
            AccessibilityNodeInfo clickable = clickableAncestor(candidate);
            if (clickable == null) continue;
            Rect b = new Rect();
            clickable.getBoundsInScreen(b);
            int cx = b.centerX() - rootBounds.left;
            int cy = b.centerY() - rootBounds.top;
            if (cy < (int) (height * 0.60) || cx > (int) (width * 0.42)) continue;
            long score = (long) cy * 10L - cx;
            if (score > bestScore) {
                bestScore = score;
                best = clickable;
            }
        }
        return best != null && best.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean clickAny(AccessibilityNodeInfo root, List<String> terms) {
        AccessibilityNodeInfo node = findContains(root, terms);
        return node != null && clickNodeOrParent(node);
    }

    private boolean clickAnyExact(AccessibilityNodeInfo root, List<String> terms) {
        AccessibilityNodeInfo node = findExactAny(root, new HashSet<>(terms));
        return node != null && clickNodeOrParent(node);
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo target = clickableAncestor(node);
        return target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 6 && current != null; i++) {
            if (current.isClickable()) return current;
            current = current.getParent();
        }
        return null;
    }

    private void saveDiagnostic(VoiceRoomStore.Room room, String value) {
        if (value.equals(safe(room.lastDiagnostic))) return;
        room.lastDiagnostic = value;
        VoiceRoomStore.update(this, room);
    }

    private String diagnosticSummary(AccessibilityNodeInfo root, VoiceRoomStore.Room room) {
        return "pkg=kakao"
                + " · title=" + containsRoomTitle(root, room.title)
                + " · input=" + containsAny(root, ROOM_READY_TERMS)
                + " · openChat=" + containsAny(root, OPEN_CHAT_CONTEXT_TERMS)
                + " · add=" + hasComposerAction(root)
                + " · voice=" + containsAny(root, VOICE_TERMS)
                + " · create=" + (containsAny(root, CREATE_STRONG_TERMS) || containsAny(root, CREATE_WEAK_TERMS))
                + " · active=" + isActiveVoiceRoom(root)
                + " · entry=" + VoiceRoomStore.pendingEntry(this);
    }

    private boolean hasComposerAction(AccessibilityNodeInfo root) {
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int width = Math.max(1, rootBounds.width());
        int height = Math.max(1, rootBounds.height());
        for (AccessibilityNodeInfo candidate : findAllContains(root, COMPOSER_ACTION_TERMS)) {
            AccessibilityNodeInfo clickable = clickableAncestor(candidate);
            if (clickable == null) continue;
            Rect b = new Rect();
            clickable.getBoundsInScreen(b);
            int cx = b.centerX() - rootBounds.left;
            int cy = b.centerY() - rootBounds.top;
            if (cy >= (int) (height * 0.60) && cx <= (int) (width * 0.42)) return true;
        }
        return false;
    }

    private static boolean containsAny(AccessibilityNodeInfo root, List<String> terms) {
        return findContains(root, terms) != null;
    }

    private static boolean containsRoomTitle(AccessibilityNodeInfo root, String expected) {
        return findRoomTitle(root, expected) != null;
    }

    private static AccessibilityNodeInfo findRoomTitle(AccessibilityNodeInfo root, String expected) {
        if (root == null || TextUtils.isEmpty(expected)) return null;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (KakaoUiPolicy.roomTitleMatches(expected, value(node.getText()))
                    || KakaoUiPolicy.roomTitleMatches(expected, value(node.getContentDescription()))) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo findExactAny(AccessibilityNodeInfo root, Set<String> terms) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String text = value(node.getText());
            String desc = value(node.getContentDescription());
            if (terms.contains(text) || terms.contains(desc)) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo findContains(AccessibilityNodeInfo root, List<String> terms) {
        List<AccessibilityNodeInfo> all = findAllContains(root, terms);
        return all.isEmpty() ? null : all.get(0);
    }

    private static List<AccessibilityNodeInfo> findAllContains(AccessibilityNodeInfo root, List<String> terms) {
        ArrayList<AccessibilityNodeInfo> out = new ArrayList<>();
        if (root == null) return out;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String text = value(node.getText());
            String desc = value(node.getContentDescription());
            boolean match = false;
            for (String term : terms) {
                if ((!text.isEmpty() && text.contains(term))
                        || (!desc.isEmpty() && desc.contains(term))) {
                    match = true;
                    break;
                }
            }
            if (match) out.add(node);
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return out;
    }

    private static String value(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String expected = new ComponentName(context,
                VoiceRoomAccessibilityService.class).flattenToString();
        for (String item : enabled.split(":")) {
            if (expected.equalsIgnoreCase(item)) return true;
        }
        return false;
    }
}
