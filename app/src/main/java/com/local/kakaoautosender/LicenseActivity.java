package com.local.kakaoautosender;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
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
    private EditText licenseInput;
    private TextView status;
    private TextView deviceCode;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        LicenseManager.Verification current = LicenseManager.verifyStored(this);
        if (current.valid) {
            openApp();
            return;
        }
        status.setText(current.message);
    }

    private android.view.View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(12, 13, 16));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(40));
        scroll.addView(root);

        TextView title = text("카톡매크로", 30, true);
        root.addView(title);
        TextView subtitle = text("라이선스 인증", 16, true);
        subtitle.setTextColor(Color.rgb(150, 190, 255));
        root.addView(subtitle, top(6));

        TextView guide = text("판매자에게 아래 기기 코드를 보내고, 받은 라이선스 키를 붙여넣어 인증해.", 13, false);
        guide.setTextColor(Color.rgb(170, 176, 188));
        root.addView(guide, top(14));

        LinearLayout deviceCard = card(Color.rgb(28, 31, 37));
        deviceCard.addView(text("내 기기 코드", 13, true));
        deviceCode = text(LicenseManager.deviceCode(this), 19, true);
        deviceCode.setTextIsSelectable(true);
        deviceCode.setTextColor(Color.rgb(126, 196, 255));
        deviceCard.addView(deviceCode, top(8));
        Button copy = button("기기 코드 복사", Color.rgb(58, 65, 78));
        copy.setOnClickListener(v -> copyDeviceCode());
        deviceCard.addView(copy, top(10));
        root.addView(deviceCard, top(18));

        root.addView(text("라이선스 키", 15, true), top(22));
        licenseInput = new EditText(this);
        licenseInput.setHint("KAS1....");
        licenseInput.setHintTextColor(Color.GRAY);
        licenseInput.setTextColor(Color.WHITE);
        licenseInput.setMinLines(5);
        licenseInput.setGravity(Gravity.TOP);
        licenseInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        licenseInput.setBackground(round(Color.rgb(35, 38, 45), 12));
        licenseInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(licenseInput, top(8));

        Button activate = button("라이선스 인증", Color.rgb(48, 88, 158));
        activate.setOnClickListener(v -> activate());
        root.addView(activate, top(12));

        status = text("", 13, true);
        status.setTextColor(Color.rgb(235, 166, 89));
        root.addView(status, top(12));

        TextView note = text("라이선스는 이 휴대폰의 기기 코드에 묶여 있어. 다른 기기에 그대로 복사해도 인증되지 않아.", 11, false);
        note.setTextColor(Color.rgb(125, 131, 143));
        root.addView(note, top(18));
        return scroll;
    }

    private void activate() {
        String value = licenseInput.getText().toString().trim();
        LicenseManager.Verification result = LicenseManager.activate(this, value);
        if (!result.valid) {
            status.setText(result.message);
            status.setTextColor(Color.rgb(235, 118, 118));
            return;
        }
        status.setText("인증 완료 · " + result.expiryLabel());
        status.setTextColor(Color.rgb(91, 224, 147));
        Toast.makeText(this, "라이선스 인증 완료", Toast.LENGTH_SHORT).show();
        openApp();
    }

    private void copyDeviceCode() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("기기 코드", deviceCode.getText()));
        Toast.makeText(this, "기기 코드를 복사했어.", Toast.LENGTH_SHORT).show();
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivityV4.class);
        startActivity(i);
        finish();
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value); v.setTextColor(Color.WHITE); v.setTextSize(sp);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private Button button(String label, int color) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setBackground(round(color, 12)); b.setMinHeight(dp(48));
        return b;
    }

    private LinearLayout card(int color) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(14), dp(14), dp(14));
        l.setBackground(round(color, 16));
        return l;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radiusDp)); return d;
    }

    private LinearLayout.LayoutParams top(int v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(v); return lp;
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
}
