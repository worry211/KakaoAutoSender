package com.local.kakaovoiceroom;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

public class WakeActivity extends Activity {
    static final String EXTRA_PROBE = "probe_only";
    private static final String KAKAO_PACKAGE = "com.kakao.talk";
    private static final long USER_BUSY_RETRY_MS = 5L * 60L * 1000L;

    private PowerManager.WakeLock wakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean probe = getIntent() != null && getIntent().getBooleanExtra(EXTRA_PROBE, false);
        boolean wasInteractive = isDeviceInteractive();

        if (Build.VERSION.SDK_INT >= 27) {
            setTurnScreenOn(true);
            setShowWhenLocked(false);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        acquireWakeLock();

        if (!VoiceRoomStore.managerActive(this) && !probe) {
            AudioGuard.restore(this);
            finishSafely();
            return;
        }

        String roomId = getIntent() == null ? "" : getIntent().getStringExtra(VoiceRoomScheduler.EXTRA_ROOM_ID);
        VoiceRoomStore.Room room = VoiceRoomStore.get(this, roomId);
        if (room == null || (!room.enabled && !probe)) {
            VoiceRoomStore.clearPending(this);
            AudioGuard.restore(this);
            VoiceRoomScheduler.scheduleNext(this);
            finishSafely();
            return;
        }

        String pendingId = VoiceRoomStore.pendingRoomId(this);
        if (!pendingId.isEmpty()) {
            if (VoiceRoomStore.hasFreshPending(this)) {
                VoiceRoomStore.setLastStatus(this, "이전 보이스룸 점검 처리 중 · 중복 실행 방지");
                if (!probe) {
                    room.nextCheckAt = Math.max(room.nextCheckAt,
                            VoiceRoomStore.pendingAt(this) + VoiceRoomStore.PENDING_TIMEOUT_MS + 5_000L);
                    VoiceRoomStore.update(this, room);
                    VoiceRoomScheduler.scheduleNext(this);
                }
                finishSafely();
                return;
            }

            boolean staleProbe = VoiceRoomStore.isProbePending(this);
            VoiceRoomStore.Room stale = VoiceRoomStore.get(this, pendingId);
            VoiceRoomStore.clearPending(this);
            AudioGuard.restore(this);
            if (stale != null) {
                stale.stageStartedAt = 0L;
                if (staleProbe) {
                    stale.status = "PROBE_ERROR";
                    stale.lastError = "안전 인식 점검이 응답 없이 종료됨";
                    VoiceRoomStore.update(this, stale);
                    VoiceRoomStore.setLastStatus(this, stale.title + " · 안전 인식 점검 실패");
                } else {
                    stale.failures += 1;
                    stale.status = "ERROR";
                    stale.lastError = "이전 자동화 작업이 응답 없이 종료됨";
                    stale.nextCheckAt = System.currentTimeMillis() + KakaoUiPolicy.retryDelayMs(stale.failures);
                    VoiceRoomStore.update(this, stale);
                    VoiceRoomStore.setLastStatus(this, stale.title + " · 이전 작업 복구 후 재시도 예정");
                }
            }
            VoiceRoomScheduler.scheduleNext(this);
            if (!probe) {
                finishSafely();
                return;
            }
        }

        KeyguardManager keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isDeviceLocked()) {
            room.status = probe ? "PROBE_ERROR" : "WAITING_UNLOCK";
            room.stageStartedAt = 0L;
            room.lastError = probe ? "안전 점검 전 휴대폰 잠금 해제가 필요함" : "휴대폰 잠금 해제가 필요함";
            if (!probe) room.nextCheckAt = System.currentTimeMillis() + 5L * 60L * 1000L;
            VoiceRoomStore.update(this, room);
            VoiceRoomStore.clearPending(this);
            AudioGuard.restore(this);
            VoiceRoomStore.setLastStatus(this, room.title + " · 잠금 해제 필요");
            VoiceRoomScheduler.scheduleNext(this);
            finishSafely();
            return;
        }

        // Do not steal the foreground from someone actively using their phone for a routine
        // scheduled re-check. NEW/CHECK_DUE are direct setup/manual actions and are allowed to
        // run immediately; long-running ACTIVE/ERROR maintenance waits until the phone is idle.
        String currentStatus = room.status == null ? "" : room.status;
        boolean directAction = "NEW".equals(currentStatus) || "CHECK_DUE".equals(currentStatus);
        if (!probe && wasInteractive && !directAction) {
            room.status = currentStatus.isEmpty() ? "CHECK_DUE" : currentStatus;
            room.stageStartedAt = 0L;
            room.lastError = "";
            room.nextCheckAt = System.currentTimeMillis() + USER_BUSY_RETRY_MS;
            VoiceRoomStore.update(this, room);
            VoiceRoomStore.setLastStatus(this,
                    room.title + " · 휴대폰 사용 중이라 자동 점검을 5분 미룸");
            VoiceRoomScheduler.scheduleNext(this);
            finishSafely();
            return;
        }

