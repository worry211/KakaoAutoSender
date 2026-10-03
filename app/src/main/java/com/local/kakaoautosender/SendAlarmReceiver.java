package com.local.kakaoautosender;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.notification.NotificationListenerService;

import java.time.LocalDate;
import java.time.ZoneId;
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

                if (!Prefs.p(app).getBoolean(Prefs.KEY_ACTIVE, false)) {
                    SendScheduler.cancel(app);
                    return;
                }

                NotificationListenerService.requestRebind(
                        new ComponentName(app, KakaoNotificationListener.class));
                KakaoNotificationListener.requestRefresh();

                long now = System.currentTimeMillis();
                ArrayList<MultiRoomStore.Profile> due = MultiRoomStore.due(app, now);
                if (due.isEmpty()) {
                    SendScheduler.scheduleNext(app);
                    return;
                }

                int attempted = 0;
                int success = 0;
                int skipped = 0;
                for (MultiRoomStore.Profile profile : due) {
                    if (!Prefs.p(app).getBoolean(Prefs.KEY_ACTIVE, false)) break;
                    if (!profile.enabled || profile.room.trim().isEmpty() || profile.message.trim().isEmpty()) continue;

                    if (!profile.unlimited() && profile.todayCount >= profile.dailyLimit) {
                        long next = nextDayStart();
                        String status = "오늘 방별 한도 도달: " + profile.room + " ("
                                + profile.todayCount + "/" + profile.dailyLimit + ")";
                        MultiRoomStore.markSkippedForLimit(app, profile.room, next, status);
                        skipped++;
                        continue;
                    }

                    attempted++;
                    boolean sent = false;
                    String failureReason = "확인된 답장 세션 없음";
                    for (int i = 0; i < 2 && !sent; i++) {
                        sent = KakaoNotificationListener.sendToRoom(app, profile.room, profile.message);
                        if (!sent) {
                            failureReason = KakaoNotificationListener.lastSendError();
                            if (failureReason == null || failureReason.trim().isEmpty()) {
                                failureReason = "확인된 답장 세션 없음";
                            }
                            sleep(450L);
                        }
                    }

                    long next = System.currentTimeMillis() + profile.intervalMinutes * 60_000L;
                    if (sent) {
                        MultiRoomStore.markSuccess(app, profile.room, next,
                                "자동전송 성공: " + profile.room);
                        success++;
                    } else {
                        String recovery = KakaoNotificationListener.hasStoredBinding(app, profile.room)
                                ? "자동복구키 있음 · 같은 방 새 알림 대기"
                                : "새 알림에서 이 방을 다시 연결 필요";
                        MultiRoomStore.markFailure(app, profile.room, next,
                                "자동전송 실패: " + failureReason + " · " + recovery);
                    }

                    // 여러 방이 같은 시각에 예정돼도 PendingIntent 답장이 한꺼번에 충돌하지 않도록 짧게 분리한다.
                    sleep(700L);
                }

                Prefs.setStatus(app, "다중방 예약 실행 · 시도 " + attempted + " · 성공 " + success
                        + (skipped > 0 ? " · 한도대기 " + skipped : ""));
                SendScheduler.scheduleNext(app);
            } catch (Throwable t) {
                Prefs.recordFailure(app);
                Prefs.setStatus(app, "다중방 자동전송 내부 오류: " + t.getClass().getSimpleName());
                SendScheduler.scheduleNext(app);
            } finally {
                result.finish();
            }
        });
    }

    private static long nextDayStart() {
        return LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                .plusMinutes(1).toInstant().toEpochMilli();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
