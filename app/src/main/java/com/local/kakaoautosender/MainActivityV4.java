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
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
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
    private static final int BG = Color.rgb(9, 11, 16);
    private static final int SURFACE = Color.rgb(18, 23, 34);
    private static final int SURFACE_2 = Color.rgb(23, 29, 42);
    private static final int FIELD = Color.rgb(15, 20, 30);
    private static final int BORDER = Color.rgb(45, 55, 75);
    private static final int TEXT = Color.rgb(238, 242, 249);
    private static final int MUTED = Color.rgb(154, 166, 188);
    private static final int ACCENT = Color.rgb(86, 112, 255);
    private static final int GREEN = Color.rgb(94, 226, 157);
    private static final int AMBER = Color.rgb(243, 190, 91);
    private static final int RED = Color.rgb(243, 113, 121);

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
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(42));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("카톡매크로", 29, true, TEXT);
        title.setLetterSpacing(-0.015f);
        brand.addView(title);
        TextView kicker = text("KAKAO AUTOMATION SUITE", 9, true, Color.rgb(121, 136, 166));
        kicker.setLetterSpacing(0.12f);
        brand.addView(kicker, topWrap(4));
        header.addView(brand, weight());
        header.addView(pill("v" + appVersion(), Color.rgb(28, 34, 49), Color.rgb(193, 204, 229)));
        root.addView(header);

        TextView subtitle = text("방마다 메시지와 스케줄을 독립적으로 관리합니다.", 12, false, MUTED);
        root.addView(subtitle, top(8));

        LinearLayout masterCard = card(SURFACE, BORDER, 20);
        LinearLayout statusHeader = new LinearLayout(this);
        statusHeader.setOrientation(LinearLayout.HORIZONTAL);
        statusHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView statusCaption = text("AUTOMATION STATUS", 9, true, Color.rgb(123, 138, 168));
        statusCaption.setLetterSpacing(0.1f);
        statusHeader.addView(statusCaption, weight());
        statusHeader.addView(pill("PROTECTED", Color.rgb(25, 49, 42), Color.rgb(122, 231, 176)));
        masterCard.addView(statusHeader);

        masterStatus = text("", 22, true, TEXT);
        masterCard.addView(masterStatus, top(14));
        systemStatus = text("", 12, false, Color.rgb(181, 191, 210));
        systemStatus.setLineSpacing(0, 1.18f);
        masterCard.addView(systemStatus, top(8));

        LinearLayout masterButtons = new LinearLayout(this);
        masterButtons.setOrientation(LinearLayout.HORIZONTAL);
        startButton = primaryButton("자동전송 시작");
        startButton.setOnClickListener(v -> startAll());
        masterButtons.addView(startButton, new LinearLayout.LayoutParams(0, dp(54), 1.55f));
        stopButton = dangerSecondaryButton("전체 중단");
        stopButton.setOnClickListener(v -> stopAll());
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, dp(54), 1f);
        stopLp.leftMargin = dp(8);
        masterButtons.addView(stopButton, stopLp);
        masterCard.addView(masterButtons, top(16));
        root.addView(masterCard, top(22));

        LinearLayout sectionRow = new LinearLayout(this);
        sectionRow.setOrientation(LinearLayout.HORIZONTAL);
        sectionRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout roomHeading = new LinearLayout(this);
        roomHeading.setOrientation(LinearLayout.VERTICAL);
        roomHeading.addView(section("자동전송 방"));
        roomHeading.addView(text("연결된 방을 선택해 메시지와 시간을 관리하세요.", 11, false, Color.rgb(118, 129, 149)), topWrap(3));
        sectionRow.addView(roomHeading, weight());
        Button refresh = tertiaryButton("↻ 새로고침");
        refresh.setOnClickListener(v -> {
            KakaoNotificationListener.requestReconnect(this);
            KakaoNotificationListener.requestRefresh();
            refreshUi();
            toast("카카오 방 연결 상태를 새로 확인했습니다.");
        });
        sectionRow.addView(refresh);
        root.addView(sectionRow, top(28));

        Button add = primaryButton("＋  새 방 연결");
        add.setOnClickListener(v -> showAddCandidates());
        root.addView(add, top(12));

        roomList = new LinearLayout(this);
        roomList.setOrientation(LinearLayout.VERTICAL);
        root.addView(roomList, top(7));

        LinearLayout summaryCard = card(Color.rgb(14, 18, 27), Color.rgb(35, 44, 61), 16);
        TextView summaryTitle = text("운영 요약", 11, true, Color.rgb(128, 142, 169));
        summaryTitle.setLetterSpacing(0.07f);
        summaryCard.addView(summaryTitle);
        summaryStatus = text("", 12, false, Color.rgb(184, 194, 213));
        summaryStatus.setLineSpacing(0, 1.16f);
        summaryCard.addView(summaryStatus, top(7));
        root.addView(summaryCard, top(12));

        LinearLayout toolsHeading = new LinearLayout(this);
        toolsHeading.setOrientation(LinearLayout.VERTICAL);
        toolsHeading.addView(section("설정 및 지원"));
        toolsHeading.addView(text("권한 확인과 문제 해결 기능입니다.", 11, false, Color.rgb(118, 129, 149)), topWrap(3));
        root.addView(toolsHeading, top(28));

        LinearLayout toolsRow = new LinearLayout(this);
        toolsRow.setOrientation(LinearLayout.HORIZONTAL);
        Button notification = toolButton("알림 접근", "권한 확인");
        notification.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        toolsRow.addView(notification, weight());
        if (Build.VERSION.SDK_INT >= 31) {
            Button exact = toolButton("정확 시각", "전송 정확도");
            exact.setOnClickListener(v -> requestExactAlarmAccess());
            LinearLayout.LayoutParams eLp = weight();
            eLp.leftMargin = dp(8);
            toolsRow.addView(exact, eLp);
        }
        root.addView(toolsRow, top(10));

        LinearLayout toolsRow2 = new LinearLayout(this);
        toolsRow2.setOrientation(LinearLayout.HORIZONTAL);
        Button diagnostics = toolButton("진단 정보", "지원용 정보");
        diagnostics.setOnClickListener(v -> showDiagnostics());
        toolsRow2.addView(diagnostics, weight());
        Button reset = toolButton("연결 초기화", "방 설정은 보존");
        reset.setTextColor(Color.rgb(245, 177, 183));
        reset.setOnClickListener(v -> resetBindings());
        LinearLayout.LayoutParams rLp = weight();
        rLp.leftMargin = dp(8);
        toolsRow2.addView(reset, rLp);
        root.addView(toolsRow2, top(8));

        LinearLayout safety = card(Color.rgb(14, 18, 27), Color.rgb(35, 44, 61), 15);
        TextView safetyTitle = text("오배송 방지", 12, true, Color.rgb(199, 211, 237));
        safety.addView(safetyTitle);
        TextView footer = text("대상이 확실하지 않으면 전송하지 않습니다. 한 방의 연결이 끊겨도 다른 방으로 대신 보내지 않습니다.", 11, false, Color.rgb(119, 131, 154));
        safety.addView(footer, top(5));
        root.addView(safety, top(18));
        return scroll;
    }

    private void refreshUi() {
        if (masterStatus == null) return;
        boolean active = Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false);
        boolean access = isNotificationAccessEnabled();
        boolean listener = KakaoNotificationListener.isListenerConnected();
        boolean exact = SendScheduler.canUseExact(this);
        ArrayList<MultiRoomStore.Profile> profiles = MultiRoomStore.list(this);

        masterStatus.setText(active ? "자동전송 실행 중" : "자동전송 대기");
        masterStatus.setTextColor(active ? GREEN : TEXT);
        StringBuilder system = new StringBuilder();
        system.append("라이선스 ").append(LicenseManager.shortStatus(this));
        system.append("  ·  알림 접근 ").append(access ? "정상" : "권한 필요");
        system.append("  ·  리스너 ").append(listener ? "정상" : "대기");
        if (Build.VERSION.SDK_INT >= 31) system.append("\n정확 시각 ").append(exact ? "사용 가능" : "선택사항 · 근사 시각 사용");
        system.append("  ·  연결 방 ").append(profiles.size()).append("개");
        String notice = LicenseManager.updateNotice(this);
        if (notice != null && !notice.trim().isEmpty()) system.append("\n").append(notice.trim());
        systemStatus.setText(system.toString());

        startButton.setEnabled(!active);
        stopButton.setEnabled(active);
        startButton.setAlpha(active ? 0.46f : 1f);
        stopButton.setAlpha(active ? 1f : 0.46f);

        roomList.removeAllViews();
        if (profiles.isEmpty()) {
            LinearLayout empty = card(SURFACE, BORDER, 18);
            TextView emptyBadge = pill("GET STARTED", Color.rgb(29, 38, 65), Color.rgb(180, 195, 255));
            empty.addView(emptyBadge, wrap());
            empty.addView(text("첫 자동전송 방을 연결하세요", 18, true, TEXT), top(13));
            TextView guide = text("원하는 카카오톡 오픈채팅방에서 새 메시지를 하나 받은 뒤 위의 ‘새 방 연결’을 선택하면 됩니다.", 13, false, Color.rgb(166, 177, 198));
            guide.setLineSpacing(0, 1.15f);
            empty.addView(guide, top(7));
            TextView safe = text("방 이름과 답장 세션이 확인된 경우에만 연결됩니다.", 11, false, Color.rgb(112, 126, 151));
            empty.addView(safe, top(10));
            roomList.addView(empty, top(7));
        } else {
            for (MultiRoomStore.Profile p : profiles) roomList.addView(roomCard(p), top(8));
        }

        int ready = MultiRoomStore.readyCount(this);
        int enabled = MultiRoomStore.enabledCount(this);
        long next = MultiRoomStore.nextDueAt(this);
        StringBuilder s = new StringBuilder();
        s.append("방 ").append(profiles.size()).append("개  ·  사용 중 ").append(enabled)
                .append("개  ·  즉시 전송 가능 ").append(ready).append("개");
        if (active && next > 0) s.append("\n다음 예정  ").append(formatDateTime(next));
        if (!access) s.append("\n알림 접근 권한을 허용해야 자동전송을 사용할 수 있습니다.");
        else if (!listener) s.append("\n카카오 알림 리스너 연결을 기다리고 있습니다.");
        summaryStatus.setText(s.toString());
    }

    private View roomCard(MultiRoomStore.Profile p) {
        boolean live = KakaoNotificationListener.hasLiveSession(p.room);
        boolean stored = KakaoNotificationListener.hasStoredBinding(this, p.room);

        LinearLayout card = card(SURFACE, BORDER, 18);
        card.setOnClickListener(v -> openEditor(p.room));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView roomTitle = text(p.title(), 17, true, TEXT);
        roomTitle.setMaxLines(2);
        titleRow.addView(roomTitle, weight());
        titleRow.addView(pill(p.enabled ? "사용 중" : "일시정지",
                p.enabled ? Color.rgb(28, 54, 44) : Color.rgb(45, 49, 59),
                p.enabled ? Color.rgb(130, 232, 180) : Color.rgb(162, 171, 190)));
        card.addView(titleRow);

        String connectionText = live ? "연결됨" : stored ? "복구 대기" : "연결 필요";
        int connectionBg = live ? Color.rgb(24, 50, 42) : stored ? Color.rgb(55, 45, 27) : Color.rgb(58, 31, 36);
        int connectionFg = live ? GREEN : stored ? AMBER : RED;
        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.addView(pill(connectionText, connectionBg, connectionFg));
        TextView schedule = text(MultiRoomStore.scheduleSummary(p), 11, true, Color.rgb(155, 168, 193));
        LinearLayout.LayoutParams scheduleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        scheduleLp.leftMargin = dp(9);
        metaRow.addView(schedule, scheduleLp);
        card.addView(metaRow, top(10));

        String preview = p.message == null ? "" : p.message.replace('\n', ' ').trim();
        if (preview.length() > 92) preview = preview.substring(0, 92) + "…";
        LinearLayout previewBox = card(FIELD, Color.rgb(34, 43, 60), 12);
        TextView message = text(preview.isEmpty() ? "메시지가 아직 설정되지 않았습니다." : preview, 12, false,
                preview.isEmpty() ? AMBER : Color.rgb(207, 215, 232));
        message.setMaxLines(3);
        previewBox.addView(message);
        card.addView(previewBox, top(10));

        String count = p.unlimited() ? "횟수 무제한" : "오늘 " + p.todayCount + "/" + p.dailyLimit + "회";
        String next = p.nextAt > 0 ? "  ·  다음 " + formatTime(p.nextAt) : "";
        String failure = p.failureStreak > 0 ? "  ·  최근 실패 " + p.failureStreak + "회" : "";
        TextView meta = text(count + next + failure, 11, false, Color.rgb(133, 146, 169));
        card.addView(meta, top(8));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button edit = smallButton("설정");
        edit.setOnClickListener(v -> openEditor(p.room));
        actions.addView(edit, weight());

        Button test = smallButton("1회 테스트");
        test.setOnClickListener(v -> testRoom(p));
        LinearLayout.LayoutParams tLp = weight();
        tLp.leftMargin = dp(6);
        actions.addView(test, tLp);

        Button toggle = smallButton(p.enabled ? "일시정지" : "사용 켜기");
        toggle.setOnClickListener(v -> toggleRoom(p));
        LinearLayout.LayoutParams gLp = weight();
        gLp.leftMargin = dp(6);
        actions.addView(toggle, gLp);
        card.addView(actions, top(12));
        return card;
    }

    private void showAddCandidates() {
        KakaoNotificationListener.requestRefresh();
        final ArrayList<KakaoNotificationListener.SessionEntry> entries = KakaoNotificationListener.candidateSessionEntries();
        if (entries.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("연결할 방을 찾지 못했습니다")
                    .setMessage("연결하려는 오픈채팅방에서 새 메시지를 하나 받은 뒤 다시 시도해 주세요.\n\n최근 알림을 직접 확인해야 한다면 ‘직접 연결’을 사용할 수 있습니다.")
                    .setPositiveButton("직접 연결", (d, w) -> showAdvancedManualAdd())
                    .setNegativeButton("닫기", null)
                    .show();
            return;
        }
        showCandidatePicker(entries, false);
    }

    private void showCandidatePicker(ArrayList<KakaoNotificationListener.SessionEntry> entries, boolean manual) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), dp(6));

        TextView description = text(
                manual ? "최근 답장 가능한 카카오 알림을 확인해 직접 연결합니다."
                        : "최근 감지된 카카오 방입니다. 검색하거나 방을 눌러 연결하세요.",
                12, false, Color.rgb(158, 170, 194));
        content.addView(description);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("방 이름 검색");
        search.setHintTextColor(Color.rgb(102, 114, 137));
        search.setTextColor(TEXT);
        search.setTextSize(14);
        search.setPadding(dp(14), dp(12), dp(14), dp(12));
        search.setBackground(roundStroke(FIELD, BORDER, 12));
        content.addView(search, top(12));

        TextView count = text("", 11, true, Color.rgb(126, 141, 168));
        count.setLetterSpacing(0.04f);
        content.addView(count, top(10));

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView listScroll = new ScrollView(this);
        listScroll.setFillViewport(false);
        listScroll.addView(rows);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(420));
        scrollLp.topMargin = dp(5);
        content.addView(listScroll, scrollLp);

        if (!manual) {
            Button advanced = tertiaryButton("방이 안 보이면 직접 연결");
            content.addView(advanced, top(10));
            advanced.setOnClickListener(v -> {
                showAdvancedManualAdd();
            });
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(manual ? "직접 연결" : "새 방 연결")
                .setView(content)
                .setNegativeButton("닫기", null)
                .create();

        Runnable render = () -> renderCandidateRows(rows, count, entries, search.getText().toString(), dialog, manual);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int countValue) { render.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        dialog.setOnShowListener(d -> render.run());
        dialog.show();
    }

    private void renderCandidateRows(
            LinearLayout rows,
            TextView count,
            ArrayList<KakaoNotificationListener.SessionEntry> entries,
            String query,
            AlertDialog dialog,
            boolean manual) {
        rows.removeAllViews();
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (KakaoNotificationListener.SessionEntry entry : entries) {
            String room = entry.suggestedRoom == null ? "" : entry.suggestedRoom.trim();
            if (!manual && room.isEmpty()) continue;
            String searchable = room.toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && !searchable.contains(normalized)) continue;
            shown++;

            LinearLayout row = card(SURFACE_2, BORDER, 14);
            row.setClickable(true);
            row.setFocusable(true);
            TextView name = text(room.isEmpty() ? "방 이름 미확인" : room, 15, true, TEXT);
            name.setMaxLines(2);
            row.addView(name);
            boolean existing = !room.isEmpty() && !MultiRoomStore.findByActualName(this, room).isEmpty();
            String meta = manual ? "직접 확인 후 연결"
                    : existing ? "기존 등록 방 · 연결 복구 가능"
                    : "새 방 후보 · 탭하여 연결";
            row.addView(text(meta, 11, false, existing ? GREEN : Color.rgb(125, 139, 166)), top(6));
            final KakaoNotificationListener.SessionEntry selected = entry;
            row.setOnClickListener(v -> {
                dialog.dismiss();
                if (manual) askManualRoomName(selected); else pairCandidate(selected);
            });
            rows.addView(row, top(6));
        }
        count.setText(shown + "개 방 표시");
        if (shown == 0) {
            LinearLayout empty = card(Color.rgb(14, 18, 27), Color.rgb(35, 44, 61), 14);
            empty.addView(text("검색 결과가 없습니다.", 13, true, Color.rgb(197, 207, 228)));
            empty.addView(text("방 이름을 다시 확인하거나 검색어를 지워 주세요.", 11, false, Color.rgb(119, 132, 156)), top(5));
            rows.addView(empty, top(6));
        }
    }

    private void pairCandidate(KakaoNotificationListener.SessionEntry entry) {
        if (entry == null || entry.suggestedRoom == null || entry.suggestedRoom.trim().isEmpty()) {
            showAdvancedManualAdd();
            return;
        }
        final String actualName = entry.suggestedRoom.trim();
        new AlertDialog.Builder(this)
                .setTitle("방 연결 확인")
                .setMessage(actualName + "\n\n이 방을 자동전송 목록에 연결할까요?")
                .setPositiveButton("연결", (d, w) -> connectCandidate(entry, actualName))
                .setNegativeButton("취소", null)
                .show();
    }

    private void connectCandidate(KakaoNotificationListener.SessionEntry entry, String actualName) {
        ArrayList<MultiRoomStore.Profile> existing = MultiRoomStore.findByActualName(this, actualName);
        if (existing.size() == 1) {
            MultiRoomStore.Profile p = existing.get(0);
            boolean ok = KakaoNotificationListener.bindRecentTokenToRoom(this, entry.token, p.room);
            if (!ok) {
                toast("알림 세션이 만료되었습니다. 방에서 새 메시지를 받은 뒤 다시 시도해 주세요.");
                return;
            }
            openEditor(p.room);
            return;
        }

        String routeAlias = "route-" + UUID.randomUUID();
        if (!KakaoNotificationListener.bindRecentTokenToRoom(this, entry.token, routeAlias)) {
            toast("알림 세션이 만료되었습니다. 방에서 새 메시지를 받은 뒤 다시 시도해 주세요.");
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
            toast("최근 답장 가능한 카카오 알림이 없습니다.");
            return;
        }
        showCandidatePicker(entries, true);
    }

    private void askManualRoomName(KakaoNotificationListener.SessionEntry entry) {
        EditText input = new EditText(this);
        input.setHint("알림에 표시된 방 이름");
        input.setTextColor(TEXT);
        input.setHintTextColor(Color.rgb(102, 114, 137));
        input.setBackground(roundStroke(FIELD, BORDER, 12));
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (entry.suggestedRoom != null) input.setText(entry.suggestedRoom);
        new AlertDialog.Builder(this)
                .setTitle("방 이름 직접 확인")
                .setMessage("오배송을 막기 위해 카카오 알림에 표시된 방 이름을 그대로 입력해 주세요.")
                .setView(input)
                .setPositiveButton("연결", (d, w) -> {
                    String actualName = input.getText().toString().trim();
                    if (actualName.isEmpty()) { toast("방 이름을 입력해 주세요."); return; }
                    String routeAlias = "route-" + UUID.randomUUID();
                    if (!KakaoNotificationListener.bindRecentTokenToRoom(this, entry.token, routeAlias)) {
                        toast("알림 세션이 만료되었습니다."); return;
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
            toast("메시지가 비어 있습니다. 방 설정에서 먼저 입력해 주세요.");
            return;
        }
        if (!KakaoNotificationListener.hasLiveSession(p.room)) {
            toast("현재 방 연결이 없습니다. 해당 방에서 새 메시지를 받은 뒤 새로고침해 주세요.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("1회 테스트 전송")
                .setMessage(p.title() + "\n\n" + p.message)
                .setPositiveButton("전송", (d, w) -> {
                    LicenseManager.runAuthorized(this, () -> {
                        boolean ok = KakaoMessageSender.send(this, p.room, p.message, RoomMediaStore.get(this, p.room));
                        Prefs.setStatus(this, ok ? "수동 전송 성공: " + p.title()
                                : "수동 전송 실패: " + p.title() + " · " + KakaoMessageSender.lastError());
                        toast(ok ? "테스트 전송에 성공했습니다." : "전송 실패: " + KakaoMessageSender.lastError());
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
            toast("알림 접근 권한을 먼저 허용해 주세요.");
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
            toast("사용 중이며 메시지가 저장된 방이 없습니다.");
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
        toast(ready == usable ? "자동전송을 시작했습니다." : "자동전송을 시작했습니다. 연결 대기 방은 새 알림이 오면 복구됩니다.");
    }

    private void stopAll() {
        DeliveryGate.stop(this);
        Prefs.setStatus(this, "전체 자동전송 즉시 중단");
        refreshUi();
        toast("모든 자동전송 예약을 중단했습니다.");
    }

    private void requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < 31 || SendScheduler.canUseExact(this)) {
            toast("정확한 시각 전송을 이미 사용할 수 있습니다.");
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            toast("이 기기에서는 권한 화면을 바로 열 수 없습니다.");
        }
    }

    private void showDiagnostics() {
        TextView body = text(LicenseManager.diagnostic(this), 12, false, Color.rgb(201, 210, 228));
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(dp(14), dp(10), dp(14), dp(10));
        ScrollView sc = new ScrollView(this);
        sc.addView(body);
        new AlertDialog.Builder(this)
                .setTitle("지원용 진단 정보")
                .setView(sc)
                .setPositiveButton("지원 정보 공유", (d, w) -> {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_TEXT, LicenseManager.diagnostic(this));
                    startActivity(Intent.createChooser(share, "지원 정보 공유"));
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void resetBindings() {
        new AlertDialog.Builder(this)
                .setTitle("카카오 방 연결을 초기화할까요?")
                .setMessage("저장한 메시지, 사진, 시간 설정은 그대로 유지하고 카카오 답장 연결 정보만 초기화합니다.")
                .setPositiveButton("연결 초기화", (d, w) -> {
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

    private String formatTime(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.KOREA).format(new Date(ms));
    }

    private String formatDateTime(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(new Date(ms));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private LinearLayout card(int fill, int stroke, int radiusDp) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        l.setBackground(roundStroke(fill, stroke, radiusDp));
        l.setElevation(dp(1));
        return l;
    }

    private TextView section(String value) {
        return text(value, 19, true, Color.rgb(226, 232, 244));
    }

    private TextView pill(String value, int bg, int fg) {
        TextView v = text(value, 10, true, fg);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(9), dp(6), dp(9), dp(6));
        v.setBackground(roundStroke(bg, Color.rgb(62, 73, 96), 14));
        return v;
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(sp);
        v.setIncludeFontPadding(false);
        if (bold) v.setTypeface(v.getTypeface(), Typeface.BOLD);
        return v;
    }

    private Button primaryButton(String label) {
        Button b = button(label, ACCENT, ACCENT, TEXT);
        b.setElevation(dp(3));
        return b;
    }

    private Button dangerSecondaryButton(String label) {
        return button(label, Color.rgb(60, 35, 42), Color.rgb(104, 51, 61), Color.rgb(249, 183, 188));
    }

    private Button tertiaryButton(String label) {
        Button b = button(label, Color.rgb(30, 36, 50), Color.rgb(55, 65, 84), Color.rgb(214, 221, 235));
        b.setTextSize(11);
        b.setMinHeight(dp(42));
        return b;
    }

    private Button toolButton(String title, String subtitle) {
        Button b = button(title + "\n" + subtitle, SURFACE_2, BORDER, Color.rgb(221, 227, 239));
        b.setTextSize(12);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(64));
        return b;
    }

    private Button smallButton(String label) {
        Button b = button(label, Color.rgb(30, 36, 50), Color.rgb(55, 65, 84), Color.rgb(214, 221, 235));
        b.setTextSize(11);
        b.setMinHeight(dp(42));
        return b;
    }

    private Button button(String label, int fill, int stroke, int textColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(13);
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
        b.setBackground(roundStroke(fill, stroke, 12));
        b.setMinHeight(dp(48));
        return b;
    }

    private GradientDrawable roundStroke(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout.LayoutParams top(int v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(v);
        return lp;
    }

    private LinearLayout.LayoutParams topWrap(int v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(v);
        return lp;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + .5f);
    }
}
