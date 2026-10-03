package com.local.kakaoautosender;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.notification.NotificationListenerService;

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
                // v0.5 안전 라우팅 마이그레이션이 아직 안 됐다면 전송 판단보다 먼저 실행한다.
                Prefs.ensureLabelSchema(app);

                if (!Prefs.p(app).getBoolean(Prefs.KEY_ACTIVE, false)) {
                    SendScheduler.cancel(app);
                    return;
                }

                String room = Prefs.p(app).getString(Prefs.KEY_ROOM, "");
                String message = Prefs.p(app).getString(Prefs.KEY_MESSAGE, "");
                if (room == null) room = "";
                if (message == null) message = "";
                room = room.trim();

                if (room.isEmpty() || message.trim().isEmpty()) {
                    Prefs.p(app).edit().putBoolean(Prefs.KEY_ACTIVE, false).apply();
                    SendScheduler.cancel(app);
                    Prefs.recordFailure(app);
                    Prefs.setStatus(app, "자동전송 중단: 방 또는 메시지 설정이 비어 있음");
                    return;
                }

                int max = Math.min(SendScheduler.MAX_DAILY_LIMIT,
                        Math.max(1, Prefs.p(app).getInt(Prefs.KEY_MAX_PER_DAY, 8)));
                int count = Prefs.getTodayCount(app);
                if (count >= max) {
                    Prefs.setStatus(app, "오늘 전송 한도 도달 (" + count + "/" + max + ") · 다음 주기에서 다시 확인");
                    rescheduleIfActive(app);
                    return;
                }

                NotificationListenerService.requestRebind(
                        new ComponentName(app, KakaoNotificationListener.class));

                boolean sent = false;
                String failureReason = "확인된 답장 세션 없음";
                for (int i = 0; i < 3 && !sent; i++) {
                    sent = KakaoNotificationListener.sendToRoom(app, room, message);
                    if (!sent) {
                        failureReason = KakaoNotificationListener.lastSendError();
                        if (failureReason == null || failureReason.trim().isEmpty()) {
                            failureReason = "확인된 답장 세션 없음";
                        }
                        try {
                            Thread.sleep(500L);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                if (sent) {
                    int newCount = Prefs.incrementTodayCount(app);
                    Prefs.setStatus(app, "자동전송 성공: " + room + " (오늘 " + newCount + "회)");
                } else {
                    int streak = Prefs.recordFailure(app);
                    String recovery = KakaoNotificationListener.hasStoredBinding(app, room)
                            ? "검증된 자동복구키 있음 · 같은 방의 새 알림으로 세션 복구 대기"
                            : "대상 방의 새 알림을 받은 뒤 직접 다시 연결 필요";
                    Prefs.setStatus(app, "자동전송 실패: " + failureReason + " · 연속 " + streak + "회 · " + recovery);
                }

                rescheduleIfActive(app);
            } catch (Throwable t) {
                int streak = Prefs.recordFailure(app);
                Prefs.setStatus(app, "자동전송 내부 오류: " + t.getClass().getSimpleName() + " · 연속 " + streak + "회");
                rescheduleIfActive(app);
            } finally {
                result.finish();
            }
        });
    }

    private static void rescheduleIfActive(Context context) {
        if (Prefs.p(context).getBoolean(Prefs.KEY_ACTIVE, false)) {
            SendScheduler.scheduleFromNow(context);
        } else {
            SendScheduler.cancel(context);
        }
    }
}
