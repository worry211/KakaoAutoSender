package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Actual Android setup-screen layout/navigation. Isolated fixture entitlement; no Kakao or network.
 */
@RunWith(AndroidJUnit4.class)
public class PhotoSetupDeviceTest {
  private Context c;
  private Instrumentation instrumentation;
  private Activity activity;
  private KakaoMacroApplication app;

  @Before
  public void setup() {
    c = ApplicationProvider.getApplicationContext();
    instrumentation = InstrumentationRegistry.getInstrumentation();
    app = (KakaoMacroApplication) c.getApplicationContext();
    instrumentation.runOnMainSync(
        () -> {
          // Avoid production heartbeat calls while testing offline screen layout with fixture
          // state.
          app.unregisterActivityLifecycleCallbacks(app);
          PhotoTourController.stop(c, "UI fixture reset");
          Prefs.p(c).edit().clear().commit();
          c.getSharedPreferences("entitlement_v2", 0)
              .edit()
              .clear()
              .putString("state", "ACTIVE")
              .putLong("validated_elapsed", SystemClock.elapsedRealtime())
              .putInt(
                  "validated_boot",
                  Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1))
              .putLong("server_time", System.currentTimeMillis() / 1000)
              .putLong("expiry", 0)
              .putLong("grace", 600)
              .commit();
          for (String room : new String[] {"fixture-a", "fixture-b"})
            MultiRoomStore.upsert(c, new MultiRoomStore.Profile(room));
        });
    assertTrue(LicenseManager.isUsable(c));
  }

  @After
  public void cleanup() {
    instrumentation.runOnMainSync(
        () -> {
          if (activity != null) activity.finish();
          PhotoTourController.stop(c, "UI fixture cleanup");
        });
    instrumentation.waitForIdleSync();
    instrumentation.runOnMainSync(() -> app.registerActivityLifecycleCallbacks(app));
  }

  private void launch(Intent intent) {
    activity = instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    instrumentation.waitForIdleSync();
    instrumentation.runOnMainSync(
        () -> {
          assertFalse(activity.isFinishing());
          PremiumChrome.polish(activity);
          // Empty pairing fields only, so the CI fixture can capture this protected setup screen.
          if (activity instanceof PanelOffActivity)
            activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        });
    instrumentation.waitForIdleSync();
  }

  private View find(View v, String label) {
    if (v instanceof TextView && label.contentEquals(((TextView) v).getText())) return v;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
        View result = find(((ViewGroup) v).getChildAt(i), label);
        if (result != null) return result;
      }
    return null;
  }

  private ScrollView scroll(View v) {
    if (v instanceof ScrollView) return (ScrollView) v;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
        ScrollView result = scroll(((ViewGroup) v).getChildAt(i));
        if (result != null) return result;
      }
    return null;
  }

  private void screenshot(String name) throws Exception {
    Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
    assertNotNull(bitmap);
    File folder = new File(c.getExternalFilesDir(null), "photo-ui");
    assertTrue(folder.isDirectory() || folder.mkdirs());
    try (FileOutputStream stream = new FileOutputStream(new File(folder, name + ".png"))) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
    } finally {
      bitmap.recycle();
    }
  }

  private void verify(String name, String... buttons) throws Exception {
    View root = activity.findViewById(android.R.id.content);
    instrumentation.runOnMainSync(
        () -> {
          for (String label : buttons) {
            View v = find(root, label);
            assertNotNull(label, v);
            for (View current = v;
                current != null;
                current = current.getParent() instanceof View ? (View) current.getParent() : null)
              assertEquals(label, View.VISIBLE, current.getVisibility());
          }
        });
    screenshot(name + "-top");
    instrumentation.runOnMainSync(
        () -> {
          ScrollView viewport = scroll(root);
          // fullScroll normally animates; Looper idle does not mean its future frames have
          // finished.
          viewport.setSmoothScrollingEnabled(false);
          viewport.fullScroll(View.FOCUS_DOWN);
        });
    instrumentation.waitForIdleSync();
    SystemClock.sleep(150);
    screenshot(name + "-bottom");
    instrumentation.runOnMainSync(
        () -> {
          View back = find(root, "돌아가기");
          if (back != null) {
            Rect bounds = new Rect();
            assertTrue(
                "Bottom action unreachable: " + name + " scrollY=" + scroll(root).getScrollY(),
                back.getGlobalVisibleRect(bounds));
            assertTrue(bounds.top >= 0);
            assertTrue(bounds.bottom <= root.getRootView().getHeight());
          }
        });
  }

  @Test
  public void setupHubShowsPermissionsStopAndBothPhotoPaths() throws Exception {
    launch(new Intent(c, PhotoKeyboardSetupActivity.class));
    verify(
        "setup",
        "사진 여러 방 전송·공통 설정",
        "3. 사진 전송 보조 접근성 설정",
        "4. 정확한 예약 시간 설정",
        "5. 중지 알림 표시 허용",
        "6. 화면 끄기 최초 연결 설정",
        "사진 예약 전체 중지·화면 복구",
        "문제 확인용 상태 복사");
  }

  @Test
  public void tourShowsCommonSettingsPreparationOnceRepeatAndStop() throws Exception {
    launch(new Intent(c, PhotoTourActivity.class));
    verify(
        "tour",
        "1. 선택 방 공통 사진·시간 설정",
        "2. 준비 안 된 선택 방 연결",
        "3. 선택 방 1회씩 시험 · 10초 후",
        "저장한 일정으로 선택 방 반복 시작",
        "사진 여러 방 전송 전체 중지",
        "방 추가·연결·삭제 관리");
  }

  @Test
  public void pairedPanelSetupShowsConnectTrialAndRestore() throws Exception {
    launch(new Intent(c, PanelOffActivity.class));
    verify(
        "panel", "2. 최초 페어링", "3. 휴대폰 자체 연결", "사진 없이 5초 화면 끄기 시험", "화면 즉시 복구·사진 순회 중지", "무선 연결 해제");
  }

  @Test
  public void commonPhotoEditorHasVisibleApplyAndNoMessageEditing() throws Exception {
    launch(
        new Intent(c, BulkRoomEditActivity.class)
            .putExtra(BulkRoomEditActivity.EXTRA_PHOTO_MODE, true)
            .putStringArrayListExtra(
                BulkRoomEditActivity.EXTRA_ROOMS,
                new ArrayList<>(Arrays.asList("fixture-a", "fixture-b"))));
    verify("common", "같은 사진 선택", "스케줄 일괄 적용", "하루 최대 횟수 일괄 적용", "2개 방에 적용");
    instrumentation.runOnMainSync(
        () -> {
          Rect r = new Rect();
          assertTrue(
              find(activity.findViewById(android.R.id.content), "2개 방에 적용")
                  .getGlobalVisibleRect(r));
        });
  }
}
