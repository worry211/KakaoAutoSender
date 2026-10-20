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

/** Setup/diagnostics only: enabling/selecting an IME is always an Android user action. */
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
    addText(body, "사진 자동전송 준비", 23);
    status = addText(body, "", 14);
    addText(body, "사진 선택 → 현재 방 확인 → 전송 준비 1회 → 시험 또는 예약", 16);
    addText(
        body,
        "카카오톡의 보낼 방에서 사진 키보드를 여세요. 처음 준비할 때는 카카오톡에서 전송 버튼을 한 번 직접 누릅니다. 사진 한 장이 실제로 전송됩니다. 준비 후 같은"
            + " 방에서 사진을 다시 고르지 않고 10초 시험·예약·반복을 시작할 수 있습니다.",
        14);
    addText(
        body,
        "현재 방을 유지해야 합니다. 다른 앱·방으로 이동하거나 입력 연결이 종료되면 중지합니다. 여러 방 순회와 실제 화면 OFF 전송은 아직 지원이 확인되지"
            + " 않았습니다. 저장 문구는 사진과 함께 보내지 않습니다.",
        14);
    addButton(body, "사진 키보드 사용 설정", this::openSettings);
    addButton(
        body,
        "키보드 선택 / 기존 키보드로 복귀",
        () -> {
          InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
          if (manager != null) manager.showInputMethodPicker();
        });
    addButton(
        body,
        "사진 전송 보조 접근성 설정",
        () -> {
          try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
          } catch (RuntimeException error) {
            status.setText("휴대폰 설정 → 접근성 → 설치된 앱에서 사진 전송 보조를 켜세요.");
          }
        });
    addButton(body, "현재 상태 새로고침", this::refresh);
    addButton(
        body,
        "사진 예약 중지 / 준비 해제",
        () -> {
          PhotoKeyboardService.stopLoop(this, null, "사용자가 설정 화면에서 사진 예약을 중지했습니다.");
          refresh();
        });
    LinearLayout details = new LinearLayout(this);
    details.setOrientation(LinearLayout.VERTICAL);
    details.setVisibility(android.view.View.GONE);
    addButton(
        body,
        "고급 설정 · 이전 처리 기록 보기/접기",
        () ->
            details.setVisibility(
                details.getVisibility() == android.view.View.GONE
                    ? android.view.View.VISIBLE
                    : android.view.View.GONE));
    body.addView(details);
    history = addText(details, "", 13);
    addButton(
        details,
        "정확한 예약 시간 설정",
        () -> {
          if (android.os.Build.VERSION.SDK_INT >= 31 && !SendScheduler.canUseExact(this)) {
            try {
              startActivity(
                  new Intent(
                      Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                      android.net.Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException error) {
              status.setText("휴대폰 설정에서 이 앱의 알람 및 리마인더를 허용하세요.");
            }
          } else refresh();
        });
    addButton(
        details,
        "중지 알림 표시 허용",
        () -> {
          if (android.os.Build.VERSION.SDK_INT >= 33
              && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                  != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 7308);
          else refresh();
        });
    addText(
        details,
        "사진 출처의 방 이름으로 수신자를 선택하지 않습니다. 현재 열린 방을 확인하세요. 같은 방의 기존 알림 자동전송과 사진 키보드 예약을 동시에 켜지 않습니다. 전체"
            + " 중지·알림 중지로 예약을 취소할 수 있습니다.",
        13);
    addText(
        details,
        "이 키보드는 문자 입력을 읽거나 저장하지 않습니다. 접근성은 기기 안에서 카카오톡의 방 제목·입력창·전송 버튼 구조를 비교합니다. 대화 내용이나 입력 문구를"
            + " 저장하거나 서버로 보내지 않습니다. 이전 기록은 현재 연결이나 상대방 수신을 보장하지 않습니다.",
        13);
    addText(
        details,
        "화면 꺼짐 시도는 키보드 고급 설정의 미검증 실험입니다. Android가 연결이나 화면 접근을 막으면 중지하며, 화면을 켜거나 잠금을 풀지 않습니다."
            + " 재부팅·업데이트·프로세스 종료 뒤 자동으로 다른 방에서 재개하지 않습니다.",
        13);
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

  private void openSettings() {
    try {
      startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
    } catch (RuntimeException error) {
      status.setText("휴대폰 설정의 일반 관리 → 키보드 목록에서 사진 키보드를 켜세요.");
    }
  }

  private void refresh() {
    if (status == null) return;
    InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
    boolean enabled = false;
    String component = new ComponentName(this, PhotoKeyboardService.class).flattenToShortString();
    if (manager != null)
      for (InputMethodInfo info : manager.getEnabledInputMethodList())
        if (component.equals(info.getId())) enabled = true;
    status.setText(
        (enabled ? "사진 키보드 사용 설정됨" : "사진 키보드 사용 설정 필요")
            + "\n현재 연결: "
            + PhotoUiService.status()
            + "\n"
            + (PhotoKeyboardService.hasActiveLoop()
                ? PhotoLoopStore.summary(this)
                : "현재 실행 중인 사진 예약 없음"));
    if (history != null)
      history.setText(
          PhotoStatusText.combine(
              PhotoKeyboardDiagnostics.summary(this),
              PhotoLoopStore.summary(this),
              SendScheduler.canUseExact(this) ? "정확한 알람 사용 가능" : "정확한 알람 권한 없음 · 요청 시각이 늦어질 수 있음"));
  }

  private TextView addText(LinearLayout body, String value, int size) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(Color.rgb(228, 234, 245));
    view.setPadding(0, 12, 0, 14);
    body.addView(view);
    return view;
  }

  private void addButton(LinearLayout body, String label, Runnable action) {
    Button button = new Button(this);
    button.setText(label);
    button.setAllCaps(false);
    button.setOnClickListener(view -> action.run());
    body.addView(button);
  }
}
