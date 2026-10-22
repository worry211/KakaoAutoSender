package com.local.kakaoautosender;

import android.app.*;
import android.content.Context;
import android.content.Intent;

final class PhotoTourAlarm {
  static final String TICK = "com.local.kakaoautosender.PHOTO_TOUR_TICK";
  static final String STOP = "com.local.kakaoautosender.PHOTO_TOUR_STOP";
  private static final int ID = 7310;

  private static PendingIntent pending(Context c, String action, String run) {
    return PendingIntent.getBroadcast(
        c,
        STOP.equals(action) ? ID + 1 : ID,
        new Intent(c, PhotoTourReceiver.class).setAction(action).putExtra("run", run),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  static void schedule(Context c, String run, long when) {
    AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    if (alarms == null) throw new IllegalStateException("Alarm unavailable");
    PendingIntent p = pending(c, TICK, run);
    try {
      if (android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
      else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
    } catch (SecurityException e) {
      alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
    }
  }

  static void cancel(Context c) {
    AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    if (alarms != null) alarms.cancel(pending(c, TICK, ""));
    NotificationManager n = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (n != null) n.cancel(ID);
  }

  static void show(Context c, String text, String run) {
    if (!PhotoTourController.active()) return;
    NotificationManager manager =
        (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null) return;
    manager.createNotificationChannel(
        new NotificationChannel("photo_tour", "사진 여러 방 순회", NotificationManager.IMPORTANCE_LOW));
    if (android.os.Build.VERSION.SDK_INT >= 33
        && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
    try {
      manager.notify(
          ID,
          new Notification.Builder(c, "photo_tour")
              .setSmallIcon(android.R.drawable.ic_menu_send)
              .setContentTitle("사진 여러 방 전송")
              .setContentText(text)
              .setVisibility(Notification.VISIBILITY_PRIVATE)
              .setOngoing(true)
              .setContentIntent(
                  PendingIntent.getActivity(
                      c,
                      ID,
                      new Intent(c, PhotoTourActivity.class),
                      PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
              .addAction(
                  new Notification.Action.Builder(null, "전체 중지", pending(c, STOP, run)).build())
              .build());
    } catch (SecurityException ignored) {
    }
  }
}
