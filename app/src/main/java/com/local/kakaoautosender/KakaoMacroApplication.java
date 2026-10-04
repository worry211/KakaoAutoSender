package com.local.kakaoautosender;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.HashSet;

public class KakaoMacroApplication extends Application
    implements Application.ActivityLifecycleCallbacks {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final HashSet<Activity> activities = new HashSet<>();
  private Activity foreground;
  private boolean checking;
  private final Runnable heartbeat = () -> checkForeground();

  @Override
  public void onCreate() {
    super.onCreate();
    AppIntegrity.initialize(this);
    if (!AppIntegrity.isAuthentic(this)) AppIntegrity.trip(this);
    Prefs.p(this)
        .edit()
        .remove("license_text_v1")
        .remove("license_install_id_v1")
        .remove("license_max_wall_time_v1")
        .apply();
    registerActivityLifecycleCallbacks(this);
  }

  private void checkForeground() {
    handler.removeCallbacks(heartbeat);
    if (foreground == null || checking) return;
    if (!AppIntegrity.isAuthentic(this)) {
      AppIntegrity.trip(this);
      routeIntegrity(foreground);
      return;
    }
    checking = true;
    LicenseManager.checkAsync(
        this,
        v -> {
          checking = false;
          if (foreground != null && !(foreground instanceof LicenseActivity) && !v.valid)
            routeLockout();
          else if (foreground instanceof LicenseActivity && v.valid) {
            Activity a = foreground;
            a.startActivity(new Intent(a, MainActivityV4.class));
            a.finish();
          }
          if (foreground != null)
            handler.postDelayed(heartbeat, LicenseManager.heartbeatMillis(this));
        });
  }

  void routeLockout() {
    if (foreground == null || foreground instanceof LicenseActivity) return;
    Activity current = foreground;
    LicenseManager.route(current);
    for (Activity a : new HashSet<>(activities)) if (!(a instanceof LicenseActivity)) a.finish();
  }

  private void routeIntegrity(Activity current) {
    if (current == null || current instanceof IntegrityGateActivity) return;
    Intent i = new Intent(current, IntegrityGateActivity.class);
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    current.startActivity(i);
    for (Activity a : new HashSet<>(activities))
      if (!(a instanceof IntegrityGateActivity)) a.finish();
  }

  @Override
  public void onActivityResumed(Activity a) {
    foreground = a;
    PremiumChrome.polish(a);
    if (!AppIntegrity.isAuthentic(this)) {
      AppIntegrity.trip(this);
      routeIntegrity(a);
      return;
    }
    LicenseManager.Verification cached = LicenseManager.verifyStored(this);
    if (!(a instanceof LicenseActivity)
        && !(a instanceof IntegrityGateActivity)
        && !cached.valid
        && !"NETWORK".equals(cached.state)) routeLockout();
    checkForeground();
  }

  @Override
  public void onActivityPaused(Activity a) {
    if (foreground == a) foreground = null;
    handler.removeCallbacks(heartbeat);
  }

  @Override
  public void onActivityCreated(Activity a, Bundle b) {
    activities.add(a);
    PremiumChrome.applyWindow(a);
  }

  @Override
  public void onActivityDestroyed(Activity a) {
    activities.remove(a);
  }

  @Override
  public void onActivityStarted(Activity a) {}

  @Override
  public void onActivityStopped(Activity a) {}

  @Override
  public void onActivitySaveInstanceState(Activity a, Bundle b) {}
}
