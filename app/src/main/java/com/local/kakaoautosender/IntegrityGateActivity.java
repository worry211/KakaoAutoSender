package com.local.kakaoautosender;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Launcher gate that refuses to enter the product from a re-signed or debuggable release APK. */
public final class IntegrityGateActivity extends Activity {
  private boolean forwarded;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    PremiumChrome.applyWindow(this);
    AppIntegrity.initialize(this);
    if (AppIntegrity.isAuthentic(this)) {
      forward();
      return;
    }
    AppIntegrity.trip(this);
    setContentView(blockedView());
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (!forwarded && !BuildConfig.DEBUG && AppIntegrity.isAuthentic(this)) forward();
  }

  private void forward() {
    if (forwarded) return;
    forwarded = true;
    startActivity(new Intent(this, LicenseActivity.class));
    finish();
  }

  private LinearLayout blockedView() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setGravity(Gravity.CENTER);
    root.setPadding(dp(24), dp(36), dp(24), dp(36));
    root.setBackgroundColor(Color.rgb(9, 11, 16));

    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setGravity(Gravity.CENTER_HORIZONTAL);
    card.setPadding(dp(22), dp(22), dp(22), dp(22));
    card.setBackground(roundStroke(Color.rgb(18, 23, 34), Color.rgb(66, 55, 70), 18));
    card.setElevation(dp(3));
    root.addView(
        card,
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

    TextView badge = text("OFFICIAL BUILD REQUIRED", 10, true, Color.rgb(248, 174, 180));
    badge.setLetterSpacing(0.08f);
    badge.setGravity(Gravity.CENTER);
    badge.setPadding(dp(10), dp(6), dp(10), dp(6));
    badge.setBackground(roundStroke(Color.rgb(55, 30, 35), Color.rgb(105, 53, 60), 14));
    card.addView(
        badge,
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

    TextView brand = text("카톡매크로", 27, true, Color.rgb(240, 244, 250));
    brand.setLetterSpacing(-0.015f);
    card.addView(brand, top(16));

    TextView title = text("앱 무결성 확인 실패", 20, true, Color.rgb(245, 151, 158));
    title.setGravity(Gravity.CENTER);
    card.addView(title, top(14));

    TextView detail =
        text(
            "현재 APK의 서명이 공식 판매용 빌드와 일치하지 않습니다.\n수정·재서명된 앱에서는 라이선스 인증과 자동전송을 사용할 수 없습니다.\n\n판매자에게 받은 공식 APK를 다시 설치해 주세요.",
            13,
            false,
            Color.rgb(181, 191, 210));
    detail.setGravity(Gravity.CENTER);
    detail.setLineSpacing(0, 1.15f);
    card.addView(detail, top(10));

    TextView code = text("보안 코드 · " + AppIntegrity.reason(), 11, false, Color.rgb(112, 123, 145));
    code.setGravity(Gravity.CENTER);
    code.setTypeface(Typeface.MONOSPACE);
    card.addView(code, top(16));

    TextView footer =
        text(
            "정품 보호 기능이 활성화되어 있습니다.",
            11,
            false,
            Color.rgb(101, 113, 136));
    footer.setGravity(Gravity.CENTER);
    root.addView(footer, top(16));
    return root;
  }

  private TextView text(String value, int sp, boolean bold, int color) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(sp);
    t.setTextColor(color);
    t.setIncludeFontPadding(false);
    if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
    return t;
  }

  private GradientDrawable roundStroke(int fill, int stroke, int radiusDp) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(dp(radiusDp));
    d.setStroke(dp(1), stroke);
    return d;
  }

  private LinearLayout.LayoutParams top(int value) {
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.topMargin = dp(value);
    return lp;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
