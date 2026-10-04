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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class MainActivityV4 extends Activity {
    private LinearLayout roomList;
    private TextView masterStatus;
    private TextView systemStatus;
    private TextView summaryStatus;
    private Button startButton;
    private Button stopButton;
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshUi(); }
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
        scroll.setBackgroundColor(Color.rgb(12, 13, 16));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(40));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("카톡매크로", 28, true);
        header.addView(title, weight());
        TextView version = pill("v" + appVersion(), Color.rgb(56, 61, 72));
        header.addView(version);
        root.addView(header);

        TextView subtitle = text("방마다 메시지와 시간을 따로 관리해", 12, false);
        subtitle.setTextColor(Color.rgb(142, 148, 160));
        root.addView(subtitle, top(4));

        LinearLayout masterCard = card(Color.rgb(27, 30, 36));
        masterStatus = text("", 18, true);
        masterCard.addView(masterStatus);
        systemStatus = text("", 12, false);
        systemStatus.setTextColor(Color.rgb(174, 180, 191));
        masterCard.addView(systemStatus, top(7));

        LinearLayout masterButtons = new LinearLayout(this);
        masterButtons.setOrientation(LinearLayout.HORIZONTAL);
        startButton = actionButton("전체 시작", Color.rgb(39, 126, 83));
        startButton.setOnClickListener(v -> startAll());
        masterButtons.addView(startButton, weight());
        stopButton = actionButton("전체 중단", Color.rgb(142, 54, 61));
        stopButton.setOnClickListener(v -> stopAll());
        LinearLayout.LayoutParams stopLp = weight();
        stopLp.leftMargin = dp(8);
        masterButtons.addView(stopButton, stopLp);
        masterCard.addView(masterButtons, top(14));
        root.addView(masterCard, top(18));

        LinearLayout sectionRow = new LinearLayout(this);
        sectionRow.setOrientation(LinearLayout.HORIZONTAL);
        sectionRow.setGravity(Gravity.CENTER_VERTICAL);
        sectionRow.addView(section("방 목록"), weight());
        Button refresh = compactButton("새로고침", Color.rgb(60, 64, 74));
        refresh.setOnClickListener(v -> {
            KakaoNotificationListener.requestReconnect(this);
            KakaoNotificationListener.requestRefresh();
            refreshUi();
            toast("방 정보를 다시 확인했어.");
        });
        sectionRow.addView(refresh);
        root.addView(sectionRow, top(24));

        TextView hint = text("목록과 선택창에는 실제 카카오 방 이름만 보여줘.", 12, false);
        hint.setTextColor(Color.rgb(139, 145, 156));
        root.addView(hint, top(4));

        Button add = actionButton("＋ 새 방 추가", Color.rgb(48, 88, 158));
        add.setOnClickListener(v -> showAddCandidates());
        root.addView(add, top(10));

        roomList = new LinearLayout(this);
        roomList.setOrientation(LinearLayout.VERTICAL);
        root.addView(roomList, top(5));

        summaryStatus = text("", 12, false);
        summaryStatus.setTextColor(Color.rgb(174, 180, 191));
        root.addView(summaryStatus, top(14));

        root.addView(section("설정 / 문제 해결"), top(26));
        LinearLayout toolsRow = new LinearLayout(this);
        toolsRow.setOrientation(LinearLayout.HORIZONTAL);
        Button notification = compactButton("알림 권한", Color.rgb(62, 65, 74));
        notification.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        toolsRow.addView(notification, weight());
        if (Build.VERSION.SDK_INT >= 31) {
            Button exact = compactButton("정확 시각", Color.rgb(62, 65, 74));
            exact.setOnClickListener(v -> requestExactAlarmAccess());
            LinearLayout.LayoutParams eLp = weight(); eLp.leftMargin = dp(7);
            toolsRow.addView(exact, eLp);
        }
        root.addView(toolsRow, top(8));

        LinearLayout toolsRow2 = new LinearLayout(this);
        toolsRow2.setOrientation(LinearLayout.HORIZONTAL);
        Button diagnostics = compactButton("진단 정보", Color.rgb(62, 65, 74));
        diagnostics.setOnClickListener(v -> showDiagnostics());
        toolsRow2.addView(diagnostics, weight());
        Button reset = compactButton("방 연결 초기화", Color.rgb(94, 58, 62));
        reset.setOnClickListener(v -> resetBindings());
        LinearLayout.LayoutParams rLp = weight(); rLp.leftMargin = dp(7);
        toolsRow2.addView(reset, rLp);
        root.addView(toolsRow2, top(7));

        TextView footer = text("전송 대상이 확실하지 않으면 보내지 않는 방식으로 동작해. 한 방 연결이 깨져도 다른 방으로 대신 전송하지 않아.", 11, false);
        footer.setTextColor(Color.rgb(112, 118, 129));
        root.addView(footer, top(18));
        return scroll;
    }

    private void refreshUi() {
        if (masterStatus == null) return;
        boolean active = Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false);
        boolean access = isNotificationAccessEnabled();
        boolean listener = KakaoNotificationListener.isListenerConnected();
        boolean exact = SendScheduler.canUseExact(this);
        ArrayList<MultiRoomStore.Profile> profiles = MultiRoomStore.list(this);

        masterStatus.setText(active ? "● 자동전송 실행 중" : "● 자동전송 중지됨");
        masterStatus.setTextColor(active ? Color.rgb(91, 224, 147) : Color.rgb(213, 216, 223));
        systemStatus.setText("라이선스 " + LicenseManager.shortStatus(this) + "\n" + LicenseManager.updateNotice(this)
                + "\n알림 접근 " + (access ? "✓" : "권한 필요")
                + "  ·  리스너 " + (listener ? "정상" : "대기")
                + (Build.VERSION.SDK_INT >= 31 ? "  ·  정확한 알람 " + (exact ? "✓" : "선택사항 · 근사") : "")
                + "\n카카오 방 연결 " + profiles.size() + "개"
                + "\n카카오 알림은 켜두세요. 소리·진동·팝업만 끌 수 있습니다.");
        startButton.setEnabled(!active);
        stopButton.setEnabled(active);

        roomList.removeAllViews();
        if (profiles.isEmpty()) {
            LinearLayout empty = card(Color.rgb(27, 30, 36));
            empty.addView(text("등록된 방이 없어", 17, true));
            TextView guide = text("원하는 오픈채팅방에서 새 메시지를 하나 받은 뒤 ‘새 방 추가’를 눌러줘.", 13, false);
            guide.setTextColor(Color.rgb(166, 171, 182));
            empty.addView(guide, top(7));
            roomList.addView(empty, top(7));
        } else {
            for (MultiRoomStore.Profile p : profiles) roomList.addView(roomCard(p), top(7));
        }

        int ready = MultiRoomStore.readyCount(this);
        int enabled = MultiRoomStore.enabledCount(this);
        long next = MultiRoomStore.nextDueAt(this);
        StringBuilder s = new StringBuilder();
        s.append("등록 ").append(profiles.size()).append("개 · 사용 ").append(enabled)
                .append("개 · 지금 전송 가능 ").append(ready).append("개");
        if (active && next > 0) s.append("\n다음 전송  ").append(formatDateTime(next));
        String last = Prefs.p(this).getString(Prefs.KEY_LAST_STATUS, "");
        if (last != null && !last.trim().isEmpty()) s.append("\n최근  ").append(last);
        summaryStatus.setText(s.toString());
    }

    private View roomCard(MultiRoomStore.Profile p) {
        boolean live = KakaoNotificationListener.hasLiveSession(p.room);
        boolean stored = KakaoNotificationListener.hasStoredBinding(this, p.room);

        LinearLayout card = card(Color.rgb(29, 32, 38));
        card.setOnClickListener(v -> openEditor(p.room));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(text(p.title(), 18, true), weight());
        TextView enabledBadge = pill(p.enabled ? "사용" : "중지",
                p.enabled ? Color.rgb(40, 122, 81) : Color.rgb(80, 83, 91));
        titleRow.addView(enabledBadge);
        card.addView(titleRow);

        String connectionText = live ? "연결됨" : stored ? "새 알림 대기" : "연결 필요";
        int connectionColor = live ? Color.rgb(84, 219, 143)
                : stored ? Color.rgb(240, 182, 77) : Color.rgb(234, 108, 108);
        TextView state = text("● " + connectionText + "   ·   " + MultiRoomStore.scheduleSummary(p), 13, true);
        state.setTextColor(connectionColor);
        card.addView(state, top(8));

        String preview = p.message == null ? "" : p.message.replace('\n', ' ').trim();
        if (preview.length() > 82) preview = preview.substring(0, 82) + "…";
        TextView message = text(preview.isEmpty() ? "메시지 미설정" : preview, 13, false);
        message.setTextColor(preview.isEmpty() ? Color.rgb(235, 166, 89) : Color.rgb(216, 219, 225));
        card.addView(message, top(8));

        String count = p.unlimited() ? "횟수 무제한" : "오늘 " + p.todayCount + "/" + p.dailyLimit + "회";
        String next = p.nextAt > 0 ? " · 다음 " + formatTime(p.nextAt) : "";
        String failure = p.failureStreak > 0 ? " · 실패 " + p.failureStreak + "회" : "";
        TextView meta = text(count + next + failure, 12, false);
        meta.setTextColor(Color.rgb(148, 155, 168));
        card.addView(meta, top(6));

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
        card.addView(actions, top(11));
        return card;
    }

    private void showAddCandidates() {
        KakaoNotificationListener.requestRefresh();
        final ArrayList<KakaoNotificationListener.SessionEntry> entries = KakaoNotificationListener.candidateSessionEntries();
        if (entries.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("새 방이 감지되지 않았어")
                    .setMessage("추가할 오픈채팅방에서 새 메시지를 하나 받은 뒤 다시 눌러줘.")
                    .setPositiveButton("감지 안 될 때 직접 연결", (d, w) -> showAdvancedManualAdd())
                    .setNegativeButton("닫기", null)
                    .show();
            return;
        }

        String[] items = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            String room = entries.get(i).suggestedRoom;
            items[i] = room == null || room.trim().isEmpty() ? "방 이름 미확인" : room.trim();
        }
        new AlertDialog.Builder(this)
                .setTitle("추가할 방 선택")
                .setItems(items, (d, which) -> pairCandidate(entries.get(which)))
                .setNegativeButton("취소", null)
                .show();
    }

    private void pairCandidate(KakaoNotificationListener.SessionEntry entry) {
        if (entry == null || entry.suggestedRoom == null || entry.suggestedRoom.trim().isEmpty()) {
            showAdvancedManualAdd();
            return;
        }
        final String actualName = entry.suggestedRoom.trim();
        new AlertDialog.Builder(this)
                .setTitle(actualName)
                .setMessage("이 방을 자동전송 목록에 추가할까?")
                .setPositiveButton("추가", (d, w) -> connectCandidate(entry, actualName))
                .setNegativeButton("취소", null)
                .show();
    }

    private void connectCandidate(KakaoNotificationListener.SessionEntry entry, String actualName) {
        ArrayList<MultiRoomStore.Profile> existing = MultiRoomStore.findByActualName(this, actualName);
        if (existing.size() == 1) {
            MultiRoomStore.Profile p = existing.get(0);
            boolean ok = KakaoNotificationListener.bindRecentTokenToRoom(this, entry.token, p.room);
            if (!ok) {
                toast("알림 세션이 만료됐어. 방에서 새 메시지를 받은 뒤 다시 시도해줘.");
                return;
            }
            openEditor(p.room);
            return;
        }

        String routeAlias = "route-" + UUID.randomUUID();
        if (!KakaoNotificationListener.bindRecentTokenToRoom(this, entry.token, routeAlias)) {
            toast("알림 세션이 만료됐어. 방에서 새 메시지를 받은 뒤 다시 시도해줘.");
            return;
        }
        MultiRoomStore.Profile p = new MultiRoomStore.Profile(routeAlias);
        p.actualRoomName = actualName;
        p.displayName = actualName;
        MultiRoomStore.upsert(this, p);
        openEditor(routeAlias);
    }

    private void showAdvancedManualAdd() {
        KakaoNotificationListener.requestRefresh();
        final ArrayList<KakaoNotificationListener.SessionEntry> entries = KakaoNotificationListener.recentSessionEntries();
        if (entries.isEmpty()) {
            toast("최근 답장 가능한 카카오 알림이 없어.");
            return;
        }
        String[] items = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            String room = entries.get(i).suggestedRoom;
            items[i] = room == null || room.trim().isEmpty()
                    ? "방 이름 미확인 · 고급 연결"
                    : room.trim();
        }
        new AlertDialog.Builder(this)
                .setTitle("직접 연결")
                .setItems(items, (d, which) -> askManualRoomName(entries.get(which)))
                .setNegativeButton("취소", null)
                .show();
    }

    private void askManualRoomName(KakaoNotificationListener.SessionEntry entry) {
        EditText input = new EditText(this);
        input.setHint("알림창에 보이는 방 이름");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        if (entry.suggestedRoom != null) input.setText(entry.suggestedRoom);
        new AlertDialog.Builder(this)
                .setTitle("방 이름 확인")
                .setMessage("오배송 방지를 위해 카카오 알림창에 보이는 방 이름을 그대로 입력해.")
                .setView(input)
                .setPositiveButton("연결", (d, w) -> {
                    String actualName = input.getText().toString().trim();
                    if (actualName.isEmpty()) { toast("방 이름이 비어 있어."); return; }
                    String routeAlias = "route-" + UUID.randomUUID();
                    if (!KakaoNotificationListener.bindRecentTokenToRoom(this, entry.token, routeAlias)) {
                        toast("세션이 만료됐어."); return;
                    }
                    MultiRoomStore.Profile p = new MultiRoomStore.Profile(routeAlias);
                    p.actualRoomName = actualName;
                    p.displayName = actualName;
                    MultiRoomStore.upsert(this, p);
                    openEditor(routeAlias);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void openEditor(String routeAlias) {
        Intent i = new Intent(this, RoomEditorActivity.class);
        i.putExtra(RoomEditorActivity.EXTRA_ROOM, routeAlias);
        startActivity(i);
    }

    private void toggleRoom(MultiRoomStore.Profile profile) {
        MultiRoomStore.Profile p = profile.copy();
        p.enabled = !p.enabled;
        if (!p.enabled) p.nextAt = 0L;
        else if (Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false) && RoomMediaStore.hasPayload(this, p)) {
            p.nextAt = MultiRoomStore.computeNextAt(p, System.currentTimeMillis());
        }
        MultiRoomStore.upsert(this, p);
        if (Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false)) SendScheduler.scheduleNext(this);
        Prefs.setStatus(this, p.title() + (p.enabled ? " 사용 켬" : " 일시정지"));
        refreshUi();
    }

    private void testRoom(MultiRoomStore.Profile p) {
        if (!RoomMediaStore.hasPayload(this, p)) {
            toast("메시지가 비어 있어. 편집에서 먼저 입력해줘.");
            return;
        }
        if (!KakaoNotificationListener.hasLiveSession(p.room)) {
            toast("이 방 연결이 현재 없어. 방에서 새 메시지를 받은 뒤 새로고침해줘.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(p.title())
                .setMessage("지금 1회 전송할까?\n\n" + p.message)
                .setPositiveButton("전송", (d, w) -> {
                    LicenseManager.runAuthorized(this, () -> {
                    boolean ok = KakaoMessageSender.send(this, p.room, p.message, RoomMediaStore.get(this, p.room));
                    Prefs.setStatus(this, ok ? "수동 전송 성공: " + p.title()
                            : "수동 전송 실패: " + p.title() + " · " + KakaoMessageSender.lastError());
                    toast(ok ? "전송 성공" : "전송 실패: " + KakaoMessageSender.lastError());
                    refreshUi();
                    });
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void startAll() {
        LicenseManager.runAuthorized(this, this::startAuthorized);
    }

    private void startAuthorized() {
        if (!isNotificationAccessEnabled()) {
            toast("알림 접근 권한부터 허용해줘.");
            return;
        }
        ArrayList<MultiRoomStore.Profile> profiles = MultiRoomStore.list(this);
        int usable = 0;
        int ready = 0;
        for (MultiRoomStore.Profile p : profiles) {
            if (!p.enabled || !RoomMediaStore.hasPayload(this, p)) continue;
            usable++;
            if (KakaoNotificationListener.hasLiveSession(p.room)) ready++;
        }
        if (usable == 0) {
            toast("사용 중이고 메시지가 저장된 방이 없어.");
            return;
        }
        synchronized (DeliveryGate.LOCK) {
            if (!LicenseManager.isUsable(this)) { LicenseManager.route(this); return; }
            Prefs.p(this).edit().putBoolean(Prefs.KEY_ACTIVE, true).commit();
        }
        MultiRoomStore.setAllNextFromNow(this);
        SendScheduler.scheduleNext(this);
        Prefs.setStatus(this, "전체 자동전송 시작 · " + usable + "개 방 · 즉시 연결 " + ready + "개");
        refreshUi();
        toast(ready == usable ? "자동전송을 시작했어." : "자동전송 시작 · 연결 대기 방은 새 알림이 오면 자동복구돼.");
    }

    private void stopAll() {
        DeliveryGate.stop(this);
        Prefs.setStatus(this, "전체 자동전송 즉시 중단");
        refreshUi();
        toast("모든 자동전송 예약을 중단했어.");
    }

    private void requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < 31 || SendScheduler.canUseExact(this)) {
            toast("정확한 시각 전송을 이미 사용할 수 있어.");
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            toast("이 기기에서는 권한 화면을 바로 열 수 없어.");
        }
    }

    private void showDiagnostics() {
        StringBuilder sb = new StringBuilder(LicenseManager.diagnostic(this));
        TextView body = text(sb.toString(), 12, false);
        body.setTextIsSelectable(true);
        body.setPadding(dp(14), dp(10), dp(14), dp(10));
        ScrollView sc = new ScrollView(this); sc.addView(body);
        new AlertDialog.Builder(this).setTitle("진단 정보").setView(sc).setPositiveButton("지원 정보 공유", (d,w) -> {
            Intent share = new Intent(Intent.ACTION_SEND); share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, LicenseManager.diagnostic(this)); startActivity(Intent.createChooser(share,"지원 정보 공유"));
        }).setNegativeButton("닫기", null).show();
    }

    private void resetBindings() {
        new AlertDialog.Builder(this)
                .setTitle("방 연결만 초기화할까?")
                .setMessage("메시지와 시간 설정은 그대로 두고 카카오 답장 연결 정보만 지워.")
                .setPositiveButton("초기화", (d, w) -> {
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

    private LinearLayout card(int color) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(14), dp(14), dp(14));
        l.setBackground(round(color, 16));
        return l;
    }

    private TextView section(String value) {
        TextView v = text(value, 18, true);
        v.setTextColor(Color.rgb(220, 228, 246));
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

    private Button actionButton(String label, int color) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setBackground(round(color, 12));
        b.setMinHeight(dp(48));
        return b;
    }

    private Button compactButton(String label, int color) {
        Button b = actionButton(label, color);
        b.setTextSize(12);
        b.setMinHeight(dp(42));
        return b;
    }

    private Button smallButton(String label) { return compactButton(label, Color.rgb(61, 65, 74)); }

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

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
}
