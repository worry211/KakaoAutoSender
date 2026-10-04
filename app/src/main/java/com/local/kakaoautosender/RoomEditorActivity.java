package com.local.kakaoautosender;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class RoomEditorActivity extends Activity {
    static final String EXTRA_ROOM = "room";

    private String room;
    private EditText displayNameInput;
    private EditText messageInput;
    private EditText intervalInput;
    private EditText timesInput;
    private EditText dailyLimitInput;
    private CheckBox unlimitedCheck;
    private CheckBox enabledCheck;
    private RadioButton intervalRadio;
    private RadioButton timesRadio;
    private LinearLayout intervalBox;
    private LinearLayout timesBox;
    private TextView connectionStatus;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        room = getIntent().getStringExtra(EXTRA_ROOM);
        if (room == null) room = "";
        room = room.trim();
        if (room.isEmpty()) {
            finish();
            return;
        }
        MultiRoomStore.ensureMigrated(this);
        if (MultiRoomStore.get(this, room) == null) MultiRoomStore.upsert(this, new MultiRoomStore.Profile(room));
        setContentView(buildUi());
        loadProfile();
    }

    @Override protected void onResume() {
        super.onResume();
        KakaoNotificationListener.requestRefresh();
        refreshConnection();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(13, 14, 17));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(22), dp(16), dp(36));
        scroll.addView(root);

        root.addView(text("방 설정", 26, true));
        TextView actualLabel = text("실제 카카오 방", 12, true);
        actualLabel.setTextColor(Color.rgb(145, 150, 160));
        root.addView(actualLabel, top(18));
        TextView actual = text(room, 17, true);
        actual.setPadding(dp(12), dp(12), dp(12), dp(12));
        actual.setBackground(round(Color.rgb(32, 35, 42), 12));
        root.addView(actual, top(5));

        connectionStatus = text("", 13, true);
        root.addView(connectionStatus, top(10));
        Button reconnect = button("이 방 연결 다시 확인");
        reconnect.setOnClickListener(v -> reconnectRoom());
        root.addView(reconnect, top(8));

        root.addView(section("내가 알아보기 쉬운 이름"), top(24));
        TextView nameHelp = text("카카오 실제 방 이름은 바뀌지 않고, 여기 이름은 이 앱 안에서만 보여.", 12, false);
        nameHelp.setTextColor(Color.GRAY);
        root.addView(nameHelp, top(4));
        displayNameInput = edit("예: 발로란트 홍보방 1", false);
        root.addView(displayNameInput, top(8));

        root.addView(section("보낼 메시지"), top(22));
        messageInput = edit("이 방에 자동으로 보낼 메시지", true);
        root.addView(messageInput, top(8));

        root.addView(section("전송 시간"), top(22));
        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.HORIZONTAL);
        intervalRadio = new RadioButton(this);
        intervalRadio.setText("간격 반복"); intervalRadio.setTextColor(Color.WHITE);
        timesRadio = new RadioButton(this);
        timesRadio.setText("매일 지정 시각"); timesRadio.setTextColor(Color.WHITE);
        modes.addView(intervalRadio, new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
        modes.addView(timesRadio, new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(modes, top(6));

        intervalBox = new LinearLayout(this);
        intervalBox.setOrientation(LinearLayout.VERTICAL);
        intervalInput = edit("간격(분) · 최소 1분", false);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalBox.addView(intervalInput);
        TextView intervalHelp = text("예: 1 = 1분마다, 10 = 10분마다, 60 = 1시간마다", 11, false);
        intervalHelp.setTextColor(Color.GRAY);
        intervalBox.addView(intervalHelp, top(4));
        root.addView(intervalBox, top(8));

        timesBox = new LinearLayout(this);
        timesBox.setOrientation(LinearLayout.VERTICAL);
        timesInput = edit("매일 보낼 시각 · 예: 09:00, 13:30, 20:00", false);
        timesBox.addView(timesInput);
        TextView timesHelp = text("쉼표나 공백으로 여러 시각을 넣을 수 있어. 정확 알람 권한이 없으면 안드로이드 배터리 정책 때문에 몇 분 정도 늦어질 수 있어.", 11, false);
        timesHelp.setTextColor(Color.GRAY);
        timesBox.addView(timesHelp, top(4));
        root.addView(timesBox, top(8));

        modes.setOnCheckedChangeListener((group, checkedId) -> updateScheduleVisibility());

        root.addView(section("횟수 / 사용 여부"), top(22));
        LinearLayout limitRow = new LinearLayout(this);
        limitRow.setOrientation(LinearLayout.HORIZONTAL);
        dailyLimitInput = edit("하루 최대 횟수", false);
        dailyLimitInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        limitRow.addView(dailyLimitInput, weight());
        unlimitedCheck = new CheckBox(this);
        unlimitedCheck.setText("무제한"); unlimitedCheck.setTextColor(Color.WHITE);
        unlimitedCheck.setOnCheckedChangeListener((b, checked) -> dailyLimitInput.setEnabled(!checked));
        LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        uLp.leftMargin = dp(10);
        limitRow.addView(unlimitedCheck, uLp);
        root.addView(limitRow, top(8));

        enabledCheck = new CheckBox(this);
        enabledCheck.setText("이 방 자동전송 사용"); enabledCheck.setTextColor(Color.WHITE);
        root.addView(enabledCheck, top(6));

        Button save = actionButton("저장", Color.rgb(48, 88, 158));
        save.setOnClickListener(v -> saveProfile(true));
        root.addView(save, top(18));

        Button test = actionButton("지금 1회 전송", Color.rgb(46, 112, 78));
        test.setOnClickListener(v -> testSend());
        root.addView(test, top(8));

        Button delete = actionButton("이 방 설정 삭제", Color.rgb(126, 52, 58));
        delete.setOnClickListener(v -> deleteProfile());
        root.addView(delete, top(8));

        Button back = button("대시보드로 돌아가기");
        back.setOnClickListener(v -> finish());
        root.addView(back, top(8));

        return scroll;
    }

    private void loadProfile() {
        MultiRoomStore.Profile p = MultiRoomStore.get(this, room);
        if (p == null) p = new MultiRoomStore.Profile(room);
        displayNameInput.setText(p.title());
        messageInput.setText(p.message);
        intervalInput.setText(String.valueOf(p.intervalMinutes));
        timesInput.setText(p.dailyTimes);
        if (p.fixedTimes()) timesRadio.setChecked(true); else intervalRadio.setChecked(true);
        unlimitedCheck.setChecked(p.unlimited());
        dailyLimitInput.setText(p.unlimited() ? "8" : String.valueOf(p.dailyLimit));
        dailyLimitInput.setEnabled(!p.unlimited());
        enabledCheck.setChecked(p.enabled);
        updateScheduleVisibility();
        refreshConnection();
    }

    private void updateScheduleVisibility() {
        boolean fixed = timesRadio != null && timesRadio.isChecked();
        if (intervalBox != null) intervalBox.setVisibility(fixed ? View.GONE : View.VISIBLE);
        if (timesBox != null) timesBox.setVisibility(fixed ? View.VISIBLE : View.GONE);
    }

    private boolean saveProfile(boolean notify) {
        MultiRoomStore.Profile p = MultiRoomStore.get(this, room);
        if (p == null) p = new MultiRoomStore.Profile(room);
        String display = displayNameInput.getText().toString().trim();
        p.displayName = display.isEmpty() ? room : display;
        p.message = messageInput.getText().toString();
        p.enabled = enabledCheck.isChecked();
        p.dailyLimit = unlimitedCheck.isChecked() ? 0 : Math.max(1, parseInt(dailyLimitInput.getText().toString(), 8));

        if (timesRadio.isChecked()) {
            String raw = timesInput.getText().toString().trim();
            if (!MultiRoomStore.hasValidTimes(raw)) {
                if (notify) toast("지정 시각을 HH:mm 형식으로 하나 이상 입력해줘.");
                return false;
            }
            p.scheduleMode = MultiRoomStore.MODE_TIMES;
            p.dailyTimes = MultiRoomStore.canonicalTimes(raw);
            timesInput.setText(p.dailyTimes);
        } else {
            p.scheduleMode = MultiRoomStore.MODE_INTERVAL;
            p.intervalMinutes = Math.max(SendScheduler.MIN_INTERVAL_MINUTES,
                    parseInt(intervalInput.getText().toString(), 60));
            intervalInput.setText(String.valueOf(p.intervalMinutes));
        }

        boolean active = Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false);
        if (active && p.enabled && !p.message.trim().isEmpty()) {
            p.nextAt = MultiRoomStore.computeNextAt(p, System.currentTimeMillis());
        } else if (!p.enabled || p.message.trim().isEmpty()) {
            p.nextAt = 0L;
        }

        MultiRoomStore.upsert(this, p);
        if (active) SendScheduler.scheduleNext(this);
        Prefs.setStatus(this, "방 설정 저장: " + p.title() + " · " + MultiRoomStore.scheduleSummary(p));
        if (notify) toast("저장했어.");
        return true;
    }

    private void testSend() {
        if (!saveProfile(false)) return;
        MultiRoomStore.Profile p = MultiRoomStore.get(this, room);
        if (p == null || p.message.trim().isEmpty()) {
            toast("보낼 메시지를 입력해줘."); return;
        }
        if (!KakaoNotificationListener.hasLiveSession(room)) {
            toast("이 방의 실시간 답장 세션이 없어. 새 메시지를 받은 뒤 ‘연결 다시 확인’을 눌러줘.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("지금 1회 전송")
                .setMessage("내 이름: " + p.title() + "\n실제 카카오 방: " + p.room + "\n\n" + p.message)
                .setPositiveButton("전송", (d, w) -> {
                    boolean ok = KakaoNotificationListener.sendToRoom(this, room, p.message);
                    Prefs.setStatus(this, ok ? "수동 전송 성공: " + p.title()
                            : "수동 전송 실패: " + p.title() + " · " + KakaoNotificationListener.lastSendError());
                    toast(ok ? "전송 성공" : "전송 실패: " + KakaoNotificationListener.lastSendError());
                    refreshConnection();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void reconnectRoom() {
        KakaoNotificationListener.requestRefresh();
        ArrayList<KakaoNotificationListener.SessionEntry> all = KakaoNotificationListener.candidateSessionEntries();
        ArrayList<KakaoNotificationListener.SessionEntry> matches = new ArrayList<>();
        for (KakaoNotificationListener.SessionEntry e : all) {
            if (e.suggestedRoom != null && e.suggestedRoom.trim().equalsIgnoreCase(room)) matches.add(e);
        }
        if (matches.isEmpty()) {
            toast("이 방의 새 알림 후보가 없어. 실제 방에서 메시지를 하나 받은 뒤 다시 눌러줘.");
            return;
        }
        String[] items = new String[matches.size()];
        for (int i = 0; i < matches.size(); i++) items[i] = matches.get(i).description;
        new AlertDialog.Builder(this)
                .setTitle("실제 방 확인 · " + room)
                .setItems(items, (d, which) -> {
                    boolean ok = KakaoNotificationListener.bindRecentTokenToRoom(this, matches.get(which).token, room);
                    toast(ok ? "이 방 연결을 다시 확인했어." : "세션이 만료됐어.");
                    refreshConnection();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void deleteProfile() {
        MultiRoomStore.Profile p = MultiRoomStore.get(this, room);
        String title = p == null ? room : p.title();
        new AlertDialog.Builder(this)
                .setTitle("이 방 설정을 삭제할까?")
                .setMessage("내 이름: " + title + "\n실제 카카오 방: " + room + "\n\n메시지와 시간 설정, 연결 규칙이 모두 삭제돼.")
                .setPositiveButton("삭제", (d, w) -> {
                    MultiRoomStore.remove(this, room);
                    KakaoNotificationListener.unbindRoom(this, room);
                    if (Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false)) SendScheduler.scheduleNext(this);
                    toast("삭제했어.");
                    finish();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void refreshConnection() {
        if (connectionStatus == null) return;
        boolean live = KakaoNotificationListener.hasLiveSession(room);
        boolean stored = KakaoNotificationListener.hasStoredBinding(this, room);
        connectionStatus.setText(live ? "● 연결 확인됨 · 지금 전송 가능"
                : stored ? "● 자동복구 정보 있음 · 이 방 새 알림 대기"
                : "● 연결 필요 · 이 방에서 새 메시지를 받은 뒤 확인");
        connectionStatus.setTextColor(live ? Color.rgb(89, 220, 146)
                : stored ? Color.rgb(245, 186, 80) : Color.rgb(238, 112, 112));
    }

    private int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private TextView section(String value) {
        TextView v = text(value, 17, true); v.setTextColor(Color.rgb(218, 228, 255)); return v;
    }
    private TextView text(String value, int sp, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextColor(Color.WHITE); v.setTextSize(sp);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); return v;
    }
    private EditText edit(String hint, boolean multiline) {
        EditText e = new EditText(this); e.setHint(hint); e.setHintTextColor(Color.GRAY); e.setTextColor(Color.WHITE);
        e.setBackground(round(Color.rgb(35, 38, 45), 10)); e.setPadding(dp(12), dp(12), dp(12), dp(12));
        if (multiline) { e.setMinLines(4); e.setGravity(Gravity.TOP); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); }
        return e;
    }
    private Button button(String label) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE); return b; }
    private Button actionButton(String label, int color) { Button b = button(label); b.setBackground(round(color, 11)); return b; }
    private GradientDrawable round(int color, int radiusDp) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); return d; }
    private LinearLayout.LayoutParams top(int v) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.topMargin = dp(v); return lp; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
}
