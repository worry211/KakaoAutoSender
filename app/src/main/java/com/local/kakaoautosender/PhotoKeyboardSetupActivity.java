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
  private TextView status;

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
    addText(body, "사진 키보드 · 시험 기능", 23);
    addText(
        body, "알림 사진 입력과 별개로 카카오톡 채팅 입력창에 사진을 전달하는 방식입니다. 카카오톡이 사진 키보드 입력을 지원해야 사용할 수 있습니다.", 15);
    addText(
        body,
        "1. 기존 앱의 방 설정에서 사진을 선택합니다.\n"
            + "2. 아래 ‘키보드 사용 설정’에서 ‘카톡매크로 사진 키보드’를 켭니다.\n"
            + "3. 카카오톡에서 시험할 일반·오픈채팅방을 직접 엽니다.\n"
            + "4. 메시지 입력창을 누르고 키보드 변경에서 사진 키보드를 선택합니다.\n"
            + "5. 사진 출처를 고르고 미리보기와 현재 받는 방을 확인한 뒤 1회 전달합니다.",
        15);
    addText(
        body,
        "사진 출처의 방 이름은 수신자가 아닙니다. 현재 열린 채팅방을 직접 확인하세요. 사진 1회 전달과 예약은 별개입니다. 저장 문구는 함께 보내지 않습니다.",
        14);
    addText(
        body,
        "예약·반복 준비\n"
            + "1. 방 설정에 간격 또는 매일 시각과 하루 한도를 저장하고, 이 방의 기존 자동전송은 끕니다.\n"
            + "2. 아래 접근성 설정에서 ‘카톡매크로 사진 전송 보조’를 켭니다.\n"
            + "3. 카카오톡 시험 방에서 사진 키보드로 사진을 고르고 현재 방 확인을 체크합니다.\n"
            + "4. ‘자동 전송 준비 · 전송 버튼 학습’을 누른 뒤 카카오톡에서 마지막 전송 버튼을 직접 한 번 누릅니다. 이 과정도 실제 사진을 보냅니다.\n"
            + "5. 원래 방으로 돌아와 사진을 다시 고른 뒤 ‘다음 일정에 1회 예약’ 또는 ‘저장한 일정으로 반복 시작’을 누릅니다.",
        14);
    addText(
        body,
        "접근성 사용 안내: 카카오톡의 방 제목과 화면 구조를 기기 안에서 비교하고, 직접 학습한 사진 전송 버튼만 누릅니다. 대화·입력 문구를 저장하거나 서버로 보내지"
            + " 않습니다. 방·화면·연결이 바뀌면 중지하며, 재부팅 후 자동으로 다른 방에 재개하지 않습니다.",
        14);
    status = addText(body, "", 14);
    addButton(body, "키보드 사용 설정", () -> openSettings());
    addButton(
        body,
        "키보드 선택 / 기존 키보드로 복귀",
        () -> {
          InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
          if (manager != null) manager.showInputMethodPicker();
        });
    addButton(body, "지원 상태 새로고침", this::refresh);
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
    addButton(
        body,
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
        body,
        "중지 알림 표시 허용",
        () -> {
          if (android.os.Build.VERSION.SDK_INT >= 33
              && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                  != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 7308);
          else refresh();
        });
    addButton(
        body,
        "사진 예약 즉시 중지 / 학습 해제",
        () -> {
          PhotoKeyboardService.stopLoop(this, null, "사용자가 설정 화면에서 사진 예약을 중지했습니다.");
          refresh();
        });
    addText(
        body,
        "이 키보드는 문자 입력을 읽거나 저장하지 않습니다. Android의 키보드 허용 안내는 직접 확인하세요. 사용을 마치면 기존 키보드로 바꾸면 됩니다. 지원 상태는"
            + " 최근 15분간의 입력 형식만 기록하며 실제 도착을 뜻하지 않습니다.",
        13);
    addText(
        body,
        "화면 꺼짐 시도는 별도 실험 옵션입니다. Android가 현재 입력 연결과 학습 화면 접근을 유지할 때만 진행하며, 연결 종료·화면 확인 불가 시 중지합니다."
            + " 화면을 켜거나 잠금을 풀지 않습니다. 화면 꺼짐에서의 실제 전송은 미검증이며 지원을 보장하지 않습니다. 절전 중 예약 시간이 늦어질 수 있습니다.",
        14);
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
    refresh();
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
        getString(
            R.string.photo_keyboard_status,
            enabled ? "사진 키보드: 사용 설정됨" : "사진 키보드: 사용 설정 필요",
            PhotoKeyboardDiagnostics.summary(this)
                + "\n\n"
                + PhotoUiService.status()
                + "\n\n"
                + PhotoLoopStore.summary(this)
                + (SendScheduler.canUseExact(this)
                    ? "\n정확한 알람 사용 가능"
                    : "\n정확한 알람 권한 없음 · 요청 시각이 늦어질 수 있음")));
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
