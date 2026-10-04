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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class RoomEditorActivity extends Activity {
    static final String EXTRA_ROOM = "room";

    private String routeAlias;
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
    private TextView nextPreview;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        routeAlias = getIntent().getStringExtra(EXTRA_ROOM);
        if (routeAlias == null) routeAlias = "";
        routeAlias = routeAlias.trim();
        if (routeAlias.isEmpty()) { finish(); return; }

        MultiRoomStore.ensureMigrated(this);
        if (MultiRoomStore.get(this, routeAlias) == null) { finish(); return; }
        setContentView(buildUi());
        loadProfile();
    }

    @Override protected void onResume() {
        super.onResume();
        KakaoNotificationListener.requestRefresh();
        refreshConnection();
        refreshNextPreview();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(12, 13, 16));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(40));
        scroll.addView(root);

        MultiRoomStore.Profile p = MultiRoomStore.get(this, routeAlias);
        String roomName = p == null ? "방 설정" : p.title();
        root.addView(text(roomName, 26, true));
        TextView subtitle = text("이 방의 메시지와 전송 시간을 설정해", 12, false);
        subtitle.setTextColor(Color.rgb(143, 149, 160));
        root.addView(subtitle, top(4));

        LinearLayout connectionCard = card(Color.rgb(28, 31, 37));
        connectionStatus = text("", 13, true);
        connectionCard.addView(connectionStatus);
        Button reconnect = compactButton("이 방 연결 다시 확인", Color.rgb(62, 66, 76));
        reconnect.setOnClickListener(v -> reconnectRoom());
        connectionCard.addView(reconnect, top(9));
        root.addView(connectionCard, top(18));

        root.addView(section("보낼 메시지"), top(24));
        messageInput = edit("자동으로 보낼 메시지", true);
        root.addView(messageInput, top(8));

        root.addView(section("전송 시간"), top(22));
        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.HORIZONTAL);
        intervalRadio = new RadioButton(this);
        intervalRadio.setText("간격 반복");
        intervalRadio.setTextColor(Color.WHITE);
        timesRadio = new RadioButton(this);
        timesRadio.setText("매일 지정 시각");
        timesRadio.setTextColor(Color.WHITE);
        modes.addView(intervalRadio, new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
        modes.addView(timesRadio, new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(modes, top(7));

        intervalBox = new LinearLayout(this);
        intervalBox.setOrientation(LinearLayout.VERTICAL);
        intervalInput = edit("간격(분) · 최소 1분", false);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalBox.addView(intervalInput);

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        int[] values = {1, 5, 10, 30, 60};
        String[] labels = {"1분", "5분", "10분", "30분", "1시간"};
        for (int i = 0; i < values.length; i++) {
            final int value = values[i];
            Button b = miniButton(labels[i]);
            b.setOnClickListener(v -> intervalInput.setText(String.valueOf(value)));
            LinearLayout.LayoutParams lp = weight();
            if (i > 0) lp.leftMargin = dp(5);
            presets.addView(b, lp);
        }
        intervalBox.addView(presets, top(7));
        TextView intervalHelp = text("1분부터 자유롭게 설정 가능해. 너무 짧은 반복은 카카오의 도배 제한이나 안드로이드 절전 정책 영향을 받을 수 있어.", 11, false);
        intervalHelp.setTextColor(Color.rgb(135, 141, 153));
        intervalBox.addView(intervalHelp, top(6));
        root.addView(intervalBox, top(8));

        timesBox = new LinearLayout(this);
        timesBox.setOrientation(LinearLayout.VERTICAL);
        timesInput = edit("예: 09:00, 13:30, 20:00", false);
        timesBox.addView(timesInput);
        TextView timesHelp = text("쉼표나 공백으로 여러 시각을 넣을 수 있어. 정확한 알람 권한이 없으면 안드로이드가 몇 분 늦출 수 있어.", 11, false);
        timesHelp.setTextColor(Color.rgb(135, 141, 153));
        timesBox.addView(timesHelp, top(6));
        root.addView(timesBox, top(8));

        modes.setOnCheckedChangeListener((group, checkedId) -> {
            updateScheduleVisibility();
            refreshNextPreview();
        });

        nextPreview = text("", 12, true);
        nextPreview.setTextColor(Color.rgb(143, 190, 255));
        root.addView(nextPreview, top(10));

        root.addView(section("횟수"), top(22));
        LinearLayout limitRow = new LinearLayout(this);
        limitRow.setOrientation(LinearLayout.HORIZONTAL);
        dailyLimitInput = edit("하루 최대 횟수", false);
        dailyLimitInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        limitRow.addView(dailyLimitInput, weight());
        unlimitedCheck = new CheckBox(this);
        unlimitedCheck.setText("무제한");
        unlimitedCheck.setTextColor(Color.WHITE);
        unlimitedCheck.setOnCheckedChangeListener((b, checked) -> dailyLimitInput.setEnabled(!checked));
        LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        uLp.leftMargin = dp(10);
        limitRow.addView(unlimitedCheck, uLp);
        root.addView(limitRow, top(8));

        enabledCheck = new CheckBox(this);
        enabledCheck.setText("이 방 자동전송 사용");
        enabledCheck.setTextColor(Color.WHITE);
        root.addView(enabledCheck, top(8));

        Button save = actionButton("저장", Color.rgb(48, 88, 158));
        save.setOnClickListener(v -> saveProfile(true));
        root.addView(save, top(18));

        Button test = actionButton("지금 1회 전송", Color.rgb(43, 116, 78));
        test.setOnClickListener(v -> testSend());
        root.addView(test, top(8));

        Button delete = actionButton("이 방 삭제", Color.rgb(126, 52, 58));
        delete.setOnClickListener(v -> deleteProfile());
        root.addView(delete, top(8));

        Button back = compactButton("대시보드로 돌아가기", Color.rgb(61, 65, 74));
        back.setOnClickListener(v -> finish());
        root.addView(back, top(8));
        return scroll;
    }

    private void loadProfile() {
        MultiRoomStore.Profile p = MultiRoomStore.get(this, routeAlias);
        if (p == null) { finish(); return; }
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
        refreshNextPreview();
    }

    private void updateScheduleVisibility() {
        boolean fixed = timesRadio != null && timesRadio.isChecked();
        if (intervalBox != null) intervalBox.setVisibility(fixed ? View.GONE : View.VISIBLE);
        if (timesBox != null) timesBox.setVisibility(fixed ? View.VISIBLE : View.GONE);
    }

    private boolean saveProfile(boolean notify) {
        MultiRoomStore.Profile p = MultiRoomStore.get(this, routeAlias);
        if (p == null) return false;

        p.message = messageInput.getText().toString();
        p.enabled = enabledCheck.isChecked();
        p.dailyLimit = unlimitedCheck.isChecked() ? 0
                : Math.max(1, parseInt(dailyLimitInput.getText().toString(), 8));

        if (timesRadio.isChecked()) {
            String raw = timesInput.getText().toString().trim();
            if (!MultiRoomStore.hasValidTimes(raw)) {
                if (notify) toast("시간을 HH:mm 형식으로 하나 이상 입력해줘.");
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
        refreshNextPreview();
        if (notify) {
            toast("저장했어.");
            if (!p.fixedTimes() && p.intervalMinutes < 5 && p.unlimited()) {
                Toast.makeText(this, "1~4분 무제한 반복은 카카오 정책에 따라 제한될 수 있어.", Toast.LENGTH_LONG).show();
            }
        }
        return true;
    }

    private void testSend() {
        if (!saveProfile(false)) return;
        MultiRoomStore.Profile p = MultiRoomStore.get(this, routeAlias);
        if (p == null || p.message.trim().isEmpty()) {
            toast("보낼 메시지를 입력해줘.");
            return;
        }
        if (!KakaoNotificationListener.hasLiveSession(routeAlias)) {
            toast("이 방 연결이 없어. 방에서 새 메시지를 받은 뒤 ‘연결 다시 확인’을 눌러줘.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(p.title())
                .setMessage("지금 1회 전송할까?\n\n" + p.message)
                .setPositiveButton("전송", (d, w) -> {
                    boolean ok = KakaoNotificationListener.sendToRoom(this, routeAlias, p.message);
                    Prefs.setStatus(this, ok ? "수동 전송 성공: " + p.title()
                            : "수동 전송 실패: " + p.title() + " · " + KakaoNotificationListener.lastSendError());
                    toast(ok ? "전송 성공" : "전송 실패: " + KakaoNotificationListener.lastSendError());
                    refreshConnection();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void reconnectRoom() {
        MultiRoomStore.Profile p = MultiRoomStore.get(this, routeAlias);
        if (p == null) return;
        KakaoNotificationListener.requestRefresh();
        ArrayList<KakaoNotificationListener.SessionEntry> all = KakaoNotificationListener.candidateSessionEntries();
        ArrayList<KakaoNotificationListener.SessionEntry> matches = new ArrayList<>();
        for (KakaoNotificationListener.SessionEntry e : all) {
            if (e.suggestedRoom != null && e.suggestedRoom.trim().equalsIgnoreCase(p.actualRoomName)) matches.add(e);
        }
        if (matches.isEmpty()) {
            toast("이 방의 새 알림이 없어. 실제 방에서 메시지를 하나 받은 뒤 다시 눌러줘.");
            return;
        }
        if (matches.size() > 1) {
            new AlertDialog.Builder(this)
                    .setTitle("같은 이름의 방이 여러 개 감지됐어")
                    .setMessage("잘못된 방 연결을 막기 위해 자동 선택하지 않았어. 대상 방에서 새 메시지를 받은 직후 다시 눌러줘.")
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }
        boolean ok = KakaoNotificationListener.bindRecentTokenToRoom(this, matches.get(0).token, routeAlias);
        toast(ok ? "연결을 다시 확인했어." : "세션이 만료됐어.");
        refreshConnection();
    }

    private void deleteProfile() {
        MultiRoomStore.Profile p = MultiRoomStore.get(this, routeAlias);
        String title = p == null ? "이 방" : p.title();
        new AlertDialog.Builder(this)
                .setTitle(title + " 삭제")
                .setMessage("메시지, 시간 설정, 카카오 연결 정보가 모두 삭제돼.")
                .setPositiveButton("삭제", (d, w) -> {
                    MultiRoomStore.remove(this, routeAlias);
                    KakaoNotificationListener.unbindRoom(this, routeAlias);
                    if (Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false)) SendScheduler.scheduleNext(this);
                    toast("삭제했어.");
                    finish();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void refreshConnection() {
        if (connectionStatus == null) return;
        boolean live = KakaoNotificationListener.hasLiveSession(routeAlias);
        boolean stored = KakaoNotificationListener.hasStoredBinding(this, routeAlias);
        connectionStatus.setText(live ? "● 연결됨 · 지금 전송 가능"
                : stored ? "● 새 알림 대기 · 오면 자동으로 복구"
                : "● 연결 필요 · 이 방에서 새 메시지를 받아줘");
        connectionStatus.setTextColor(live ? Color.rgb(86, 220, 144)
                : stored ? Color.rgb(241, 183, 77) : Color.rgb(234, 108, 108));
    }

    private void refreshNextPreview() {
        if (nextPreview == null) return;
        MultiRoomStore.Profile p = MultiRoomStore.get(this, routeAlias);
        if (p == null) return;
        MultiRoomStore.Profile temp = p.copy();
        if (timesRadio != null && timesRadio.isChecked()) {
            String raw = timesInput == null ? p.dailyTimes : timesInput.getText().toString();
            if (!MultiRoomStore.hasValidTimes(raw)) {
                nextPreview.setText("다음 전송: 시간을 입력해줘");
                return;
            }
            temp.scheduleMode = MultiRoomStore.MODE_TIMES;
            temp.dailyTimes = MultiRoomStore.canonicalTimes(raw);
        } else {
            temp.scheduleMode = MultiRoomStore.MODE_INTERVAL;
            temp.intervalMinutes = Math.max(1, parseInt(intervalInput == null ? "" : intervalInput.getText().toString(), p.intervalMinutes));
        }
        long next = MultiRoomStore.computeNextAt(temp, System.currentTimeMillis());
        nextPreview.setText("다음 전송 기준  ·  " + new SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(new Date(next)));
    }

    private int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private LinearLayout card(int color) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(14), dp(14), dp(14));
        l.setBackground(round(color, 15));
        return l;
    }

    private TextView section(String value) {
        TextView v = text(value, 17, true);
        v.setTextColor(Color.rgb(220, 228, 246));
        return v;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value); v.setTextColor(Color.WHITE); v.setTextSize(sp);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private EditText edit(String hint, boolean multiline) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(Color.GRAY); e.setTextColor(Color.WHITE);
        e.setBackground(round(Color.rgb(35, 38, 45), 10));
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        if (multiline) {
            e.setMinLines(4); e.setGravity(Gravity.TOP);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        }
        return e;
    }

    private Button actionButton(String label, int color) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setBackground(round(color, 11)); b.setMinHeight(dp(48));
        return b;
    }

    private Button compactButton(String label, int color) {
        Button b = actionButton(label, color); b.setTextSize(12); b.setMinHeight(dp(42)); return b;
    }

    private Button miniButton(String label) {
        Button b = compactButton(label, Color.rgb(57, 61, 70));
        b.setTextSize(11);
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); return d;
    }

    private LinearLayout.LayoutParams top(int v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(v); return lp;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
}
