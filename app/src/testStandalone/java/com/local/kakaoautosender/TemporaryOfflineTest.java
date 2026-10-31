package com.local.kakaoautosender;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class TemporaryOfflineTest {
  @Test public void independentPackageRetainsLocalAdbPermission() throws Exception {
    Context c=RuntimeEnvironment.getApplication();
    assertEquals("com.local.kakaoautosender.temporaryrc4",c.getPackageName());
    assertTrue(java.util.Arrays.asList(c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions).contains("android.permission.INTERNET"));
  }
  @Test public void noStoredLoginOrExpiredCommercialStateIsRequired() {
    Context c=RuntimeEnvironment.getApplication();
    c.getSharedPreferences("entitlement_v2",0).edit().clear().putString("state","EXPIRED").commit();
    assertTrue(LicenseManager.verifyStored(c).valid);
    assertTrue(LicenseManager.validate(c).valid);
    assertTrue(LicenseManager.updateNotice(c).isEmpty());
  }
  @Test public void launcherEntersDashboardWithoutLogin() {
    IntegrityGateActivity a=Robolectric.buildActivity(IntegrityGateActivity.class).create().get();
    Intent next=Shadows.shadowOf(a).getNextStartedActivity();
    assertNotNull(next);
    assertEquals(MainActivityV4.class.getName(),next.getComponent().getClassName());
  }
  @Test public void stopStillBlocksScheduledDispatch() {
    Context c=RuntimeEnvironment.getApplication();
    Prefs.p(c).edit().putBoolean(Prefs.KEY_ACTIVE,true).commit();
    DeliveryGate.scheduled(true);
    try { assertTrue(DeliveryGate.allowed(c)); DeliveryGate.stop(c); assertFalse(DeliveryGate.allowed(c)); }
    finally { DeliveryGate.scheduled(false); }
  }
}
