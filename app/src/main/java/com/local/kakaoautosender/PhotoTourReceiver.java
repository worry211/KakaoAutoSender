package com.local.kakaoautosender;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class PhotoTourReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent intent) {
    if (intent == null) return;
    if (PhotoTourAlarm.STOP.equals(intent.getAction()))
      PhotoTourController.stopRun(c, intent.getStringExtra("run"));
    else if (PhotoTourAlarm.TICK.equals(intent.getAction()))
      PhotoTourController.tick(c, intent.getStringExtra("run"));
  }
}
