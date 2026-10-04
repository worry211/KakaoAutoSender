package com.local.kakaoautosender;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
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
    private static final int PRIMARY = Color.rgb(86, 112, 255);
    private static final int PRIMARY_SOFT = Color.rgb(30, 40, 72);
    private static final int TEXT = Color.rgb(238, 242, 249);
    private static final int MUTED = Color.rgb(156, 168, 190);
    private static final int DIM = Color.rgb(108, 121, 145);
    private static final int GREEN = Color.rgb(94, 226, 157);
    private static final int AMBER = Color.rgb(243, 190, 91);
    private static final int RED = Color.rgb(243, 113, 121);

    private LinearLayout roomList;
    private TextView masterStatus;
    private TextView systemStatus;
    private TextView summaryStatus;
    private TextView roomCountValue;
    private TextView enabledCountValue;
    private TextView readyCountValue;
    private TextView statusBadge;
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
        root.setPadding(dp(20), dp(24), dp(20), dp(44));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("카톡매크로", 30, true, TEXT);
        title.setLetterSpacing(-0.015f);
        brand.addView(title);
        TextView brandSub = text("AUTOMATION CONTROL", 10, true, Color.rgb(123, 138, 168));
        brandSub.setLetterSpacing(0.12f);
        brand.addView(brandSub, topWrap(4));
        header.addView(brand, weight());
        header.addView(pill("v" + appVersion(), Color.rgb(28, 34, 49), Color.rgb(180, 194, 223)));
        root.addView(header);

        TextView subtitle = text("방별 메시지와 일정을 안전하게 운영하세요.", 13, false, MUTED);
        root.addView(subtitle, top(8));

        LinearLayout hero = premiumCard(PRIMARY_SOFT, Color.rgb(60, 76, 128), 19);
        LinearLayout heroTop = new LinearLayout(this);
        heroTop.setOrientation(LinearLayout.HORIZONTAL);
        heroTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView controlLabel = text("AUTOMATION", 10, true, Color.rgb(173, 190, 255));
        controlLabel.setLetterSpacing(0.10f);
        heroTop.addView(controlLabel, weight());
        statusBadge = pill("준비 중", Color.rgb(35, 43, 63), Color.rgb(190, 200, 221));
        heroTop.addView(statusBadge);
        hero.addView(heroTop);

        masterStatus = text("자동전송 준비 중", 24, true, TEXT);
        masterStatus.setLetterSpacing(-0.012f);
        hero.addView(masterStatus, top(14));

        systemStatus = text("상태를 확인하고 있습니다.", 13, false, Color.rgb(183, 193, 212));
        systemStatus.setLineSpacing(0, 1.18f);
        hero.addView(systemStatus, top(7));

        LinearLayout masterButtons = new LinearLayout(this);
        masterButtons.setOrientation(LinearLayout.HORIZONTAL);
        startButton = actionButton("자동전송 시작", PRIMARY, PRIMARY, TEXT);
        startButton.setOnClickListener(v -> startAll());
        masterButtons.addView(startButton, weight());
        stopButton = actionButton("중단", Color.rgb(52, 37, 45), Color.rgb(111, 57, 66), Color.rgb(246, 174, 180));
        stopButton.setOnClickListener(v -> stopAll());
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(dp(112), LinearLayout.LayoutParams.WRAP_CONTENT);
        stopLp.leftMargin = dp(9);
        masterButtons.addView(stopButton, stopLp);
        hero.addView(masterButtons, top(16));
        root.addView(hero, top(22));

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        roomCountValue = metric(metrics, "연결된 방", "0", 0);
        enabledCountValue = metric(metrics, "사용 중", "0", 1);
        readyCountValue = metric(metrics, "전송 가능", "0", 2);
        root.addView(metrics, top(12));

        LinearLayout roomsHeader = new LinearLayout(this);
        roomsHeader.setOrientation(LinearLayout.HORIZONTAL);
        roomsHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout roomsTitle = new LinearLayout(this);
        roomsTitle.setOrientation(LinearLayout.VERTICAL);
        roomsTitle.addView(text("방 관리", 20, true, TEXT));
        roomsTitle.addView(text("실제 카카오 방 이름 기준으로 연결합니다.", 11, false, DIM), topWrap(3));
        roomsHeader.addView(roomsTitle, weight());
        Button refresh = compactButton("새로고침", Color.rgb(28, 34, 48), Color.rgb(54, 65, 87), Color.rgb(203, 211, 228));
        refresh.setOnClickListener(v -> {
            KakaoNotificationListener.requestReconnect(this);
            KakaoNotificationListener.requestRefresh();
            refreshUi();
            toast("방 정보를 다시 확인했습니다.");
        });
        roomsHeader.addView(refresh);
        root.addView(roomsHeader, top(28));

        Button add = actionButton("＋  방 연결하기", PRIMARY, PRIMARY, TEXT);
        add.setOnClickListener(v -> showAddCandidates());
        root.addView(add, top(12));

        roomList = new LinearLayout(this);
        roomList.setOrientation(LinearLayout.VERTICAL);
        root.addView(roomList, top(5));

        summaryStatus = text("", 12, false, Color.rgb(164, 175, 196));
        summaryStatus.setBackground(roundStroke(Color.rgb(15, 20, 30), Color.rgb(35, 44, 61), 12));
        summaryStatus.setPadding(dp(14), dp(11), dp(14), dp(11));
        summaryStatus.setVisibility(View.GONE);
        root.addView(summaryStatus, top(12));

        LinearLayout toolsTitle = new LinearLayout(this);
        toolsTitle.setOrientation(LinearLayout.VERTICAL);
        toolsTitle.addView(text("설정 및 지원", 20, true, TEXT));
        toolsTitle.addView(text("필요한 항목만 열어 빠르게 점검할 수 있습니다.", 11, false, DIM), topWrap(3));
        root.addView(toolsTitle, top(30));

        LinearLayout toolsRow = new LinearLayout(this);
        toolsRow.setOrientation(LinearLayout.HORIZONTAL);
        toolsRow.addView(toolAction("알림 접근", "카카오 답장 연결", false,
                v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))), weight());
        if (Build.VERSION.SDK_INT >= 31) {
            LinearLayout.LayoutParams lp = weight();
            lp.leftMargin = dp(8);
            toolsRow.addView(toolAction("정확한 시각", "예약 정확도 설정", false,
                    v -> requestExactAlarmAccess()), lp);
        }
        root.addView(toolsRow, top(10));

        LinearLayout toolsRow2 = new LinearLayout(this);
        toolsRow2.setOrientation(LinearLayout.HORIZONTAL);
        toolsRow2.addView(toolAction("진단 및 지원", "오류 정보 확인", false,
                v -> showDiagnostics()), weight());
        LinearLayout.LayoutParams dangerLp = weight();
        dangerLp.leftMargin = dp(8);
        toolsRow2.addView(toolAction("연결 초기화", "메시지 설정은 유지", true,
                v -> resetBindings()), dangerLp);
        root.addView(toolsRow2, top(8));

        TextView footer = text(
                "보호 모드 · 전송 대상이 확실하지 않으면 보내지 않으며, 한 방의 연결이 끊겨도 다른 방으로 대신 전송하지 않습니다.",
                11,
                false,
                Color.rgb(101, 113, 136));
        footer.setGravity(Gravity.CENTER);
        footer.setLineSpacing(0, 1.15f);
        root.addView(footer, top(22));
        return scroll;
    }

    private TextView metric(LinearLayout parent, String label, String initial, int index) {
        LinearLayout card = premiumCard(SURFACE, BORDER, 14);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView value = text(initial, 21, true, TEXT);
        value.setGravity(Gravity.CENTER);
        card.addView(value);
        TextView name = text(label, 10, true, DIM);
        name.setGravity(Gravity.CENTER);
        card.addView(name, topWrap(3));
        LinearLayout.LayoutParams lp = weight();
        if (index > 0) lp.leftMargin = dp(7);
        parent.addView(card, lp);
        return value;
    }

    private View toolAction(String title, String detail, boolean danger, View.OnClickListener click) {
        LinearLayout card = premiumCard(
                danger ? Color.rgb(45, 28, 34) : SURFACE,
                danger ? Color.rgb(91, 47, 57) : BORDER,
                14);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(click);
        TextView icon = text(danger ? "!" : "•", 14, true,
                danger ? Color.rgb(246, 157, 166) : Color.rgb(152, 170, 255));
        card.addView(icon);
        card.addView(text(title, 14, true, TEXT), topWrap(5));
        card.addView(text(detail, 10, false, danger ? Color.rgb(189, 138, 146) : DIM), topWrap(3));
        return card;
    }

    private void refreshUi() {
        if (masterStatus == null) return;
        boolean active = Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false);
        boolean access = isNotificationAccessEnabled();
        boolean listener = KakaoNotificationListener.isListenerConnected();
        boolean exact = SendScheduler.canUseExact(this);
        ArrayList<MultiRoomStore.Profile> profiles = MultiRoomStore.list(this);
        int ready = MultiRoomStore.readyCount(this);
        int enabled = MultiRoomStore.enabledCount(this);

        if (active) {
            masterStatus.setText("자동전송 실행 중");
            masterStatus.setTextColor(GREEN);
            stylePill(statusBadge, "LIVE", Color.rgb(25, 49, 42), Color.rgb(122, 231, 176));
        } else if (!access) {
            masterStatus.setText("알림 접근 권한이 필요합니다");
            masterStatus.setTextColor(AMBER);
            stylePill(statusBadge, "SETUP", Color.rgb(55, 44, 26), Color.rgb(244, 198, 107));
        } else {
            masterStatus.setText("자동전송 준비됨");
            masterStatus.setTextColor(TEXT);
            stylePill(statusBadge, "READY", Color.rgb(32, 43, 68), Color.rgb(172, 190, 255));
        }

        String license = LicenseManager.shortStatus(this);
        String update = LicenseManager.updateNotice(this);
        StringBuilder health = new StringBuilder();
        health.append("라이선스 ").append(license);
        if (update != null && !update.trim().isEmpty()) health.append(" · ").append(update.trim());
        health.append("\n");
        health.append(access ? "알림 접근 정상" : "알림 접근 권한 필요");
        health.append(" · ").append(listener ? "리스너 연결됨" : "리스너 연결 대기");
        if (Build.VERSION.SDK_INT >= 31) {
            health.append(" · ").append(exact ? "정확 시각 사용" : "근사 시각 사용");
        }
        systemStatus.setText(health.toString());
        systemStatus.setTextColor(!access ? AMBER : Color.rgb(183, 193, 212));

        roomCountValue.setText(String.valueOf(profiles.size()));
        enabledCountValue.setText(String.valueOf(enabled));
        readyCountValue.setText(String.valueOf(ready));

        startButton.setEnabled(!active && access);
        startButton.setAlpha(startButton.isEnabled() ? 1f : 0.55f);
        stopButton.setEnabled(active);
        stopButton.setAlpha(active ? 1f : 0.45f);

        roomList.removeAllViews();
        if (profiles.isEmpty()) {
            LinearLayout empty = premiumCard(SURFACE, BORDER, 17);
            TextView plus = text("＋", 28, true, Color.rgb(147, 165, 255));
            plus.setGravity(Gravity.CENTER);
            empty.addView(plus);
            TextView title = text("아직 연결된 방이 없습니다", 17, true, TEXT);
            title.setGravity(Gravity.CENTER);
            empty.addView(title, top(7));
            TextView guide = text(
                    "1. 추가할 카카오 오픈채팅방에서 새 메시지를 받으세요.\n2. 위의 ‘방 연결하기’를 누르세요.\n3. 방을 선택한 뒤 메시지와 전송 시간을 설정하세요.",
                    12,
                    false,
                    Color.rgb(162, 173, 194));
            guide.setLineSpacing(0, 1.25f);
            empty.addView(guide, top(10));
            roomList.addView(empty, top(9));
        } else {
            for (MultiRoomStore.Profile p : profiles) roomList.addView(roomCard(p), top(9));
        }

        long next = MultiRoomStore.nextDueAt(this);
        if (active && next > 0) {
            summaryStatus.setText("다음 예약 전송  ·  " + formatDateTime(next));
            summaryStatus.setVisibility(View.VISIBLE);
        } else if (active && enabled > 0 && ready == 0) {
            summaryStatus.setText("연결 대기 중  ·  카카오 방에 새 알림이 오면 연결 상태를 다시 확인합니다.");
            summaryStatus.setVisibility(View.VISIBLE);
        } else {
            summaryStatus.setVisibility(View.GONE);
        }
    }

    private View roomCard(MultiRoomStore.Profile p) {
        boolean live = KakaoNotificationListener.hasLiveSession(p.room);
        boolean stored = KakaoNotificationListener.hasStoredBinding(this, p.room);

        LinearLayout card = premiumCard(SURFACE, BORDER, 17);
        card.setOnClickListener(v -> openEditor(p.room));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView roomTitle = text(p.title(), 17, true, TEXT);
        roomTitle.setMaxLines(2);
        titleRow.addView(roomTitle, weight());
        TextView enabledBadge = pill(
                p.enabled ? "사용 중" : "중지",
                p.enabled ? Color.rgb(26, 53, 45) : Color.rgb(45, 49, 59),
                p.enabled ? Color.rgb(119, 229, 174) : Color.rgb(166, 174, 191));
        titleRow.addView(enabledBadge);
        card.addView(titleRow);

        String connectionText = live ? "연결됨" : stored ? "새 알림 대기" : "연결 필요";
        int connectionColor = live ? GREEN : stored ? AMBER : RED;
        TextView state = text("●  " + connectionText + "    " + MultiRoomStore.scheduleSummary(p), 12, true, connectionColor);
        state.setMaxLines(2);
        card.addView(state, top(8));

        String preview = p.message == null ? "" : p.message.replace('\n', ' ').trim();
        if (preview.length() > 92) preview = preview.substring(0, 92) + "…";
        LinearLayout previewBox = premiumCard(FIELD, Color.rgb(34, 43, 59), 11);
        previewBox.setPadding(dp(11), dp(9), dp(11), dp(9));
        TextView message = text(preview.isEmpty() ? "메시지를 아직 설정하지 않았습니다." : preview, 12, false,
                preview.isEmpty() ? AMBER : Color.rgb(209, 216, 230));
        previewBox.addView(message);
        card.addView(previewBox, top(10));

        String count = p.unlimited() ? "횟수 무제한" : "오늘 " + p.todayCount + "/" + p.dailyLimit + "회";
        String next = p.nextAt > 0 ? " · 다음 " + formatTime(p.nextAt) : "";
        String failure = p.failureStreak > 0 ? " · 최근 실패 " + p.failureStreak + "회" : "";
        TextView meta = text(count + next + failure, 11, false, Color.rgb(137, 150, 174));
        card.addView(meta, top(8));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button edit = compactButton("설정", Color.rgb(36, 48, 84), Color.rgb(66, 83, 139), Color.rgb(202, 211, 255));
        edit.setOnClickListener(v -> openEditor(p.room));
        actions.addView(edit, weight());

        Button test = compactButton("1회 전송", Color.rgb(28, 36, 50), Color.rgb(52, 64, 85), Color.rgb(214, 220, 232));
        test.setOnClickListener(v -> testRoom(p));
        LinearLayout.LayoutParams tLp = weight(); tLp.leftMargin = dp(7);
        actions.addView(test, tLp);

        Button toggle = compactButton(
                p.enabled ? "일시정지" : "사용 켜기",
                p.enabled ? Color.rgb(44, 31, 37) : Color.rgb(26, 48, 42),
                p.enabled ? Color.rgb(85, 47, 56) : Color.rgb(48, 88, 72),
                p.enabled ? Color.rgb(236, 164, 171) : Color.rgb(136, 224, 178));
        toggle.setOnClickListener(v -> toggleRoom(p));
        LinearLayout.LayoutParams gLp = weight(); gLp.leftMargin = dp(7);
        actions.addView(toggle, gLp);
        card.addView(actions, top(12));
        return card;
    }

    private void showAddCandidates() {
        KakaoNotificationListener.requestRefresh();
        final ArrayList<KakaoNotificationListener.SessionEntry> entries = KakaoNotificationListener.candidateSessionEntries();
        if (entries.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("새 방을 찾지 못했습니다")
                    .setMessage("추가할 오픈채팅방에서 새 메시지를 하나 받은 뒤 다시 시도하세요.\n\n알림 접근이 허용되어 있고 카카오톡 알림이 켜져 있어야 합니다.")
                    .setPositiveButton("직접 연결", (d, w) -> showAdvancedManualAdd())
                    .setNegativeButton("닫기", null)
                    .show();
            return;
        }
        showCandidatePicker(entries);
    }

    private void showCandidatePicker(ArrayList<KakaoNotificationListener.SessionEntry> entries) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(18), dp(20), dp(18));
        sheet.setBackground(roundStroke(Color.rgb(17, 22, 33), Color.rgb(48, 59, 80), 22));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(text("방 연결하기", 22, true, TEXT));
        titleBox.addView(text("최근 카카오 알림에서 답장 가능한 방을 찾았습니다.", 11, false, DIM), topWrap(4));
        head.addView(titleBox, weight());
        head.addView(pill(entries.size() + "개", Color.rgb(31, 42, 70), Color.rgb(177, 193, 255)));
        sheet.addView(head);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("방 이름 검색");
        search.setTextSize(14);
        search.setTextColor(TEXT);
        search.setHintTextColor(Color.rgb(92, 103, 124));
        search.setPadding(dp(14), dp(13), dp(14), dp(13));
        search.setBackground(roundStroke(FIELD, BORDER, 12));
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        sheet.addView(search, top(15));

        TextView guide = text("원하는 방을 누르면 연결 확인 후 설정 화면으로 이동합니다.", 11, false, Color.rgb(130, 143, 166));
        sheet.addView(guide, top(8));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(420));
        scrollLp.topMargin = dp(10);
        sheet.addView(scroll, scrollLp);

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        Button manual = compactButton("직접 연결", Color.rgb(28, 34, 48), Color.rgb(54, 65, 87), Color.rgb(203, 211, 228));
        manual.setOnClickListener(v -> {
            dialog.dismiss();
            showAdvancedManualAdd();
        });
        footer.addView(manual, weight());
        Button close = compactButton("닫기", Color.rgb(36, 42, 56), Color.rgb(58, 68, 88), Color.rgb(210, 217, 231));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeLp = weight();
        closeLp.leftMargin = dp(8);
        footer.addView(close, closeLp);
        sheet.addView(footer, top(12));

        renderCandidateRows(list, entries, "", dialog);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderCandidateRows(list, entries, s == null ? "" : s.toString(), dialog);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        dialog.setContentView(sheet);
        dialog.setOnShowListener(d -> {
            Window w = dialog.getWindow();
            if (w == null) return;
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.BOTTOM;
            lp.dimAmount = 0.68f;
            w.setAttributes(lp);
        });
        dialog.show();
    }

    private void renderCandidateRows(
            LinearLayout list,
            ArrayList<KakaoNotificationListener.SessionEntry> entries,
            String query,
            Dialog dialog) {
        list.removeAllViews();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (KakaoNotificationListener.SessionEntry entry : entries) {
            String room = entry == null || entry.suggestedRoom == null ? "" : entry.suggestedRoom.trim();
            if (room.isEmpty()) continue;
            if (!needle.isEmpty() && !room.toLowerCase(Locale.ROOT).contains(needle)) continue;
            shown++;
            list.addView(candidateRow(entry, room, dialog), top(7));
        }
        if (shown == 0) {
            LinearLayout empty = premiumCard(FIELD, Color.rgb(36, 45, 61), 13);
            TextView title = text("검색 결과가 없습니다", 14, true, TEXT);
            title.setGravity(Gravity.CENTER);
            empty.addView(title);
            TextView detail = text("방 이름을 다시 확인해 주세요.", 11, false, DIM);
            detail.setGravity(Gravity.CENTER);
            empty.addView(detail, top(4));
            list.addView(empty, top(8));
        }
    }

    private View candidateRow(KakaoNotificationListener.SessionEntry entry, String room, Dialog dialog) {
        boolean existing = MultiRoomStore.findByActualName(this, room).size() == 1;
        LinearLayout row = premiumCard(existing ? Color.rgb(24, 35, 44) : SURFACE_2,
                existing ? Color.rgb(50, 86, 75) : BORDER, 14);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            dialog.dismiss();
            pairCandidate(entry);
        });

        LinearLayout first = new LinearLayout(this);
        first.setOrientation(LinearLayout.HORIZONTAL);
        first.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(room, 15, true, TEXT);
        name.setMaxLines(2);
        first.addView(name, weight());
        String badge = existing ? "재연결" : confidenceLabel(entry.confidence);
        int badgeBg = existing ? Color.rgb(28, 57, 48) : Color.rgb(34, 43, 67);
        int badgeFg = existing ? Color.rgb(126, 230, 178) : Color.rgb(178, 191, 246);
        first.addView(pill(badge, badgeBg, badgeFg));
        row.addView(first);

        TextView meta = text(
                existing ? "이미 등록된 방입니다. 선택하면 현재 답장 연결을 갱신합니다."
                        : "최근 답장 가능한 카카오 알림에서 감지됨",
                11,
                false,
                existing ? Color.rgb(138, 188, 166) : Color.rgb(136, 149, 173));
        row.addView(meta, top(6));
        return row;
    }

    private String confidenceLabel(int confidence) {
        if (confidence >= 3) return "정확도 높음";
        if (confidence == 2) return "확인 권장";
        return "직접 확인";
    }

    private void pairCandidate(KakaoNotificationListener.SessionEntry entry) {
        if (entry == null || entry.suggestedRoom == null || entry.suggestedRoom.trim().isEmpty()) {
            showAdvancedManualAdd();
            return;
        }
        final String actualName = entry.suggestedRoom.trim();
        boolean existing = MultiRoomStore.findByActualName(this, actualName).size() == 1;
        new AlertDialog.Builder(this)
                .setTitle(existing ? "방 연결 갱신" : "방 추가 확인")
                .setMessage(actualName + "\n\n" + (existing
                        ? "이 방의 현재 카카오 답장 연결을 새 알림 기준으로 갱신합니다."
                        : "이 방을 자동전송 목록에 추가합니다. 방 이름을 다시 확인해 주세요."))
                .setPositiveButton(existing ? "재연결" : "추가", (d, w) -> connectCandidate(entry, actualName))
                .setNegativeButton("취소", null)
                .show();
    }

    private void connectCandidate(KakaoNotificationListener.SessionEntry entry, String actualName) {
        ArrayList<MultiRoomStore.Profile> existing = MultiRoomStore.findByActualName(this, actualName);
        if (existing.size() == 1) {
            MultiRoomStore.Profile p = existing.get(0);
            boolean ok = KakaoNotificationListener.bindRecentTokenToRoom(this, entry.token, p.room);
            if (!ok) {
                toast("알림 세션이 만료되었습니다. 방에서 새 메시지를 받은 뒤 다시 시도하세요.");
                return;
            }
            toast("방 연결을 갱신했습니다.");
            openEditor(p.room);
            return;
        }

        String routeAlias = "route-" + UUID.randomUUID();
        if (!KakaoNotificationListener.bindRecentTokenToRoom(this, entry.token, routeAlias)) {
            toast("알림 세션이 만료되었습니다. 방에서 새 메시지를 받은 뒤 다시 시도하세요.");
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
        String[] items = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            String room = entries.get(i).suggestedRoom;
            items[i] = room == null || room.trim().isEmpty()
                    ? "방 이름 미확인 · 직접 연결"
                    : room.trim();
        }
        new AlertDialog.Builder(this)
                .setTitle("직접 연결")
                .setMessage("자동 감지 결과가 맞지 않을 때만 사용하세요.")
                .setItems(items, (d, which) -> askManualRoomName(entries.get(which)))
                .setNegativeButton("취소", null)
                .show();
    }

    private void askManualRoomName(KakaoNotificationListener.SessionEntry entry) {
        EditText input = new EditText(this);
        input.setHint("알림창에 표시된 방 이름");
        input.setTextColor(TEXT);
        input.setHintTextColor(DIM);
        input.setSingleLine(true);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackground(roundStroke(FIELD, BORDER, 12));
        if (entry.suggestedRoom != null) input.setText(entry.suggestedRoom);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("방 이름 확인")
                .setMessage("오배송 방지를 위해 카카오 알림에 표시된 방 이름을 그대로 입력하세요.")
                .setView(box)
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
            toast("메시지 또는 사진을 먼저 설정해 주세요.");
            return;
        }
        if (!KakaoNotificationListener.hasLiveSession(p.room)) {
            toast("현재 방 연결이 없습니다. 방에서 새 메시지를 받은 뒤 새로고침해 주세요.");
            return;
        }
        String preview = p.message == null ? "" : p.message.trim();
        if (preview.length() > 180) preview = preview.substring(0, 180) + "…";
        new AlertDialog.Builder(this)
                .setTitle("1회 전송 확인")
                .setMessage(p.title() + "\n\n" + (preview.isEmpty() ? "사진 첨부 전송" : preview))
                .setPositiveButton("전송", (d, w) -> LicenseManager.runAuthorized(this, () -> {
                    boolean ok = KakaoMessageSender.send(this, p.room, p.message, RoomMediaStore.get(this, p.room));
                    Prefs.setStatus(this, ok ? "수동 전송 성공: " + p.title()
                            : "수동 전송 실패: " + p.title() + " · " + KakaoMessageSender.lastError());
                    toast(ok ? "전송했습니다." : "전송 실패: " + KakaoMessageSender.lastError());
                    refreshUi();
                }))
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
            toast("사용 중이며 전송 내용이 저장된 방이 없습니다.");
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
        toast(ready == usable ? "자동전송을 시작했습니다." : "자동전송을 시작했습니다. 연결 대기 방은 새 알림으로 복구됩니다.");
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
        TextView body = text(LicenseManager.diagnostic(this), 12, false, Color.rgb(200, 208, 224));
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(dp(14), dp(10), dp(14), dp(10));
        ScrollView sc = new ScrollView(this);
        sc.addView(body);
        new AlertDialog.Builder(this)
                .setTitle("진단 및 지원")
                .setView(sc)
                .setPositiveButton("지원 정보 공유", (d,w) -> {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_TEXT, LicenseManager.diagnostic(this));
                    startActivity(Intent.createChooser(share,"지원 정보 공유"));
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void resetBindings() {
        new AlertDialog.Builder(this)
                .setTitle("방 연결을 초기화할까요?")
                .setMessage("메시지, 사진, 전송 시간 설정은 그대로 유지하고 카카오 답장 연결 정보만 초기화합니다.")
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

    private String formatTime(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.KOREA).format(new Date(ms));
    }

    private String formatDateTime(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(new Date(ms));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private LinearLayout premiumCard(int color, int stroke, int radiusDp) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        l.setBackground(roundStroke(color, stroke, radiusDp));
        l.setElevation(dp(2));
        return l;
    }

    private TextView pill(String value, int bg, int fg) {
        TextView v = text(value, 10, true, fg);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), dp(6), dp(10), dp(6));
        v.setBackground(roundStroke(bg, Color.rgb(66, 78, 103), 14));
        return v;
    }

    private void stylePill(TextView view, String value, int bg, int fg) {
        if (view == null) return;
        view.setText(value);
        view.setTextColor(fg);
        view.setBackground(roundStroke(bg, Color.rgb(66, 78, 103), 14));
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(sp);
        v.setIncludeFontPadding(false);
        v.setLineSpacing(0, 1.08f);
        if (bold) v.setTypeface(v.getTypeface(), Typeface.BOLD);
        return v;
    }

    private Button actionButton(String label, int fill, int stroke, int textColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(14);
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
        b.setBackground(roundStroke(fill, stroke, 12));
        b.setMinHeight(dp(52));
        b.setPadding(dp(12), dp(4), dp(12), dp(4));
        return b;
    }

    private Button compactButton(String label, int fill, int stroke, int textColor) {
        Button b = actionButton(label, fill, stroke, textColor);
        b.setTextSize(12);
        b.setMinHeight(dp(44));
        return b;
    }

    private GradientDrawable roundStroke(int color, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout.LayoutParams top(int v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(v);
        return lp;
    }

    private LinearLayout.LayoutParams topWrap(int v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(v);
        return lp;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + .5f);
    }
}