        launchKakao(room, probe);
    }

    private void launchKakao(VoiceRoomStore.Room room, boolean probe) {
        long now = System.currentTimeMillis();
        room.status = probe ? "PROBE_OPENING_KAKAO" : "OPENING_KAKAO";
        room.stageStartedAt = now;
        room.lastError = "";
        room.lastDiagnostic = "launch=scheduled";
        if (!probe) room.nextCheckAt = now + VoiceRoomStore.PENDING_TIMEOUT_MS + 5_000L;
        VoiceRoomStore.update(this, room);
        VoiceRoomStore.setPending(this, room.id,
                probe ? VoiceRoomStore.MODE_PROBE : VoiceRoomStore.MODE_AUTO,
                VoiceRoomStore.ENTRY_UNKNOWN);
        VoiceRoomStore.setLastStatus(this,
                room.title + (probe ? " · 안전 인식 점검 시작" : " · 카카오톡 여는 중"));

        List<Intent> candidates = buildKakaoLaunchCandidates(room);
        if (candidates.isEmpty()) {
            fail(room, probe, "카카오톡 실행 경로를 찾지 못함");
            return;
        }

        if (!probe) AudioGuard.muteForTask(this);

        Exception lastError = null;
        for (int i = 0; i < candidates.size(); i++) {
            Intent target = candidates.get(i);
            String entry = Intent.ACTION_VIEW.equals(target.getAction())
                    ? VoiceRoomStore.ENTRY_DEEPLINK : VoiceRoomStore.ENTRY_LAUNCHER;
            try {
                VoiceRoomStore.updatePendingEntry(this, entry);
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(target);
                VoiceRoomStore.setLastStatus(this,
                        room.title + " · 카카오톡 실행 성공 (경로 " + (i + 1) + ")");
                if (!probe) VoiceRoomScheduler.scheduleNext(this);
                getWindow().getDecorView().postDelayed(this::finishSafely, 3_000L);
                return;
            } catch (Exception e) {
                lastError = e;
            }
        }

        fail(room, probe, "카카오톡 실행 실패 · " + describe(lastError));
    }

    private List<Intent> buildKakaoLaunchCandidates(VoiceRoomStore.Room room) {
        ArrayList<Intent> out = new ArrayList<>();

        if (KakaoUiPolicy.isOpenChatUrl(room.roomUrl)) {
            try {
                Intent deepLink = new Intent(Intent.ACTION_VIEW, Uri.parse(room.roomUrl.trim()));
                deepLink.setPackage(KAKAO_PACKAGE);
                out.add(deepLink);
            } catch (Exception ignored) {}
        }

        try {
            Intent launcher = getPackageManager().getLaunchIntentForPackage(KAKAO_PACKAGE);
            if (launcher != null) out.add(launcher);
        } catch (Exception ignored) {}

        try {
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            main.setPackage(KAKAO_PACKAGE);
            if (main.resolveActivity(getPackageManager()) != null) out.add(main);
        } catch (Exception ignored) {}

        return out;
    }

    private boolean isDeviceInteractive() {
        try {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return power != null && power.isInteractive();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String describe(Exception error) {
        if (error == null) return "원인 미확인";
        String name = error.getClass().getSimpleName();
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return name;
        message = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (message.length() > 120) message = message.substring(0, 120);
        return name + ": " + message;
    }

    private void fail(VoiceRoomStore.Room room, boolean probe, String error) {
        room.status = probe ? "PROBE_ERROR" : "ERROR";
        room.stageStartedAt = 0L;
        room.lastError = error;
        if (!probe) {
            room.failures += 1;
            room.nextCheckAt = System.currentTimeMillis() + KakaoUiPolicy.retryDelayMs(room.failures);
        }
        VoiceRoomStore.update(this, room);
        VoiceRoomStore.clearPending(this);
        AudioGuard.restore(this);
        VoiceRoomStore.setLastStatus(this, room.title + " · " + error);
        VoiceRoomScheduler.scheduleNext(this);
        finishSafely();
    }

    private void acquireWakeLock() {
        try {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power == null) return;
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceRoom:Wake");
            wakeLock.acquire(45_000L);
        } catch (Exception ignored) {}
    }

    private void finishSafely() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        if (!isFinishing()) finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onDestroy() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
