package com.local.kakaoautosender;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && Prefs.p(context).getBoolean(Prefs.KEY_ACTIVE, false)) {
            SendScheduler.scheduleFromNow(context);
            Prefs.setStatus(context, "재부팅 후 자동전송 예약 복구됨");
        }
    }
}
