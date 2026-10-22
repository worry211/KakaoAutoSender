package com.local.kakaoautosender;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class PhotoLoopAlarm {
  static final String TICK = "com.local.kakaoautosender.PHOTO_LOOP_TICK";
  static final String STOP = "com.local.kakaoautosender.PHOTO_LOOP_STOP";
  private static final int ID = 7308;
  private static final String CHANNEL = "photo_keyboard_schedule";

  private PhotoLoopAlarm() {}

  static PendingIntent pending(Context c, String action, String run, long next) {
    return PendingIntent.getBroadcast(
        c,
        action.equals(STOP) ? ID + 1 : ID,
        new Intent(c, PhotoLoopReceiver.class)
            .setAction(action)
            .putExtra("run", run)
            .putExtra("when", next),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  static void schedule(Context c, PhotoLoopSession.Plan plan) {
    AlarmManager manager = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    if (manager == null) throw new IllegalStateException("Alarm service unavailable");
    PendingIntent intent = pending(c, TICK, plan.run, plan.next);
    try {
      if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms())
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.next, intent);
      else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.next, intent);
    } catch (SecurityException denied) {
      manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.next, intent);
    }
    show(c, plan);
  }

  static void cancel(Context c) {
    AlarmManager manager = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    if (manager != null) manager.cancel(pending(c, TICK, "", 0));
    NotificationManager notifications =
        (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (notifications != null) notifications.cancel(ID);
  }

  private static void show(Context c, PhotoLoopSession.Plan plan) {
    NotificationManager manager =
        (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null) return;
    manager.createNotificationChannel(
        new NotificationChannel(CHANNEL, "사진 키보드 예약", NotificationManager.IMPORTANCE_LOW));
    if (Build.VERSION.SDK_INT >= 33
        && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
    Notification notification =
        new Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("사진 키보드 예약 실행 중")
            .setContentText(
                "다음 요청 "
                    + new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date(plan.next))
                    + (plan.screenOff ? " · 화면 꺼짐 시도 켜짐" : " · 화면 켜짐 전용"))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    c,
                    ID,
                    new Intent(c, PhotoKeyboardSetupActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
            .addAction(
                new Notification.Action.Builder(null, "사진 예약 중지", pending(c, STOP, plan.run, 0))
                    .build())
            .build();
    try {
      manager.notify(ID, notification);
    } catch (SecurityException ignored) {
    }
  }
}
