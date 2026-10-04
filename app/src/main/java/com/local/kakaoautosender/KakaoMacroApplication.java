package com.local.kakaoautosender;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public class KakaoMacroApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Activity currentActivity;
    private final Runnable guard = new Runnable() {
        @Override public void run() {
            Activity a = currentActivity;
            if (a != null && !(a instanceof LicenseActivity) && !LicenseManager.isUsable(a)) {
                Prefs.p(a).edit().putBoolean(Prefs.KEY_ACTIVE, false).apply();
                SendScheduler.cancel(a);
                Intent i = new Intent(a, LicenseActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
                a.finish();
            }
            handler.postDelayed(this, 15_000L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
        handler.post(guard);
    }

    @Override public void onActivityResumed(Activity activity) {
        currentActivity = activity;
        if (!(activity instanceof LicenseActivity) && !LicenseManager.isUsable(activity)) {
            Prefs.p(activity).edit().putBoolean(Prefs.KEY_ACTIVE, false).apply();
            SendScheduler.cancel(activity);
            Intent i = new Intent(activity, LicenseActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            activity.startActivity(i);
            activity.finish();
        }
    }

    @Override public void onActivityPaused(Activity activity) {
        if (currentActivity == activity) currentActivity = null;
    }

    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
