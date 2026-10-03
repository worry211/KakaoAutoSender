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

public class MainActivity extends Activity {
    private EditText roomInput;
    private EditText messageInput;
    private EditText intervalInput;
    private EditText maxInput;
    private TextView permissionStatus;
    private TextView automationStatus;
    private TextView routingStatus;
    private TextView runtimeStatus;
    private TextView latestSessionStatus;
    private Button startButton;
    private Button stopButton;
    private boolean sessionReceiverRegistered = false;

    private final BroadcastReceiver sessionReceiver = new BroadcastReceiver() {
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
        registerSessionReceiver();
    }

    @Override
    protected void onStop() {
        unregisterSessionReceiver();
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
        scroll.setBackgroundColor(Color.rgb(16, 17, 19));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(36));
        scroll.addView(root);

        root.addView(text("카톡 자동전송", 27, true));
        TextView version = text("v" + appVersion() + " · 안전 라우팅", 12, false);
        version.setTextColor(Color.GRAY);
        root.addView(version, lpTop(4));

        TextView sub = text(
                "자동 감지는 후보만 보여주고, 사용자가 확인한 카카오 답장 세션에만 전송합니다.",
                14, false);
        sub.setTextColor(Color.LTGRAY);
        root.addView(sub, lpTop(10));

        automationStatus = text("", 17, true);
        root.addView(automationStatus, lpTop(20));

        routingStatus = text("", 14, true);
        root.addView(routingStatus, lpTop(8));

        permissionStatus = text("", 14, true);
        root.addView(permissionStatus, lpTop(10));

        Button permission = button("알림 접근 권한 설정");
        permission.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(permission, lpTop(8));

        root.addView(sectionTitle("1. 대상 방 연결"), lpTop(24));
        TextView stepHelp = text(
                "오배송 방지를 위해 '감지됨'만으로는 연결하지 않습니다. 후보를 확인하거나 최근 알림을 직접 골라야 합니다.",
                12, false);
        stepHelp.setTextColor(Color.GRAY);
        root.addView(stepHelp, lpTop(6));

        roomInput = edit("대상 오픈채팅방 이름", false);
        root.addView(roomInput, lpTop(10));

        Button detected = button("감지된 방 후보 확인 / 연결");
        detected.setOnClickListener(v -> showDetectedCandidates());
        root.addView(detected, lpTop(8));

        Button recent = button("최근 카톡 알림에서 직접 선택 / 연결");
        recent.setOnClickListener(v -> showRecentSessions());
        root.addView(recent, lpTop(8));

        Button unbind = button("현재 방 연결 해제");
        unbind.setOnClickListener(v -> unbindCurrentRoom());
        root.addView(unbind, lpTop(8));

        latestSessionStatus = text("", 13, false);
        latestSessionStatus.setTextColor(Color.LTGRAY);
        root.addView(latestSessionStatus, lpTop(12));

        root.addView(sectionTitle("2. 메시지 / 전송 제한"), lpTop(24));
        messageInput = edit("자동으로 보낼 메시지", true);
        root.addView(messageInput, lpTop(8));

        intervalInput = edit("전송 간격(분) - 최소 30", false);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(intervalInput, lpTop(12));

        maxInput = edit("하루 최대 자동전송 횟수 - 최대 24", false);
        maxInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(maxInput, lpTop(10));

        Button test = button("현재 연결로 1회 테스트 전송");
        test.setOnClickListener(v -> testSend());
        root.addView(test, lpTop(14));

        root.addView(sectionTitle("3. 자동전송"), lpTop(24));
        startButton = button("자동전송 시작");
        startButton.setBackgroundColor(Color.rgb(43, 115, 76));
        startButton.setOnClickListener(v -> startAutomation());

        stopButton = button("즉시 중단");
        stopButton.setBackgroundColor(Color.rgb(132, 54, 54));
        stopButton.setOnClickListener(v -> stopAutomation());

