package com.local.kakaoautosender;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;

public class MainActivity extends Activity {
    private EditText roomInput;
    private EditText messageInput;
    private EditText intervalInput;
    private EditText maxInput;
    private TextView permissionStatus;
    private TextView runtimeStatus;
    private TextView latestSessionStatus;
    private Button startStop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        loadValues();
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        KakaoNotificationListener.requestRefresh();
        refreshUi();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(18, 18, 18));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(root);

        root.addView(text("카톡 자동전송", 26, true));
        TextView sub = text("카카오톡 알림의 '답장' 기능을 이용해 화면을 열지 않고 전송합니다.", 14, false);
        sub.setTextColor(Color.LTGRAY);
        root.addView(sub, lpTop(8));

        permissionStatus = text("", 14, true);
        root.addView(permissionStatus, lpTop(20));

        Button permission = button("1. 알림 접근 권한 열기");
        permission.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(permission, lpTop(10));

        roomInput = edit("대상 오픈채팅방 이름", false);
        root.addView(roomInput, lpTop(22));

        Button detected = button("자동 감지된 방에서 선택");
        detected.setOnClickListener(v -> showDetectedRooms());
        root.addView(detected, lpTop(8));

        latestSessionStatus = text("", 13, false);
        latestSessionStatus.setTextColor(Color.LTGRAY);
        root.addView(latestSessionStatus, lpTop(12));

        Button bindLatest = button("최근 카톡 알림을 이 방으로 연결");
        bindLatest.setOnClickListener(v -> bindLatestSession());
        root.addView(bindLatest, lpTop(8));

        TextView pairingHelp = text("방 이름이 자동으로 안 잡히면: 위에 방 이름 직접 입력 → 대상 방에서 새 메시지 1개 받기 → 바로 위 버튼 누르기", 12, false);
        pairingHelp.setTextColor(Color.GRAY);
        root.addView(pairingHelp, lpTop(6));

        messageInput = edit("자동으로 보낼 메시지", true);
        root.addView(messageInput, lpTop(18));

        intervalInput = edit("전송 간격(분) - 최소 30", false);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(intervalInput, lpTop(18));

        maxInput = edit("하루 최대 전송 횟수 - 최대 24", false);
        maxInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(maxInput, lpTop(12));

        Button test = button("지금 1회 테스트 전송");
        test.setOnClickListener(v -> testSend());
        root.addView(test, lpTop(18));

        startStop = button("자동전송 시작");
        startStop.setOnClickListener(v -> toggleActive());
        root.addView(startStop, lpTop(10));

        runtimeStatus = text("", 14, false);
        runtimeStatus.setTextColor(Color.LTGRAY);
        root.addView(runtimeStatus, lpTop(20));

        TextView notes = text(
                "사용 순서\n" +
                "① 알림 접근 허용\n" +
                "② 대상 오픈채팅방에서 새 메시지 하나 받기\n" +
                "③ 자동 감지되면 방 선택, 안 되면 방 이름 직접 입력 후 '최근 카톡 알림을 이 방으로 연결'\n" +
                "④ 테스트 전송 성공 확인 후 자동전송 시작\n\n" +
                "카카오톡 알림 권한 자체는 켜둬야 합니다. 소리·진동·팝업만 숨겨도 됩니다.",
                13, false);
        notes.setTextColor(Color.GRAY);
        root.addView(notes, lpTop(24));
        return scroll;
    }

    private void loadValues() {
        SharedPreferences p = Prefs.p(this);
        roomInput.setText(p.getString(Prefs.KEY_ROOM, ""));
        messageInput.setText(p.getString(Prefs.KEY_MESSAGE, ""));
        intervalInput.setText(String.valueOf(p.getInt(Prefs.KEY_INTERVAL_MIN, 60)));
        maxInput.setText(String.valueOf(p.getInt(Prefs.KEY_MAX_PER_DAY, 8)));
    }

    private void saveValues() {
        int interval = parseInt(intervalInput.getText().toString(), 60);
        int max = parseInt(maxInput.getText().toString(), 8);
        interval = Math.max(SendScheduler.MIN_INTERVAL_MINUTES, interval);
        max = Math.min(SendScheduler.MAX_DAILY_LIMIT, Math.max(1, max));
        intervalInput.setText(String.valueOf(interval));
        maxInput.setText(String.valueOf(max));
        Prefs.p(this).edit()
                .putString(Prefs.KEY_ROOM, roomInput.getText().toString().trim())
                .putString(Prefs.KEY_MESSAGE, messageInput.getText().toString())
                .putInt(Prefs.KEY_INTERVAL_MIN, interval)
                .putInt(Prefs.KEY_MAX_PER_DAY, max)
                .apply();
    }

    private void bindLatestSession() {
        String room = roomInput.getText().toString().trim();
        if (room.isEmpty()) {
            toast("먼저 대상 오픈채팅방 이름을 직접 입력해줘.");
            return;
        }
        if (!KakaoNotificationListener.hasLatestReplyTarget()) {
            toast("최근 답장 세션이 없어. 대상 방에서 새 메시지를 하나 받은 뒤 바로 다시 눌러줘.");
            return;
        }
        boolean ok = KakaoNotificationListener.bindLatestToRoom(this, room);
        if (ok) {
            saveValues();
            toast("연결 완료. 이제 테스트 전송을 눌러봐.");
        } else {
            toast("연결 실패. 대상 방에서 새 메시지를 다시 받은 뒤 시도해줘.");
        }
        refreshUi();
    }

    private void testSend() {
        saveValues();
        String room = roomInput.getText().toString().trim();
        String msg = messageInput.getText().toString();
        if (room.isEmpty() || msg.trim().isEmpty()) {
            toast("방과 메시지를 먼저 입력해줘.");
            return;
        }
        if (!isNotificationAccessEnabled()) {
            toast("먼저 알림 접근 권한을 켜줘.");
            return;
        }
        boolean ok = KakaoNotificationListener.sendToRoom(this, room, msg);
        if (ok) {
            Prefs.setStatus(this, "수동 테스트 전송 성공: " + room);
            toast("테스트 전송 성공");
        } else {
            Prefs.setStatus(this, "테스트 실패: 답장 세션 없음");
            toast("세션이 없어. 자동감지가 안 되면 '최근 카톡 알림을 이 방으로 연결'을 먼저 해줘.");
        }
        refreshUi();
    }

    private void toggleActive() {
        saveValues();
        SharedPreferences p = Prefs.p(this);
        boolean active = p.getBoolean(Prefs.KEY_ACTIVE, false);
        if (active) {
            p.edit().putBoolean(Prefs.KEY_ACTIVE, false).apply();
            SendScheduler.cancel(this);
            Prefs.setStatus(this, "자동전송 중지됨");
        } else {
            if (!isNotificationAccessEnabled()) {
                toast("알림 접근 권한부터 켜줘.");
                return;
            }
            if (roomInput.getText().toString().trim().isEmpty() || messageInput.getText().toString().trim().isEmpty()) {
                toast("방과 메시지를 입력해줘.");
                return;
            }
            p.edit().putBoolean(Prefs.KEY_ACTIVE, true).apply();
            SendScheduler.scheduleFromNow(this);
            Prefs.setStatus(this, "자동전송 시작됨");
        }
        refreshUi();
    }

    private void showDetectedRooms() {
        KakaoNotificationListener.requestRefresh();
        ArrayList<String> all = KakaoNotificationListener.liveLabels();
        if (all.isEmpty()) all.addAll(Prefs.recentLabels(this));
        all = new ArrayList<>(new HashSet<>(all));
        all.sort(String.CASE_INSENSITIVE_ORDER);
        if (all.isEmpty()) {
            toast("자동 감지된 방이 없어. 방 이름을 직접 입력하고 최근 카톡 알림을 연결하면 돼.");
            return;
        }
        final String[] items = all.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("감지된 카카오톡 대화")
                .setItems(items, (d, which) -> roomInput.setText(items[which]))
                .setNegativeButton("취소", null)
                .show();
    }

    private void refreshUi() {
        boolean access = isNotificationAccessEnabled();
        permissionStatus.setText(access ? "● 알림 접근: 허용됨" : "● 알림 접근: 꺼져 있음");
        permissionStatus.setTextColor(access ? Color.rgb(80, 220, 140) : Color.rgb(255, 120, 120));

        latestSessionStatus.setText(KakaoNotificationListener.latestSessionDescription());

        boolean active = Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false);
        startStop.setText(active ? "자동전송 중지" : "자동전송 시작");

        String status = Prefs.p(this).getString(Prefs.KEY_LAST_STATUS, "아직 기록 없음");
        long at = Prefs.p(this).getLong(Prefs.KEY_LAST_STATUS_AT, 0L);
        long next = Prefs.p(this).getLong(Prefs.KEY_NEXT_AT, 0L);
        int count = Prefs.getTodayCount(this);
        StringBuilder sb = new StringBuilder();
        sb.append("상태: ").append(status);
        if (at > 0) sb.append("\n최근 변경: ").append(formatTime(at));
        sb.append("\n오늘 성공 전송: ").append(count).append("회");
        if (active && next > 0) sb.append("\n다음 예약(대략): ").append(formatTime(next));
        sb.append("\n연결된 방 세션: ").append(KakaoNotificationListener.liveLabels().size()).append("개");
        runtimeStatus.setText(sb.toString());
    }

    private boolean isNotificationAccessEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(new ComponentName(this, KakaoNotificationListener.class).flattenToString());
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    private String formatTime(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(new Date(ms));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sp);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private EditText edit(String hint, boolean multiline) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.GRAY);
        e.setTextColor(Color.WHITE);
        e.setBackgroundColor(Color.rgb(42, 42, 42));
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        if (multiline) {
            e.setMinLines(4);
            e.setGravity(android.view.Gravity.TOP);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        }
        return e;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams lpTop(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }
}
