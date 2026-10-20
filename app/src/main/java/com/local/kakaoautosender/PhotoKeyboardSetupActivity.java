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
        "사진 출처의 방 이름은 자동으로 선택되는 수신자가 아닙니다. 현재 열린 채팅방에 전달되며 즉시 전송될 수 있습니다. 문구·예약·반복 전송은 하지 않습니다. 화면을"
            + " 끄거나 입력창을 닫으면 선택을 취소합니다.",
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
    addText(
        body,
        "이 키보드는 문자 입력을 읽거나 저장하지 않습니다. Android의 키보드 허용 안내는 직접 확인하세요. 사용을 마치면 기존 키보드로 바꾸면 됩니다. 지원 상태는"
            + " 최근 15분간의 입력 형식만 기록하며 실제 도착을 뜻하지 않습니다.",
        13);
    addText(body, "화면 꺼짐 사진 자동전송은 구현되지 않았습니다. 이 기능을 켜도 기존 알림 자동전송의 지원 범위는 바뀌지 않습니다.", 14);
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
            PhotoKeyboardDiagnostics.summary(this)));
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
