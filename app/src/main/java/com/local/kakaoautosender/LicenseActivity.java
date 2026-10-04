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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LicenseActivity extends Activity {
  private EditText key;
  private TextView statusTitle, statusDetail, support;
  private Button activate, retry;
  private boolean busy;

  private static final int BG = Color.rgb(9, 11, 16);
  private static final int SURFACE = Color.rgb(18, 23, 34);
  private static final int SURFACE_2 = Color.rgb(23, 29, 42);
  private static final int FIELD = Color.rgb(15, 20, 30);
  private static final int BORDER = Color.rgb(45, 55, 75);
  private static final int PRIMARY = Color.rgb(86, 112, 255);
  private static final int PRIMARY_SOFT = Color.rgb(30, 40, 72);
  private static final int TEXT = Color.rgb(238, 242, 249);
  private static final int GREEN = Color.rgb(94, 226, 157);
  private static final int AMBER = Color.rgb(243, 190, 91);
  private static final int RED = Color.rgb(243, 113, 121);
  private static final String KEY_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
  private static final Pattern ACTIVATION_KEY_PATTERN =
      Pattern.compile("KM-(?:[" + KEY_ALPHABET + "]{4}-){5}[" + KEY_ALPHABET + "]{4}");

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    PremiumChrome.applyWindow(this);
    setContentView(buildUi());
    check();
  }

  private ScrollView buildUi() {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(BG);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(20), dp(28), dp(20), dp(40));
    scroll.addView(root);

    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);

    LinearLayout brandBox = new LinearLayout(this);
    brandBox.setOrientation(LinearLayout.VERTICAL);
    TextView brand = text("카톡매크로", 30, true, TEXT);
    brand.setLetterSpacing(-0.015f);
    brandBox.addView(brand);
    TextView brandSub = text("KAKAO AUTOMATION SUITE", 10, true, Color.rgb(123, 138, 168));
    brandSub.setLetterSpacing(0.12f);
    brandBox.addView(brandSub, top(4));
    header.addView(brandBox, weight());

    TextView version = pill("v" + appVersion(), Color.rgb(28, 34, 49), Color.rgb(180, 194, 223));
    header.addView(version);
    root.addView(header);

    LinearLayout hero = premiumCard(PRIMARY_SOFT, Color.rgb(60, 76, 128));
    TextView secure = pill("SECURE LICENSE", Color.rgb(42, 55, 94), Color.rgb(173, 190, 255));
    secure.setLetterSpacing(0.07f);
    hero.addView(secure, wrap());
    TextView heroTitle = text("정품 인증부터 안전하게", 22, true, TEXT);
    heroTitle.setLetterSpacing(-0.01f);
    hero.addView(heroTitle, top(14));
    hero.addView(
        text(
            "발급받은 1회용 키를 인증하면 이 설치에 자동으로 연결됩니다. 이후에는 별도의 기기 코드 교환 없이 바로 사용할 수 있습니다.",
            13,
            false,
            Color.rgb(183, 193, 212)),
        top(7));

    LinearLayout trustRow = new LinearLayout(this);
    trustRow.setOrientation(LinearLayout.HORIZONTAL);
    trustRow.addView(trustChip("1기기 연결"), weight());
    LinearLayout.LayoutParams t2 = weight();
    t2.leftMargin = dp(6);
    trustRow.addView(trustChip("서버 실시간 확인"), t2);
    LinearLayout.LayoutParams t3 = weight();
    t3.leftMargin = dp(6);
    trustRow.addView(trustChip("설정 자동 보존"), t3);
    hero.addView(trustRow, top(15));
    root.addView(hero, top(22));

    root.addView(section("라이선스 키", "판매자에게 받은 KM 키를 입력하세요."), top(24));

    key = new EditText(this);
    key.setSingleLine(true);
    key.setTextColor(TEXT);
    key.setTextSize(15);
    key.setTypeface(Typeface.MONOSPACE);
    key.setLetterSpacing(0.025f);
    key.setHintTextColor(Color.rgb(92, 103, 124));
    key.setHint("KM-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX");
    key.setPadding(dp(15), dp(15), dp(15), dp(15));
    key.setBackground(roundStroke(FIELD, BORDER, 12));
    key.setInputType(
        InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    root.addView(key, top(10));

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

    LinearLayout statusCard = premiumCard(SURFACE, BORDER);
    LinearLayout statusTop = new LinearLayout(this);
    statusTop.setOrientation(LinearLayout.HORIZONTAL);
    statusTop.setGravity(Gravity.CENTER_VERTICAL);
    TextView statusLabel = text("인증 상태", 11, true, Color.rgb(120, 133, 157));
    statusLabel.setLetterSpacing(0.08f);
    statusTop.addView(statusLabel, weight());
    TextView protectedPill = pill("PROTECTED", Color.rgb(25, 49, 42), Color.rgb(122, 231, 176));
    statusTop.addView(protectedPill);
    statusCard.addView(statusTop);

    statusTitle = text("라이선스 확인 중", 18, true, TEXT);
    statusCard.addView(statusTitle, top(13));
    statusDetail = text("서버에서 사용 권한을 확인하고 있습니다.", 13, false, Color.rgb(177, 187, 205));
    statusCard.addView(statusDetail, top(6));
    retry = secondaryButton("상태 다시 확인");
    retry.setOnClickListener(v -> check());
    statusCard.addView(retry, top(13));
    root.addView(statusCard, top(18));

    root.addView(section("처음 사용하는 경우", "3단계만 완료하면 준비가 끝납니다."), top(26));
    LinearLayout guide = premiumCard(SURFACE, BORDER);
    guide.addView(step("01", "라이선스 인증", "판매자에게 받은 KM 키를 한 번 입력합니다."));
    guide.addView(divider(), top(12));
    guide.addView(step("02", "알림 접근 허용", "카카오톡의 답장 세션을 확인하기 위해 필요합니다."), top(12));
    guide.addView(divider(), top(12));
    guide.addView(step("03", "방 연결 후 1회 테스트", "대상 방을 확인한 뒤 실제 예약 전송을 시작합니다."), top(12));
    root.addView(guide, top(9));

    root.addView(section("지원", "문의할 때 아래 정보만 전달하면 됩니다."), top(26));
    LinearLayout supportCard = premiumCard(SURFACE_2, BORDER);
    support = text("지원 정보 준비 중…", 12, false, Color.rgb(177, 187, 205));
    support.setTextIsSelectable(true);
    support.setTypeface(Typeface.MONOSPACE);
    supportCard.addView(support);
    Button copy = secondaryButton("지원 정보 복사");
    copy.setOnClickListener(v -> copySupport());
    supportCard.addView(copy, top(12));
    TextView privacy =
        text(
            "개인정보 보호: 지원 정보에는 카카오 방 이름, 메시지 내용, 사진이 포함되지 않습니다.",
            11,
            false,
            Color.rgb(112, 123, 145));
    supportCard.addView(privacy, top(9));
    root.addView(supportCard, top(9));

    TextView footer =
        text(
            "라이선스가 만료·정지·취소되면 자동전송은 즉시 중단되며, 저장한 방·메시지·시간 설정은 그대로 보존됩니다.",
            11,
            false,
            Color.rgb(104, 115, 137));
    footer.setGravity(Gravity.CENTER);
    root.addView(footer, top(20));
    return scroll;
  }

  static String normalizeActivationKeyInput(String raw) {
    if (raw == null) return "";
    String upper = raw.toUpperCase(Locale.ROOT);
    Matcher direct = ACTIVATION_KEY_PATTERN.matcher(upper);
    if (direct.find()) return direct.group();

    String compact = upper.replaceAll("\\s+", "");
    Matcher compactMatch = ACTIVATION_KEY_PATTERN.matcher(compact);
    if (compactMatch.find()) return compactMatch.group();
    return compact;
  }

  static boolean isActivationKeyFormat(String value) {
    return value != null && ACTIVATION_KEY_PATTERN.matcher(value).matches();
  }

  private void activate() {
    if (busy) return;
    String entered = normalizeActivationKeyInput(key.getText().toString());
    key.setText(entered);
    key.setSelection(key.length());
    if (entered.isEmpty()) {
      showStatus("라이선스 키를 입력하세요", "판매자에게 받은 KM 키를 붙여넣어 주세요.", AMBER);
      return;
    }
    if (!isActivationKeyFormat(entered)) {
      showStatus(
          "라이선스 키 형식을 확인하세요",
          "KM-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX 형식의 발급 키가 필요합니다. 안내문 전체를 붙여넣어도 됩니다.",
          AMBER);
      return;
    }
    setBusy(true);
    showStatus("라이선스 인증 중", "키를 확인하고 이 설치에 안전하게 연결하고 있습니다.", Color.rgb(151, 174, 255));
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
    String normalized = normalizeActivationKeyInput(value.toString());
    key.setText(normalized);
    key.setSelection(key.length());
    if (isActivationKeyFormat(normalized)) {
      showStatus("라이선스 키 준비 완료", "키 형식을 확인했습니다. ‘라이선스 인증’을 눌러 주세요.", GREEN);
    }
  }

  private void check() {
    if (busy) return;
    setBusy(true);
    showStatus("라이선스 확인 중", "기존 인증 정보를 확인하고 있습니다.", Color.rgb(151, 174, 255));
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
    return "지원 코드  "
        + id
        + "\n상태  "
        + (v.state == null ? "INVALID" : v.state)
        + "\n앱 버전  "
        + appVersion();
  }

  private void copySupport() {
    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
    if (cm == null) return;
    cm.setPrimaryClip(ClipData.newPlainText("카톡매크로 지원 정보", LicenseManager.diagnostic(this)));
    toast("지원 정보를 복사했습니다.");
  }

  private void showStatus(String title, String detail, int color) {
    if (statusTitle == null) return;
    statusTitle.setText("●  " + title);
    statusTitle.setTextColor(color);
    statusDetail.setText(detail == null || detail.trim().isEmpty() ? "상태를 확인해 주세요." : detail.trim());
  }

  private void setBusy(boolean v) {
    busy = v;
    activate.setEnabled(!v);
    retry.setEnabled(!v);
    activate.setAlpha(v ? 0.68f : 1f);
    retry.setAlpha(v ? 0.58f : 1f);
    activate.setText(v ? "보안 확인 중…" : "라이선스 인증");
  }

  private LinearLayout step(String number, String title, String detail) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.TOP);
    TextView badge = text(number, 11, true, Color.rgb(190, 202, 255));
    badge.setGravity(Gravity.CENTER);
    badge.setLetterSpacing(0.06f);
    badge.setBackground(roundStroke(Color.rgb(28, 37, 63), Color.rgb(67, 83, 133), 12));
    LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(38), dp(30));
    row.addView(badge, badgeLp);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.addView(text(title, 14, true, TEXT));
    body.addView(text(detail, 12, false, Color.rgb(155, 166, 186)), top(3));
    LinearLayout.LayoutParams bodyLp = weight();
    bodyLp.leftMargin = dp(12);
    row.addView(body, bodyLp);
    return row;
  }

  private TextView trustChip(String value) {
    TextView t = text(value, 10, true, Color.rgb(173, 188, 221));
    t.setGravity(Gravity.CENTER);
    t.setPadding(dp(6), dp(8), dp(6), dp(8));
    t.setBackground(roundStroke(Color.rgb(24, 31, 49), Color.rgb(54, 67, 97), 10));
    return t;
  }

  private LinearLayout section(String title, String detail) {
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    TextView t = text(title, 17, true, Color.rgb(226, 232, 244));
    TextView d = text(detail, 11, false, Color.rgb(118, 129, 149));
    box.addView(t);
    box.addView(d, top(3));
    return box;
  }

  private LinearLayout premiumCard(int fill, int stroke) {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setPadding(dp(17), dp(17), dp(17), dp(17));
    l.setBackground(roundStroke(fill, stroke, 17));
    l.setElevation(dp(2));
    return l;
  }

  private TextView divider() {
    TextView d = new TextView(this);
    d.setBackgroundColor(Color.rgb(39, 47, 63));
    d.setHeight(dp(1));
    return d;
  }

  private TextView text(String value, int size, boolean bold, int color) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextColor(color);
    t.setTextSize(size);
    t.setLineSpacing(0, 1.08f);
    if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
    return t;
  }

  private TextView pill(String value, int bg, int fg) {
    TextView t = text(value, 10, true, fg);
    t.setGravity(Gravity.CENTER);
    t.setPadding(dp(10), dp(6), dp(10), dp(6));
    t.setBackground(roundStroke(bg, Color.rgb(67, 78, 101), 14));
    return t;
  }

  private Button primaryButton(String value) {
    Button b = button(value, PRIMARY, PRIMARY);
    b.setElevation(dp(3));
    return b;
  }

  private Button secondaryButton(String value) {
    return button(value, Color.rgb(30, 36, 50), Color.rgb(58, 68, 88));
  }

  private Button button(String value, int color, int stroke) {
    Button b = new Button(this);
    b.setText(value);
    b.setAllCaps(false);
    b.setTextColor(TEXT);
    b.setTextSize(13);
    b.setTypeface(b.getTypeface(), Typeface.BOLD);
    b.setMinHeight(dp(50));
    b.setBackground(roundStroke(color, stroke, 12));
    return b;
  }

  private GradientDrawable roundStroke(int color, int stroke, int radiusDp) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radiusDp));
    d.setStroke(dp(1), stroke);
    return d;
  }

  private LinearLayout.LayoutParams top(int v) {
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.topMargin = dp(v);
    return lp;
  }

  private LinearLayout.LayoutParams weight() {
    return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
  }

  private LinearLayout.LayoutParams wrap() {
    return new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
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
