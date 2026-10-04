package com.local.kakaoautosender;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
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
    root.setPadding(dp(28), dp(36), dp(28), dp(36));
    root.setBackgroundColor(Color.rgb(12, 13, 16));

    TextView title = text("앱 무결성 확인 실패", 23, true, Color.WHITE);
    title.setGravity(Gravity.CENTER);
    root.addView(title);

    TextView detail =
        text(
            "이 APK는 공식 판매용 서명과 일치하지 않습니다.\n수정·재서명된 앱에서는 라이선스 인증과 자동전송을 사용할 수 없습니다.\n\n판매자에게 받은 공식 APK를 다시 설치해 주세요.",
            14,
            false,
            Color.rgb(184, 190, 202));
    detail.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams detailLp =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    detailLp.topMargin = dp(14);
    root.addView(detail, detailLp);

    TextView code =
        text("보안 코드: " + AppIntegrity.reason(), 11, false, Color.rgb(132, 139, 151));
    code.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams codeLp =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    codeLp.topMargin = dp(18);
    root.addView(code, codeLp);
    return root;
  }

  private TextView text(String value, int sp, boolean bold, int color) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(sp);
    t.setTextColor(color);
    if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
    return t;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
