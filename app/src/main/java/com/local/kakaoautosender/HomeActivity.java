package com.local.kakaoautosender;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class HomeActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!LicenseManager.isUsable(this)) {
            startActivity(new Intent(this, LicenseActivity.class));
            finish();
            return;
        }
        setContentView(buildUi());
    }

    private android.view.View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(12, 13, 16));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(40));
        scroll.addView(root);

        root.addView(text("카톡매크로", 30, true));
        TextView sub = text("카카오톡 공식 앱에 로그인된 본인 계정을 그대로 사용하는 로컬 자동화 도구", 13, false);
        sub.setTextColor(Color.rgb(156, 163, 176));
        root.addView(sub, top(5));

        LinearLayout voice = card();
        voice.addView(text("보이스룸 자동관리", 21, true));
        TextView vd = text("휴대폰에 직접 설치해서 여러 오픈채팅방을 관리하는 모바일 모드야.", 13, false);
        vd.setTextColor(Color.rgb(174, 180, 191));
        voice.addView(vd, top(7));
        Button voiceOpen = button("보이스룸 매니저 열기", Color.rgb(48, 88, 158));
        voiceOpen.setOnClickListener(v -> startActivity(new Intent(this, VoiceRoomManagerActivity.class)));
        voice.addView(voiceOpen, top(12));
        root.addView(voice, top(20));

        LinearLayout sender = card();
        sender.addView(text("카톡 자동전송", 21, true));
        TextView sd = text("기존 다중방 메시지·사진 자동전송 기능", 13, false);
        sd.setTextColor(Color.rgb(174, 180, 191));
        sender.addView(sd, top(7));
        Button senderOpen = button("자동전송 열기", Color.rgb(58, 65, 78));
        senderOpen.setOnClickListener(v -> startActivity(new Intent(this, MainActivityV4.class)));
        sender.addView(senderOpen, top(12));
        root.addView(sender, top(12));

        TextView privacy = text("카카오 아이디·비밀번호·로그인 토큰은 이 앱에 입력하거나 저장하지 않아.", 12, false);
        privacy.setTextColor(Color.rgb(126, 196, 255));
        root.addView(privacy, top(18));
        return scroll;
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
        b.setBackground(round(color, 12)); b.setMinHeight(dp(48)); return b;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(14), dp(14), dp(14), dp(14));
        l.setBackground(round(Color.rgb(28, 31, 37), 16)); return l;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); return d;
    }

    private LinearLayout.LayoutParams top(int v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(v); return lp;
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
}
