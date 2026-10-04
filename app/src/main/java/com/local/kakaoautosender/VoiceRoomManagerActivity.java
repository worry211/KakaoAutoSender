package com.local.kakaoautosender;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class VoiceRoomManagerActivity extends Activity {
    private LinearLayout roomList;
    private TextView masterStatus;
    private Switch muteSwitch;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!LicenseManager.isUsable(this)) {
            startActivity(new Intent(this, LicenseActivity.class));
            finish();
            return;
        }
        setContentView(buildUi());
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private android.view.View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(12, 13, 16));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(40));
        scroll.addView(root);

        root.addView(text("보이스룸 매니저", 28, true));
        TextView sub = text("카카오톡 공식 앱에 로그인된 본인 계정을 사용해. 카카오 로그인 정보는 이 앱에 저장하지 않아.", 12, false);
        sub.setTextColor(Color.rgb(145, 153, 168));
        root.addView(sub, top(5));

        LinearLayout master = card(Color.rgb(27, 30, 36));
        masterStatus = text("", 17, true);
        master.addView(masterStatus);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button start = button("전체 시작", Color.rgb(39, 126, 83));
        start.setOnClickListener(v -> startAll());
        buttons.addView(start, weight());
        Button stop = button("전체 중단", Color.rgb(142, 54, 61));
        stop.setOnClickListener(v -> stopAll());
        LinearLayout.LayoutParams stopLp = weight();
        stopLp.leftMargin = dp(7);
        buttons.addView(stop, stopLp);
        master.addView(buttons, top(12));

        muteSwitch = new Switch(this);
        muteSwitch.setText("자동관리 중 보이스룸 소리 음소거 모드");
        muteSwitch.setTextColor(Color.WHITE);
        muteSwitch.setChecked(VoiceRoomStore.isMuteAudio(this));
        muteSwitch.setOnCheckedChangeListener((buttonView, checked) -> VoiceRoomStore.setMuteAudio(this, checked));
        master.addView(muteSwitch, top(8));
        root.addView(master, top(18));

        root.addView(text("최초 설정", 18, true), top(22));
        Button accessibility = button("보이스룸 자동화 접근성 설정 열기", Color.rgb(58, 65, 78));
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, top(8));
        Button battery = button("배터리 최적화 설정 열기", Color.rgb(58, 65, 78));
        battery.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        root.addView(battery, top(7));

        TextView lock = text("화면 OFF 상태 운영은 목표로 하지만, PIN·패턴 같은 보안 잠금이 실제로 잠기면 이를 우회하지 않아. 실전 전 한 번 직접 테스트해서 기기별 동작을 확인해야 해.", 11, false);
        lock.setTextColor(Color.rgb(235, 166, 89));
        root.addView(lock, top(9));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.addView(text("관리할 방", 18, true), weight());
        Button add = button("＋ 방 추가", Color.rgb(48, 88, 158));
        add.setOnClickListener(v -> addRoom());
        header.addView(add);
        root.addView(header, top(24));

        roomList = new LinearLayout(this);
        roomList.setOrientation(LinearLayout.VERTICAL);
        root.addView(roomList, top(6));
        return scroll;
    }

    private void addRoom() {
        EditText input = new EditText(this);
        input.setHint("카톡에 보이는 오픈채팅방 이름 그대로");
        input.setSingleLine(true);
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("보이스룸 방 추가")
                .setView(input)
                .setNegativeButton("취소", null)
                .setPositiveButton("추가", (d, w) -> {
                    String title = input.getText().toString().trim();
                    if (title.isEmpty()) {
                        toast("방 이름을 입력해줘.");
                        return;
                    }
                    VoiceRoomStore.add(this, title);
                    refresh();
                }).show();
    }

    private void startAll() {
        if (VoiceRoomStore.list(this).isEmpty()) {
            toast("먼저 방을 추가해줘.");
            return;
        }
        VoiceRoomStore.setGlobalEnabled(this, true);
        refresh();
        toast("모바일 보이스룸 자동관리를 켰어.");
    }

    private void stopAll() {
        VoiceRoomStore.setGlobalEnabled(this, false);
        refresh();
        toast("보이스룸 자동관리를 중단했어.");
    }

    private void refresh() {
        if (masterStatus == null || roomList == null) return;
        boolean running = VoiceRoomStore.isGlobalEnabled(this);
        masterStatus.setText(running ? "● 자동관리 실행 중" : "● 자동관리 중지됨");
        masterStatus.setTextColor(running ? Color.rgb(91, 224, 147) : Color.rgb(213, 216, 223));
        if (muteSwitch != null) muteSwitch.setChecked(VoiceRoomStore.isMuteAudio(this));

        roomList.removeAllViews();
        if (VoiceRoomStore.list(this).isEmpty()) {
            TextView empty = text("등록된 방이 없어. 카카오톡에 보이는 정확한 방 이름으로 추가해줘.", 13, false);
            empty.setTextColor(Color.rgb(166, 171, 182));
            roomList.addView(empty, top(8));
            return;
        }
        for (VoiceRoomStore.Room room : VoiceRoomStore.list(this)) roomList.addView(roomCard(room), top(8));
    }

    private android.view.View roomCard(VoiceRoomStore.Room room) {
        LinearLayout card = card(Color.rgb(29, 32, 38));
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.addView(text(room.title, 17, true), weight());
        Switch enabled = new Switch(this);
        enabled.setText(room.enabled ? "ON" : "OFF");
        enabled.setTextColor(Color.WHITE);
        enabled.setChecked(room.enabled);
        enabled.setOnCheckedChangeListener((buttonView, checked) -> {
            room.enabled = checked;
            VoiceRoomStore.upsert(this, room);
            refresh();
        });
        titleRow.addView(enabled);
        card.addView(titleRow);

        TextView info = text(statusLabel(room.status) + " · 실패 " + room.failures + "회", 12, true);
        info.setTextColor("ERROR".equals(room.status) ? Color.rgb(234, 108, 108) : Color.rgb(174, 180, 191));
        card.addView(info, top(7));
        if (room.lastError != null && !room.lastError.isEmpty()) {
            TextView err = text(room.lastError, 11, false);
            err.setTextColor(Color.rgb(235, 166, 89));
            card.addView(err, top(5));
        }

        Button delete = button("이 방 삭제", Color.rgb(70, 72, 80));
        delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("방 삭제")
                .setMessage("‘" + room.title + "’ 설정을 삭제할까?")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (d, w) -> {
                    VoiceRoomStore.remove(this, room.id);
                    refresh();
                }).show());
        card.addView(delete, top(9));
        return card;
    }

    private String statusLabel(String s) {
        if (s == null || s.isEmpty() || "NEW".equals(s)) return "대기";
        if ("ACTIVE".equals(s)) return "실행 중";
        if ("CREATED".equals(s)) return "재개설 완료";
        if ("ERROR".equals(s)) return "오류";
        return s;
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
        b.setBackground(round(color, 12)); b.setMinHeight(dp(44)); return b;
    }

    private LinearLayout card(int color) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(13), dp(13), dp(13), dp(13));
        l.setBackground(round(color, 16)); return l;
    }

    private GradientDrawable round(int color, int r) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(r)); return d;
    }

    private LinearLayout.LayoutParams top(int v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(v); return lp;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
