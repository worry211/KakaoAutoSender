package com.local.kakaovoiceroom;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class VoiceRoomAccessibilityService extends AccessibilityService {
    private static final String KAKAO_PACKAGE = "com.kakao.talk";
    private static final long JOB_TIMEOUT_MS = 60_000L;
    private static final long STEP_DEBOUNCE_MS = 350L;
    private static final long FOLLOW_UP_MS = 650L;

    private static final List<String> ROOM_READY_TERMS = Arrays.asList(
            "메시지 입력", "메시지를 입력", "채팅 입력", "메시지 보내기");
    private static final List<String> MORE_TERMS = Arrays.asList("더보기", "메뉴", "+");
    private static final List<String> VOICE_TERMS = Arrays.asList("보이스룸");
    private static final List<String> CREATE_TERMS = Arrays.asList(
            "보이스룸 만들기", "보이스룸 시작", "보이스룸 열기", "시작하기");
    private static final List<String> CONFIRM_TERMS = Arrays.asList("시작", "만들기");
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
            if (room != null) fail(room, "카카오톡 화면 인식 제한시간 초과");
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
        if (pendingAt > 0 && now - pendingAt > JOB_TIMEOUT_MS) {
            fail(room, "카카오톡 화면 인식 제한시간 초과");
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            scheduleFollowUp();
            return;
        }

        try {
            CharSequence rootPackage = root.getPackageName();
            if (rootPackage == null || !KAKAO_PACKAGE.contentEquals(rootPackage)) {
                // A delayed follow-up may run after the user/app returned to VoiceRoom Manager.
                // Never inspect or click a non-Kakao window.
                scheduleFollowUp();
                return;
            }

            boolean titleVisible = containsExact(root, room.title);
            boolean roomScreen = titleVisible && containsAny(root, ROOM_READY_TERMS);
            String status = room.status == null ? "" : room.status;
            boolean openingRoom = "OPENING_ROOM".equals(status)
                    || "PROBE_OPENING_ROOM".equals(status);
            boolean roomMenu = "ROOM_MENU".equals(status) || "PROBE_ROOM_MENU".equals(status);
            boolean voiceMenu = "VOICE_MENU".equals(status) || "PROBE_VOICE_MENU".equals(status);
            boolean creating = "CREATING".equals(status);
            boolean modalProgress = roomMenu || voiceMenu || creating;

            if (!roomScreen && !modalProgress) {
                // Once we clicked a room, wait for the room screen instead of clicking the
                // same title again on every Accessibility event/follow-up tick.
                if (openingRoom) {
                    scheduleFollowUp();
                    return;
                }

                AccessibilityNodeInfo roomNode = findExact(root, room.title);
                if (roomNode != null && clickNodeOrParent(roomNode)) {
                    room.status = probe ? "PROBE_OPENING_ROOM" : "OPENING_ROOM";
                    room.lastError = "";
                    VoiceRoomStore.update(this, room);
                    VoiceRoomStore.setLastStatus(this, room.title + " · 대상 방 진입 중");
                    scheduleFollowUp();
                } else {
                    scheduleFollowUp();
                }
                return;
            }

            if (roomScreen && !modalProgress) {
                room.status = probe ? "PROBE_ROOM_VERIFIED" : "ROOM_VERIFIED";
                room.lastError = "";
                VoiceRoomStore.update(this, room);
                VoiceRoomStore.setLastStatus(this, room.title + " · 대상 방 확인 완료");

                if (isActiveVoiceRoom(root)) {
                    if (probe) finishProbe(room, "기존 보이스룸 활성 상태 인식 성공");
                    else markActive(room, now);
                    return;
                }

                if (clickAny(root, MORE_TERMS)) {
                    room.status = probe ? "PROBE_ROOM_MENU" : "ROOM_MENU";
                    VoiceRoomStore.update(this, room);
                    VoiceRoomStore.setLastStatus(this, room.title + " · 방 메뉴 확인 중");
                    scheduleFollowUp();
                } else {
                    scheduleFollowUp();
                }
                return;
            }

            if (roomMenu) {
                if (isActiveVoiceRoom(root)) {
                    if (probe) finishProbe(room, "기존 보이스룸 활성 상태 인식 성공");
                    else markActive(room, now);
                    return;
                }
                if (clickAny(root, VOICE_TERMS)) {
                    room.status = probe ? "PROBE_VOICE_MENU" : "VOICE_MENU";
                    room.lastError = "";
                    VoiceRoomStore.update(this, room);
                    VoiceRoomStore.setLastStatus(this, room.title + " · 보이스룸 메뉴 진입");
                    scheduleFollowUp();
                    return;
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

                if (containsAny(root, CREATE_TERMS)) {
                    if (probe) {
                        finishProbe(room, "보이스룸 생성 화면까지 안전하게 인식 성공");
                        return;
                    }
                    if (clickAny(root, CREATE_TERMS)) {
                        room.status = "CREATING";
                        room.lastError = "";
                        VoiceRoomStore.update(this, room);
                        VoiceRoomStore.setLastStatus(this, room.title + " · 보이스룸 생성 확인 중");
                        scheduleFollowUp();
                        return;
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
                    scheduleFollowUp();
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

    private boolean isActiveVoiceRoom(AccessibilityNodeInfo root) {
        if (containsAny(root, STRONG_ACTIVE_TERMS)) return true;
        return containsAny(root, SPEAKER_TERMS) && containsAny(root, LISTENER_TERMS);
    }

    private void finishProbe(VoiceRoomStore.Room room, String message) {
        room.status = "PROBE_OK";
        room.lastError = "";
        VoiceRoomStore.update(this, room);
        VoiceRoomStore.setLastStatus(this,
                room.title + " · " + message + " · 생성 버튼은 누르지 않음");
        finishPending();
    }

    private void markActive(VoiceRoomStore.Room room, long now) {
        boolean createdByUs = "CREATING".equals(room.status);
        if (createdByUs) room.startedAt = now;
        room.failures = 0;
        room.lastError = "";
        room.status = room.startedAt <= 0L ? "ACTIVE_UNKNOWN_START" : "ACTIVE";
        room.nextCheckAt = VoiceRoomTiming.nextActiveCheck(room.startedAt, now);
        VoiceRoomStore.update(this, room);
        VoiceRoomStore.setLastStatus(this, room.title + " · 보이스룸 활성 확인");
        finishPending();
    }

    private void fail(VoiceRoomStore.Room room, String error) {
        boolean probe = VoiceRoomStore.isProbePending(this);
        if (probe) {
            room.status = "PROBE_ERROR";
            room.lastError = error;
        } else {
            room.failures += 1;
            room.status = "ERROR";
            room.lastError = error;
            room.nextCheckAt = System.currentTimeMillis() + VoiceRoomStore.ERROR_RETRY_MS;
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

    private boolean clickAny(AccessibilityNodeInfo root, List<String> terms) {
        AccessibilityNodeInfo node = findContains(root, terms);
        return node != null && clickNodeOrParent(node);
    }

    private boolean clickAnyExact(AccessibilityNodeInfo root, List<String> terms) {
        AccessibilityNodeInfo node = findExactAny(root, new HashSet<>(terms));
        return node != null && clickNodeOrParent(node);
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 5 && current != null; i++) {
            if (current.isClickable()) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            current = current.getParent();
        }
        return false;
    }

    private static boolean containsAny(AccessibilityNodeInfo root, List<String> terms) {
        return findContains(root, terms) != null;
    }

    private static boolean containsExact(AccessibilityNodeInfo root, String term) {
        return findExact(root, term) != null;
    }

    private static AccessibilityNodeInfo findExact(AccessibilityNodeInfo root, String term) {
        if (root == null || TextUtils.isEmpty(term)) return null;
        return findExactAny(root, new HashSet<>(Arrays.asList(term)));
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
        if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String text = value(node.getText());
            String desc = value(node.getContentDescription());
            for (String term : terms) {
                if ((!text.isEmpty() && text.contains(term))
                        || (!desc.isEmpty() && desc.contains(term))) return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }

    private static String value(CharSequence value) {
        return value == null ? "" : value.toString().trim();
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
