package com.local.kakaoautosender;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** One visible checklist and navigation hub for single-room and multi-room photo operation. */
public final class PhotoKeyboardSetupActivity extends Activity {
  private static java.lang.ref.WeakReference<PhotoKeyboardSetupActivity> visible =
      new java.lang.ref.WeakReference<>(null);
  private TextView status;
  private TextView history;

  static void uiStatusChanged() {
    PhotoKeyboardSetupActivity activity = visible.get();
    if (activity != null) activity.runOnUiThread(activity::refresh);
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    PremiumChrome.applyWindow(this);
    ScrollView scroll = new ScrollView(this);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    int padding = Math.round(20 * getResources().getDisplayMetrics().density);
    body.setPadding(padding, padding, padding, padding);
    body.setBackgroundColor(Color.rgb(9, 11, 16));
    scroll.addView(body);
    PhotoSettingsLayout.apply(this, scroll);
    addText(body, "사진 전송 설정·도움말", 23);
    status = addText(body, "", 14);
    addText(body, "한 방도 여러 방도: 방 선택 → 공통 사진·시간 → 방 최초 준비 → 시험·예약·반복", 15);
    addButton(
        body, "사진 여러 방 전송·공통 설정", () -> startActivity(new Intent(this, PhotoTourActivity.class)));
    addText(
        body,
        "아래 항목은 처음 한 번 설정합니다. 사진은 방 설정에 저장되며 최근 키보드 선택도 유지됩니다. 첫 전송 버튼 학습은 실제 사진 한 장을 보냅니다.",
        13);
    addButton(
        body,
        "1. 사진 키보드 사용 설정",
        () -> open(Settings.ACTION_INPUT_METHOD_SETTINGS, "설정의 키보드 목록에서 사진 키보드를 켜세요."));
    addButton(
        body,
        "2. 사진 키보드 선택 / 기존 키보드로 복귀",
        () -> {
          InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
          if (manager != null) manager.showInputMethodPicker();
        });
    addButton(
        body,
        "3. 사진 전송 보조 접근성 설정",
        () -> open(Settings.ACTION_ACCESSIBILITY_SETTINGS, "설정 → 접근성 → 설치된 앱에서 사진 전송 보조를 켜세요."));
    addButton(
        body,
        "4. 정확한 예약 시간 설정",
        () -> {
          if (android.os.Build.VERSION.SDK_INT >= 31 && !SendScheduler.canUseExact(this)) {
            try {
              startActivity(
                  new Intent(
                      Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                      android.net.Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException error) {
              status.setText("설정에서 이 앱의 알람 및 리마인더를 허용하세요.");
            }
          } else refresh();
        });
    addButton(
        body,
        "5. 중지 알림 표시 허용",
        () -> {
          if (android.os.Build.VERSION.SDK_INT >= 33
              && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                  != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 7308);
          else open(Settings.ACTION_APP_NOTIFICATION_SETTINGS, "설정에서 이 앱의 알림을 허용하세요.");
        });
    addButton(
        body, "6. 화면 끄기 최초 연결 설정", () -> startActivity(new Intent(this, PanelOffActivity.class)));
    addText(
        body,
        "기본 모드는 화면이 꺼지면 대기합니다. 패널 OFF 모드는 Android 11 이상에서 최초 무선 디버깅 페어링이 필요하며 보안 잠금을 풀어 보내지 않습니다."
            + " 실제 기기별 동작은 확인이 필요합니다.",
        13);
    addButton(body, "현재 상태 새로고침", this::refresh);
    addButton(
        body,
        "사진 예약 전체 중지·화면 복구",
        () -> {
          PhotoKeyboardService.cancelPending();
          PhotoKeyboardService.stopLoop(this, null, "사용자가 설정 화면에서 사진 예약을 중지했습니다.");
          PanelOffManager.end("사용자가 화면 복구를 요청했습니다.");
          refresh();
        });
    LinearLayout details = new LinearLayout(this);
    details.setOrientation(LinearLayout.VERTICAL);
    details.setVisibility(android.view.View.GONE);
    addButton(
        body,
        "실행·처리 기록 보기/접기",
        () -> {
          details.setVisibility(
              details.getVisibility() == android.view.View.GONE
                  ? android.view.View.VISIBLE
                  : android.view.View.GONE);
          refresh();
        });
    body.addView(details);
    history = addText(details, "", 13);
    addText(
        details,
        "사진 출처는 수신자 선택이 아닙니다. 현재 카카오톡 방을 확인해야 하며 저장 문구를 사진과 함께 보내지 않습니다. 같은 방의 기존 알림 자동전송은 꺼야 합니다."
            + " 여러 방 화면에서 선택 방의 기존 예약만 정리할 수 있습니다.",
        13);
    addText(
        details,
        "사진·대화 문구·페어링 코드를 서버로 보내지 않습니다. 접근성은 기기 안에서 방 제목·입력창·전송 버튼을 비교합니다. 재부팅·앱 종료 후 자동 재개하지 않습니다."
            + " 전송 절차 실행과 상대방 실제 수신은 구분합니다.",
        13);
    addButton(
        body,
        "문제 확인용 상태 복사",
        () -> {
          android.content.ClipboardManager clipboard =
              (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
          if (clipboard != null) {
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("사진 전송 상태", PhotoSupportReport.build(this)));
            android.widget.Toast.makeText(
                    this,
                    "상태를 복사했습니다. 사진·대화 문구·페어링 코드는 포함하지 않습니다.",
                    android.widget.Toast.LENGTH_LONG)
                .show();
          }
        });
    addButton(body, "돌아가기", this::finish);
    setContentView(scroll);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (!AppIntegrity.isAuthentic(this)) {
      AppIntegrity.trip(this);
      finish();
      return;
    }
    if (!LicenseManager.isUsable(this)) {
      LicenseManager.route(this);
      return;
    }
    visible = new java.lang.ref.WeakReference<>(this);
    refresh();
  }

  @Override
  protected void onPause() {
    if (visible.get() == this) visible.clear();
    super.onPause();
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(code, permissions, results);
    refresh();
  }

  private void open(String action, String fallback) {
    try {
      startActivity(new Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
    } catch (RuntimeException error) {
      status.setText(fallback);
    }
  }

  private void refresh() {
    if (status == null) return;
    InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
    boolean enabled = false;
    ComponentName keyboard = new ComponentName(this, PhotoKeyboardService.class);
    if (manager != null)
      for (InputMethodInfo info : manager.getEnabledInputMethodList())
        if (keyboard.equals(ComponentName.unflattenFromString(info.getId()))) enabled = true;
    android.app.NotificationManager notices =
        (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    status.setText(
        "사진 키보드: "
            + (enabled ? "사용 설정됨" : "사용 설정 필요")
            + " / "
            + (PhotoTourController.keyboardSelected(this) ? "현재 선택됨" : "현재 선택 필요")
            + "\n사진 전송 보조: "
            + (PhotoUiService.connected() ? "연결됨" : "접근성 설정 필요")
            + "\n정확한 예약: "
            + (SendScheduler.canUseExact(this) ? "사용 가능" : "권한 없음 · 지연 가능")
            + "\n중지 알림: "
            + (notices != null && notices.areNotificationsEnabled() ? "표시 가능" : "알림 허용 필요")
            + "\n화면 패널 OFF: "
            + (PanelOffManager.connected() ? "자체 연결됨 · 실기기 확인 필요" : "연결 필요 · 선택 기능")
            + "\n현재 연결: "
            + PhotoUiService.status()
            + "\n"
            + (PhotoTourController.active()
                ? PhotoTourController.status(this)
                : PhotoKeyboardService.hasActiveLoop()
                    ? PhotoLoopStore.summary(this)
                    : "현재 실행 중인 사진 예약 없음"));
    if (history != null)
      history.setText(
          PhotoStatusText.combine(
              PhotoKeyboardDiagnostics.summary(this),
              PhotoLoopStore.summary(this),
              PhotoTourController.status(this)));
  }

  private TextView addText(LinearLayout p, String label, int size) {
    TextView v = new TextView(this);
    v.setText(label);
    v.setTextSize(size);
    v.setTextColor(Color.rgb(228, 234, 245));
    v.setPadding(0, 12, 0, 14);
    p.addView(v);
    return v;
  }

  private void addButton(LinearLayout p, String label, Runnable click) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    PhotoControls.style(b, label);
    b.setOnClickListener(v -> click.run());
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = Math.round(8 * getResources().getDisplayMetrics().density);
    p.addView(b, params);
  }
}
