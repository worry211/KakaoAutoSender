package com.local.kakaoautosender;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivityV3 extends Activity {
    private EditText roomInput;
    private EditText messageInput;
    private EditText intervalInput;
    private EditText maxInput;
    private CheckBox unlimitedCheck;
    private CheckBox roomEnabledCheck;
    private TextView automationStatus;
    private TextView listenerStatus;
    private TextView roomStatus;
    private TextView profilesStatus;
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
        MultiRoomStore.ensureMigrated(this);
        setContentView(buildUi());
        loadInitialProfile();
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
        root.setPadding(dp(18), dp(24), dp(18), dp(38));
        scroll.addView(root);

        root.addView(text("카톡 자동전송", 28, true));
        TextView version = text("v" + appVersion() + " · 다중방 / 방별 스케줄", 12, false);
        version.setTextColor(Color.GRAY);
        root.addView(version, top(4));

        automationStatus = text("", 17, true);
        root.addView(automationStatus, top(18));
        listenerStatus = text("", 14, true);
        root.addView(listenerStatus, top(7));

        Button permission = button("알림 접근 권한 설정");
        permission.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(permission, top(10));

        root.addView(section("1. 방 연결 / 선택"), top(26));
        TextView help = text("여러 오픈채팅방을 각각 연결하고, 방마다 메시지·전송 간격·하루 횟수를 따로 저장할 수 있어.", 12, false);
        help.setTextColor(Color.GRAY);
        root.addView(help, top(5));

        Button profiles = button("등록된 방 선택 / 관리");
        profiles.setOnClickListener(v -> showProfileSelector());
        root.addView(profiles, top(10));

        roomInput = edit("오픈채팅방 이름", false);
        root.addView(roomInput, top(8));

        Button candidates = button("감지된 방 후보에서 연결");
        candidates.setOnClickListener(v -> showCandidates());
        root.addView(candidates, top(8));

        Button recent = button("최근 카톡 알림에서 직접 연결");
        recent.setOnClickListener(v -> showRecentSessions());
        root.addView(recent, top(8));

        roomStatus = text("", 13, false);
        roomStatus.setTextColor(Color.LTGRAY);
        root.addView(roomStatus, top(12));

        root.addView(section("2. 이 방 전송 설정"), top(26));
        messageInput = edit("이 방에 자동으로 보낼 메시지", true);
        root.addView(messageInput, top(8));

        intervalInput = edit("이 방 전송 간격(분) · 최소 30", false);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(intervalInput, top(8));

        LinearLayout limitRow = new LinearLayout(this);
        limitRow.setOrientation(LinearLayout.HORIZONTAL);
        maxInput = edit("하루 최대 횟수", false);
        maxInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        limitRow.addView(maxInput, weight());
        unlimitedCheck = new CheckBox(this);
        unlimitedCheck.setText("무제한");
        unlimitedCheck.setTextColor(Color.WHITE);
        unlimitedCheck.setOnCheckedChangeListener((buttonView, checked) -> maxInput.setEnabled(!checked));
        LinearLayout.LayoutParams unlimitedLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        unlimitedLp.leftMargin = dp(10);
        limitRow.addView(unlimitedCheck, unlimitedLp);
        root.addView(limitRow, top(8));

        roomEnabledCheck = new CheckBox(this);
        roomEnabledCheck.setText("이 방 자동전송 사용");
        roomEnabledCheck.setTextColor(Color.WHITE);
        root.addView(roomEnabledCheck, top(6));

        Button saveProfile = button("이 방 저장 / 업데이트");
        saveProfile.setOnClickListener(v -> saveCurrentProfile(true));
        root.addView(saveProfile, top(8));

        Button test = button("이 방 1회 테스트 전송");
        test.setOnClickListener(v -> confirmTestSend());
        root.addView(test, top(8));

        Button delete = button("이 방 설정 삭제");
        delete.setOnClickListener(v -> deleteCurrentProfile());
        root.addView(delete, top(8));

        root.addView(section("3. 전체 자동전송"), top(26));
        TextView globalHelp = text("시작을 누르면 '자동전송 사용'이 켜진 모든 방이 각자 저장한 간격으로 따로 예약돼.", 12, false);
        globalHelp.setTextColor(Color.GRAY);
        root.addView(globalHelp, top(5));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        startButton = button("전체 시작");
        startButton.setBackgroundColor(Color.rgb(42, 118, 77));
        startButton.setOnClickListener(v -> startAutomation());
        controls.addView(startButton, weight());

        stopButton = button("전체 즉시 중단");
        stopButton.setBackgroundColor(Color.rgb(142, 58, 58));
        stopButton.setOnClickListener(v -> stopAutomation());
        LinearLayout.LayoutParams stopLp = weight();
        stopLp.leftMargin = dp(8);
        controls.addView(stopButton, stopLp);
        root.addView(controls, top(8));

        profilesStatus = text("", 13, false);
        profilesStatus.setTextColor(Color.LTGRAY);
        root.addView(profilesStatus, top(16));

        runtimeStatus = text("", 13, false);
        runtimeStatus.setTextColor(Color.LTGRAY);
        root.addView(runtimeStatus, top(12));

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

        TextView footer = text("각 방은 사용자가 확인한 카카오 답장 세션에만 전송해. 세션을 확인할 수 없으면 다른 방으로 추측 전송하지 않고 그 방만 실패 처리해.", 12, false);
        footer.setTextColor(Color.GRAY);
        root.addView(footer, top(22));
        return scroll;
    }

    private void showProfileSelector() {
        final ArrayList<MultiRoomStore.Profile> profiles = MultiRoomStore.list(this);
        if (profiles.isEmpty()) {
            toast("아직 저장된 방이 없어. 방을 연결한 뒤 설정을 저장해줘.");
            return;
        }
        String[] items = new String[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            MultiRoomStore.Profile p = profiles.get(i);
            String limit = p.unlimited() ? "무제한" : p.todayCount + "/" + p.dailyLimit + "회";
            items[i] = (p.enabled ? "● " : "○ ") + p.room + "\n"
                    + p.intervalMinutes + "분 간격 · " + limit
                    + (KakaoNotificationListener.hasLiveSession(p.room) ? " · 연결됨" : " · 세션대기");
        }
        new AlertDialog.Builder(this)
                .setTitle("등록된 방 · " + profiles.size() + "개")
                .setItems(items, (d, which) -> loadProfile(profiles.get(which)))
                .setNegativeButton("취소", null)
                .show();
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
                        loadOrPrepareRoom(room);
                        toast("방 연결 완료. 이 방 설정을 저장해줘.");
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
            toast("방 이름을 입력한 뒤 최근 알림을 골라줘.");
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
                .setMessage(entry.description + "\n\n이 알림이 실제 이 방에서 온 게 맞을 때만 연결해.")
                .setPositiveButton("확인 · 연결", (d, w) -> {
                    boolean ok = KakaoNotificationListener.bindRecentTokenToRoom(this, entry.token, room);
                    if (ok) {
                        loadOrPrepareRoom(room);
                        toast("연결 완료. 이 방 설정을 저장해줘.");
                    } else {
                        toast("세션이 만료됐어. 새 메시지를 받은 뒤 다시 시도해줘.");
                    }
                    refreshUi();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private boolean saveCurrentProfile(boolean notify) {
        String room = roomInput.getText().toString().trim();
        if (room.isEmpty()) {
            if (notify) toast("방을 먼저 연결하거나 이름을 입력해줘.");
            return false;
        }

        MultiRoomStore.Profile old = MultiRoomStore.get(this, room);
        MultiRoomStore.Profile p = old == null ? new MultiRoomStore.Profile(room) : old;
        p.room = room;
        p.message = messageInput.getText().toString();
        p.intervalMinutes = Math.max(SendScheduler.MIN_INTERVAL_MINUTES,
                parseInt(intervalInput.getText().toString(), 60));
        p.dailyLimit = unlimitedCheck.isChecked() ? 0 : Math.max(1, parseInt(maxInput.getText().toString(), 8));
        p.enabled = roomEnabledCheck.isChecked();

        boolean masterActive = Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false);
        if (masterActive && p.enabled && !p.message.trim().isEmpty()) {
            p.nextAt = System.currentTimeMillis() + p.intervalMinutes * 60_000L;
        } else if (!p.enabled || p.message.trim().isEmpty()) {
            p.nextAt = 0L;
        }

        MultiRoomStore.upsert(this, p);
        intervalInput.setText(String.valueOf(p.intervalMinutes));
        maxInput.setText(p.unlimited() ? "8" : String.valueOf(p.dailyLimit));
        if (masterActive) SendScheduler.scheduleNext(this);
        Prefs.setStatus(this, "방 설정 저장: " + room + " · " + p.intervalMinutes + "분 · "
                + (p.unlimited() ? "횟수 무제한" : "하루 " + p.dailyLimit + "회"));
        if (notify) toast("이 방 설정을 저장했어.");
        refreshUi();
        return true;
    }

    private void confirmTestSend() {
        if (!saveCurrentProfile(false)) return;
        String room = roomInput.getText().toString().trim();
        MultiRoomStore.Profile p = MultiRoomStore.get(this, room);
        if (p == null || p.message.trim().isEmpty()) {
            toast("이 방에 보낼 메시지를 입력해줘.");
            return;
        }
        if (!KakaoNotificationListener.hasLiveSession(room)) {
            toast("전송 차단: 이 방의 확인된 실시간 세션이 없어.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("1회 테스트 전송")
                .setMessage("대상: " + room + "\n\n" + p.message)
                .setPositiveButton("전송", (d, w) -> {
                    boolean ok = KakaoNotificationListener.sendToRoom(this, room, p.message);
                    if (ok) {
                        Prefs.markManualSuccess(this);
                        Prefs.setStatus(this, "테스트 전송 성공: " + room);
                        toast("전송 성공.");
                    } else {
                        String reason = KakaoNotificationListener.lastSendError();
                        Prefs.recordFailure(this);
                        Prefs.setStatus(this, "테스트 실패: " + room + " · " + reason);
                        toast("테스트 실패: " + reason);
                    }
                    refreshUi();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void deleteCurrentProfile() {
        String room = roomInput.getText().toString().trim();
        if (room.isEmpty()) {
            toast("삭제할 방을 선택해줘.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("이 방 설정을 삭제할까?")
                .setMessage(room + "\n\n이 방의 자동전송 설정과 저장된 연결 규칙을 삭제해.")
                .setPositiveButton("삭제", (d, w) -> {
                    MultiRoomStore.remove(this, room);
                    KakaoNotificationListener.unbindRoom(this, room);
                    clearEditor();
                    SendScheduler.scheduleNext(this);
                    toast("방 설정을 삭제했어.");
                    refreshUi();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void startAutomation() {
        saveCurrentProfile(false);
        if (!isNotificationAccessEnabled()) {
            toast("알림 접근 권한부터 켜줘.");
            return;
        }
        ArrayList<MultiRoomStore.Profile> profiles = MultiRoomStore.list(this);
        int usable = 0;
        int live = 0;
        for (MultiRoomStore.Profile p : profiles) {
            if (!p.enabled || p.message.trim().isEmpty()) continue;
            usable++;
            if (KakaoNotificationListener.hasLiveSession(p.room)) live++;
        }
        if (usable == 0) {
            toast("자동전송 사용이 켜져 있고 메시지가 저장된 방이 없어.");
            return;
        }
        if (live == 0) {
            toast("시작 차단: 현재 전송 가능한 실시간 방 연결이 하나도 없어.");
            return;
        }
        Prefs.p(this).edit().putBoolean(Prefs.KEY_ACTIVE, true).apply();
        MultiRoomStore.setAllNextFromNow(this);
        SendScheduler.scheduleNext(this);
        Prefs.setStatus(this, "다중방 자동전송 시작 · 사용 " + usable + "개 · 현재 연결 " + live + "개");
        toast("전체 자동전송을 시작했어. 방마다 저장한 간격으로 따로 보낼게.");
        refreshUi();
    }

    private void stopAutomation() {
        Prefs.p(this).edit().putBoolean(Prefs.KEY_ACTIVE, false).apply();
        SendScheduler.cancel(this);
        Prefs.setStatus(this, "전체 자동전송 즉시 중단");
        toast("모든 방의 자동전송 예약을 중단했어.");
        refreshUi();
    }

    private void loadInitialProfile() {
        ArrayList<MultiRoomStore.Profile> profiles = MultiRoomStore.list(this);
        if (!profiles.isEmpty()) loadProfile(profiles.get(0));
        else clearEditor();
    }

    private void loadOrPrepareRoom(String room) {
        MultiRoomStore.Profile p = MultiRoomStore.get(this, room);
        if (p != null) {
            loadProfile(p);
            return;
        }
        roomInput.setText(room);
        messageInput.setText("");
        intervalInput.setText("60");
        maxInput.setText("8");
        unlimitedCheck.setChecked(false);
        roomEnabledCheck.setChecked(true);
    }

    private void loadProfile(MultiRoomStore.Profile p) {
        if (p == null) return;
        roomInput.setText(p.room);
        messageInput.setText(p.message);
        intervalInput.setText(String.valueOf(p.intervalMinutes));
        unlimitedCheck.setChecked(p.unlimited());
        maxInput.setText(p.unlimited() ? "8" : String.valueOf(p.dailyLimit));
        maxInput.setEnabled(!p.unlimited());
        roomEnabledCheck.setChecked(p.enabled);
        refreshUi();
    }

    private void clearEditor() {
        roomInput.setText("");
        messageInput.setText("");
        intervalInput.setText("60");
        maxInput.setText("8");
        unlimitedCheck.setChecked(false);
        roomEnabledCheck.setChecked(true);
    }

    private void showDiagnostics() {
        String room = roomInput.getText().toString().trim();
        StringBuilder info = new StringBuilder(KakaoNotificationListener.diagnostics(this, room));
        info.append("\n\n[다중방 프로필]");
        ArrayList<MultiRoomStore.Profile> profiles = MultiRoomStore.list(this);
        if (profiles.isEmpty()) info.append("\n없음");
        for (MultiRoomStore.Profile p : profiles) {
            info.append("\n• ").append(p.room)
                    .append(" · ").append(p.enabled ? "사용" : "중지")
                    .append(" · ").append(p.intervalMinutes).append("분")
                    .append(" · ").append(p.unlimited() ? "무제한" : p.todayCount + "/" + p.dailyLimit)
                    .append(" · next=").append(p.nextAt > 0 ? formatTime(p.nextAt) : "-")
                    .append(" · 실패=").append(p.failureStreak);
        }
        String text = info.toString();
        TextView body = text(text, 12, false);
        body.setTextIsSelectable(true);
        body.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroller = new ScrollView(this);
        scroller.addView(body);
        new AlertDialog.Builder(this)
                .setTitle("진단 정보")
                .setView(scroller)
                .setPositiveButton("복사", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("KakaoAutoSender diagnostics", text));
                    toast("진단 정보를 복사했어.");
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void resetConnections() {
        new AlertDialog.Builder(this)
                .setTitle("모든 카카오 방 연결을 초기화할까?")
                .setMessage("자동전송을 중단하고 카카오 답장 세션 연결 규칙을 지워. 저장한 방별 메시지/간격 설정은 남겨둬.")
                .setPositiveButton("연결만 초기화", (d, w) -> {
                    Prefs.p(this).edit().putBoolean(Prefs.KEY_ACTIVE, false).apply();
                    SendScheduler.cancel(this);
                    KakaoNotificationListener.clearRuntimeAndBindings(this);
                    toast("카카오 방 연결을 초기화했어. 방별 설정은 유지돼.");
                    refreshUi();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void refreshUi() {
        if (automationStatus == null) return;
        boolean active = Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false);
        boolean access = isNotificationAccessEnabled();
        String room = roomInput == null ? "" : roomInput.getText().toString().trim();
        MultiRoomStore.Profile selected = room.isEmpty() ? null : MultiRoomStore.get(this, room);
        boolean live = !room.isEmpty() && KakaoNotificationListener.hasLiveSession(room);
        boolean stored = !room.isEmpty() && KakaoNotificationListener.hasStoredBinding(this, room);

        automationStatus.setText(active ? "● 전체 자동전송 실행 중" : "● 전체 자동전송 중지됨");
        automationStatus.setTextColor(active ? Color.rgb(80, 220, 140) : Color.LTGRAY);
        listenerStatus.setText(access
                ? "● 알림 접근 허용 · 리스너 " + (KakaoNotificationListener.isListenerConnected() ? "연결됨" : "연결 대기")
                : "● 알림 접근 꺼짐");
        listenerStatus.setTextColor(access ? Color.rgb(90, 210, 150) : Color.rgb(255, 120, 120));

        if (room.isEmpty()) {
            roomStatus.setText("현재 선택한 방 없음 · 감지 후보 또는 등록된 방에서 선택");
        } else {
            roomStatus.setText("현재 방: " + room
                    + "\n실시간 답장 세션: " + (live ? "확인됨" : "없음")
                    + " · 자동복구: " + (stored ? "가능" : "없음")
                    + (selected == null ? " · 프로필 미저장" : " · 프로필 저장됨"));
        }

        ArrayList<MultiRoomStore.Profile> profiles = MultiRoomStore.list(this);
        StringBuilder ps = new StringBuilder();
        ps.append("등록된 방: ").append(profiles.size()).append("개")
                .append(" · 사용: ").append(MultiRoomStore.enabledCount(this)).append("개")
                .append(" · 현재 세션 준비: ").append(MultiRoomStore.readyCount(this)).append("개");
        int shown = 0;
        for (MultiRoomStore.Profile p : profiles) {
            if (shown >= 8) {
                ps.append("\n… 외 ").append(profiles.size() - shown).append("개");
                break;
            }
            ps.append("\n").append(p.enabled ? "● " : "○ ").append(p.room)
                    .append(" · ").append(p.intervalMinutes).append("분")
                    .append(" · ").append(p.unlimited() ? "무제한" : p.todayCount + "/" + p.dailyLimit + "회");
            if (active && p.enabled && p.nextAt > 0) ps.append(" · 다음 ").append(formatTime(p.nextAt));
            shown++;
        }
        profilesStatus.setText(ps.toString());

        String last = Prefs.p(this).getString(Prefs.KEY_LAST_STATUS, "아직 기록 없음");
        long next = MultiRoomStore.nextDueAt(this);
        runtimeStatus.setText("상태: " + last
                + "\n감지된 방 후보: " + KakaoNotificationListener.candidateSessionEntries().size() + "개"
                + " · 확인된 실시간 세션: " + KakaoNotificationListener.liveLabels().size() + "개"
                + (active && next > 0 ? "\n가장 가까운 다음 예약: " + formatTime(next) : ""));

        startButton.setEnabled(!active);
        stopButton.setEnabled(active);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
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
