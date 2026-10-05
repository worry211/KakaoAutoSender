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
import android.os.Handler;
import android.os.Looper;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
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
    private TextView masterBadge;
    private TextView systemStatus;
    private TextView summaryRooms;
    private TextView summaryEnabled;
    private TextView summaryReady;
    private TextView summaryNote;
    private Button startButton;
    private Button stopButton;
    private boolean receiverRegistered;
    private static final int REQUEST_BULK_EDIT = 4302;
    private EditText roomSearch;
    private LinearLayout bulkBar;
    private TextView bulkSelectionLabel;
    private final LinkedHashSet<String> selectedRooms = new LinkedHashSet<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = this::refreshUi;
    private String roomQuery = "";
    private String lastRoomRenderFingerprint = "";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { scheduleRefresh(); }
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
        uiHandler.removeCallbacks(refreshRunnable);
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        KakaoNotificationListener.requestRefresh();
        scheduleRefresh();
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
        masterBadge = pill("READY", Color.rgb(32, 43, 68), Color.rgb(172, 190, 255));
        statusHeader.addView(masterBadge);
        masterCard.addView(statusHeader);

        masterStatus = text("", 22, true, TEXT);
        masterCard.addView(masterStatus, top(14));
        systemStatus = text("", 12, false, Color.rgb(181, 191, 210));
        systemStatus.setLineSpacing(0, 1.18f);
        masterCard.addView(systemStatus, top(8));

        LinearLayout masterButtons = new LinearLayout(this);
        masterButtons.setOrientation(LinearLayout.HORIZONTAL);
        startButton = primaryButton("자동전송 시작");
        startButton.setContentDescription("전체 자동전송 시작 또는 설정 계속하기");
        startButton.setOnClickListener(v -> startAll());
        masterButtons.addView(startButton, new LinearLayout.LayoutParams(0, dp(54), 1.55f));
        stopButton = dangerSecondaryButton("전체 중단");
        stopButton.setContentDescription("실행 중인 모든 자동전송 즉시 중단");
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

        LinearLayout roomTools = new LinearLayout(this);
        roomTools.setOrientation(LinearLayout.HORIZONTAL);
        roomTools.setGravity(Gravity.CENTER_VERTICAL);
        roomSearch = new EditText(this);
        roomSearch.setSingleLine(true);
        roomSearch.setHint("방 검색");
        roomSearch.setHintTextColor(Color.rgb(102, 114, 137));
        roomSearch.setTextColor(TEXT);
        roomSearch.setTextSize(13);
        roomSearch.setPadding(dp(13), dp(10), dp(13), dp(10));
        roomSearch.setBackground(roundStroke(FIELD, BORDER, 12));
        roomTools.addView(roomSearch, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button selectAll = tertiaryButton("다중 선택");
        LinearLayout.LayoutParams selectLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
        selectLp.leftMargin = dp(8);
        roomTools.addView(selectAll, selectLp);
        root.addView(roomTools, top(9));

        roomSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                roomQuery = s == null ? "" : s.toString();
                invalidateRoomList();
                scheduleRefresh();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        selectAll.setOnClickListener(v -> selectVisibleRooms());

        bulkBar = card(Color.rgb(23, 29, 42), Color.rgb(66, 84, 133), 16);
        bulkBar.setVisibility(View.GONE);
        bulkSelectionLabel = text("", 13, true, Color.rgb(196, 207, 238));
        bulkBar.addView(bulkSelectionLabel);
        LinearLayout bulkActions = new LinearLayout(this);
        bulkActions.setOrientation(LinearLayout.HORIZONTAL);
        Button bulkEdit = smallButton("일괄 편집");
        bulkEdit.setOnClickListener(v -> openBulkEditor());
        bulkActions.addView(bulkEdit, weight());
        Button bulkEnable = smallButton("사용 켜기");
        bulkEnable.setOnClickListener(v -> applySelectedEnabled(true));
        LinearLayout.LayoutParams bulkEnableLp = weight();
        bulkEnableLp.leftMargin = dp(6);
        bulkActions.addView(bulkEnable, bulkEnableLp);
        Button bulkPause = smallButton("일시정지");
        bulkPause.setOnClickListener(v -> applySelectedEnabled(false));
        LinearLayout.LayoutParams bulkPauseLp = weight();
        bulkPauseLp.leftMargin = dp(6);
        bulkActions.addView(bulkPause, bulkPauseLp);
        bulkBar.addView(bulkActions, top(9));
        Button clearSelection = tertiaryButton("선택 해제");
        clearSelection.setOnClickListener(v -> {
            selectedRooms.clear();
            invalidateRoomList();
            refreshUi();
        });
        bulkBar.addView(clearSelection, top(7));
        root.addView(bulkBar, top(8));

        roomList = new LinearLayout(this);
        roomList.setOrientation(LinearLayout.VERTICAL);
        root.addView(roomList, top(7));

        LinearLayout summaryCard = card(Color.rgb(14, 18, 27), Color.rgb(35, 44, 61), 16);
        LinearLayout summaryHeader = new LinearLayout(this);
        summaryHeader.setOrientation(LinearLayout.HORIZONTAL);
        summaryHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView summaryTitle = text("운영 요약", 11, true, Color.rgb(128, 142, 169));
        summaryTitle.setLetterSpacing(0.07f);
        summaryHeader.addView(summaryTitle, weight());
        summaryHeader.addView(pill("LIVE", Color.rgb(24, 34, 52), Color.rgb(158, 177, 225)));
        summaryCard.addView(summaryHeader);

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        summaryRooms = metricValue();
        summaryEnabled = metricValue();
        summaryReady = metricValue();
        metrics.addView(metric("연결된 방", summaryRooms), weight());
        LinearLayout.LayoutParams enabledLp = weight();
        enabledLp.leftMargin = dp(7);
        metrics.addView(metric("사용 중", summaryEnabled), enabledLp);
        LinearLayout.LayoutParams readyLp = weight();
        readyLp.leftMargin = dp(7);
        metrics.addView(metric("전송 준비", summaryReady), readyLp);
        summaryCard.addView(metrics, top(12));

        summaryNote = text("", 11, false, Color.rgb(142, 155, 180));
        summaryNote.setLineSpacing(0, 1.14f);
        summaryCard.addView(summaryNote, top(11));
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
        int enabled = 0;
        int ready = 0;
        int usable = 0;
        for (MultiRoomStore.Profile p : profiles) {
            if (p.enabled) enabled++;
            if (p.enabled && RoomMediaStore.hasPayload(this, p)) {
                usable++;
                if (KakaoNotificationListener.hasLiveSession(p.room)) ready++;
            }
        }

        if (active && usable > 0 && ready < usable) {
            masterStatus.setText("자동전송 실행 중 · 연결 대기");
            masterStatus.setTextColor(AMBER);
            stylePill(masterBadge, "WAIT", Color.rgb(55, 45, 27), AMBER);
        } else if (active) {
            masterStatus.setText("자동전송 실행 중");
            masterStatus.setTextColor(GREEN);
            stylePill(masterBadge, "LIVE", Color.rgb(25, 49, 42), Color.rgb(122, 231, 176));
        } else if (!access) {
            masterStatus.setText("알림 접근 권한이 필요합니다");
            masterStatus.setTextColor(AMBER);
            stylePill(masterBadge, "SETUP", Color.rgb(55, 45, 27), AMBER);
        } else if (!listener) {
            masterStatus.setText("카카오 연결을 기다리는 중");
            masterStatus.setTextColor(AMBER);
            stylePill(masterBadge, "WAIT", Color.rgb(55, 45, 27), AMBER);
        } else if (usable == 0) {
            masterStatus.setText("방 설정을 완료하세요");
            masterStatus.setTextColor(TEXT);
            stylePill(masterBadge, "SETUP", Color.rgb(45, 49, 59), Color.rgb(180, 190, 211));
        } else {
            masterStatus.setText("자동전송 준비됨");
            masterStatus.setTextColor(TEXT);
            stylePill(masterBadge, "READY", Color.rgb(32, 43, 68), Color.rgb(172, 190, 255));
        }

        StringBuilder system = new StringBuilder();
        system.append("라이선스 ").append(LicenseManager.shortStatus(this));
        system.append("  ·  알림 접근 ").append(access ? "정상" : "권한 필요");
        system.append("\n카카오 연결 ").append(listener ? "정상" : "대기");
        if (Build.VERSION.SDK_INT >= 31) {
            system.append("  ·  예약 정확도 ").append(exact ? "정확 시각" : "근사 시각");
        }
        system.append("  ·  연결 방 ").append(profiles.size()).append("개");
        String notice = LicenseManager.updateNotice(this);
        if (notice != null && !notice.trim().isEmpty()) system.append("\n").append(notice.trim());
        systemStatus.setText(system.toString());
        systemStatus.setTextColor(!access || !listener ? AMBER : Color.rgb(181, 191, 210));

        startButton.setEnabled(!active);
        stopButton.setEnabled(active);
        if (active) startButton.setText("자동전송 실행 중");
        else if (!access) startButton.setText("알림 접근 필요");
        else if (usable == 0) startButton.setText("방 설정을 완료하세요");
        else startButton.setText("자동전송 시작");
        startButton.setAlpha(startButton.isEnabled() ? 1f : 0.46f);
        stopButton.setAlpha(active ? 1f : 0.46f);

        LinkedHashSet<String> existingAliases = new LinkedHashSet<>();
        Map<String, Integer> titleCounts = new HashMap<>();
        int visibleRooms = 0;
        for (MultiRoomStore.Profile p : profiles) {
            existingAliases.add(p.room);
            String titleKey = RoomRouting.normalizeTitle(p.actualRoomName);
            titleCounts.put(titleKey, titleCounts.getOrDefault(titleKey, 0) + 1);
            if (matchesRoomQuery(p)) visibleRooms++;
        }
        if (selectedRooms.retainAll(existingAliases)) invalidateRoomList();
        String renderFingerprint = buildRoomRenderFingerprint(profiles);
        if (!renderFingerprint.equals(lastRoomRenderFingerprint)) {
            roomList.removeAllViews();
            if (profiles.isEmpty()) {
                LinearLayout empty = card(SURFACE, BORDER, 18);
                TextView emptyBadge = pill("GET STARTED", Color.rgb(29, 38, 65), Color.rgb(180, 195, 255));
                empty.addView(emptyBadge, wrap());
                empty.addView(text("첫 자동전송 방을 연결하세요", 18, true, TEXT), top(13));
                TextView guide = text("카카오톡에서 대상 방의 새 메시지를 하나 받은 뒤 ‘새 방 연결’에서 방을 선택하세요.", 13, false, Color.rgb(166, 177, 198));
                guide.setLineSpacing(0, 1.15f);
                empty.addView(guide, top(7));
                LinearLayout path = card(Color.rgb(14, 18, 27), Color.rgb(35, 44, 61), 12);
                TextView steps = text("01  새 메시지 받기   →   02  방 연결   →   03  1회 테스트", 11, true, Color.rgb(153, 170, 209));
                steps.setGravity(Gravity.CENTER);
                path.addView(steps);
                empty.addView(path, top(11));
                TextView safe = text("방 이름과 답장 세션이 함께 확인된 경우에만 연결됩니다.", 11, false, Color.rgb(112, 126, 151));
                empty.addView(safe, top(10));
                roomList.addView(empty, top(7));
            } else if (visibleRooms == 0) {
                LinearLayout empty = card(SURFACE, BORDER, 18);
                empty.addView(text("검색 결과가 없습니다", 16, true, TEXT));
                empty.addView(text("다른 방 이름으로 검색해 보세요.", 11, false, MUTED), top(6));
                roomList.addView(empty, top(7));
            } else {
                for (MultiRoomStore.Profile p : profiles) {
                    if (!matchesRoomQuery(p)) continue;
                    int sameTitle = titleCounts.getOrDefault(RoomRouting.normalizeTitle(p.actualRoomName), 0);
                    roomList.addView(roomCard(p, sameTitle), top(8));
                }
            }
            lastRoomRenderFingerprint = renderFingerprint;
        }
        updateBulkBar();

        long next = 0L;
        for (MultiRoomStore.Profile p : profiles) {
            if (!p.enabled || !RoomMediaStore.hasPayload(this, p) || p.nextAt <= 0L) continue;
            if (next == 0L || p.nextAt < next) next = p.nextAt;
        }
        summaryRooms.setText(String.valueOf(profiles.size()));
        summaryEnabled.setText(String.valueOf(enabled));
        summaryReady.setText(String.valueOf(ready));

        boolean warning = false;
        String note;
        if (!access) {
            warning = true;
            note = "알림 접근 권한이 필요합니다. 아래 ‘알림 접근’에서 허용해 주세요.";
        } else if (!listener) {
            warning = true;
            note = "카카오 알림 연결을 기다리고 있습니다. 새 메시지를 받은 뒤 새로고침해 주세요.";
        } else if (profiles.isEmpty()) {
            note = "첫 방을 연결하면 메시지·스케줄·전송 상태를 여기서 한눈에 확인할 수 있습니다.";
        } else if (enabled == 0) {
            note = "연결된 방은 있지만 현재 사용 중인 자동전송 방이 없습니다.";
        } else if (ready < enabled) {
            warning = true;
            note = "사용 중인 방 중 " + (enabled - ready) + "개가 연결 대기 중입니다. 해당 방의 새 메시지를 받으면 복구됩니다.";
        } else if (active && next > 0) {
            note = "다음 전송 예정  ·  " + formatDateTime(next);
        } else {
            note = "모든 활성 방이 전송 준비 상태입니다.";
        }
        summaryNote.setText(note);
        summaryNote.setTextColor(warning ? AMBER : Color.rgb(142, 155, 180));
    }

    private View roomCard(MultiRoomStore.Profile p, int sameTitleCount) {
        boolean live = KakaoNotificationListener.hasLiveSession(p.room);
        boolean stored = KakaoNotificationListener.hasStoredBinding(this, p.room);

        boolean selected = selectedRooms.contains(p.room);
        LinearLayout card = card(selected ? Color.rgb(22, 29, 48) : SURFACE, selected ? ACCENT : BORDER, 18);
        card.setOnClickListener(v -> {
            if (!selectedRooms.isEmpty()) toggleSelection(p.room);
            else openEditor(p.room);
        });
        card.setOnLongClickListener(v -> {
            toggleSelection(p.room);
            return true;
        });

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView roomTitle = text(p.title(), 17, true, TEXT);
        roomTitle.setMaxLines(2);
        titleRow.addView(roomTitle, weight());
        if (selected) titleRow.addView(pill("✓ 선택", Color.rgb(39, 51, 89), Color.rgb(190, 202, 255)));
        titleRow.addView(pill(p.enabled ? "사용 중" : "일시정지",
                p.enabled ? Color.rgb(28, 54, 44) : Color.rgb(45, 49, 59),
                p.enabled ? Color.rgb(130, 232, 180) : Color.rgb(162, 171, 190)));
        card.addView(titleRow);

        if (sameTitleCount > 1) {
            String hint = Prefs.bindingHintForAlias(this, p.room);
            card.addView(text(hint.isEmpty() ? "같은 제목의 별도 방 · 현재 세션으로 구분"
                            : "같은 제목의 별도 방 · 구분 #" + hint,
                    10, true, hint.isEmpty() ? AMBER : Color.rgb(153, 170, 209)), top(6));
        }

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

        String recent = p.lastSuccessAt > 0
                ? "최근 성공  ·  " + formatDateTime(p.lastSuccessAt)
                : p.failureStreak > 0
                    ? "최근 전송 확인 필요  ·  실패 " + p.failureStreak + "회"
                    : "최근 전송 기록 없음";
        TextView recentView = text(recent, 10, false,
                p.failureStreak > 0 ? AMBER : Color.rgb(112, 126, 151));
        card.addView(recentView, top(6));

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
        if (selectedRooms.isEmpty()) card.addView(actions, top(12));
        return card;
    }

    private void scheduleRefresh() {
        uiHandler.removeCallbacks(refreshRunnable);
        uiHandler.postDelayed(refreshRunnable, 90L);
    }

    private void invalidateRoomList() {
        lastRoomRenderFingerprint = "";
    }

    private boolean matchesRoomQuery(MultiRoomStore.Profile p) {
        String query = RoomRouting.normalizeTitle(roomQuery);
        if (query.isEmpty()) return true;
        String title = RoomRouting.normalizeTitle(p.title());
        String actual = RoomRouting.normalizeTitle(p.actualRoomName);
        return title.contains(query) || actual.contains(query);
    }

    private String buildRoomRenderFingerprint(ArrayList<MultiRoomStore.Profile> profiles) {
        StringBuilder fp = new StringBuilder(RoomRouting.normalizeTitle(roomQuery)).append('|');
        for (String selected : selectedRooms) fp.append("S:").append(selected).append('|');
        for (MultiRoomStore.Profile p : profiles) {
            if (!matchesRoomQuery(p)) continue;
            fp.append(p.room).append('|').append(p.title()).append('|').append(p.enabled).append('|')
                    .append(p.message).append('|').append(p.scheduleMode).append('|').append(p.intervalMinutes).append('|')
                    .append(p.dailyTimes).append('|').append(p.dailyLimit).append('|').append(p.todayCount).append('|')
                    .append(p.nextAt).append('|').append(p.failureStreak).append('|').append(p.lastSuccessAt).append('|')
                    .append(KakaoNotificationListener.hasLiveSession(p.room)).append('|')
                    .append(KakaoNotificationListener.hasStoredBinding(this, p.room)).append(';');
        }
        return fp.toString();
    }

    private void toggleSelection(String room) {
        if (room == null || room.trim().isEmpty()) return;
        if (!selectedRooms.add(room)) selectedRooms.remove(room);
        invalidateRoomList();
        refreshUi();
    }

    private void selectVisibleRooms() {
        ArrayList<MultiRoomStore.Profile> profiles = MultiRoomStore.list(this);
        boolean anyUnselected = false;
        for (MultiRoomStore.Profile p : profiles) {
            if (matchesRoomQuery(p) && !selectedRooms.contains(p.room)) { anyUnselected = true; break; }
        }
        if (anyUnselected) {
            for (MultiRoomStore.Profile p : profiles) if (matchesRoomQuery(p)) selectedRooms.add(p.room);
        } else {
            for (MultiRoomStore.Profile p : profiles) if (matchesRoomQuery(p)) selectedRooms.remove(p.room);
        }
        invalidateRoomList();
        refreshUi();
    }

    private void updateBulkBar() {
        if (bulkBar == null || bulkSelectionLabel == null) return;
        int count = selectedRooms.size();
        bulkBar.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        bulkSelectionLabel.setText(count + "개 방 선택 · 길게 누르거나 탭해서 선택 변경");
    }

    private void openBulkEditor() {
        if (selectedRooms.isEmpty()) { toast("먼저 방을 선택해 주세요."); return; }
        Intent intent = new Intent(this, BulkRoomEditActivity.class);
        intent.putStringArrayListExtra(BulkRoomEditActivity.EXTRA_ROOMS, new ArrayList<>(selectedRooms));
        startActivityForResult(intent, REQUEST_BULK_EDIT);
    }

    private void applySelectedEnabled(boolean enabled) {
        if (selectedRooms.isEmpty()) return;
        MultiRoomStore.BulkPatch patch = new MultiRoomStore.BulkPatch();
        patch.applyEnabled = true;
        patch.enabled = enabled;
        int changed = MultiRoomStore.applyBulkPatch(this, new ArrayList<>(selectedRooms), patch);
        if (Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false)) SendScheduler.scheduleNext(this);
        Prefs.setStatus(this, (enabled ? "선택 방 사용 켬 · " : "선택 방 일시정지 · ") + changed + "개");
        selectedRooms.clear();
        invalidateRoomList();
        refreshUi();
        toast(changed + "개 방을 " + (enabled ? "사용 상태로 변경했습니다." : "일시정지했습니다."));
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BULK_EDIT && resultCode == RESULT_OK) {
            selectedRooms.clear();
            invalidateRoomList();
            refreshUi();
        }
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
                        : "최근 감지된 카카오 방입니다. 같은 제목도 고유 식별자가 있으면 각각 구분합니다.",
                12, false, Color.rgb(158, 170, 194));
        content.addView(description);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("방 이름 또는 구분 코드 검색");
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
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int pickerHeight = Math.min(dp(500), Math.max(dp(300), screenHeight - dp(390)));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, pickerHeight);
        scrollLp.topMargin = dp(5);
        content.addView(listScroll, scrollLp);

        if (!manual) {
            Button advanced = tertiaryButton("최근 알림에서 직접 연결");
            content.addView(advanced, top(10));
            advanced.setOnClickListener(v -> showAdvancedManualAdd());
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

    private int candidateTitleCount(ArrayList<KakaoNotificationListener.SessionEntry> entries, String room) {
        int result = 0;
        for (KakaoNotificationListener.SessionEntry entry : entries) {
            if (entry != null && RoomRouting.sameTitle(room, entry.suggestedRoom)) result++;
        }
        return result;
    }

    private void renderCandidateRows(
            LinearLayout rows,
            TextView count,
            ArrayList<KakaoNotificationListener.SessionEntry> entries,
            String query,
            AlertDialog dialog,
            boolean manual) {
        rows.removeAllViews();
        String normalized = RoomRouting.normalizeTitle(query);
        int shown = 0;
        for (KakaoNotificationListener.SessionEntry entry : entries) {
            String room = entry.suggestedRoom == null ? "" : entry.suggestedRoom.trim();
            if (!manual && room.isEmpty()) continue;
            String searchable = RoomRouting.normalizeTitle(room + " " + entry.identityCode);
            if (!normalized.isEmpty() && !searchable.contains(normalized)) continue;
            shown++;

            String mappedAlias = KakaoNotificationListener.mappedAliasForToken(this, entry.token);
            String liveAlias = KakaoNotificationListener.liveAliasForToken(entry.token);
            boolean conflict = KakaoNotificationListener.identityBindingConflictForToken(this, entry.token);
            boolean duplicateTitle = candidateTitleCount(entries, room) > 1
                    || (!room.isEmpty() && MultiRoomStore.sameTitleCount(this, room) > 0);
            boolean recover = mappedAlias != null || liveAlias != null;

            LinearLayout row = card(SURFACE_2, BORDER, 14);
            row.setClickable(true);
            row.setFocusable(true);
            LinearLayout rowTop = new LinearLayout(this);
            rowTop.setOrientation(LinearLayout.HORIZONTAL);
            rowTop.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text(room.isEmpty() ? "방 이름 미확인" : room, 15, true, TEXT);
            name.setMaxLines(2);
            rowTop.addView(name, weight());

            String badge = conflict ? "식별 충돌"
                    : recover ? "복구"
                    : duplicateTitle && entry.persistentIdentity ? "동일 제목 · 구분됨"
                    : duplicateTitle ? "동일 제목 · 확인 필요"
                    : manual ? "확인" : "연결";
            int badgeBg = conflict ? Color.rgb(58, 31, 36)
                    : recover ? Color.rgb(25, 49, 42)
                    : duplicateTitle && !entry.persistentIdentity ? Color.rgb(55, 45, 27)
                    : Color.rgb(29, 38, 65);
            int badgeFg = conflict ? RED
                    : recover ? GREEN
                    : duplicateTitle && !entry.persistentIdentity ? AMBER
                    : Color.rgb(180, 195, 255);
            rowTop.addView(pill(badge, badgeBg, badgeFg));
            row.addView(rowTop);

            String meta = conflict
                    ? "하나의 카카오 고유 식별자가 여러 등록 방에 연결되어 있어 자동 연결을 차단합니다."
                    : recover
                    ? "이미 확인된 카카오 방입니다. 현재 답장 세션만 안전하게 복구합니다."
                    : duplicateTitle && entry.persistentIdentity
                    ? "같은 제목이지만 카카오 고유 식별자가 달라 별도 방으로 구분됩니다."
                    : duplicateTitle
                    ? "고유 식별자가 없어 같은 제목의 방을 확정할 수 없으면 전송을 차단합니다."
                    : entry.persistentIdentity
                    ? "카카오 고유 식별자 확인됨 · 재실행 후에도 안전 복구할 수 있습니다."
                    : manual
                    ? "알림에 표시된 방 이름을 확인한 뒤 현재 세션에 연결합니다."
                    : "현재 알림 세션 기준 · 재연결 시 새 메시지가 필요할 수 있습니다.";
            row.addView(text(meta, 11, false,
                    conflict ? RED : duplicateTitle && !entry.persistentIdentity ? AMBER : Color.rgb(125, 139, 166)), top(6));
            if (entry.observedAt > 0L) {
                String signal = ageLabel(entry.observedAt) + " · 신뢰도 " + confidenceLabel(entry.confidence);
                if (duplicateTitle && !entry.identityCode.isEmpty()) {
                    signal += entry.persistentIdentity ? " · 구분 #" + entry.identityCode : " · 세션 #" + entry.identityCode;
                }
                row.addView(text(signal, 10, false, Color.rgb(105, 119, 145)), top(5));
            }
            row.setContentDescription((room.isEmpty() ? "방 이름 미확인" : room) + " · " + badge);
            final KakaoNotificationListener.SessionEntry selected = entry;
            row.setOnClickListener(v -> {
                dialog.dismiss();
                if (manual) askManualRoomName(selected); else pairCandidate(selected);
            });
            rows.addView(row, top(6));
        }
        count.setText(shown + "개 방 표시 · 방 이름을 눌러 연결");
        if (shown == 0) {
            LinearLayout empty = card(Color.rgb(14, 18, 27), Color.rgb(35, 44, 61), 14);
            empty.addView(text("검색 결과가 없습니다.", 13, true, Color.rgb(197, 207, 228)));
            empty.addView(text("방 이름이나 구분 코드를 다시 확인하거나 검색어를 지워 주세요.", 11, false, Color.rgb(119, 132, 156)), top(5));
            rows.addView(empty, top(6));
        }
    }

    private void pairCandidate(KakaoNotificationListener.SessionEntry entry) {
        if (entry == null || entry.suggestedRoom == null || entry.suggestedRoom.trim().isEmpty()) {
            showAdvancedManualAdd();
            return;
        }
        final String actualName = entry.suggestedRoom.trim();
        String detail = actualName;
        if ((MultiRoomStore.sameTitleCount(this, actualName) > 0) && !entry.identityCode.isEmpty()) {
            detail += "\n" + (entry.persistentIdentity ? "구분 #" : "세션 #") + entry.identityCode;
        }
        new AlertDialog.Builder(this)
                .setTitle("방 연결 확인")
                .setMessage(detail + "\n\n이 카카오 방을 자동전송 목록에 연결할까요?")
                .setPositiveButton("연결", (d, w) -> connectCandidate(entry, actualName))
                .setNegativeButton("취소", null)
                .show();
    }

    private void connectCandidate(KakaoNotificationListener.SessionEntry entry, String actualName) {
        if (entry == null || actualName == null || actualName.trim().isEmpty()) return;
        actualName = actualName.trim();

        if (KakaoNotificationListener.identityBindingConflictForToken(this, entry.token)) {
            connectionBlocked("방 식별자 충돌이 감지되었습니다",
                    "같은 카카오 고유 식별자가 여러 등록 방에 연결되어 있습니다. 오배송 방지를 위해 연결하지 않았습니다. ‘연결 초기화’ 후 대상 방에서 새 메시지를 받아 다시 연결해 주세요.");
            return;
        }

        String mappedAlias = KakaoNotificationListener.mappedAliasForToken(this, entry.token);
        if (mappedAlias != null) {
            MultiRoomStore.Profile mapped = MultiRoomStore.get(this, mappedAlias);
            if (mapped != null && bindAndOpen(entry, mapped)) {
                if (!RoomRouting.sameTitle(mapped.actualRoomName, actualName)) {
                    toast("같은 카카오 방이 다른 이름으로 감지되어 기존 등록 방으로 통합했습니다.");
                }
                return;
            }
            connectionBlocked("중복 연결을 차단했습니다",
                    "이 카카오 방은 이미 다른 등록 정보와 연결되어 있습니다. 기존 방 설정을 확인하거나 연결 정보를 초기화한 뒤 다시 시도해 주세요.");
            return;
        }

        String liveAlias = KakaoNotificationListener.liveAliasForToken(entry.token);
        if (liveAlias != null) {
            MultiRoomStore.Profile live = MultiRoomStore.get(this, liveAlias);
            if (live != null && bindAndOpen(entry, live)) {
                if (!RoomRouting.sameTitle(live.actualRoomName, actualName)) {
                    toast("동일 알림 세션이 다른 이름으로 감지되어 기존 방으로 통합했습니다.");
                }
                return;
            }
            connectionBlocked("같은 방의 중복 등록을 차단했습니다",
                    "현재 카카오 알림 세션이 이미 다른 등록 방에 연결되어 있습니다. 새 항목을 만들지 않았습니다.");
            return;
        }

        ArrayList<MultiRoomStore.Profile> sameName = MultiRoomStore.findByActualName(this, actualName);
        if (entry.persistentIdentity) {
            ArrayList<MultiRoomStore.Profile> unbound = new ArrayList<>();
            for (MultiRoomStore.Profile p : sameName) {
                if (!KakaoNotificationListener.hasStoredBinding(this, p.room)) unbound.add(p);
            }
            if (unbound.size() == 1) {
                if (bindAndOpen(entry, unbound.get(0))) return;
                connectionBlocked("방 연결을 확인할 수 없습니다",
                        "기존 설정과 카카오 고유 식별자가 충돌해 연결하지 않았습니다. 대상 방에서 새 메시지를 받은 뒤 다시 시도해 주세요.");
                return;
            }
            if (unbound.size() > 1) {
                connectionBlocked("같은 제목의 기존 설정이 여러 개 있습니다",
                        "어느 설정을 이어받아야 하는지 안전하게 판단할 수 없습니다. 불필요한 중복 방을 정리한 뒤 다시 연결해 주세요.");
                return;
            }

            String routeAlias = "route-" + entry.identityFingerprint.substring(0, Math.min(24, entry.identityFingerprint.length()));
            MultiRoomStore.Profile deterministic = MultiRoomStore.get(this, routeAlias);
            if (deterministic != null) {
                if (bindAndOpen(entry, deterministic)) return;
                connectionBlocked("방 고유 식별자 연결을 확인할 수 없습니다",
                        "기존 방 정보와 현재 카카오 알림이 일치하지 않아 안전상 연결하지 않았습니다.");
                return;
            }
            createAndOpen(entry, routeAlias, actualName);
            return;
        }

        if (sameName.size() == 1) {
            MultiRoomStore.Profile existing = sameName.get(0);
            if (KakaoNotificationListener.hasStoredBinding(this, existing.room)) {
                connectionBlocked("같은 제목의 방을 구분할 정보가 부족합니다",
                        "기존 방에는 카카오 고유 식별자가 있지만 현재 알림에는 고유 식별자가 없습니다. 잘못된 방 전송을 막기 위해 자동 연결하지 않았습니다. 대상 방에서 새 메시지를 다시 받아 주세요.");
                return;
            }
            if (bindAndOpen(entry, existing)) return;
            connectionBlocked("방 연결을 확인할 수 없습니다",
                    "현재 알림 세션이 다른 방과 겹쳐 보여 새 방을 만들지 않았습니다.");
            return;
        }
        if (sameName.size() > 1) {
            connectionBlocked("같은 제목의 방을 안전하게 구분할 수 없습니다",
                    "현재 알림에는 카카오 고유 식별자가 없어 같은 제목의 여러 방 중 대상을 확정할 수 없습니다. 오배송 방지를 위해 연결을 차단했습니다.");
            return;
        }
        createAndOpen(entry, "route-" + UUID.randomUUID(), actualName);
    }

    private boolean bindAndOpen(KakaoNotificationListener.SessionEntry entry, MultiRoomStore.Profile profile) {
        if (profile == null) return false;
        if (!KakaoNotificationListener.bindRecentTokenToRoom(this, entry.token, profile.room)) return false;
        openEditor(profile.room);
        return true;
    }

    private void createAndOpen(KakaoNotificationListener.SessionEntry entry, String routeAlias, String actualName) {
        if (!KakaoNotificationListener.bindRecentTokenToRoom(this, entry.token, routeAlias)) {
            connectionBlocked("방 연결을 안전하게 완료하지 못했습니다",
                    "알림 세션이 만료되었거나 동일 카카오 방의 중복 연결이 감지되었습니다. 대상 방에서 새 메시지를 하나 받은 뒤 다시 시도해 주세요.");
            return;
        }
        MultiRoomStore.Profile p = new MultiRoomStore.Profile(routeAlias);
        p.actualRoomName = actualName;
        p.displayName = actualName;
        MultiRoomStore.upsert(this, p);
        openEditor(routeAlias);
    }

    private void connectionBlocked(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("확인", null)
                .show();
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
                .setMessage("오배송을 막기 위해 카카오 알림에 표시된 방 이름을 그대로 입력해 주세요. 같은 제목은 고유 식별자가 있을 때만 자동으로 구분합니다.")
                .setView(input)
                .setPositiveButton("연결", (d, w) -> {
                    String actualName = input.getText().toString().trim();
                    if (actualName.isEmpty()) { toast("방 이름을 입력해 주세요."); return; }
                    connectCandidate(entry, actualName);
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
            new AlertDialog.Builder(this)
                    .setTitle("알림 접근 권한이 필요합니다")
                    .setMessage("카카오톡의 답장 세션을 안전하게 확인하려면 알림 접근 권한이 필요합니다. 권한을 허용한 뒤 대상 방에서 새 메시지를 하나 받아 주세요.")
                    .setPositiveButton("권한 설정 열기", (d, w) ->
                            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
                    .setNegativeButton("나중에", null)
                    .show();
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
            AlertDialog.Builder setup = new AlertDialog.Builder(this)
                    .setTitle("자동전송 방 설정이 필요합니다")
                    .setMessage(profiles.isEmpty()
                            ? "자동전송할 카카오 방을 먼저 연결하고 메시지 또는 사진과 전송 스케줄을 저장해 주세요."
                            : "연결된 방에 메시지 또는 사진과 전송 스케줄을 저장해 주세요.")
                    .setNegativeButton("닫기", null);
            if (profiles.isEmpty()) {
                setup.setPositiveButton("새 방 연결", (d, w) -> showAddCandidates());
            } else {
                setup.setPositiveButton("방 설정 열기", (d, w) -> openEditor(profiles.get(0).room));
            }
            setup.show();
            return;
        }
        final int usableCount = usable;
        final int readyCount = ready;
        if (ready < usable) {
            new AlertDialog.Builder(this)
                    .setTitle("일부 방이 연결 대기 중입니다")
                    .setMessage("사용할 방 " + usable + "개 중 현재 " + ready + "개가 즉시 전송 가능합니다.\n\n연결 대기 방은 해당 카카오 방에서 새 메시지를 받으면 복구됩니다.")
                    .setPositiveButton("대기 방 포함 시작", (d, w) -> commitStart(usableCount, readyCount))
                    .setNegativeButton("취소", null)
                    .show();
            return;
        }
        commitStart(usable, ready);
    }

    private void commitStart(int usable, int ready) {
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

    private String ageLabel(long observedAt) {
        long age = Math.max(0L, System.currentTimeMillis() - observedAt);
        if (age < 60_000L) return "방금 감지";
        long minutes = age / 60_000L;
        if (minutes < 60L) return minutes + "분 전 감지";
        long hours = minutes / 60L;
        if (hours < 24L) return hours + "시간 전 감지";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(new Date(observedAt)) + " 감지";
    }

    private String confidenceLabel(int confidence) {
        if (confidence >= 3) return "높음";
        if (confidence >= 2) return "중간";
        return "낮음";
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

    private TextView metricValue() {
        TextView value = text("0", 20, true, TEXT);
        value.setGravity(Gravity.CENTER);
        return value;
    }

    private LinearLayout metric(String label, TextView value) {
        LinearLayout box = card(Color.rgb(18, 23, 34), Color.rgb(40, 49, 67), 12);
        box.setPadding(dp(8), dp(11), dp(8), dp(10));
        box.setGravity(Gravity.CENTER);
        box.addView(value, wrap());
        TextView caption = text(label, 10, true, Color.rgb(118, 132, 158));
        caption.setGravity(Gravity.CENTER);
        box.addView(caption, topWrap(4));
        return box;
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

    private void stylePill(TextView view, String value, int bg, int fg) {
        if (view == null) return;
        view.setText(value);
        view.setTextColor(fg);
        view.setBackground(roundStroke(bg, Color.rgb(62, 73, 96), 14));
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
