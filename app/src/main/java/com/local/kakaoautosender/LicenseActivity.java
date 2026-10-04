package com.local.kakaoautosender;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class LicenseActivity extends Activity {
  private EditText key;
  private TextView statusTitle, statusDetail, support;
  private Button activate, retry;
  private boolean busy;

  private static final int BG = Color.rgb(12, 13, 16);
  private static final int CARD = Color.rgb(27, 30, 36);
  private static final int FIELD = Color.rgb(35, 38, 45);
  private static final int PRIMARY = Color.rgb(48, 88, 158);
  private static final int MUTED = Color.rgb(151, 158, 171);
  private static final int GREEN = Color.rgb(91, 224, 147);
  private static final int AMBER = Color.rgb(240, 182, 77);
  private static final int RED = Color.rgb(234, 108, 108);

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    setContentView(buildUi());
    check();
  }

  private ScrollView buildUi() {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(BG);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(20), dp(28), dp(20), dp(36));
    scroll.addView(root);

    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView brand = text("카톡매크로", 30, true, Color.WHITE);
    header.addView(brand, weight());
    TextView version = text("v" + appVersion(), 11, true, Color.rgb(215, 225, 246));
    version.setGravity(Gravity.CENTER);
    version.setPadding(dp(9), dp(5), dp(9), dp(5));
    version.setBackground(round(Color.rgb(56, 61, 72), 14));
    header.addView(version);
    root.addView(header);

    TextView subtitle = text("라이선스 인증 후 바로 사용할 수 있습니다.", 13, false, MUTED);
    root.addView(subtitle, top(5));

    LinearLayout intro = card();
    intro.addView(text("한 번만 인증하면 됩니다", 18, true, Color.WHITE));
    intro.addView(text("판매자에게 받은 KM-... 키를 붙여넣으세요. 별도의 기기코드 전달은 필요하지 않습니다.", 13, false, Color.rgb(181, 187, 198)), top(7));
    root.addView(intro, top(20));

    root.addView(section("라이선스 키"), top(22));
    key = new EditText(this);
    key.setSingleLine(true);
    key.setTextColor(Color.WHITE);
    key.setTextSize(15);
    key.setHintTextColor(Color.rgb(112, 118, 129));
    key.setHint("KM-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX");
    key.setPadding(dp(13), dp(13), dp(13), dp(13));
    key.setBackground(round(FIELD, 11));
    key.setInputType(
        InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    root.addView(key, top(8));

    LinearLayout keyButtons = new LinearLayout(this);
    keyButtons.setOrientation(LinearLayout.HORIZONTAL);
    Button paste = secondaryButton("붙여넣기");
    paste.setOnClickListener(v -> pasteKey());
    keyButtons.addView(paste, weight());
    activate = primaryButton("라이선스 인증");
    activate.setOnClickListener(v -> activate());
    LinearLayout.LayoutParams activateLp = weight();
    activateLp.leftMargin = dp(8);
    keyButtons.addView(activate, activateLp);
    root.addView(keyButtons, top(9));

    LinearLayout statusCard = card();
    statusTitle = text("라이선스 확인 중", 17, true, Color.WHITE);
    statusCard.addView(statusTitle);
    statusDetail = text("서버에서 사용 권한을 확인하고 있습니다.", 13, false, Color.rgb(179, 185, 197));
    statusCard.addView(statusDetail, top(6));
    retry = secondaryButton("다시 확인");
    retry.setOnClickListener(v -> check());
    statusCard.addView(retry, top(11));
    root.addView(statusCard, top(18));

    root.addView(section("처음 사용하는 경우"), top(24));
    LinearLayout guide = card();
    guide.addView(step("1", "라이선스 인증", "판매자에게 받은 KM 키를 한 번 입력합니다."));
    guide.addView(step("2", "알림 접근 허용", "카카오톡의 답장 세션을 확인하기 위해 필요합니다."), top(10));
    guide.addView(step("3", "방 연결 후 1회 테스트", "대상 방을 확인한 뒤 예약 전송을 시작합니다."), top(10));
    root.addView(guide, top(8));

    root.addView(section("지원"), top(24));
    LinearLayout supportCard = card();
    support = text("지원 정보 준비 중…", 12, false, Color.rgb(166, 173, 186));
    support.setTextIsSelectable(true);
    supportCard.addView(support);
    Button copy = secondaryButton("지원 정보 복사");
    copy.setOnClickListener(v -> copySupport());
    supportCard.addView(copy, top(10));
    TextView privacy = text("지원 정보에는 카카오 방 이름, 메시지 내용, 사진이 포함되지 않습니다.", 11, false, Color.rgb(119, 126, 139));
    supportCard.addView(privacy, top(8));
    root.addView(supportCard, top(8));

    TextView footer = text("라이선스가 만료·정지·취소되면 자동전송은 중단되며, 저장한 방과 메시지 설정은 그대로 보존됩니다.", 11, false, Color.rgb(113, 120, 132));
    root.addView(footer, top(18));
    return scroll;
  }

  private void activate() {
    if (busy) return;
    String entered = key.getText().toString().trim();
    if (entered.isEmpty()) {
      showStatus("라이선스 키를 입력하세요", "판매자에게 받은 KM-... 키를 붙여넣어 주세요.", AMBER);
      return;
    }
    setBusy(true);
    showStatus("라이선스 인증 중", "키를 확인하고 이 설치에 안전하게 연결하고 있습니다.", Color.rgb(143, 190, 255));
    LicenseManager.activateAsync(this, entered, this::result);
  }

  private void pasteKey() {
    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
    if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) {
      toast("클립보드에 붙여넣을 내용이 없습니다.");
      return;
    }
    CharSequence value = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
    if (value == null) return;
    key.setText(value.toString().trim());
    key.setSelection(key.length());
  }

  private void check() {
    if (busy) return;
    setBusy(true);
    showStatus("라이선스 확인 중", "기존 인증 정보를 확인하고 있습니다.", Color.rgb(143, 190, 255));
    LicenseManager.checkAsync(this, this::result);
  }

  private void result(LicenseManager.Verification v) {
    if (isFinishing() || isDestroyed()) return;
    setBusy(false);
    support.setText(supportSummary(v));
    if (v.valid) {
      showStatus("인증 완료", v.expiryLabel(), GREEN);
      key.setText("");
      startActivity(new Intent(this, MainActivityV4.class));
      finish();
      return;
    }
    int color = "NETWORK".equals(v.state) || "MAINTENANCE".equals(v.state) ? AMBER : RED;
    showStatus(statusName(v.state), v.message, color);
  }

  private String statusName(String state) {
    if ("NETWORK".equals(state)) return "서버 연결 필요";
    if ("EXPIRED".equals(state)) return "라이선스 만료";
    if ("SUSPENDED".equals(state)) return "라이선스 정지";
    if ("REVOKED".equals(state) || "DELETED".equals(state)) return "라이선스 사용 불가";
    if ("DEVICE_MISMATCH".equals(state)) return "다른 기기에 등록된 키";
    if ("UPDATE_REQUIRED".equals(state)) return "앱 업데이트 필요";
    if ("MAINTENANCE".equals(state)) return "서비스 점검 중";
    if ("ALREADY_USED".equals(state)) return "이미 사용된 키";
    return "라이선스 인증 필요";
  }

  private String supportSummary(LicenseManager.Verification v) {
    String id = v.licenseId == null || v.licenseId.isEmpty() ? "미등록" : v.licenseId;
    return "지원 코드  " + id + "\n상태  " + (v.state == null ? "INVALID" : v.state) + "\n앱 버전  " + appVersion();
  }

  private void copySupport() {
    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
    if (cm == null) return;
    cm.setPrimaryClip(ClipData.newPlainText("카톡매크로 지원 정보", LicenseManager.diagnostic(this)));
    toast("지원 정보를 복사했습니다.");
  }

  private void showStatus(String title, String detail, int color) {
    if (statusTitle == null) return;
    statusTitle.setText("● " + title);
    statusTitle.setTextColor(color);
    statusDetail.setText(detail == null || detail.trim().isEmpty() ? "상태를 확인해 주세요." : detail.trim());
  }

  private void setBusy(boolean v) {
    busy = v;
    activate.setEnabled(!v);
    retry.setEnabled(!v);
    activate.setText(v ? "확인 중…" : "라이선스 인증");
  }

  private LinearLayout step(String number, String title, String detail) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.TOP);
    TextView badge = text(number, 12, true, Color.WHITE);
    badge.setGravity(Gravity.CENTER);
    badge.setBackground(round(PRIMARY, 18));
    LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(30), dp(30));
    row.addView(badge, badgeLp);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.addView(text(title, 14, true, Color.WHITE));
    body.addView(text(detail, 12, false, Color.rgb(157, 164, 177)), top(2));
    LinearLayout.LayoutParams bodyLp = weight();
    bodyLp.leftMargin = dp(10);
    row.addView(body, bodyLp);
    return row;
  }

  private LinearLayout card() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setPadding(dp(15), dp(15), dp(15), dp(15));
    l.setBackground(round(CARD, 15));
    return l;
  }

  private TextView section(String value) {
    return text(value, 17, true, Color.rgb(220, 228, 246));
  }

  private TextView text(String value, int size, boolean bold, int color) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextColor(color);
    t.setTextSize(size);
    if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
    return t;
  }

  private Button primaryButton(String value) {
    return button(value, PRIMARY);
  }

  private Button secondaryButton(String value) {
    return button(value, Color.rgb(61, 65, 74));
  }

  private Button button(String value, int color) {
    Button b = new Button(this);
    b.setText(value);
    b.setAllCaps(false);
    b.setTextColor(Color.WHITE);
    b.setTextSize(13);
    b.setMinHeight(dp(48));
    b.setBackground(round(color, 11));
    return b;
  }

  private GradientDrawable round(int color, int radiusDp) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radiusDp));
    return d;
  }

  private LinearLayout.LayoutParams top(int v) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.topMargin = dp(v);
    return lp;
  }

  private LinearLayout.LayoutParams weight() {
    return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
  }

  private String appVersion() {
    try {
      return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (Exception e) {
      return "?";
    }
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  private int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }
}
