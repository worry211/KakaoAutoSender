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
                if (!Prefs.p(app).getBoolean(Prefs.KEY_ACTIVE, false)) return;

                int max = Math.min(SendScheduler.MAX_DAILY_LIMIT,
                        Math.max(1, Prefs.p(app).getInt(Prefs.KEY_MAX_PER_DAY, 8)));
                int count = Prefs.getTodayCount(app);
                if (count >= max) {
                    Prefs.setStatus(app, "오늘 전송 한도 도달 (" + count + "/" + max + ")");
                    SendScheduler.scheduleFromNow(app);
                    return;
                }

                String room = Prefs.p(app).getString(Prefs.KEY_ROOM, "");
                String message = Prefs.p(app).getString(Prefs.KEY_MESSAGE, "");

                NotificationListenerService.requestRebind(
                        new ComponentName(app, KakaoNotificationListener.class));

                boolean sent = false;
                for (int i = 0; i < 8 && !sent; i++) {
                    KakaoNotificationListener.requestRefresh();
                    sent = KakaoNotificationListener.sendToRoom(app, room, message);
                    if (!sent) {
                        try { Thread.sleep(500L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    }
                }

                if (sent) {
                    int newCount = Prefs.incrementTodayCount(app);
                    Prefs.setStatus(app, "자동전송 성공: " + room + " (오늘 " + newCount + "회)");
                } else {
                    Prefs.setStatus(app, "자동전송 실패: 현재 답장 세션 없음. 대상 방에서 새 메시지를 받아주세요.");
                }

                SendScheduler.scheduleFromNow(app);
            } finally {
                result.finish();
            }
        });
    }
}
