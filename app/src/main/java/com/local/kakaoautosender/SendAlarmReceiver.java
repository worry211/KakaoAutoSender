package com.local.kakaoautosender;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SendAlarmReceiver extends BroadcastReceiver {
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult result = goAsync();
        final Context app = context.getApplicationContext();

        executor.execute(() -> {
            try {
                Prefs.ensureLabelSchema(app);
                MultiRoomStore.ensureMigrated(app);

                if (!LicenseManager.isUsable(app)) {
                    Prefs.p(app).edit().putBoolean(Prefs.KEY_ACTIVE, false).apply();
                    SendScheduler.cancel(app);
                    Prefs.setStatus(app, "라이선스 인증 필요 · 자동전송 중단");
                    return;
                }

                if (!Prefs.p(app).getBoolean(Prefs.KEY_ACTIVE, false)) {
                    SendScheduler.cancel(app);
                    return;
                }

                NotificationListenerService.requestRebind(
                        new ComponentName(app, KakaoNotificationListener.class));
                waitForListenerAndRefresh();

                long now = System.currentTimeMillis();
                ArrayList<MultiRoomStore.Profile> due = ReliabilityTiming.due(app, now);
                if (due.isEmpty()) {
                    SendScheduler.scheduleNext(app);
                    return;
                }

                // Process exactly one room per alarm invocation. This keeps the receiver
                // short-lived and prevents multiple Kakao reply PendingIntents from being
                // fired back-to-back when several rooms become due together.
                MultiRoomStore.Profile profile = due.get(0);
                if (!profile.enabled || profile.room.trim().isEmpty() || profile.message.trim().isEmpty()) {
                    SendScheduler.scheduleNext(app);
                    return;
                }

                String visibleName = profile.title();
                boolean attempted = false;
                boolean sent = false;
                boolean skippedForLimit = false;
                String failureReason = "확인된 답장 세션 없음";

                if (!profile.unlimited() && profile.todayCount >= profile.dailyLimit) {
                    long next = MultiRoomStore.nextAfterDailyLimit(profile, System.currentTimeMillis());
                    String status = "오늘 방별 한도 도달: " + visibleName + " ("
                            + profile.todayCount + "/" + profile.dailyLimit + ")";
                    MultiRoomStore.markSkippedForLimit(app, profile.room, next, status);
                    skippedForLimit = true;
                } else {
                    attempted = true;

                    // Advance the persisted schedule before touching Kakao. If Android kills
                    // this process after the reply is accepted but before our success bookkeeping,
                    // the same due item will not immediately be sent a second time on restart.
                    long reservedNext = MultiRoomStore.computeNextAt(profile, System.currentTimeMillis());
                    MultiRoomStore.Profile reserved = profile.copy();
                    reserved.nextAt = reservedNext;
                    MultiRoomStore.upsert(app, reserved);

                    for (int i = 0; i < 3 && !sent; i++) {
                        if (!Prefs.p(app).getBoolean(Prefs.KEY_ACTIVE, false)) break;
                        sent = KakaoNotificationListener.sendToRoom(app, profile.room, profile.message);
                        if (!sent) {
                            failureReason = KakaoNotificationListener.lastSendError();
                            if (failureReason == null || failureReason.trim().isEmpty()) {
                                failureReason = "확인된 답장 세션 없음";
                            }
                            if (i < 2) {
                                KakaoNotificationListener.requestRefresh();
                                sleep(ReliabilityTiming.retryGapMillis());
                            }
                        }
                    }

                    if (sent) {
                        MultiRoomStore.markSuccess(app, profile.room, reservedNext,
                                "자동전송 성공: " + visibleName);
                    } else {
                        String recovery = KakaoNotificationListener.hasStoredBinding(app, profile.room)
                                ? "자동복구 정보 있음 · 같은 방 새 알림 대기"
                                : "새 알림에서 이 방을 다시 연결 필요";
                        MultiRoomStore.markFailure(app, profile.room, reservedNext,
                                "자동전송 실패: " + visibleName + " · " + failureReason + " · " + recovery);
                    }
                }

                if (!Prefs.p(app).getBoolean(Prefs.KEY_ACTIVE, false)) {
                    SendScheduler.cancel(app);
                    return;
                }

                long nextDue = ReliabilityTiming.nextEffectiveDueAt(app);
                if (nextDue <= 0L) {
                    SendScheduler.cancel(app);
                    return;
                }

                long nowAfter = System.currentTimeMillis();
                long scheduledAt = nextDue;
                int gapSeconds = 0;

                // If another room is already due (or is about to be due), force a 2-5s
                // cross-room gap. We schedule a new alarm instead of sleeping inside this
                // receiver, so large room sets do not hit BroadcastReceiver time limits.
                if (attempted) {
                    long gap = ReliabilityTiming.roomGapMillis();
                    long minimumAfterCurrentRoom = nowAfter + gap;
                    if (scheduledAt < minimumAfterCurrentRoom) {
                        scheduledAt = minimumAfterCurrentRoom;
                        gapSeconds = ReliabilityTiming.secondsCeil(gap);
                    }
                }

                StringBuilder status = new StringBuilder();
                if (skippedForLimit) {
                    status.append("예약 실행 · 한도대기 · ").append(visibleName);
                } else if (sent) {
                    status.append("예약 실행 · 성공 · ").append(visibleName);
                } else {
                    status.append("예약 실행 · 실패 · ").append(visibleName);
                }
                if (gapSeconds > 0) status.append(" · 다음 방 ").append(gapSeconds).append("초 후");
                Prefs.setStatus(app, status.toString());
                SendScheduler.scheduleAt(app, scheduledAt);
            } catch (Throwable t) {
                Prefs.recordFailure(app);
                Prefs.setStatus(app, "자동전송 내부 오류: " + t.getClass().getSimpleName());
                SendScheduler.scheduleNext(app);
            } finally {
                result.finish();
            }
        });
    }

    private static void waitForListenerAndRefresh() {
        for (int i = 0; i < 8; i++) {
            if (KakaoNotificationListener.isListenerConnected()) break;
            sleep(250L);
        }
        KakaoNotificationListener.requestRefresh();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
