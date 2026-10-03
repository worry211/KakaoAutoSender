package com.local.kakaoautosender;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
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
import java.util.Locale;

public class MainActivityV2 extends Activity {
    private EditText roomInput;
    private EditText messageInput;
    private EditText intervalInput;
    private EditText maxInput;
    private TextView connectionStatus;
    private TextView listenerStatus;
    private TextView automationStatus;
    private TextView latestStatus;
    private TextView runtimeStatus;
    private Button startButton;
    private Button stopButton;
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshUi();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.ensureLabelSchema(this);
        setContentView(buildUi());
        loadValues();
        refreshUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerUpdates();
    }

    @Override
    protected void onStop() {
        unregisterUpdates();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        KakaoNotificationListener.requestRefresh();
        refreshUi();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(15, 16, 18));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(36));
        scroll.addView(root);

        root.addView(text("카톡 자동전송", 28, true));
        TextView version = text("v" + appVersion() + " · 방 확인 후 전송", 12, false);
        version.setTextColor(Color.GRAY);
        root.addView(version, top(4));

        automationStatus = text("", 17, true);
        root.addView(automationStatus, top(20));
        connectionStatus = text("", 15, true);
        root.addView(connectionStatus, top(8));
        listenerStatus = text("", 14, true);
        root.addView(listenerStatus, top(8));

        Button permission = button("알림 접근 권한 설정");
        permission.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(permission, top(10));

        root.addView(section("1. 대상 오픈채팅 연결"), top(26));
        TextView pairingHelp = text("새 메시지를 받은 뒤 아래 첫 버튼을 누르면, 안드로이드가 알고 있는 실제 대화방 이름을 후보로 보여줍니다.", 12, false);
        pairingHelp.setTextColor(Color.GRAY);
        root.addView(pairingHelp, top(5));

        roomInput = edit("대상 오픈채팅방 이름", false);
        root.addView(roomInput, top(10));

        Button candidates = button("감지된 방 후보에서 선택");
        candidates.setOnClickListener(v -> showCandidates());
        root.addView(candidates, top(8));

        Button recent = button("최근 카톡 알림에서 직접 선택");
        recent.setOnClickListener(v -> showRecentSessions());
        root.addView(recent, top(8));

        Button unbind = button("현재 방 연결 해제");
        unbind.setOnClickListener(v -> unbindCurrentRoom());
        root.addView(unbind, top(8));

        latestStatus = text("", 13, false);
        latestStatus.setTextColor(Color.LTGRAY);
        root.addView(latestStatus, top(12));

        root.addView(section("2. 보낼 메시지"), top(26));
        messageInput = edit("자동으로 보낼 메시지", true);
        root.addView(messageInput, top(8));

        LinearLayout limits = new LinearLayout(this);
        limits.setOrientation(LinearLayout.HORIZONTAL);
        intervalInput = edit("간격(분)", false);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxInput = edit("하루 최대", false);
        maxInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        limits.addView(intervalInput, weight());
        LinearLayout.LayoutParams maxLp = weight();
        maxLp.leftMargin = dp(8);
        limits.addView(maxInput, maxLp);
        root.addView(limits, top(10));

        Button save = button("설정 저장");
        save.setOnClickListener(v -> saveSettingsWithSafety());
        root.addView(save, top(8));

        Button test = button("현재 연결로 1회 테스트 전송");
        test.setOnClickListener(v -> confirmTestSend());
        root.addView(test, top(10));

        root.addView(section("3. 자동전송"), top(26));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);

        startButton = button("자동전송 시작");
        startButton.setBackgroundColor(Color.rgb(42, 118, 77));
        startButton.setOnClickListener(v -> startAutomation());
        controls.addView(startButton, weight());

        stopButton = button("즉시 중단");
        stopButton.setBackgroundColor(Color.rgb(142, 58, 58));
        stopButton.setOnClickListener(v -> stopAutomation());
        LinearLayout.LayoutParams stopLp = weight();
        stopLp.leftMargin = dp(8);
        controls.addView(stopButton, stopLp);
        root.addView(controls, top(8));

        runtimeStatus = text("", 14, false);
        runtimeStatus.setTextColor(Color.LTGRAY);
        root.addView(runtimeStatus, top(18));

        root.addView(section("점검 / 복구"), top(26));
        Button rescan = button("카카오 알림 다시 스캔 / 리스너 재연결");
        rescan.setOnClickListener(v -> {
            KakaoNotificationListener.requestReconnect(this);
            KakaoNotificationListener.requestRefresh();
            toast("재스캔을 요청했어.");
            refreshUi();
        });
        root.addView(rescan, top(8));

        Button diagnostics = button("진단 정보 보기 / 복사");
        diagnostics.setOnClickListener(v -> showDiagnostics());
        root.addView(diagnostics, top(8));

        Button reset = button("모든 방 연결 정보 초기화");
        reset.setOnClickListener(v -> resetConnections());
        root.addView(reset, top(8));

        TextView footer = text("방 이름을 추측해서 보내지 않습니다. 확인된 답장 세션이 없으면 전송 자체를 차단합니다.", 12, false);
        footer.setTextColor(Color.GRAY);
        root.addView(footer, top(22));
        return scroll;
    }

    private void showCandidates() {
        KakaoNotificationListener.requestRefresh();
        final ArrayList<KakaoNotificationListener.SessionEntry> entries = KakaoNotificationListener.candidateSessionEntries();
        if (entries.isEmpty()) {
            toast("감지된 방 후보가 없어. 대상 방에서 새 메시지를 받은 뒤 다시 눌러줘.");
            return;
        }

        String[] items = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            KakaoNotificationListener.SessionEntry e = entries.get(i);
            items[i] = (e.suggestedRoom == null ? "방 이름 미확인" : e.suggestedRoom) + "\n" + e.description;
        }

        // AlertDialog에서 setMessage와 setItems를 함께 쓰면 일부 Samsung/One UI에서 목록이 보이지 않을 수 있다.
        // 목록 전용 다이얼로그로 렌더링하고, 다음 단계에서 별도 확인 창을 띄운다.
        new AlertDialog.Builder(this)
                .setTitle("감지된 방 후보 · " + entries.size() + "개")
                .setItems(items, (d, which) -> confirmCandidate(entries.get(which)))
                .setNegativeButton("취소", null)
                .show();
    }

    private void confirmCandidate(KakaoNotificationListener.SessionEntry entry) {
        if (entry == null || entry.suggestedRoom == null || entry.suggestedRoom.trim().isEmpty()) return;
        String room = entry.suggestedRoom.trim();
        new AlertDialog.Builder(this)
                .setTitle("이 방이 맞아?")
                .setMessage(room + "\n\n" + entry.description + "\n\n실제 대상 오픈채팅방이 맞을 때만 연결해.")
                .setPositiveButton("맞음 · 연결", (d, w) -> {
                    boolean ok = KakaoNotificationListener.bindRecentTokenToRoom(this, entry.token, room);
                    if (ok) {
                        roomInput.setText(room);
                        saveValues();
                        toast("방 연결 완료. 테스트 전송으로 마지막 확인해줘.");
                    } else {
                        toast("세션이 만료됐어. 새 메시지를 받은 뒤 다시 시도해줘.");
                    }
                    refreshUi();
                })
                .setNegativeButton("아님", null)
                .show();
    }

    private void showRecentSessions() {
        KakaoNotificationListener.requestRefresh();
        String room = roomInput.getText().toString().trim();
        if (room.isEmpty()) {
            toast("자동 감지가 안 될 때만 방 이름을 직접 입력한 뒤 사용해줘.");
            return;
        }
        final ArrayList<KakaoNotificationListener.SessionEntry> entries = KakaoNotificationListener.recentSessionEntries();
        if (entries.isEmpty()) {
            toast("최근 답장 가능한 카카오 알림이 없어.");
            return;
        }
        String[] items = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) items[i] = entries.get(i).description;

        new AlertDialog.Builder(this)
                .setTitle("최근 알림 · 시간/보낸사람 확인")
                .setItems(items, (d, which) -> confirmManualPair(entries.get(which), room))
                .setNegativeButton("취소", null)
                .show();
    }

    private void confirmManualPair(KakaoNotificationListener.SessionEntry entry, String room) {
        new AlertDialog.Builder(this)
                .setTitle("'" + room + "'에 연결할까?")
                .setMessage(entry.description + "\n\n이 알림이 실제 대상 방에서 온 게 맞을 때만 연결해.")
                .setPositiveButton("확인 · 연결", (d, w) -> {
                    boolean ok = KakaoNotificationListener.bindRecentTokenToRoom(this, entry.token, room);
                    if (ok) {
                        saveValues();
                        toast("연결 완료. 테스트 전송으로 확인해줘.");
                    } else {
                        toast("세션이 만료됐어. 새 메시지를 받은 뒤 다시 시도해줘.");
                    }
                    refreshUi();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void unbindCurrentRoom() {
        String room = roomInput.getText().toString().trim();
        if (room.isEmpty()) {
            toast("연결 해제할 방이 없어.");
            return;
        }
        stopAutomationInternal(false);
        boolean removed = KakaoNotificationListener.unbindRoom(this, room);
        toast(removed ? "방 연결을 해제했어." : "저장된 연결이 없어.");
        refreshUi();
    }

    private void confirmTestSend() {
        saveValues();
        String room = roomInput.getText().toString().trim();
        String message = messageInput.getText().toString();
        if (room.isEmpty() || message.trim().isEmpty()) {
            toast("방과 메시지를 먼저 설정해줘.");
            return;
        }
        if (!KakaoNotificationListener.hasLiveSession(room)) {
            toast("전송 차단: 이 방은 아직 확인된 실시간 세션이 없어.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("1회 테스트 전송")
                .setMessage("대상: " + room + "\n\n" + message)
                .setPositiveButton("전송", (d, w) -> {
                    boolean ok = KakaoNotificationListener.sendToRoom(this, room, message);
                    if (ok) {
                        Prefs.markManualSuccess(this);
                        Prefs.setStatus(this, "테스트 전송 성공: " + room);
                        toast("전송 성공. 실제 카톡 방을 확인해줘.");
                    } else {
                        String reason = KakaoNotificationListener.lastSendError();
                        Prefs.recordFailure(this);
                        Prefs.setStatus(this, "테스트 실패: " + reason);
                        toast("테스트 실패: " + reason);
                    }
                    refreshUi();
                })
                .setNegativeButton("취소", null)
                .show();
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
            toast("방과 메시지를 먼저 설정해줘.");
            return;
        }
        if (!KakaoNotificationListener.hasLiveSession(room)) {
            toast("시작 차단: 확인된 실시간 방 연결이 없어.");
            return;
        }
        Prefs.p(this).edit().putBoolean(Prefs.KEY_ACTIVE, true).apply();
        SendScheduler.scheduleFromNow(this);
        Prefs.setStatus(this, "자동전송 시작: " + room);
        toast("자동전송 시작. 확인된 이 방으로만 보낼게.");
        refreshUi();
    }

    private void stopAutomation() {
        stopAutomationInternal(true);
    }

    private void stopAutomationInternal(boolean notify) {
        Prefs.p(this).edit().putBoolean(Prefs.KEY_ACTIVE, false).apply();
        SendScheduler.cancel(this);
        Prefs.setStatus(this, "자동전송 중단됨");
        if (notify) toast("자동전송과 예약을 모두 중단했어.");
        refreshUi();
    }

    private void saveSettingsWithSafety() {
        SharedPreferences p = Prefs.p(this);
        String oldRoom = p.getString(Prefs.KEY_ROOM, "");
        boolean active = p.getBoolean(Prefs.KEY_ACTIVE, false);
        saveValues();
        String newRoom = roomInput.getText().toString().trim();
        if (active && !sameRoom(oldRoom, newRoom)) {
            stopAutomationInternal(false);
            Prefs.setStatus(this, "대상 방 변경으로 자동전송 중단 · 새 방 연결 필요");
            toast("대상 방이 바뀌어서 안전상 자동전송을 중단했어.");
        } else if (active) {
            SendScheduler.scheduleFromNow(this);
            Prefs.setStatus(this, "설정 저장 · 다음 예약 갱신");
            toast("저장했고 다음 예약도 갱신했어.");
        } else {
            Prefs.setStatus(this, "설정 저장됨");
            toast("설정 저장 완료.");
        }
        refreshUi();
    }

    private void saveValues() {
        int interval = Math.max(SendScheduler.MIN_INTERVAL_MINUTES, parseInt(intervalInput.getText().toString(), 60));
        int max = Math.min(SendScheduler.MAX_DAILY_LIMIT, Math.max(1, parseInt(maxInput.getText().toString(), 8)));
        intervalInput.setText(String.valueOf(interval));
        maxInput.setText(String.valueOf(max));
        Prefs.p(this).edit()
                .putString(Prefs.KEY_ROOM, roomInput.getText().toString().trim())
                .putString(Prefs.KEY_MESSAGE, messageInput.getText().toString())
                .putInt(Prefs.KEY_INTERVAL_MIN, interval)
                .putInt(Prefs.KEY_MAX_PER_DAY, max)
                .apply();
    }

    private void loadValues() {
        SharedPreferences p = Prefs.p(this);
        roomInput.setText(p.getString(Prefs.KEY_ROOM, ""));
        messageInput.setText(p.getString(Prefs.KEY_MESSAGE, ""));
        intervalInput.setText(String.valueOf(p.getInt(Prefs.KEY_INTERVAL_MIN, 60)));
        maxInput.setText(String.valueOf(p.getInt(Prefs.KEY_MAX_PER_DAY, 8)));
    }

    private void showDiagnostics() {
        String info = KakaoNotificationListener.diagnostics(this, roomInput.getText().toString());
        TextView body = text(info, 12, false);
        body.setTextIsSelectable(true);
        body.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroller = new ScrollView(this);
        scroller.addView(body);
        new AlertDialog.Builder(this)
                .setTitle("진단 정보")
                .setView(scroller)
                .setPositiveButton("복사", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("KakaoAutoSender diagnostics", info));
                    toast("진단 정보를 복사했어.");
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void resetConnections() {
        new AlertDialog.Builder(this)
                .setTitle("모든 방 연결을 초기화할까?")
                .setMessage("자동전송을 중단하고 확인된 방 연결과 자동복구 규칙을 지웁니다.")
                .setPositiveButton("초기화", (d, w) -> {
                    stopAutomationInternal(false);
                    KakaoNotificationListener.clearRuntimeAndBindings(this);
                    refreshUi();
                    toast("방 연결 정보를 초기화했어.");
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void refreshUi() {
        if (automationStatus == null || connectionStatus == null || listenerStatus == null || latestStatus == null) return;
        SharedPreferences p = Prefs.p(this);
        boolean active = p.getBoolean(Prefs.KEY_ACTIVE, false);
        boolean access = isNotificationAccessEnabled();
        String room = roomInput == null ? "" : roomInput.getText().toString().trim();
        boolean live = !room.isEmpty() && KakaoNotificationListener.hasLiveSession(room);
        boolean stored = !room.isEmpty() && KakaoNotificationListener.hasStoredBinding(this, room);

        automationStatus.setText(active ? "● 자동전송 실행 중" : "● 자동전송 중지됨");
        automationStatus.setTextColor(active ? Color.rgb(80, 220, 140) : Color.LTGRAY);

        connectionStatus.setText(live
                ? "● 대상 연결 확인됨 · " + room
                : "● 대상 연결 미확인 · 전송 차단");
        connectionStatus.setTextColor(live ? Color.rgb(90, 210, 150) : Color.rgb(255, 175, 90));

        listenerStatus.setText(access
                ? "● 알림 접근 허용 · 리스너 " + (KakaoNotificationListener.isListenerConnected() ? "연결됨" : "연결 대기")
                : "● 알림 접근 꺼짐");
        listenerStatus.setTextColor(access ? Color.rgb(90, 210, 150) : Color.rgb(255, 120, 120));

        latestStatus.setText("최근 알림: " + KakaoNotificationListener.latestSessionDescription());
        startButton.setEnabled(!active);
        stopButton.setEnabled(active);

        String status = p.getString(Prefs.KEY_LAST_STATUS, "아직 기록 없음");
        long next = p.getLong(Prefs.KEY_NEXT_AT, 0L);
        int count = Prefs.getTodayCount(this);
        int failures = p.getInt(Prefs.KEY_FAILURE_STREAK, 0);
        StringBuilder sb = new StringBuilder();
        sb.append("상태: ").append(status)
                .append("\n오늘 자동전송 성공: ").append(count).append("회")
                .append("\n연속 실패: ").append(failures).append("회")
                .append("\n감지된 방 후보: ").append(KakaoNotificationListener.candidateSessionEntries().size()).append("개")
                .append("\n확인된 실시간 세션: ").append(KakaoNotificationListener.liveLabels().size()).append("개");
        if (!room.isEmpty()) sb.append("\n자동복구: ").append(stored ? "가능" : "없음");
        if (active && next > 0) sb.append("\n다음 예약(대략): ").append(formatTime(next));
        runtimeStatus.setText(sb.toString());
    }

    private void registerUpdates() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(KakaoNotificationListener.ACTION_SESSIONS_UPDATED);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(receiver, filter);
            receiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    private void unregisterUpdates() {
        if (!receiverRegistered) return;
        try {
            unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        receiverRegistered = false;
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

    private static boolean sameRoom(String a, String b) {
        return (a == null ? "" : a.trim()).equalsIgnoreCase(b == null ? "" : b.trim());
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private String formatTime(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(new Date(ms));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private TextView section(String value) {
        TextView v = text(value, 17, true);
        v.setTextColor(Color.rgb(205, 225, 255));
        return v;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
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
        e.setBackgroundColor(Color.rgb(38, 40, 44));
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

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(dp);
        return lp;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
