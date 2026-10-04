package com.local.kakaoautosender;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class LicenseActivity extends Activity {
  private EditText key;
  private TextView status, support;
  private Button activate, retry;
  private boolean busy;

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(Color.rgb(12, 13, 16));
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(22), dp(32), dp(22), dp(32));
    scroll.addView(root);
    root.addView(text("카톡매크로", 30));
    root.addView(text("판매자에게 받은 일회용 키로 시작하세요.", 15));
    key = new EditText(this);
    key.setSingleLine(true);
    key.setTextColor(Color.WHITE);
    key.setHintTextColor(Color.GRAY);
    key.setHint("KM-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX");
    key.setInputType(
        InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    root.addView(key);
    Button paste = button("붙여넣기");
    paste.setOnClickListener(
        v -> {
          ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
          if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null)
            key.setText(cm.getPrimaryClip().getItemAt(0).coerceToText(this));
        });
    root.addView(paste);
    activate = button("활성화");
    activate.setOnClickListener(
        v -> {
          if (busy || key.getText().toString().trim().isEmpty()) return;
          setBusy(true);
          status.setText("활성화 확인 중…");
          LicenseManager.activateAsync(this, key.getText().toString(), this::result);
        });
    root.addView(activate);
    retry = button("인터넷 연결 후 다시 확인");
    retry.setOnClickListener(v -> check());
    root.addView(retry);
    status = text("라이선스 확인 중…", 16);
    root.addView(status);
    support = text(LicenseManager.diagnostic(this), 12);
    support.setTextIsSelectable(true);
    root.addView(support);
    root.addView(text("기기 변경은 판매자에게 새 키를 요청하세요. 방과 메시지 설정은 인증이 중단되어도 보존됩니다.", 13));
    setContentView(scroll);
    check();
  }

  private void check() {
    if (busy) return;
    setBusy(true);
    LicenseManager.checkAsync(this, this::result);
  }

  private void result(LicenseManager.Verification v) {
    if (isFinishing() || isDestroyed()) return;
    setBusy(false);
    status.setText(v.valid ? v.expiryLabel() : v.message);
    support.setText(LicenseManager.diagnostic(this));
    if (v.valid) {
      startActivity(new Intent(this, MainActivityV4.class));
      finish();
    }
  }

  private void setBusy(boolean v) {
    busy = v;
    activate.setEnabled(!v);
    retry.setEnabled(!v);
  }

  private TextView text(String value, int size) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextColor(Color.WHITE);
    t.setTextSize(size);
    t.setPadding(0, dp(12), 0, dp(12));
    return t;
  }

  private Button button(String value) {
    Button b = new Button(this);
    b.setText(value);
    b.setAllCaps(false);
    b.setMinHeight(dp(48));
    return b;
  }

  private int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }
}
