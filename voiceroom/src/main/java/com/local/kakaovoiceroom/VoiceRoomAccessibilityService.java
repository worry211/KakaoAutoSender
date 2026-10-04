package com.local.kakaovoiceroom;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class VoiceRoomAccessibilityService extends AccessibilityService {
    private static final String KAKAO_PACKAGE = "com.kakao.talk";
    private static final long JOB_TIMEOUT_MS = VoiceRoomStore.PENDING_TIMEOUT_MS;
    private static final long STEP_DEBOUNCE_MS = 250L;
    private static final long FOLLOW_UP_MS = 550L;
    private static final long AUDIO_TOGGLE_SETTLE_MS = 900L;

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
    private static final List<String> STRONG_ACTIVE_TERMS = Arrays.asList(
            "보이스룸 종료", "보이스룸 나가기", "보이스 룸 종료", "보이스 룸 나가기");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastStepAt;
    private String watchedRoomId = "";
    private boolean followUpScheduled;
    private String activeEvidenceRoomId = "";
    private int activeEvidenceCount;
    private int audioGuardPasses;
    private long lastAudioToggleAt;

    private enum ProtectionResult { SAFE, CLICKED, WAITING, UNKNOWN }

    private static final class AudioControls {
        AccessibilityNodeInfo mic;
        AccessibilityNodeInfo speaker;
    }

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

    @Override protected void onServiceConnected() {
        VoiceRoomStore.setLastStatus(this, "접근성 자동화 연결됨");
        if (!VoiceRoomStore.pendingRoomId(this).isEmpty()) handler.post(this::stepPendingJob);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
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
        resetActiveEvidence();
        resetAudioGuard();
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
                if (stageExpired(room, now)) fail(room, stageError(room.status, "카카오톡이 전경이 아님"));
                else scheduleFollowUp();
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
            boolean named = "CREATING_NAMED".equals(status);
            boolean confirming = "CREATING_CONFIRMING".equals(status);
            boolean audioGuard = status.startsWith("AUDIO_GUARD_");
            boolean modalProgress = roomMenu || voiceMenu || creating || named || confirming || audioGuard;

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
                if (clickComposerAction(root)) {
                    transition(room,
                            probe ? "PROBE_ROOM_MENU" : "ROOM_MENU",
                            room.title + " · 하단 + 메뉴 확인 중");
                }
                scheduleFollowUp();
                return;
            }

            if (roomMenu) {
                if (clickAny(root, VOICE_TERMS)) {
                    transition(room,
                            probe ? "PROBE_VOICE_MENU" : "VOICE_MENU",
                            room.title + " · 보이스룸 전용 화면 확인 중");
                }
                scheduleFollowUp();
                return;
            }

            if (voiceMenu) {
                if (confirmActiveVoiceRoom(root, room)) {
                    if (probe) {
                        finishProbe(room, "기존 보이스룸 활성 상태 인식 성공");
                    } else {
                        beginAudioGuard(root, room, now, false);
                    }
                    return;
                }

                if (hasCreateSheet(root)) {
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
                        transition(room, "CREATING", room.title + " · 보이스룸 생성 폼 진입 중");
                    }
                }
                scheduleFollowUp();
                return;
            }

            if (creating) {
                AccessibilityNodeInfo nameInput = findCreateNameInput(root);
                if (nameInput == null) {
                    scheduleFollowUp();
                    return;
                }
                String desired = KakaoUiPolicy.voiceRoomName(room.title);
                String current = value(nameInput.getText());
                if (desired.equals(current)) {
                    transition(room, "CREATING_NAMED",
                            room.title + " · 보이스룸 이름 확인 완료 · 만들기 버튼 대기");
                    scheduleFollowUp();
                    return;
                }
                if (setTextRobust(nameInput, desired)) {
                    transition(room, "CREATING_NAMED",
                            room.title + " · 보이스룸 이름 입력 완료 · 만들기 버튼 활성 대기");
                }
                scheduleFollowUp();
                return;
            }

            if (named) {
                AccessibilityNodeInfo submit = bestClickableMatching(root, CREATE_SUBMIT_TERMS, true);
                if (submit != null && submit.isEnabled()
                        && submit.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    transition(room, "CREATING_CONFIRMING",
                            room.title + " · 만들기 실행 완료 · 실제 보이스룸 활성 증거 확인 중");
                    scheduleFollowUp();
                    return;
                }
                scheduleFollowUp();
                return;
            }

            if (confirming) {
                if (confirmActiveVoiceRoom(root, room)) {
                    beginAudioGuard(root, room, now, true);
                    return;
                }
                scheduleFollowUp();
                return;
            }

            if (audioGuard) {
                runAudioGuard(root, room, now);
                return;
            }

            scheduleFollowUp();
        } finally {
            root.recycle();
        }
    }

    private void transition(VoiceRoomStore.Room room, String nextStatus, String message) {
        if (!nextStatus.equals(room.status)) {
            room.stageStartedAt = System.currentTimeMillis();
            resetActiveEvidence();
            resetAudioGuard();
        }
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
        if (s.contains("AUDIO_GUARD")) return 12_000L;
        if (s.contains("CREATING_CONFIRMING")) return 25_000L;
        if (s.contains("CREATING_NAMED")) return 20_000L;
        if (s.contains("CREATING")) return 25_000L;
        return 18_000L;
    }

    private String stageError(String status, String detail) {
        String s = safe(status);
        String base;
        if (s.contains("OPENING_KAKAO")) base = "카카오톡은 열렸지만 대상 오픈채팅방을 확인하지 못함";
        else if (s.contains("OPENING_ROOM")) base = "방 선택 후 채팅 화면 진입을 확인하지 못함";
        else if (s.contains("ROOM_VERIFIED") || s.contains("ROOM_MENU")) base = "하단 + 메뉴에서 보이스룸 항목을 찾지 못함";
        else if (s.contains("VOICE_MENU")) base = "보이스룸 전용 화면에서 활성/생성 상태를 확인하지 못함";
        else if (s.contains("AUDIO_GUARD")) base = "보이스룸은 활성이나 마이크/스피커 보호 확인을 완료하지 못함";
        else if (s.contains("CREATING_CONFIRMING")) base = "만들기 실행 뒤 실제 보이스룸 활성 증거를 확인하지 못함";
        else if (s.contains("CREATING_NAMED")) base = "보이스룸 이름 입력 후 만들기 버튼이 활성화되지 않음";
        else if (s.contains("CREATING")) base = "보이스룸 생성 폼 처리에 실패함";
        else base = "카카오톡 화면 인식 제한시간 초과";
        return base + " · " + detail;
    }

    private boolean confirmActiveVoiceRoom(AccessibilityNodeInfo root, VoiceRoomStore.Room room) {
        String status = safe(room.status);
        boolean allowedStage = "VOICE_MENU".equals(status)
                || "PROBE_VOICE_MENU".equals(status)
                || "CREATING_CONFIRMING".equals(status);
        if (!allowedStage || !hasStrongActiveEvidence(root)) {
            resetActiveEvidence();
            return false;
        }

        if (!room.id.equals(activeEvidenceRoomId)) {
            activeEvidenceRoomId = room.id;
            activeEvidenceCount = 1;
            VoiceRoomStore.setLastStatus(this, room.title + " · 보이스룸 활성 강한 증거 1/2 확인");
            return false;
        }
        activeEvidenceCount += 1;
        return activeEvidenceCount >= 2;
    }

    private boolean hasStrongActiveEvidence(AccessibilityNodeInfo root) {
        if (hasCreateSheet(root)) return false;
        AccessibilityNodeInfo exit = bestClickableMatching(root, STRONG_ACTIVE_TERMS, false);
        return exit != null && exit.isEnabled() && exit.isVisibleToUser();
    }

    private void resetActiveEvidence() {
        activeEvidenceRoomId = "";
        activeEvidenceCount = 0;
    }

    private void resetAudioGuard() {
        audioGuardPasses = 0;
        lastAudioToggleAt = 0L;
    }

    private void beginAudioGuard(
            AccessibilityNodeInfo root, VoiceRoomStore.Room room, long now, boolean createdByUs) {
        room.micMuted = false;
        room.speakerMuted = false;
        room.audioCheckedAt = 0L;
        transition(room,
                createdByUs ? "AUDIO_GUARD_CREATED" : "AUDIO_GUARD_EXISTING",
                room.title + " · 보이스룸 활성 · 마이크/스피커 안전 확인 중");
        runAudioGuard(root, room, now);
    }

    private void runAudioGuard(AccessibilityNodeInfo root, VoiceRoomStore.Room room, long now) {
        if (!hasStrongActiveEvidence(root)) {
            scheduleFollowUp();
            return;
        }

        AudioControls controls = findAudioControls(root);
        ProtectionResult mic = protectControl(controls.mic, true, now);
        ProtectionResult speaker = protectControl(controls.speaker, false, now);

        if (mic == ProtectionResult.SAFE) room.micMuted = true;
        if (speaker == ProtectionResult.SAFE) room.speakerMuted = true;
        room.audioCheckedAt = now;
        VoiceRoomStore.update(this, room);

        boolean clicked = mic == ProtectionResult.CLICKED || speaker == ProtectionResult.CLICKED;
        boolean waiting = mic == ProtectionResult.WAITING || speaker == ProtectionResult.WAITING;
        if (clicked || waiting) {
            VoiceRoomStore.setLastStatus(this,
                    room.title + " · 마이크/스피커 보호 적용 후 상태 재확인 중");
            scheduleFollowUp();
            return;
        }

        audioGuardPasses += 1;
        if ((room.micMuted && room.speakerMuted) || audioGuardPasses >= 3) {
            markActive(room, now);
            return;
        }

        VoiceRoomStore.setLastStatus(this,
                room.title + " · 보룸 활성 · 오디오 컨트롤 의미 확인 중 " + audioGuardPasses + "/3");
        scheduleFollowUp();
    }

    private ProtectionResult protectControl(AccessibilityNodeInfo node, boolean microphone, long now) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) {
            return ProtectionResult.UNKNOWN;
        }

        String label = controlLabel(node);
        VoiceRoomAudioPolicy.State state = microphone
                ? VoiceRoomAudioPolicy.micState(label)
                : VoiceRoomAudioPolicy.speakerState(label);

        if (state == VoiceRoomAudioPolicy.State.UNKNOWN && node.isCheckable()) {
            state = node.isChecked() ? VoiceRoomAudioPolicy.State.ON : VoiceRoomAudioPolicy.State.OFF;
        } else if (state == VoiceRoomAudioPolicy.State.UNKNOWN && node.isSelected()) {
            state = VoiceRoomAudioPolicy.State.ON;
        }

        if (state == VoiceRoomAudioPolicy.State.OFF) return ProtectionResult.SAFE;
        if (state != VoiceRoomAudioPolicy.State.ON) return ProtectionResult.UNKNOWN;

        if (now - lastAudioToggleAt < AUDIO_TOGGLE_SETTLE_MS) return ProtectionResult.WAITING;
        try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastAudioToggleAt = now;
                return ProtectionResult.CLICKED;
            }
        } catch (Exception ignored) {}
        return ProtectionResult.UNKNOWN;
    }

    private AudioControls findAudioControls(AccessibilityNodeInfo root) {
        AudioControls out = new AudioControls();

        for (AccessibilityNodeInfo node : findAllNodes(root)) {
            if (!node.isVisibleToUser()) continue;
            AccessibilityNodeInfo clickable = clickableAncestor(node);
            if (clickable == null || !clickable.isVisibleToUser() || !clickable.isEnabled()) continue;
            String label = controlLabel(clickable);
            if (out.mic == null && VoiceRoomAudioPolicy.micState(label) != VoiceRoomAudioPolicy.State.UNKNOWN) {
                out.mic = clickable;
            }
            if (out.speaker == null
                    && VoiceRoomAudioPolicy.speakerState(label) != VoiceRoomAudioPolicy.State.UNKNOWN) {
                out.speaker = clickable;
            }
        }

        if (out.mic != null && out.speaker != null) return out;

        AccessibilityNodeInfo exit = bestClickableMatching(root, STRONG_ACTIVE_TERMS, false);
        if (exit == null) return out;
        Rect exitBounds = new Rect();
        exit.getBoundsInScreen(exitBounds);
        if (exitBounds.isEmpty()) return out;

        AccessibilityNodeInfo ancestor = exit.getParent();
        for (int depth = 0; depth < 5 && ancestor != null; depth++) {
            AccessibilityNodeInfo nearestLeft = null;
            AccessibilityNodeInfo secondLeft = null;
            long nearestDx = Long.MAX_VALUE;
            long secondDx = Long.MAX_VALUE;
            long exitArea = Math.max(1L, (long) exitBounds.width() * exitBounds.height());

            for (AccessibilityNodeInfo candidate : findAllNodes(ancestor)) {
                if (candidate == exit || !candidate.isClickable()
                        || !candidate.isVisibleToUser() || !candidate.isEnabled()) continue;
                Rect b = new Rect();
                candidate.getBoundsInScreen(b);
                if (b.isEmpty()) continue;
                long area = Math.max(1L, (long) b.width() * b.height());
                if (area < exitArea / 5L || area > exitArea * 5L) continue;
                if (Math.abs(b.centerY() - exitBounds.centerY()) > Math.max(80, exitBounds.height() * 2)) continue;
                long dx = (long) exitBounds.centerX() - b.centerX();
                if (dx <= 0L) continue;
                if (dx < nearestDx) {
                    secondDx = nearestDx;
                    secondLeft = nearestLeft;
                    nearestDx = dx;
                    nearestLeft = candidate;
                } else if (dx < secondDx) {
                    secondDx = dx;
                    secondLeft = candidate;
                }
            }

            if (out.speaker == null && nearestLeft != null) out.speaker = nearestLeft;
            if (out.mic == null && secondLeft != null) out.mic = secondLeft;
            if (out.mic != null && out.speaker != null) break;
            ancestor = ancestor.getParent();
        }
        return out;
    }

    private String controlLabel(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder out = new StringBuilder();
        appendLabel(out, node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) appendLabel(out, child);
        }
        return out.toString().trim();
    }

    private void appendLabel(StringBuilder out, AccessibilityNodeInfo node) {
        String text = value(node.getText());
        String desc = value(node.getContentDescription());
        if (!text.isEmpty()) out.append(' ').append(text);
        if (!desc.isEmpty()) out.append(' ').append(desc);
    }

    private boolean hasCreateSheet(AccessibilityNodeInfo root) {
        return containsAny(root, CREATE_STRONG_TERMS)
                && findExactAny(root, new HashSet<>(CREATE_SUBMIT_TERMS)) != null;
    }

    private AccessibilityNodeInfo findCreateNameInput(AccessibilityNodeInfo root) {
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int width = Math.max(1, rootBounds.width());
        int height = Math.max(1, rootBounds.height());
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MIN_VALUE;

        for (AccessibilityNodeInfo node : findAllNodes(root)) {
            if (!node.isVisibleToUser()) continue;
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            if (b.isEmpty()) continue;
            int cy = b.centerY() - rootBounds.top;
            if (cy < (int) (height * 0.24) || cy > (int) (height * 0.70)) continue;
            if (b.width() < (int) (width * 0.40)) continue;

            String cls = value(node.getClassName());
            boolean editClass = cls.contains("EditText") || cls.contains("TextField") || cls.contains("TextInput");
            boolean editable = node.isEditable();
            boolean focused = node.isFocused() || node.isAccessibilityFocused();
            boolean setText = supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT);
            int maxTextLength = node.getMaxTextLength();
            boolean plausibleLength = maxTextLength < 0 || maxTextLength >= 1;
            if (!(editClass || editable || setText || focused) || !plausibleLength) continue;

            long score = (long) b.width() * 12L
                    - Math.abs(cy - (long) (height * 0.42));
            if (editClass) score += 50_000L;
            if (editable) score += 40_000L;
            if (setText) score += 35_000L;
            if (focused) score += 25_000L;
            if (maxTextLength == 30) score += 60_000L;
            if (node.isEnabled()) score += 5_000L;
            if (score > bestScore) {
                bestScore = score;
                best = node;
            }
        }
        return best;
    }

    private boolean setTextRobust(AccessibilityNodeInfo node, String text) {
        if (node == null || !node.isVisibleToUser()) return false;
        String desired = text == null ? "" : text;
        if (value(node.getText()).equals(desired)) return true;

        try {
            if (supportsAction(node, AccessibilityNodeInfo.ACTION_FOCUS)) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            }
        } catch (Exception ignored) {}

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, desired);
        try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true;
        } catch (Exception ignored) {}

        AccessibilityNodeInfo current = node.getParent();
        for (int i = 0; i < 3 && current != null; i++) {
            try {
                if (supportsAction(current, AccessibilityNodeInfo.ACTION_SET_TEXT)
                        && current.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true;
                if (current.isClickable()) current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } catch (Exception ignored) {}
            current = current.getParent();
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (supportsAction(child, AccessibilityNodeInfo.ACTION_SET_TEXT)
                        && child.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true;
            } catch (Exception ignored) {}
        }
        try {
            if (node.isClickable()) node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Exception ignored) {}
        return false;
    }

    private boolean supportsAction(AccessibilityNodeInfo node, int actionId) {
        if (node == null) return false;
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            if (action != null && action.getId() == actionId) return true;
        }
        return false;
    }

    private void finishProbe(VoiceRoomStore.Room room, String message) {
        room.safeProbePassed = true;
        room.verifiedAt = System.currentTimeMillis();
        room.status = "PROBE_OK";
        room.lastError = "";
        room.stageStartedAt = 0L;
        VoiceRoomStore.update(this, room);
        VoiceRoomStore.setLastStatus(this,
                room.title + " · " + message + " · 생성 버튼은 누르지 않음");
        finishPending();
    }

    private void markActive(VoiceRoomStore.Room room, long now) {
        boolean manual = VoiceRoomStore.isManualPending(this);
        boolean createdByUs = "CREATING_CONFIRMING".equals(room.status)
                || "AUDIO_GUARD_CREATED".equals(room.status);
        boolean existingVoiceRoom = "VOICE_MENU".equals(room.status)
                || "AUDIO_GUARD_EXISTING".equals(room.status);
        if (!createdByUs && !existingVoiceRoom) {
            fail(room, "활성 증거 단계가 올바르지 않아 성공 처리를 거부함");
            return;
        }
        if (createdByUs) room.startedAt = now;
        if (manual) {
            room.safeProbePassed = true;
            room.liveCheckPassed = true;
            room.verifiedAt = now;
        }
        room.failures = 0;
        room.lastError = "";
        room.stageStartedAt = 0L;
        room.status = room.startedAt <= 0L ? "ACTIVE_UNKNOWN_START" : "ACTIVE";
        room.nextCheckAt = VoiceRoomTiming.nextActiveCheck(room.startedAt, now);
        VoiceRoomStore.update(this, room);
        String audio = room.micMuted && room.speakerMuted
                ? " · 마이크/스피커 무음 확인"
                : " · 보룸 활성, 오디오 상태는 확인 필요";
        VoiceRoomStore.setLastStatus(this,
                room.title + " · 보이스룸 활성 확인" + audio);
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
            room.liveCheckPassed = false;
            room.startedAt = 0L;
            room.nextCheckAt = 0L;
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
        boolean direct = VoiceRoomStore.isProbePending(this) || VoiceRoomStore.isManualPending(this);
        handler.removeCallbacks(timeoutRunnable);
        handler.removeCallbacks(followUpRunnable);
        followUpScheduled = false;
        watchedRoomId = "";
        resetActiveEvidence();
        resetAudioGuard();
        VoiceRoomStore.clearPending(this);
        VoiceRoomScheduler.scheduleNext(this);

        if (direct) {
            handler.postDelayed(this::openManager, 350L);
        } else {
            handler.postDelayed(() -> {
                performGlobalAction(GLOBAL_ACTION_HOME);
                if (Build.VERSION.SDK_INT >= 28 && !isDeviceSecure()) {
                    handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN), 250L);
                }
            }, 250L);
        }
    }

    private void openManager() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    private boolean isDeviceSecure() {
        try {
            KeyguardManager keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            return keyguard != null && keyguard.isDeviceSecure();
        } catch (Exception ignored) {
            return true;
        }
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
        return best != null && best.performAction(AccessibilityNodeInfo.ACTION_CLICK);
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
            if (clickable == null || !clickable.isVisibleToUser()) continue;
            long score = composerScore(rootBounds, clickable, width, height);
            if (score > bestScore) {
                bestScore = score;
                best = clickable;
            }
        }
        if (best != null || !allowGeometryFallback) return best;

        for (AccessibilityNodeInfo node : findAllNodes(root)) {
            if (!node.isClickable() || !node.isVisibleToUser()) continue;
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

    private AccessibilityNodeInfo bestClickableMatching(
            AccessibilityNodeInfo root, List<String> terms, boolean exact) {
        AccessibilityNodeInfo best = null;
        long bestArea = Long.MAX_VALUE;
        for (AccessibilityNodeInfo node : findAllNodes(root)) {
            String text = value(node.getText());
            String desc = value(node.getContentDescription());
            if (!matchesAny(text, desc, terms, exact)) continue;
            AccessibilityNodeInfo clickable = clickableAncestor(node);
            if (clickable == null || !clickable.isVisibleToUser()) continue;
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
        AccessibilityNodeInfo input = findCreateNameInput(root);
        AccessibilityNodeInfo submit = bestClickableMatching(root, CREATE_SUBMIT_TERMS, true);
        return "pkg=kakao"
                + " · title=" + containsRoomTitle(root, room.title)
                + " · input=" + hasComposerInput(root)
                + " · openChat=" + containsAny(root, OPEN_CHAT_CONTEXT_TERMS)
                + " · add=" + hasComposerAction(root)
                + " · voice=" + containsAny(root, VOICE_TERMS)
                + " · create=" + (containsAny(root, CREATE_STRONG_TERMS) || containsAny(root, CREATE_WEAK_TERMS))
                + " · createSheet=" + hasCreateSheet(root)
                + " · nameInput=" + (input != null)
                + " · submit=" + (submit != null)
                + " · submitReady=" + (submit != null && submit.isEnabled())
                + " · activeStrong=" + hasStrongActiveEvidence(root)
                + " · activeEvidenceCount=" + activeEvidenceCount
                + " · micSafe=" + room.micMuted
                + " · speakerSafe=" + room.speakerMuted
                + " · audioPasses=" + audioGuardPasses
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

    private static AccessibilityNodeInfo findExactAny(AccessibilityNodeInfo root, Set<String> terms) {
        if (root == null) return null;
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