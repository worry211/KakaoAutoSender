package com.local.kakaoautosender;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivityV4 extends Activity {
    private LinearLayout roomList;
    private TextView masterStatus;
    private TextView systemStatus;
    private TextView summaryStatus;
    private Button startButton;
    private Button stopButton;
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refreshUi();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.ensureLabelSchema(this);
        MultiRoomStore.ensureMigrated(this);
        setContentView(buildUi());
        refreshUi();
    }

    @Override protected void onStart() {
        super.onStart();
        registerUpdates();
    }

    @Override protected void onStop() {
        unregisterUpdates();
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        KakaoNotificationListener.requestRefresh();
        refreshUi();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(13, 14, 17));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(22), dp(16), dp(36));
        scroll.addView(root);

        root.addView(text("카톡 자동전송", 28, true));
        TextView subtitle = text("v" + appVersion() + " · 방별 자동전송 대시보드", 12, false);
        subtitle.setTextColor(Color.rgb(145, 150, 160));
        root.addView(subtitle, top(3));

        LinearLayout masterCard = card();
        masterStatus = text("", 18, true);
        masterCard.addView(masterStatus);
        systemStatus = text("", 13, false);
        systemStatus.setTextColor(Color.LTGRAY);
        masterCard.addView(systemStatus, top(8));

        LinearLayout masterButtons = new LinearLayout(this);
        masterButtons.setOrientation(LinearLayout.HORIZONTAL);
        startButton = actionButton("전체 시작", Color.rgb(38, 126, 82));
        startButton.setOnClickListener(v -> startAll());
        masterButtons.addView(startButton, weight());
        stopButton = actionButton("전체 중단", Color.rgb(142, 55, 62));
        stopButton.setOnClickListener(v -> stopAll());
        LinearLayout.LayoutParams stopLp = weight();
        stopLp.leftMargin = dp(8);
        masterButtons.addView(stopButton, stopLp);
        masterCard.addView(masterButtons, top(14));
        root.addView(masterCard, top(18));

        root.addView(section("내 자동전송 방"), top(24));
        TextView roomHint = text("내가 붙인 이름을 크게 보여주고, 실제 카카오 방 이름은 아래에 따로 표시해 헷갈리지 않게 했어.", 12, false);
        roomHint.setTextColor(Color.rgb(145, 150, 160));
        root.addView(roomHint, top(5));

        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        Button add = actionButton("＋ 새 방 추가", Color.rgb(48, 88, 158));
        add.setOnClickListener(v -> showAddCandidates());
        addRow.addView(add, weight());
        Button scan = actionButton("새로고침", Color.rgb(70, 72, 80));
        scan.setOnClickListener(v -> {
            KakaoNotificationListener.requestReconnect(this);
            KakaoNotificationListener.requestRefresh();
            toast("카카오 알림을 다시 확인했어.");
            refreshUi();
        });
        LinearLayout.LayoutParams scanLp = weight();
        scanLp.leftMargin = dp(8);
        addRow.addView(scan, scanLp);
        root.addView(addRow, top(10));

        roomList = new LinearLayout(this);
        roomList.setOrientation(LinearLayout.VERTICAL);
        root.addView(roomList, top(8));

        summaryStatus = text("", 13, false);
        summaryStatus.setTextColor(Color.rgb(185, 190, 200));
        root.addView(summaryStatus, top(16));

        root.addView(section("권한 / 점검"), top(26));
        Button notification = button("알림 접근 권한");
        notification.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(notification, top(8));

        if (Build.VERSION.SDK_INT >= 31) {
            Button exact = button("정확한 시각 전송 권한 (선택)");
            exact.setOnClickListener(v -> requestExactAlarmAccess());
            root.addView(exact, top(8));
        }

        Button diagnostics = button("진단 정보 보기");
        diagnostics.setOnClickListener(v -> showDiagnostics());
        root.addView(diagnostics, top(8));

        Button reset = button("카카오 방 연결만 초기화");
        reset.setOnClickListener(v -> resetBindings());
        root.addView(reset, top(8));

        TextView footer = text("실제 전송은 확인된 방 세션에만 한다. 방 연결이 불확실하면 다른 방으로 대신 보내지 않고 해당 방 전송만 실패 처리한다.", 11, false);
        footer.setTextColor(Color.rgb(120, 124, 132));
        root.addView(footer, top(20));
        return scroll;
    }

    private void refreshUi() {
        boolean active = Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false);
        boolean access = isNotificationAccessEnabled();
        boolean listener = KakaoNotificationListener.isListenerConnected();
        boolean exact = SendScheduler.canUseExact(this);
        ArrayList<MultiRoomStore.Profile> profiles = MultiRoomStore.list(this);

        masterStatus.setText(active ? "● 전체 자동전송 실행 중" : "● 전체 자동전송 중지됨");
        masterStatus.setTextColor(active ? Color.rgb(93, 226, 150) : Color.rgb(205, 208, 215));
        systemStatus.setText("알림 접근 " + (access ? "허용" : "꺼짐")
                + " · 리스너 " + (listener ? "연결됨" : "대기")
                + (Build.VERSION.SDK_INT >= 31 ? " · 정확한 시각 " + (exact ? "가능" : "근사 전송") : ""));
        startButton.setEnabled(!active);
        stopButton.setEnabled(active);

        roomList.removeAllViews();
        if (profiles.isEmpty()) {
            LinearLayout empty = card();
            TextView e1 = text("아직 등록된 방이 없어", 17, true);
            empty.addView(e1);
            TextView e2 = text("대상 오픈채팅에서 메시지를 하나 받은 뒤 ‘새 방 추가’를 눌러줘.", 13, false);
            e2.setTextColor(Color.LTGRAY);
            empty.addView(e2, top(7));
            roomList.addView(empty, top(4));
        } else {
            for (MultiRoomStore.Profile p : profiles) roomList.addView(roomCard(p), top(7));
        }

        long next = MultiRoomStore.nextDueAt(this);
        summaryStatus.setText("등록 " + profiles.size() + "개 · 사용 " + MultiRoomStore.enabledCount(this)
                + "개 · 지금 전송 가능 " + MultiRoomStore.readyCount(this) + "개"
                + (active && next > 0 ? "\n가장 가까운 다음 전송: " + formatDateTime(next) : "")
                + "\n최근 상태: " + Prefs.p(this).getString(Prefs.KEY_LAST_STATUS, "기록 없음"));
    }

    private View roomCard(MultiRoomStore.Profile p) {
        LinearLayout card = card();
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text(p.title(), 18, true);
        titleRow.addView(title, weight());
        TextView state = pill(p.enabled ? "사용" : "중지",
                p.enabled ? Color.rgb(40, 125, 82) : Color.rgb(85, 87, 94));
        titleRow.addView(state);
        card.addView(titleRow);

        TextView actual = text("실제 카카오 방  ·  " + p.room, 12, false);
        actual.setTextColor(Color.rgb(150, 157, 170));
        card.addView(actual, top(5));

        boolean live = KakaoNotificationListener.hasLiveSession(p.room);
        boolean stored = KakaoNotificationListener.hasStoredBinding(this, p.room);
        TextView connection = text((live ? "● 연결 확인됨" : stored ? "● 새 알림 대기" : "● 연결 필요")
                + "   ·   " + MultiRoomStore.scheduleSummary(p), 13, true);
        connection.setTextColor(live ? Color.rgb(89, 220, 146)
                : stored ? Color.rgb(245, 186, 80) : Color.rgb(238, 112, 112));
        card.addView(connection, top(9));

        String preview = p.message == null ? "" : p.message.replace('\n', ' ').trim();
        if (preview.length() > 70) preview = preview.substring(0, 70) + "…";
        TextView message = text(preview.isEmpty() ? "메시지 미설정" : "“" + preview + "”", 13, false);
        message.setTextColor(Color.rgb(215, 218, 224));
        card.addView(message, top(9));

        String limit = p.unlimited() ? "오늘 횟수 제한 없음" : "오늘 " + p.todayCount + "/" + p.dailyLimit + "회";
        String next = p.nextAt > 0 ? " · 다음 " + formatTime(p.nextAt) : "";
        TextView meta = text(limit + next + (p.failureStreak > 0 ? " · 연속 실패 " + p.failureStreak : ""), 12, false);
        meta.setTextColor(Color.rgb(150, 157, 170));
        card.addView(meta, top(7));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button edit = smallButton("편집");
        edit.setOnClickListener(v -> openEditor(p.room));
        actions.addView(edit, weight());
        Button test = smallButton("1회 전송");
        test.setOnClickListener(v -> testRoom(p));
        LinearLayout.LayoutParams tLp = weight(); tLp.leftMargin = dp(6);
        actions.addView(test, tLp);
        Button toggle = smallButton(p.enabled ? "일시정지" : "사용 켜기");
        toggle.setOnClickListener(v -> toggleRoom(p));
        LinearLayout.LayoutParams gLp = weight(); gLp.leftMargin = dp(6);
        actions.addView(toggle, gLp);
        card.addView(actions, top(12));

        card.setOnClickListener(v -> openEditor(p.room));
        return card;
    }

    private void showAddCandidates() {
        KakaoNotificationListener.requestRefresh();
        final ArrayList<KakaoNotificationListener.SessionEntry> entries = KakaoNotificationListener.candidateSessionEntries();
        if (entries.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("감지된 새 방이 없어")
                    .setMessage("추가할 오픈채팅방에서 새 메시지를 하나 받은 뒤 다시 눌러줘.\n\n알림에는 방 이름이 보이는데 목록에 안 뜨면 ‘최근 알림에서 직접 추가’를 사용하면 돼.")
                    .setPositiveButton("최근 알림에서 직접 추가", (d, w) -> showRecentForManualAdd())
                    .setNegativeButton("닫기", null)
                    .show();
            return;
        }
        String[] items = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            KakaoNotificationListener.SessionEntry e = entries.get(i);
            String room = e.suggestedRoom == null ? "방 이름 미확인" : e.suggestedRoom;
            items[i] = room + "\n" + e.description;
        }
        new AlertDialog.Builder(this)
                .setTitle("추가할 실제 카카오 방 선택")
                .setItems(items, (d, which) -> pairCandidate(entries.get(which)))
                .setPositiveButton("최근 알림에서 직접 추가", (d, w) -> showRecentForManualAdd())
                .setNegativeButton("취소", null)
                .show();
    }

    private void pairCandidate(KakaoNotificationListener.SessionEntry entry) {
        if (entry == null || entry.suggestedRoom == null || entry.suggestedRoom.trim().isEmpty()) {
            toast("이 알림은 방 이름을 확인할 수 없어. 직접 추가를 사용해줘.");
            return;
        }
        String room = entry.suggestedRoom.trim();
        new AlertDialog.Builder(this)
                .setTitle("이 방을 추가할까?")
                .setMessage("실제 카카오 방\n" + room + "\n\n" + entry.description)
                .setPositiveButton("연결하고 설정", (d, w) -> {
                    if (!KakaoNotificationListener.bindRecentTokenToRoom(this, entry.token, room)) {
                        toast("알림 세션이 만료됐어. 새 메시지를 받은 뒤 다시 시도해줘.");
                        return;
                    }
                    MultiRoomStore.Profile p = MultiRoomStore.get(this, room);
                    if (p == null) MultiRoomStore.upsert(this, new MultiRoomStore.Profile(room));
                    openEditor(room);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void showRecentForManualAdd() {
        KakaoNotificationListener.requestRefresh();
        final ArrayList<KakaoNotificationListener.SessionEntry> entries = KakaoNotificationListener.recentSessionEntries();
        if (entries.isEmpty()) {
            toast("최근 답장 가능한 카카오 알림이 없어.");
            return;
        }
        String[] items = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) items[i] = entries.get(i).description;
        new AlertDialog.Builder(this)
                .setTitle("최근 카카오 알림 선택")
                .setItems(items, (d, which) -> askManualRoomName(entries.get(which)))
                .setNegativeButton("취소", null)
                .show();
    }

    private void askManualRoomName(KakaoNotificationListener.SessionEntry entry) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("알림창에 보이는 실제 오픈채팅방 이름");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        if (entry.suggestedRoom != null) input.setText(entry.suggestedRoom);
        new AlertDialog.Builder(this)
                .setTitle("실제 카카오 방 이름 확인")
                .setMessage(entry.description + "\n\n오배송 방지를 위해 알림창의 방 이름과 정확히 같게 입력해.")
                .setView(input)
                .setPositiveButton("연결", (d, w) -> {
                    String room = input.getText().toString().trim();
                    if (room.isEmpty()) { toast("방 이름이 비어 있어."); return; }
                    if (!KakaoNotificationListener.bindRecentTokenToRoom(this, entry.token, room)) {
                        toast("세션이 만료됐어."); return;
                    }
                    if (MultiRoomStore.get(this, room) == null) MultiRoomStore.upsert(this, new MultiRoomStore.Profile(room));
                    openEditor(room);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void openEditor(String room) {
        Intent i = new Intent(this, RoomEditorActivity.class);
        i.putExtra(RoomEditorActivity.EXTRA_ROOM, room);
        startActivity(i);
    }

    private void toggleRoom(MultiRoomStore.Profile profile) {
        MultiRoomStore.Profile p = profile.copy();
        p.enabled = !p.enabled;
        if (!p.enabled) p.nextAt = 0L;
        else if (Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false) && !p.message.trim().isEmpty()) {
            p.nextAt = MultiRoomStore.computeNextAt(p, System.currentTimeMillis());
        }
        MultiRoomStore.upsert(this, p);
        if (Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false)) SendScheduler.scheduleNext(this);
        Prefs.setStatus(this, p.title() + (p.enabled ? " 사용 켬" : " 일시정지"));
        refreshUi();
    }

    private void testRoom(MultiRoomStore.Profile p) {
        if (p.message == null || p.message.trim().isEmpty()) {
            toast("이 방은 메시지가 비어 있어. 편집에서 먼저 입력해줘.");
            return;
        }
        if (!KakaoNotificationListener.hasLiveSession(p.room)) {
            toast("이 방의 답장 세션이 없어. 방에서 새 메시지를 받은 뒤 새로고침해줘.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("1회 전송 · " + p.title())
                .setMessage("실제 방: " + p.room + "\n\n" + p.message)
                .setPositiveButton("전송", (d, w) -> {
                    boolean ok = KakaoNotificationListener.sendToRoom(this, p.room, p.message);
                    Prefs.setStatus(this, ok ? "수동 전송 성공: " + p.title()
                            : "수동 전송 실패: " + p.title() + " · " + KakaoNotificationListener.lastSendError());
                    toast(ok ? "전송 성공" : "전송 실패: " + KakaoNotificationListener.lastSendError());
                    refreshUi();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void startAll() {
        if (!isNotificationAccessEnabled()) {
            toast("알림 접근 권한부터 허용해줘.");
            return;
        }
        ArrayList<MultiRoomStore.Profile> profiles = MultiRoomStore.list(this);
        int usable = 0;
        for (MultiRoomStore.Profile p : profiles) {
            if (p.enabled && p.message != null && !p.message.trim().isEmpty()) usable++;
        }
        if (usable == 0) {
            toast("사용 중이고 메시지가 저장된 방이 없어.");
            return;
        }
        Prefs.p(this).edit().putBoolean(Prefs.KEY_ACTIVE, true).apply();
        MultiRoomStore.setAllNextFromNow(this);
        SendScheduler.scheduleNext(this);
        Prefs.setStatus(this, "전체 자동전송 시작 · " + usable + "개 방");
        refreshUi();
        toast("전체 자동전송 시작");
    }

    private void stopAll() {
        Prefs.p(this).edit().putBoolean(Prefs.KEY_ACTIVE, false).apply();
        SendScheduler.cancel(this);
        Prefs.setStatus(this, "전체 자동전송 즉시 중단");
        refreshUi();
        toast("모든 자동전송 예약을 중단했어.");
    }

    private void requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < 31 || SendScheduler.canUseExact(this)) {
            toast("정확한 시각 전송 권한을 이미 사용할 수 있어.");
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            toast("이 기기에서는 정확한 알람 권한 화면을 바로 열 수 없어.");
        }
    }

    private void showDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append(KakaoNotificationListener.diagnostics(this, ""));
        sb.append("\n\n[대시보드]");
        sb.append("\n전체 활성: ").append(Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false));
        sb.append("\n정확 알람: ").append(SendScheduler.canUseExact(this));
        for (MultiRoomStore.Profile p : MultiRoomStore.list(this)) {
            sb.append("\n• ").append(p.title()).append(" / 실제=").append(p.room)
                    .append(" / ").append(MultiRoomStore.scheduleSummary(p))
                    .append(" / ").append(p.enabled ? "사용" : "중지")
                    .append(" / ").append(p.unlimited() ? "무제한" : p.todayCount + "/" + p.dailyLimit)
                    .append(" / next=").append(p.nextAt > 0 ? formatDateTime(p.nextAt) : "-");
        }
        TextView body = text(sb.toString(), 12, false);
        body.setTextIsSelectable(true);
        body.setPadding(dp(14), dp(10), dp(14), dp(10));
        ScrollView sc = new ScrollView(this); sc.addView(body);
        new AlertDialog.Builder(this).setTitle("진단 정보").setView(sc).setNegativeButton("닫기", null).show();
    }

    private void resetBindings() {
        new AlertDialog.Builder(this)
                .setTitle("카카오 방 연결만 초기화할까?")
                .setMessage("내가 저장한 방 이름/메시지/시간 설정은 그대로 두고, 카카오 답장 연결 정보만 지워.")
                .setPositiveButton("연결만 초기화", (d, w) -> {
                    stopAll();
                    KakaoNotificationListener.clearRuntimeAndBindings(this);
                    refreshUi();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerUpdates() {
        if (receiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter(KakaoNotificationListener.ACTION_SESSIONS_UPDATED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(receiver, filter);
            receiverRegistered = true;
        } catch (Throwable ignored) {}
    }

    private void unregisterUpdates() {
        if (!receiverRegistered) return;
        try { unregisterReceiver(receiver); } catch (Throwable ignored) {}
        receiverRegistered = false;
    }

    private boolean isNotificationAccessEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(new ComponentName(this, KakaoNotificationListener.class).flattenToString());
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    private String formatTime(long ms) { return new SimpleDateFormat("HH:mm", Locale.KOREA).format(new Date(ms)); }
    private String formatDateTime(long ms) { return new SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(new Date(ms)); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(14), dp(14), dp(14));
        l.setBackground(round(Color.rgb(30, 32, 38), 16));
        return l;
    }

    private TextView section(String value) {
        TextView v = text(value, 18, true);
        v.setTextColor(Color.rgb(218, 228, 255));
        return v;
    }

    private TextView pill(String value, int color) {
        TextView v = text(value, 11, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(9), dp(5), dp(9), dp(5));
        v.setBackground(round(color, 14));
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

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        return b;
    }

    private Button actionButton(String label, int color) {
        Button b = button(label);
        b.setBackground(round(color, 12));
        return b;
    }

    private Button smallButton(String label) {
        Button b = actionButton(label, Color.rgb(62, 65, 74));
        b.setTextSize(12);
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LinearLayout.LayoutParams top(int v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(v); return lp;
    }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
}
