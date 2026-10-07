package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SaleReadinessTest {
  private Context context;

  @Before
  public void prepare() throws Exception {
    context = RuntimeEnvironment.getApplication();
    Prefs.p(context).edit().clear().commit();
    context.getSharedPreferences("entitlement_v2", 0).edit().clear().commit();
    Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 7);
    context
        .getSharedPreferences("entitlement_v2", 0)
        .edit()
        .putString("state", "ACTIVE")
        .putLong("validated_elapsed", SystemClock.elapsedRealtime())
        .putInt("validated_boot", 7)
        .putLong("server_time", 100000)
        .putLong("expiry", 0)
        .putLong("grace", 600)
        .commit();
    KakaoNotificationListener.clearRuntimeAndBindings(context);
  }

  @Test
  public void activationKeyHelperExtractsKeyFromSellerMessage() {
    String key = "KM-2345-6789-ABCD-EFGH-JKMN-PQRS";
    assertEquals(
        key,
        LicenseActivity.normalizeActivationKeyInput(
            "구매 감사합니다.\n인증 키: " + key.toLowerCase() + "\n앱에서 붙여넣어 주세요."));
    assertTrue(LicenseActivity.isActivationKeyFormat(key));
  }

  @Test
  public void activationKeyHelperCompactsWhitespaceButRejectsBadAlphabet() {
    assertEquals(
        "KM-2345-6789-ABCD-EFGH-JKMN-PQRS",
        LicenseActivity.normalizeActivationKeyInput(" km-2345-6789-ABCD- EFGH-JKMN-PQRS \n"));
    assertFalse(
        LicenseActivity.isActivationKeyFormat(
            LicenseActivity.normalizeActivationKeyInput("KM-2345-6789-ABCD-EFGH-JKMN-PQRO")));
  }

  @Test
  public void imageMimeMustBeConcrete() {
    assertEquals(
        "image/png", RoomEditorActivity.resolveConcreteImageMime("image/png", "anything.bin"));
    assertEquals(
        "image/jpeg", RoomEditorActivity.resolveConcreteImageMime("IMAGE/JPEG", "anything.bin"));
    assertNull(RoomEditorActivity.resolveConcreteImageMime("image/*", "photo.unknownext"));
    assertNull(
        RoomEditorActivity.resolveConcreteImageMime(
            "application/octet-stream", "photo.unknownext"));
  }

  @Test
  public void textSendFailsClosedWhenOnlyDataRemoteInputExists() throws Exception {
    installTarget(false, true);
    assertFalse(
        KakaoMessageSender.send(context, "room-a", "hello", new RoomMediaStore.Media("", "", "")));
    assertTrue(KakaoMessageSender.lastError().contains("텍스트"));
  }

  @Test
  public void textSendWorksOnlyThroughFreeFormRemoteInput() throws Exception {
    installTarget(true, true);
    assertTrue(
        KakaoMessageSender.send(context, "room-a", "hello", new RoomMediaStore.Media("", "", "")));
    assertEquals("", KakaoMessageSender.lastError());
  }

  @Test
  public void freeFormFilterExcludesDataOnlyInput() {
    RemoteInput dataOnly =
        new RemoteInput.Builder("data")
            .setAllowFreeFormInput(false)
            .setAllowDataType("image/png", true)
            .build();
    RemoteInput text = new RemoteInput.Builder("text").setAllowFreeFormInput(true).build();
    RemoteInput[] filtered = KakaoMessageSender.freeFormInputs(new RemoteInput[] {dataOnly, text});
    assertEquals(1, filtered.length);
    assertEquals("text", filtered[0].getResultKey());
  }

  @SuppressWarnings("unchecked")
  private void installTarget(boolean freeForm, boolean imageData) throws Exception {
    java.lang.reflect.Field field = KakaoNotificationListener.class.getDeclaredField("sessions");
    field.setAccessible(true);
    Map<String, KakaoNotificationListener.ReplyTarget> sessions =
        (Map<String, KakaoNotificationListener.ReplyTarget>) field.get(null);

    RemoteInput.Builder builder = new RemoteInput.Builder("reply").setAllowFreeFormInput(freeForm);
    if (imageData) builder.setAllowDataType("image/png", true);
    PendingIntent pendingIntent =
        PendingIntent.getBroadcast(
            context, 404, new Intent("SALE_READINESS_REPLY"), PendingIntent.FLAG_UPDATE_CURRENT);
    sessions.put(
        "room-a",
        new KakaoNotificationListener.ReplyTarget(
            "room-a",
            pendingIntent,
            new RemoteInput[] {builder.build()},
            0,
            0,
            "sale-test",
            "",
            "",
            "room-a",
            3,
            "test",
            new ArrayList<>(),
            true));
    java.lang.reflect.Field recent =
        KakaoNotificationListener.class.getDeclaredField("recentTargets");
    recent.setAccessible(true);
    ((Map<String, KakaoNotificationListener.ReplyTarget>) recent.get(null))
        .put("sale-test", sessions.get("room-a"));
  }
}
