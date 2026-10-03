package com.local.kakaoautosender;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
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
    private TextView automationStatus;
    private TextView runtimeStatus;
    private TextView latestSessionStatus;
    private Button startButton;
    private Button stopButton;

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
        root.setPadding(dp(20), dp(28), dp(20), dp(36));
        scroll.addView(root);

        root.addView(text("카톡 자동전송", 27, true));
        TextView version = text("v" + appVersion() + " · 휴대폰 내부에서만 동작", 12, false);
        version.setTextColor(Color.GRAY);
        root.addView(version, lpTop(4));

        TextView sub = text("카카오톡 알림의 '답장' 세션을 이용해 카톡 화면을 열지 않고 전송합니다.", 14, false);
        sub.setTextColor(Color.LTGRAY);
        root.addView(sub, lpTop(10));

        automationStatus = text("", 17, true);
        root.addView(automationStatus, lpTop(20));

        permissionStatus = text("", 14, true);
        root.addView(permissionStatus, lpTop(12));

        Button permission = button("알림 접근 권한 설정 열기");
        permission.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(permission, lpTop(8));

        root.addView(sectionTitle("1. 대상 오픈채팅 연결"), lpTop(24));
        roomInput = edit("대상 오픈채팅방 이름", false);
        root.addView(roomInput, lpTop(8));

        Button detected = button("자동 감지된 방에서 선택");
        detected.setOnClickListener(v -> showDetectedRooms());
        root.addView(detected, lpTop(8));

        Button recent = button("최근 카톡 알림에서 직접 선택해서 연결");
        recent.setOnClickListener(v -> showRecentSessions());
        root.addView(recent, lpTop(8));

        latestSessionStatus = text("", 13, false);
        latestSessionStatus.setTextColor(Color.LTGRAY);
        root.addView(latestSessionStatus, lpTop(12));

        Button bindLatest = button("가장 최근 카톡 알림을 이 방으로 연결");
        bindLatest.setOnClickListener(v -> bindLatestSession());
        root.addView(bindLatest, lpTop(8));

        TextView pairingHelp = text(
                "자동 감지가 실패해도 한 번 수동 연결하면 알림의 고유 식별자를 저장해 같은 방을 다음부터 자동으로 다시 연결하도록 만들었습니다.",
                12, false);
        pairingHelp.setTextColor(Color.GRAY);
        root.addView(pairingHelp, lpTop(7));

        root.addView(sectionTitle("2. 메시지와 전송 주기"), lpTop(24));
        messageInput = edit("자동으로 보낼 메시지", true);
        root.addView(messageInput, lpTop(8));

        intervalInput = edit("전송 간격(분) - 최소 30", false);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(intervalInput, lpTop(12));

        maxInput = edit("하루 최대 전송 횟수 - 최대 24", false);
        maxInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(maxInput, lpTop(10));

        Button test = button("지금 1회 테스트 전송");
        test.setOnClickListener(v -> testSend());
        root.addView(test, lpTop(14));

        root.addView(sectionTitle("3. 자동전송 제어"), lpTop(24));
        startButton = button("자동전송 시작");
        startButton.setBackgroundColor(Color.rgb(45, 125, 80));
        startButton.setOnClickListener(v -> startAutomation());

        stopButton = button("즉시 중단");
        stopButton.setBackgroundColor(Color.rgb(145, 55, 55));
        stopButton.setOnClickListener(v -> stopAutomation());

        LinearLayout controlRow = new LinearLayout(this);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);
        controlRow.addView(startButton, weightedButton());
        LinearLayout.LayoutParams stopLp = weightedButton();
        stopLp.leftMargin = dp(8);
        controlRow.addView(stopButton, stopLp);
        root.addView(controlRow, lpTop(8));

        runtimeStatus = text("", 14, false);
        runtimeStatus.setTextColor(Color.LTGRAY);
        root.addView(runtimeStatus, lpTop(18));

        root.addView(sectionTitle("점검 / 복구"), lpTop(24));
        Button diagnostics = button("진단 정보 보기 / 복사");
        diagnostics.setOnClickListener(v -> showDiagnostics());
        root.addView(diagnostics, lpTop(8));

        Button reset = button("방 연결 정보 초기화");
        reset.setOnClickListener(v -> confirmResetConnections());
        root.addView(reset, lpTop(8));

        TextView notes = text(
                "권장 순서\n" +
                "① 알림 접근 허용\n" +
                "② 대상 오픈채팅방에서 새 메시지 하나 받기\n" +
                "③ 자동 감지 또는 '최근 카톡 알림에서 직접 선택'으로 방 연결\n" +
                "④ 1회 테스트 전송 확인\n" +
                "⑤ 자동전송 시작\n\n" +
                "카카오톡 알림 권한 자체는 켜둬야 합니다. 소리·진동·팝업은 숨겨도 됩니다. 카카오톡이 답장 세션을 갱신하거나 휴대폰이 재부팅된 뒤에는 새 알림이 한 번 필요할 수 있습니다.",
                12, false);
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
            toast("먼저 대상 오픈채팅방 이름을 입력해줘.");
            return;
        }
        KakaoNotificationListener.requestRefresh();
        if (!KakaoNotificationListener.hasLatestReplyTarget()) {
            toast("최근 답장 세션이 없어. 대상 방에서 새 메시지를 하나 받은 뒤 다시 눌러줘.");
            return;
        }
        if (KakaoNotificationListener.bindLatestToRoom(this, room)) {
            saveValues();
            toast("연결 완료. 이제 1회 테스트 전송을 눌러봐.");
        } else {
            toast("연결 실패. 대상 방에서 새 메시지를 다시 받은 뒤 시도해줘.");
        }
        refreshUi();
    }

    private void showRecentSessions() {
        KakaoNotificationListener.requestRefresh();
        String room = roomInput.getText().toString().trim();
        if (room.isEmpty()) {
            toast("먼저 연결할 오픈채팅방 이름을 입력해줘.");
            return;
        }
        final ArrayList<KakaoNotificationListener.SessionEntry> entries = KakaoNotificationListener.recentSessionEntries();
        if (entries.isEmpty()) {
            toast("최근 답장 가능한 카카오 알림이 없어. 대상 방에서 메시지를 하나 받아줘.");
            return;
        }
        String[] items = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) items[i] = entries.get(i).description;

        new AlertDialog.Builder(this)
                .setTitle("대상 방에서 온 알림을 선택")
                .setItems(items, (d, which) -> {
                    KakaoNotificationListener.SessionEntry entry = entries.get(which);
                    boolean ok = KakaoNotificationListener.bindRecentTokenToRoom(this, entry.token, room);
                    if (ok) {
                        saveValues();
                        toast("선택한 알림을 '" + room + "'에 연결했어.");
                    } else {
                        toast("해당 세션이 만료됐어. 새 메시지를 받은 뒤 다시 시도해줘.");
                    }
                    refreshUi();
                })
                .setNegativeButton("취소", null)
                .show();
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
            String reason = KakaoNotificationListener.lastSendError();
            Prefs.setStatus(this, "테스트 실패: " + reason);
            toast("테스트 실패: " + reason + "\n최근 알림에서 방을 다시 연결해봐.");
        }
        refreshUi();
    }

    private void startAutomation() {
        saveValues();
        String room = roomInput.getText().toString().trim();
        String message = messageInput.getText().toString().trim();
        if (!isNotificationAccessEnabled()) {
            toast("알림 접근 권한부터 켜줘.");
            return;
        }
        if (room.isEmpty() || message.isEmpty()) {
            toast("방과 메시지를 먼저 입력해줘.");
            return;
        }
        if (!KakaoNotificationListener.hasLiveSession(room)
                && !KakaoNotificationListener.hasStoredBinding(this, room)) {
            toast("아직 이 방과 연결된 카카오 알림이 없어. 먼저 대상 방 메시지를 받고 연결해줘.");
            return;
        }

        Prefs.p(this).edit().putBoolean(Prefs.KEY_ACTIVE, true).apply();
        SendScheduler.scheduleFromNow(this);
        int interval = Prefs.p(this).getInt(Prefs.KEY_INTERVAL_MIN, 60);
        if (KakaoNotificationListener.hasLiveSession(room)) {
            Prefs.setStatus(this, "자동전송 시작됨 · 첫 예약은 약 " + interval + "분 후");
        } else {
            Prefs.setStatus(this, "자동전송 시작됨 · 저장된 방 연결 사용 · 다음 카카오 알림으로 세션 복구 대기");
        }
        toast("자동전송을 시작했어.");
        refreshUi();
    }

    private void stopAutomation() {
        Prefs.p(this).edit().putBoolean(Prefs.KEY_ACTIVE, false).apply();
        SendScheduler.cancel(this);
        Prefs.setStatus(this, "자동전송 즉시 중단됨");
        toast("자동전송을 중단했어. 예약도 취소했어.");
        refreshUi();
    }

    private void showDetectedRooms() {
        KakaoNotificationListener.requestRefresh();
        ArrayList<String> all = KakaoNotificationListener.liveLabels();
        if (all.isEmpty()) all.addAll(Prefs.recentLabels(this));
        all = new ArrayList<>(new HashSet<>(all));
        all.sort(String.CASE_INSENSITIVE_ORDER);
        if (all.isEmpty()) {
            toast("자동 감지된 방이 없어. '최근 카톡 알림에서 직접 선택해서 연결'을 사용해줘.");
            return;
        }
        final String[] items = all.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("감지/연결된 카카오톡 방")
                .setItems(items, (d, which) -> roomInput.setText(items[which]))
                .setNegativeButton("취소", null)
                .show();
    }

    private void showDiagnostics() {
        String diagnostics = KakaoNotificationListener.diagnostics(this, roomInput.getText().toString());
        TextView body = text(diagnostics, 12, false);
        body.setTextIsSelectable(true);
        body.setPadding(dp(16), dp(12), dp(16), dp(12));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);

        new AlertDialog.Builder(this)
                .setTitle("진단 정보")
                .setView(scroll)
                .setPositiveButton("복사", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("KakaoAutoSender diagnostics", diagnostics));
                    toast("진단 정보를 복사했어.");
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void confirmResetConnections() {
        new AlertDialog.Builder(this)
                .setTitle("방 연결 정보를 초기화할까?")
                .setMessage("자동전송을 중단하고 저장된 방 식별자와 현재 답장 세션을 지웁니다. 메시지/주기 입력값은 유지합니다.")
                .setPositiveButton("초기화", (d, w) -> {
                    stopAutomation();
                    KakaoNotificationListener.clearRuntimeAndBindings(this);
                    refreshUi();
                    toast("방 연결 정보를 초기화했어.");
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void refreshUi() {
        boolean access = isNotificationAccessEnabled();
        permissionStatus.setText(access
                ? "● 알림 접근: 허용됨 · 리스너 " + (KakaoNotificationListener.isListenerConnected() ? "연결됨" : "연결 대기")
                : "● 알림 접근: 꺼져 있음");
        permissionStatus.setTextColor(access ? Color.rgb(80, 220, 140) : Color.rgb(255, 120, 120));

        latestSessionStatus.setText(KakaoNotificationListener.latestSessionDescription());

        SharedPreferences p = Prefs.p(this);
        boolean active = p.getBoolean(Prefs.KEY_ACTIVE, false);
        automationStatus.setText(active ? "● 자동전송 실행 중" : "● 자동전송 중지됨");
        automationStatus.setTextColor(active ? Color.rgb(80, 220, 140) : Color.LTGRAY);
        startButton.setEnabled(!active);
        stopButton.setEnabled(active);

        String status = p.getString(Prefs.KEY_LAST_STATUS, "아직 기록 없음");
        long at = p.getLong(Prefs.KEY_LAST_STATUS_AT, 0L);
        long next = p.getLong(Prefs.KEY_NEXT_AT, 0L);
        long lastSuccess = p.getLong(Prefs.KEY_LAST_SUCCESS_AT, 0L);
        int count = Prefs.getTodayCount(this);
        int failures = p.getInt(Prefs.KEY_FAILURE_STREAK, 0);

        String room = roomInput == null ? "" : roomInput.getText().toString().trim();
        StringBuilder sb = new StringBuilder();
        sb.append("상태: ").append(status);
        if (at > 0) sb.append("\n최근 변경: ").append(formatTime(at));
        sb.append("\n오늘 성공 전송: ").append(count).append("회");
        sb.append("\n연속 실패: ").append(failures).append("회");
        if (lastSuccess > 0) sb.append("\n마지막 성공: ").append(formatTime(lastSuccess));
        if (active && next > 0) sb.append("\n다음 예약(대략): ").append(formatTime(next));
        sb.append("\n실시간 방 세션: ").append(KakaoNotificationListener.liveLabels().size()).append("개");
        if (!room.isEmpty()) {
            sb.append("\n현재 선택 방: ")
                    .append(KakaoNotificationListener.hasLiveSession(room) ? "실시간 연결됨" :
                            (KakaoNotificationListener.hasStoredBinding(this, room) ? "저장 연결 있음 / 실시간 세션 대기" : "미연결"));
        }
        runtimeStatus.setText(sb.toString());
    }

    private boolean isNotificationAccessEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(new ComponentName(this, KakaoNotificationListener.class).flattenToString());
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
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

    private TextView sectionTitle(String s) {
        TextView v = text(s, 16, true);
        v.setTextColor(Color.rgb(205, 225, 255));
        return v;
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
            e.setGravity(Gravity.TOP);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        }
        return e;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        return b;
    }

    private LinearLayout.LayoutParams weightedButton() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
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