        LinearLayout controlRow = new LinearLayout(this);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);
        controlRow.addView(startButton, weightedButton());
        LinearLayout.LayoutParams stopLp = weightedButton();
        stopLp.leftMargin = dp(8);
        controlRow.addView(stopButton, stopLp);
        root.addView(controlRow, lpTop(8));

        Button apply = button("설정 저장");
        apply.setOnClickListener(v -> applySettings());
        root.addView(apply, lpTop(8));

        runtimeStatus = text("", 14, false);
        runtimeStatus.setTextColor(Color.LTGRAY);
        root.addView(runtimeStatus, lpTop(18));

        root.addView(sectionTitle("점검 / 복구"), lpTop(24));
        Button reconnect = button("카카오 알림 다시 스캔 / 리스너 재연결");
        reconnect.setOnClickListener(v -> reconnectListener());
        root.addView(reconnect, lpTop(8));

        Button diagnostics = button("진단 정보 보기 / 복사");
        diagnostics.setOnClickListener(v -> showDiagnostics());
        root.addView(diagnostics, lpTop(8));

        Button reset = button("모든 방 연결 정보 초기화");
        reset.setOnClickListener(v -> confirmResetConnections());
        root.addView(reset, lpTop(8));

        TextView notes = text(
                "권장 순서\n" +
                "① 알림 접근 허용\n" +
                "② 대상 오픈채팅방에서 새 메시지 하나 받기\n" +
                "③ '감지된 방 후보' 또는 '최근 카톡 알림'에서 실제 대상 알림을 확인해 연결\n" +
                "④ 1회 테스트 전송으로 대상 방 확인\n" +
                "⑤ 자동전송 시작\n\n" +
                "v0.5부터 예전 자동 연결 정보는 안전상 폐기됩니다. 카카오톡/안드로이드가 방 식별자를 충분히 주지 않으면 자동감지보다 직접 선택이 더 정확합니다.",
                12, false);
        notes.setTextColor(Color.GRAY);
        root.addView(notes, lpTop(24));
        return scroll;
    }

    private void registerSessionReceiver() {
        if (sessionReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(KakaoNotificationListener.ACTION_SESSIONS_UPDATED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(sessionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(sessionReceiver, filter);
            }
            sessionReceiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    private void unregisterSessionReceiver() {
        if (!sessionReceiverRegistered) return;
        try {
            unregisterReceiver(sessionReceiver);
        } catch (Throwable ignored) {
        }
        sessionReceiverRegistered = false;
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

    private void applySettings() {
        SharedPreferences p = Prefs.p(this);
        String oldRoom = p.getString(Prefs.KEY_ROOM, "");
        boolean active = p.getBoolean(Prefs.KEY_ACTIVE, false);
        saveValues();
        String newRoom = roomInput.getText().toString().trim();

        if (active && !sameRoom(oldRoom, newRoom)) {
            p.edit().putBoolean(Prefs.KEY_ACTIVE, false).apply();
            SendScheduler.cancel(this);
            Prefs.setStatus(this, "대상 방 변경 감지 · 안전을 위해 자동전송 중단 · 새 방 재연결 필요");
            toast("대상 방이 바뀌어서 자동전송을 중단했어. 새 방을 다시 연결해줘.");
        } else if (active) {
            SendScheduler.scheduleFromNow(this);
            Prefs.setStatus(this, "설정 저장됨 · 다음 예약 갱신");
            toast("설정을 저장하고 예약을 갱신했어.");
        } else {
            Prefs.setStatus(this, "설정 저장됨");
            toast("설정을 저장했어.");
        }
        refreshUi();
    }

    private void showDetectedCandidates() {
        KakaoNotificationListener.requestRefresh();
        final ArrayList<KakaoNotificationListener.SessionEntry> entries = KakaoNotificationListener.candidateSessionEntries();
        if (entries.isEmpty()) {
            toast("신뢰할 만한 방 후보를 못 찾았어. 대상 방에서 새 메시지를 받은 뒤 '최근 카톡 알림'에서 직접 선택해줘.");
            return;
        }

        String[] items = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) items[i] = entries.get(i).description;

        new AlertDialog.Builder(this)
                .setTitle("감지된 방 후보")
                .setMessage("후보를 누른 뒤 실제 대상 방이 맞는지 한 번 더 확인해. 선택만으로는 전송되지 않아.")
                .setItems(items, (d, which) -> confirmCandidatePair(entries.get(which)))
                .setNegativeButton("취소", null)
                .show();
    }

    private void confirmCandidatePair(KakaoNotificationListener.SessionEntry entry) {
        if (entry == null || entry.suggestedRoom == null || entry.suggestedRoom.trim().isEmpty()) return;
        String suggested = entry.suggestedRoom.trim();
        new AlertDialog.Builder(this)
                .setTitle("이 방이 맞아?")
                .setMessage("감지 후보: " + suggested + "\n\n" + entry.description
                        + "\n\n실제 대상 오픈채팅방이 맞을 때만 연결해.")
                .setPositiveButton("맞음 · 연결", (d, w) -> {
                    roomInput.setText(suggested);
                    boolean ok = KakaoNotificationListener.bindRecentTokenToRoom(this, entry.token, suggested);
                    if (ok) {
                        saveValues();
                        toast("'" + suggested + "' 연결 완료. 1회 테스트로 마지막 확인해줘.");
                    } else {
                        toast("세션이 만료됐어. 대상 방에서 새 메시지를 받은 뒤 다시 시도해줘.");
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
            toast("먼저 실제 대상 오픈채팅방 이름을 입력해줘.");
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
                .setTitle("대상 방에서 온 알림을 직접 선택")
                .setMessage("시간·보낸사람·메시지 미리보기를 보고 정확한 알림을 골라.")
                .setItems(items, (d, which) -> confirmManualPair(entries.get(which), room))
                .setNegativeButton("취소", null)
                .show();
    }

    private void confirmManualPair(KakaoNotificationListener.SessionEntry entry, String room) {
        new AlertDialog.Builder(this)
                .setTitle("'" + room + "'에 연결할까?")
                .setMessage(entry.description + "\n\n이 알림이 실제 '" + room + "' 방에서 온 게 맞을 때만 연결해.")
                .setPositiveButton("확인 · 연결", (d, w) -> {
                    boolean ok = KakaoNotificationListener.bindRecentTokenToRoom(this, entry.token, room);
                    if (ok) {
                        saveValues();
                        toast("연결 완료. 이제 1회 테스트 전송으로 확인해줘.");
                    } else {
                        toast("해당 세션이 만료됐어. 새 메시지를 받은 뒤 다시 시도해줘.");
                    }
                    refreshUi();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void unbindCurrentRoom() {
        String room = roomInput.getText().toString().trim();
        if (room.isEmpty()) {
            toast("해제할 방 이름이 비어 있어.");
            return;
        }
        boolean active = Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false);
        if (active) {
            Prefs.p(this).edit().putBoolean(Prefs.KEY_ACTIVE, false).apply();
            SendScheduler.cancel(this);
        }
        boolean removed = KakaoNotificationListener.unbindRoom(this, room);
        toast(removed ? "'" + room + "' 연결을 해제했어." : "이 방에 저장된 연결이 없어.");
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
        if (!KakaoNotificationListener.hasLiveSession(room)) {
            toast("안전 차단: 이 방은 아직 확인된 실시간 세션이 없어. 최근 카톡 알림에서 정확한 방을 연결해줘.");
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("테스트 전송")
                .setMessage("확인된 연결 '" + room + "'로 지금 1회 보낼까?\n\n" + msg)
                .setPositiveButton("전송", (d, w) -> performTestSend(room, msg))
                .setNegativeButton("취소", null)
                .show();
    }

    private void performTestSend(String room, String msg) {
        boolean ok = KakaoNotificationListener.sendToRoom(this, room, msg);
        if (ok) {
            Prefs.markManualSuccess(this);
            Prefs.setStatus(this, "수동 테스트 전송 성공: " + room);
            toast("테스트 전송 성공. 실제 카톡 방을 확인한 뒤 자동전송을 시작해줘.");
        } else {
            String reason = KakaoNotificationListener.lastSendError();
            Prefs.recordFailure(this);
            Prefs.setStatus(this, "테스트 실패: " + reason);
            toast("테스트 실패: " + reason);
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
        if (!KakaoNotificationListener.hasLiveSession(room)) {
            toast("자동전송 시작 차단: 현재 확인된 실시간 세션이 없어. 대상 방에서 새 메시지를 받고 다시 연결해줘.");
            return;
        }

        Prefs.p(this).edit().putBoolean(Prefs.KEY_ACTIVE, true).apply();
        SendScheduler.scheduleFromNow(this);
        int interval = Prefs.p(this).getInt(Prefs.KEY_INTERVAL_MIN, 60);
        Prefs.setStatus(this, "자동전송 시작됨 · 검증된 방 " + room + " · 첫 예약 약 " + interval + "분 후");
        toast("자동전송 시작. 대상은 확인된 '" + room + "'만 사용해.");
        refreshUi();
    }

    private void stopAutomation() {
        Prefs.p(this).edit().putBoolean(Prefs.KEY_ACTIVE, false).apply();
        SendScheduler.cancel(this);
        Prefs.setStatus(this, "자동전송 즉시 중단됨");
        toast("자동전송을 중단했고 예약도 취소했어.");
        refreshUi();
    }

    private void reconnectListener() {
        KakaoNotificationListener.requestReconnect(this);
        KakaoNotificationListener.requestRefresh();
        toast("리스너 재연결과 카카오 알림 재스캔을 요청했어.");
        refreshUi();
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
                .setTitle("모든 방 연결을 초기화할까?")
                .setMessage("자동전송을 중단하고 저장된 자동복구 식별자와 현재 답장 세션을 지웁니다. 메시지/주기 입력값은 유지합니다.")
                .setPositiveButton("초기화", (d, w) -> {
                    Prefs.p(this).edit().putBoolean(Prefs.KEY_ACTIVE, false).apply();
                    SendScheduler.cancel(this);
                    KakaoNotificationListener.clearRuntimeAndBindings(this);
                    refreshUi();
                    toast("모든 방 연결 정보를 초기화했어.");
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void refreshUi() {
        if (permissionStatus == null || automationStatus == null || routingStatus == null
                || latestSessionStatus == null || startButton == null || stopButton == null
                || runtimeStatus == null) return;

        boolean access = isNotificationAccessEnabled();
        permissionStatus.setText(access
                ? "● 알림 접근: 허용 · 리스너 " + (KakaoNotificationListener.isListenerConnected() ? "연결됨" : "연결 대기")
                : "● 알림 접근: 꺼져 있음");
        permissionStatus.setTextColor(access ? Color.rgb(80, 220, 140) : Color.rgb(255, 120, 120));

        latestSessionStatus.setText("최근 알림: " + KakaoNotificationListener.latestSessionDescription());

        SharedPreferences p = Prefs.p(this);
        boolean active = p.getBoolean(Prefs.KEY_ACTIVE, false);
        automationStatus.setText(active ? "● 자동전송 실행 중" : "● 자동전송 중지됨");
        automationStatus.setTextColor(active ? Color.rgb(80, 220, 140) : Color.LTGRAY);
        startButton.setEnabled(!active);
        stopButton.setEnabled(active);

        String room = roomInput == null ? "" : roomInput.getText().toString().trim();
        boolean live = !room.isEmpty() && KakaoNotificationListener.hasLiveSession(room);
        boolean stored = !room.isEmpty() && KakaoNotificationListener.hasStoredBinding(this, room);
        routingStatus.setText(live
                ? "● 대상 연결: 확인됨 · 이 방으로만 전송 가능"
                : "● 대상 연결: 미확인 · 전송 차단");
        routingStatus.setTextColor(live ? Color.rgb(90, 210, 150) : Color.rgb(255, 175, 90));

        String status = p.getString(Prefs.KEY_LAST_STATUS, "아직 기록 없음");
        long at = p.getLong(Prefs.KEY_LAST_STATUS_AT, 0L);
        long next = p.getLong(Prefs.KEY_NEXT_AT, 0L);
        long lastSuccess = p.getLong(Prefs.KEY_LAST_SUCCESS_AT, 0L);
        int count = Prefs.getTodayCount(this);
        int failures = p.getInt(Prefs.KEY_FAILURE_STREAK, 0);

        StringBuilder sb = new StringBuilder();
        sb.append("상태: ").append(status);
        if (at > 0) sb.append("\n최근 변경: ").append(formatTime(at));
        sb.append("\n오늘 자동전송 성공: ").append(count).append("회");
        sb.append("\n연속 실패: ").append(failures).append("회");
        if (lastSuccess > 0) sb.append("\n마지막 성공: ").append(formatTime(lastSuccess));
        if (active && next > 0) sb.append("\n다음 예약(대략): ").append(formatTime(next));
        sb.append("\n검증된 실시간 세션: ").append(KakaoNotificationListener.liveLabels().size()).append("개");
        sb.append("\n감지 후보: ").append(KakaoNotificationListener.candidateSessionEntries().size()).append("개");
        if (!room.isEmpty()) {
            sb.append("\n현재 방: ").append(room);
            sb.append("\n현재 방 실시간 연결: ").append(live ? "확인됨" : "없음");
            sb.append("\n현재 방 자동복구: ").append(stored ? "가능" : "없음");
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

    private static boolean sameRoom(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        return x.equalsIgnoreCase(y);
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
