package com.local.kakaoautosender;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PhotoLoopReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null) return;
    String run = intent.getStringExtra("run");
    if (PhotoLoopAlarm.STOP.equals(intent.getAction())) {
      PhotoKeyboardService.stopLoop(context, run, "사용자가 사진 예약을 중지했습니다.");
      return;
    }
    if (!PhotoLoopAlarm.TICK.equals(intent.getAction())) return;
    if (!PhotoKeyboardService.hasActiveLoop()) {
      PhotoLoopStore.stop(context, "입력 연결이 없어 사진 예약 중지 · 채팅방에서 다시 시작하세요.");
      PhotoLoopAlarm.cancel(context);
      return;
    }
    PendingResult pending = goAsync();
    Handler main = new Handler(Looper.getMainLooper());
    PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    PowerManager.WakeLock wake =
        power == null
            ? null
            : power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KakaoMacro:PhotoDispatch");
    if (wake != null) wake.acquire(10_000L);
    AtomicBoolean finished = new AtomicBoolean();
    Runnable done =
        () -> {
          if (!finished.compareAndSet(false, true)) return;
          if (wake != null && wake.isHeld()) wake.release();
          pending.finish();
        };
    Runnable timeout =
        () -> {
          if (finished.get()) return;
          PhotoKeyboardService.stopLoop(context, run, "사진 저장소 또는 입력 응답 시간 초과 · 중복 방지를 위해 중지");
          done.run();
        };
    main.postDelayed(timeout, 8_000L);
    try {
      PhotoKeyboardService.dispatchLoop(
          run,
          intent.getLongExtra("when", 0),
          () -> {
            main.removeCallbacks(timeout);
            done.run();
          });
    } catch (RuntimeException failure) {
      PhotoKeyboardService.stopLoop(context, run, "사진 예약 내부 오류로 중지 · 자동 재시도 없음");
      main.removeCallbacks(timeout);
      done.run();
    }
  }
}
