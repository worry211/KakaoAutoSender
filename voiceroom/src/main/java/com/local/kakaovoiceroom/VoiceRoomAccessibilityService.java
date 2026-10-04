package com.local.kakaovoiceroom;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VoiceRoomAccessibilityService extends AccessibilityService {
    private static final String KAKAO_PACKAGE = "com.kakao.talk";
    private static final long JOB_TIMEOUT_MS = VoiceRoomStore.PENDING_TIMEOUT_MS;
    private static final long STEP_DEBOUNCE_MS = 300L;
    private static final long FOLLOW_UP_MS = 700L;
    private static final long MANUAL_FOREGROUND_LOST_GRACE_MS = 5_000L;

    private static final List<String> ROOM_READY_TERMS = Arrays.asList(
            "메시지 입력", "메시지를 입력", "메시지 입력하기", "채팅 입력", "메시지 보내기");
    private static final List<String> OPEN_CHAT_CONTEXT_TERMS = Arrays.asList(
            "공유하기", "링크 공유", "QR 코드", "오픈채팅");
    private static final List<String> COMPOSER_ACTION_TERMS = Arrays.asList(
            "+", "추가", "첨부", "더하기");
    private static final List<String> VOICE_TERMS = Arrays.asList("보이스룸", "보이스 룸");
    private static final List<String> CREATE_STRONG_TERMS = Arrays.asList(
            "보이스룸 만들기", "보이스룸 시작", "보이스룸 열기", "보이스룸 개설",
            "보이스 룸 만들기", "보이스 룸 시작");
    private static final List<String> CREATE_WEAK_TERMS = Arrays.asList("시작하기");
    private static final List<String> CREATE_SUBMIT_TERMS = Arrays.asList("만들기");
    private static final List<String> CONFIRM_TERMS = Arrays.asList("시작", "만들기", "확인");
    private static final List<String> STRONG_ACTIVE_TERMS = Arrays.asList(
            "보이스룸 종료", "보이스룸 나가기", "보이스 룸 종료", "보이스 룸 나가기");
    private static final List<String> SPEAKER_TERMS = Arrays.asList(
            "스피커 신청", "스피커로 참여", "스피커");
    private static final List<String> LISTENER_TERMS = Arrays.asList(
            "리스너로 참여", "리스너", "청취자");

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
        if (!VoiceRoomStore.managerActive(this)
                && !VoiceRoomStore.isProbePending(this)
                && !VoiceRoomStore.isManualPending(this)) return;
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
        boolean manual = VoiceRoomStore.isManualPending(this);
        VoiceRoomStore.Room room = VoiceRoomStore.get(this, roomId);
        if (room == null || (!room.enabled && !probe && !manual)
                || (!VoiceRoomStore.managerActive(this) && !probe && !manual)) {
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
                if ((manual || probe) && pendingAt > 0L
                        && now - pendingAt >= MANUAL_FOREGROUND_LOST_GRACE_MS) {
                    fail(room, manual ? "실제 점검이 중단됨 · 카카오톡 화면을 벗어남"
                            : "안전 점검이 중단됨 · 카카오톡 화면을 벗어남");
                } else if (stageExpired(room, now)) {
                    fail(room, stageError(room.status, "카카오톡이 전경이 아님"));
                } else {
                    scheduleFollowUp();
                }
                return;
            }

            saveDiagnostic(room, diagnosticSummary(root, room));

            boolean titleVisible = containsRoomTitle(root, room.title);
            boolean composerVisible = hasComposerInput(root);
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

                if (hasCreateForm(root)) {
                    if (probe) {
                        finishProbe(room, "보이스룸 생성 입력 화면까지 안전하게 인식 성공");
                        return;
                    }
                    transition(room, "CREATING", room.title + " · 보이스룸 이름 입력 준비");
                    scheduleFollowUp();
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

                AccessibilityNodeInfo nameInput = findCreateNameInput(root);
                if (nameInput == null) {
                    VoiceRoomStore.setLastStatus(this,
                            room.title + " · 생성 입력칸 탐색 중");
                    scheduleFollowUp();
                    return;
                }

                String desired = KakaoUiPolicy.voiceRoomName(room.title);
                String current = value(nameInput.getText());
                if (!desired.equals(current)) {
                    if (setTextRobust(nameInput, desired)) {
                        VoiceRoomStore.setLastStatus(this,
                                room.title + " · 보이스룸 이름 입력 요청 완료 · UI 반영 확인 중");
                    }
                    scheduleFollowUp();
                    return;
                }

                if (clickAnyExact(root, CREATE_SUBMIT_TERMS)) {
                    transition(room, "CREATING_CONFIRMING",
                            room.title + " · 만들기 실행 후 활성 확인 중");
                } else {
                    VoiceRoomStore.setLastStatus(this,
                            room.title + " · 이름 확인 완료 · 만들기 버튼 활성 대기");
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
        return room.stageStartedAt > 0L
                && now - room.stageStartedAt > stageTimeoutMs(room.status);
    }

    private long stageTimeoutMs(String status) {
        String s = safe(status);
        if (s.contains("OPENING_KAKAO") || s.contains("OPENING_ROOM")) return 25_000L;
        if (s.contains("ROOM_VERIFIED") || s.contains("ROOM_MENU")) return 15_000L;
        if (s.contains("VOICE_MENU")) return 18_000L;
        if (s.contains("CREATING")) return 30_000L;
        return 18_000L;
    }

    private String stageError(String status, String detail) {
        String s = safe(status);
        String base;
        if (s.contains("OPENING_KAKAO")) base = "카카오톡은 열렸지만 대상 오픈채팅방을 확인하지 못함";
        else if (s.contains("OPENING_ROOM")) base = "방 선택 후 채팅 화면 진입을 확인하지 못함";
        else if (s.contains("ROOM_VERIFIED") || s.contains("ROOM_MENU")) base = "하단 + 메뉴에서 보이스룸 항목을 찾지 못함";
        else if (s.contains("VOICE_MENU")) base = "보이스룸 화면에서 활성/생성 상태를 확인하지 못함";
        else if (s.contains("CREATING")) base = "보이스룸 이름 입력/생성 또는 활성 확인에 실패함";
        else base = "카카오톡 화면 인식 제한시간 초과";
        return base + " · " + detail;
    }

    private boolean isActiveVoiceRoom(AccessibilityNodeInfo root) {
        if (containsAny(root, STRONG_ACTIVE_TERMS)) return true;
        return containsAny(root, VOICE_TERMS)
                && containsAny(root, SPEAKER_TERMS)
                && containsAny(root, LISTENER_TERMS);
    }

    private boolean hasCreateForm(AccessibilityNodeInfo root) {
        return findCreateNameInput(root) != null
                && containsAny(root, CREATE_STRONG_TERMS)
                && findExactAny(root, CREATE_SUBMIT_TERMS) != null;
    }

    private AccessibilityNodeInfo findCreateNameInput(AccessibilityNodeInfo root) {
        if (root == null) return null;
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int width = Math.max(1, rootBounds.width());
        int height = Math.max(1, rootBounds.height());

        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (isLikelyCreateInput(focused, rootBounds, width, height)) return focused;

        AccessibilityNodeInfo best = null;
        long bestScore = Long.MIN_VALUE;
        for (AccessibilityNodeInfo node : findAllNodes(root)) {
            if (!isLikelyCreateInput(node, rootBounds, width, height)) continue;
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            int cy = b.centerY() - rootBounds.top;
            CharSequence className = node.getClassName();
            boolean editClass = className != null && className.toString().contains("EditText");
            boolean setText = supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT);
            int maxLength = node.getMaxTextLength();

            long score = (long) b.width() * 10L
                    - Math.abs(cy - (long) (height * 0.43));
            if (node.isFocused()) score += 20_000_000L;
            if (maxLength == 30) score += 15_000_000L;
            if (setText) score += 8_000_000L;
            if (node.isEditable()) score += 5_000_000L;
            if (editClass) score += 3_000_000L;
            if (score > bestScore) {
                bestScore = score;
                best = node;
            }
        }
        return best;
    }

    private boolean isLikelyCreateInput(
            AccessibilityNodeInfo node, Rect rootBounds, int width, int height) {
        if (node == null || !node.isVisibleToUser()) return false;
        CharSequence className = node.getClassName();
        boolean editClass = className != null && className.toString().contains("EditText");
        boolean canSetText = supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT);
        boolean inputCapable = editClass || node.isEditable() || canSetText || node.isFocused();
        if (!inputCapable) return false;

        Rect b = new Rect();
        node.getBoundsInScreen(b);
        if (b.isEmpty()) return false;
        int cy = b.centerY() - rootBounds.top;
        if (cy < (int) (height * 0.24) || cy > (int) (height * 0.72)) return false;
        if (b.width() < (int) (width * 0.35)) return false;
        return b.height() <= (int) (height * 0.25);
    }

    private boolean setTextRobust(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        if (performSetText(node, text)) return true;
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (performSetText(node, text)) return true;

        AccessibilityNodeInfo parent = node.getParent();
        for (int i = 0; i < 3 && parent != null; i++) {
            parent.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (performSetText(parent, text)) return true;
            parent = parent.getParent();
        }

        for (AccessibilityNodeInfo child : findAllNodes(node)) {
            if (child == node) continue;
            child.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (performSetText(child, text)) return true;
        }
        return false;
    }

    private boolean performSetText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        if (!node.isEditable() && !supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) return false;
        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text == null ? "" : text);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private boolean supportsAction(AccessibilityNodeInfo node, int actionId) {
        if (node == null) return false;
        List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
        if (actions == null) return false;
        for (AccessibilityNodeInfo.AccessibilityAction action : actions) {
            if (action != null && action.getId() == actionId) return true;
        }
        return false;
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
        boolean manual = VoiceRoomStore.isManualPending(this);
        room.stageStartedAt = 0L;
        room.lastError = error;
        if (probe) {
            room.status = "PROBE_ERROR";
        } else if (manual) {
            room.status = "MANUAL_ERROR";
        } else {
            room.failures += 1;
            room.status = "ERROR";
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

    private boolean clickComposerAction(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo best = bestComposerControl(root, true);
        return best != null && best.isEnabled()
                && best.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private AccessibilityNodeInfo bestComposerControl(AccessibilityNodeInfo root, boolean allowGeometryFallback) {
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int width = Math.max(1, rootBounds.width());
        int height = Math.max(1, rootBounds.height());
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MIN_VALUE;

        List<AccessibilityNodeInfo> semantic = findAllContains(root, COMPOSER_ACTION_TERMS);
        for (AccessibilityNodeInfo candidate : semantic) {
            AccessibilityNodeInfo clickable = clickableAncestor(candidate);
            if (clickable == null || !clickable.isVisibleToUser() || !clickable.isEnabled()) continue;
            long score = composerScore(rootBounds, clickable, width, height);
            if (score > bestScore) {
                bestScore = score;
                best = clickable;
            }
        }
        if (best != null || !allowGeometryFallback) return best;

        for (AccessibilityNodeInfo node : findAllNodes(root)) {
            if (!node.isClickable() || !node.isVisibleToUser() || !node.isEnabled()) continue;
            long score = composerScore(rootBounds, node, width, height);
            if (score > bestScore) {
                bestScore = score;
                best = node;
            }
        }
        return best;
    }

    private long composerScore(Rect rootBounds, AccessibilityNodeInfo node, int width, int height) {
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        if (b.isEmpty()) return Long.MIN_VALUE;
        int cx = b.centerX() - rootBounds.left;
        int cy = b.centerY() - rootBounds.top;
        int bw = b.width();
        int bh = b.height();

        if (cy < (int) (height * 0.70) || cx > (int) (width * 0.30)) return Long.MIN_VALUE;
        if (bw > (int) (width * 0.30) || bh > (int) (height * 0.22)) return Long.MIN_VALUE;

        long targetX = (long) (width * 0.09);
        long targetY = (long) (height * 0.90);
        long dx = cx - targetX;
        long dy = cy - targetY;
        long distancePenalty = dx * dx + dy * dy;
        long areaPenalty = (long) bw * (long) bh;
        return -distancePenalty * 10L - areaPenalty;
    }

    private boolean hasComposerAction(AccessibilityNodeInfo root) {
        return bestComposerControl(root, true) != null;
    }

    private boolean hasComposerInput(AccessibilityNodeInfo root) {
        if (containsAny(root, ROOM_READY_TERMS)) return true;
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int width = Math.max(1, rootBounds.width());
        int height = Math.max(1, rootBounds.height());
        for (AccessibilityNodeInfo node : findAllNodes(root)) {
            CharSequence className = node.getClassName();
            if (className == null || !className.toString().contains("EditText")) continue;
            if (!node.isVisibleToUser()) continue;
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            int cy = b.centerY() - rootBounds.top;
            if (cy >= (int) (height * 0.65) && b.width() >= (int) (width * 0.30)) return true;
        }
        return false;
    }

    private boolean clickAny(AccessibilityNodeInfo root, List<String> terms) {
        AccessibilityNodeInfo node = bestClickableMatching(root, terms, false);
        return node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean clickAnyExact(AccessibilityNodeInfo root, List<String> terms) {
        AccessibilityNodeInfo node = bestClickableMatching(root, terms, true);
        return node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private AccessibilityNodeInfo bestClickableMatching(
            AccessibilityNodeInfo root, List<String> terms, boolean exact) {
        AccessibilityNodeInfo best = null;
        long bestArea = Long.MAX_VALUE;
        for (AccessibilityNodeInfo node : findAllNodes(root)) {
            String text = value(node.getText());
            String desc = value(node.getContentDescription());
            if (!matchesAny(text, desc, terms, exact)) continue;
            AccessibilityNodeInfo clickable = clickableAncestor(node);
            if (clickable == null || !clickable.isVisibleToUser() || !clickable.isEnabled()) continue;
            Rect b = new Rect();
            clickable.getBoundsInScreen(b);
            if (b.isEmpty()) continue;
            long area = (long) b.width() * (long) b.height();
            if (area < bestArea) {
                bestArea = area;
                best = clickable;
            }
        }
        return best;
    }

    private boolean matchesAny(String text, String desc, List<String> terms, boolean exact) {
        for (String term : terms) {
            if (exact) {
                if (term.equals(text) || term.equals(desc)) return true;
            } else if ((!text.isEmpty() && text.contains(term))
                    || (!desc.isEmpty() && desc.contains(term))) {
                return true;
            }
        }
        return false;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo target = clickableAncestor(node);
        return target != null && target.isEnabled()
                && target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
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
        AccessibilityNodeInfo createInput = findCreateNameInput(root);
        AccessibilityNodeInfo submit = findExactAny(root, CREATE_SUBMIT_TERMS);
        AccessibilityNodeInfo clickableSubmit = bestClickableMatching(root, CREATE_SUBMIT_TERMS, true);
        return "pkg=kakao"
                + " · title=" + containsRoomTitle(root, room.title)
                + " · input=" + hasComposerInput(root)
                + " · openChat=" + containsAny(root, OPEN_CHAT_CONTEXT_TERMS)
                + " · add=" + hasComposerAction(root)
                + " · voice=" + containsAny(root, VOICE_TERMS)
                + " · create=" + (containsAny(root, CREATE_STRONG_TERMS) || containsAny(root, CREATE_WEAK_TERMS))
                + " · form=" + hasCreateForm(root)
                + " · nameInput=" + (createInput != null)
                + " · submit=" + (submit != null)
                + " · submitReady=" + (clickableSubmit != null)
                + " · active=" + isActiveVoiceRoom(root)
                + " · entry=" + VoiceRoomStore.pendingEntry(this);
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

    private static AccessibilityNodeInfo findExactAny(
            AccessibilityNodeInfo root, List<String> terms) {
        if (root == null) return null;
        for (AccessibilityNodeInfo node : findAllNodes(root)) {
            String text = value(node.getText());
            String desc = value(node.getContentDescription());
            for (String term : terms) {
                if (term.equals(text) || term.equals(desc)) return node;
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
        for (AccessibilityNodeInfo node : findAllNodes(root)) {
            String text = value(node.getText());
            String desc = value(node.getContentDescription());
            for (String term : terms) {
                if ((!text.isEmpty() && text.contains(term))
                        || (!desc.isEmpty() && desc.contains(term))) {
                    out.add(node);
                    break;
                }
            }
        }
        return out;
    }

    private static List<AccessibilityNodeInfo> findAllNodes(AccessibilityNodeInfo root) {
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
