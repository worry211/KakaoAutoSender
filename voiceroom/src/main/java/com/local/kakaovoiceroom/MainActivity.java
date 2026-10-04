package com.local.kakaovoiceroom;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout roomList;
    private TextView masterStatus;
    private TextView systemStatus;
    private TextView footerStatus;
    private Button startButton;
    private Button stopButton;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refreshUi();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(12, 13, 16));
        getWindow().setNavigationBarColor(Color.rgb(12, 13, 16));
        setContentView(buildUi());
        AudioGuard.recoverIfStale(this);
        recoverInterruptedDirectCheckOnForeground();
        recoverStalePendingOnForeground();
        refreshUi();
    }

    @Override protected void onResume() {
        super.onResume();
        AudioGuard.recoverIfStale(this);
        recoverInterruptedDirectCheckOnForeground();
        recoverStalePendingOnForeground();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(12, 13, 16));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(40));
        scroll.addView(root);

        root.addView(text("보이스룸 매니저", 28, true));
        TextView subtitle = text("v" + BuildConfig.VERSION_NAME + " · 카톡매크로와 완전히 분리된 별도 앱 · 현재 휴대폰의 카카오톡 계정을 사용", 12, false);
        subtitle.setTextColor(Color.rgb(145, 151, 164));
        root.addView(subtitle, top(5));

        LinearLayout info = card(Color.rgb(24, 28, 35));
        TextView privacy = text("카카오 아이디·비밀번호를 이 앱에 입력하지 않아.", 14, true);
        privacy.setTextColor(Color.rgb(111, 192, 255));
        info.addView(privacy);
        TextView privacy2 = text("공식 카카오톡 앱에 이미 로그인된 본인 계정으로, 등록한 오픈채팅방만 자동관리해.", 12, false);
        privacy2.setTextColor(Color.rgb(177, 183, 194));
        info.addView(privacy2, top(6));
        root.addView(info, top(16));

        LinearLayout master = card(Color.rgb(28, 31, 38));
        masterStatus = text("", 19, true);
        master.addView(masterStatus);
        systemStatus = text("", 12, false);
        systemStatus.setTextColor(Color.rgb(174, 180, 192));
        master.addView(systemStatus, top(7));

        LinearLayout masterButtons = new LinearLayout(this);
        masterButtons.setOrientation(LinearLayout.HORIZONTAL);
        startButton = button("전체 시작", Color.rgb(43, 132, 87));
        startButton.setOnClickListener(v -> startManager());
        masterButtons.addView(startButton, weight());
        stopButton = button("전체 중단", Color.rgb(145, 59, 67));
        stopButton.setOnClickListener(v -> stopManager());
        LinearLayout.LayoutParams stopLp = weight();
        stopLp.leftMargin = dp(8);
        masterButtons.addView(stopButton, stopLp);
        master.addView(masterButtons, top(14));
        root.addView(master, top(14));

        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.HORIZONTAL);
        section.setGravity(Gravity.CENTER_VERTICAL);
        section.addView(text("관리할 오픈채팅방", 18, true), weight());
        Button add = compactButton("＋ 방 추가", Color.rgb(55, 94, 164));
        add.setOnClickListener(v -> showRoomDialog(null));
        section.addView(add);
        root.addView(section, top(22));

        roomList = new LinearLayout(this);
        roomList.setOrientation(LinearLayout.VERTICAL);
        root.addView(roomList, top(5));

        root.addView(text("필수 설정", 18, true), top(24));
        LinearLayout tools1 = new LinearLayout(this);
        tools1.setOrientation(LinearLayout.HORIZONTAL);
        Button accessibility = compactButton("접근성 켜기", Color.rgb(64, 69, 80));
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        tools1.addView(accessibility, weight());
        Button exact = compactButton("정확 알람", Color.rgb(64, 69, 80));
        exact.setOnClickListener(v -> openExactAlarmSettings());
        LinearLayout.LayoutParams exactLp = weight();
        exactLp.leftMargin = dp(7);
        tools1.addView(exact, exactLp);
        root.addView(tools1, top(8));

        LinearLayout tools2 = new LinearLayout(this);
        tools2.setOrientation(LinearLayout.HORIZONTAL);
        Button battery = compactButton("배터리 설정", Color.rgb(64, 69, 80));
        battery.setOnClickListener(v -> openBatterySettings());
        tools2.addView(battery, weight());
        Button kakao = compactButton("카카오톡 열기", Color.rgb(64, 69, 80));
        kakao.setOnClickListener(v -> openKakao());
        LinearLayout.LayoutParams kakaoLp = weight();
        kakaoLp.leftMargin = dp(7);
        tools2.addView(kakao, kakaoLp);
        root.addView(tools2, top(7));

        Button screenOffTest = compactButton("20초 화면 OFF 자동점검 테스트", Color.rgb(52, 80, 122));
        screenOffTest.setOnClickListener(v -> scheduleQuickAutoTest());
        root.addView(screenOffTest, top(7));

        TextView note = text("‘관리 OFF’는 보이스룸 종료가 아니라 48시간 자동 재점검/재개설만 끈 상태야. 전체 시작 중에는 마이크·스피커 무음 상태를 보호하고, 카카오가 명확한 스피커 요청 차단 토글을 보여주면 자동으로 끄며, 들어온 스피커 요청은 ‘거절/거부’가 확실할 때만 자동 거절해.", 11, false);
        note.setTextColor(Color.rgb(132, 138, 150));
        root.addView(note, top(14));

        footerStatus = text("", 12, false);
        footerStatus.setTextColor(Color.rgb(174, 180, 192));
        root.addView(footerStatus, top(18));
        return scroll;
    }

    private void refreshUi() {
        if (masterStatus == null) return;
        boolean active = VoiceRoomStore.managerActive(this);
        boolean accessibility = VoiceRoomAccessibilityService.isEnabled(this);
        boolean exact = VoiceRoomScheduler.canExact(this);
        boolean kakao = getPackageManager().getLaunchIntentForPackage("com.kakao.talk") != null;
        List<VoiceRoomStore.Room> rooms = VoiceRoomStore.list(this);

        int liveCount = 0;
        int audioProtected = 0;
        for (VoiceRoomStore.Room room : rooms) {
            if (!room.liveCheckPassed) continue;
            liveCount += 1;
            if (room.micMuted && room.speakerMuted) audioProtected += 1;
        }

        if (active) {
            masterStatus.setText("● 보이스룸 자동관리 실행 중 · 요청 보호 ON");
            masterStatus.setTextColor(Color.rgb(93, 224, 148));
        } else if (liveCount > 0) {
            masterStatus.setText("● 보이스룸 " + liveCount + "개 활성 · 자동관리 꺼짐");
            masterStatus.setTextColor(Color.rgb(222, 190, 98));
        } else {
            masterStatus.setText("● 보이스룸 자동관리 중지됨");
            masterStatus.setTextColor(Color.rgb(218, 221, 227));
        }

        String audioSummary = liveCount <= 0 ? "" : "  ·  오디오 보호 " + audioProtected + "/" + liveCount;
        String requestSummary = active ? "  ·  스피커 요청 자동거절" : "";
        systemStatus.setText("접근성 " + (accessibility ? "정상" : "설정 필요")
                + "  ·  카카오톡 " + (kakao ? "확인" : "미설치")
                + "  ·  알람 " + (exact ? "정확" : "근사") + audioSummary + requestSummary);
        startButton.setEnabled(!active);
        stopButton.setEnabled(active);

        roomList.removeAllViews();
        if (rooms.isEmpty()) {
            LinearLayout empty = card(Color.rgb(28, 31, 38));
            empty.addView(text("아직 등록한 방이 없어", 16, true));
            TextView guide = text("방 이름을 정확히 입력하고, 가능하면 open.kakao.com 오픈채팅 링크도 같이 등록해줘.", 12, false);
            guide.setTextColor(Color.rgb(166, 172, 183));
            empty.addView(guide, top(6));
            roomList.addView(empty, top(7));
        } else {
            for (VoiceRoomStore.Room room : rooms) roomList.addView(roomCard(room), top(7));
        }

        String last = VoiceRoomStore.lastStatus(this);
        String busy = VoiceRoomStore.pendingRoomId(this).isEmpty() ? "" : " · 작업 처리 중";
        int rejected = VoiceRoomRuntimeGuard.rejectedCount(this);
        int requestBlocks = VoiceRoomRuntimeGuard.requestToggleDisableCount(this);
        int audioFixes = VoiceRoomRuntimeGuard.passiveAudioFixCount(this);
        String guard = "\n런타임 보호  요청거절 " + rejected + "회 · 요청차단 " + requestBlocks
                + "회 · 오디오 재보호 " + audioFixes + "회";
        footerStatus.setText("v" + BuildConfig.VERSION_NAME + " · 등록 " + rooms.size() + "개" + busy
                + guard + (last.isEmpty() ? "" : "\n최근  " + last));
    }

    private View roomCard(VoiceRoomStore.Room room) {
        LinearLayout card = card(Color.rgb(29, 32, 39));
        card.setOnClickListener(v -> showRoomDialog(room));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.addView(text(room.title, 17, true), weight());
        TextView badge = pill(room.enabled ? "관리 ON" : "관리 OFF",
                room.enabled ? Color.rgb(40, 122, 81) : Color.rgb(78, 82, 92));
        topRow.addView(badge);
        card.addView(topRow);

        TextView status = text(statusLabel(room.status) + "  ·  " + remainingLabel(room), 12, true);
        status.setTextColor(statusColor(room.status));
        card.addView(status, top(7));

        String verification = "검증  "
                + (room.safeProbePassed ? "안전 ✓" : "안전 필요")
                + "  ·  "
                + (room.liveCheckPassed ? "실제 활성 ✓" : "실제 필요");
        TextView verified = text(verification, 11, true);
        verified.setTextColor(room.liveCheckPassed
                ? Color.rgb(102, 205, 145) : Color.rgb(198, 171, 104));
        card.addView(verified, top(5));

        if (room.liveCheckPassed) {
            String audio = "오디오  마이크 " + (room.micMuted ? "✓" : "확인 필요")
                    + "  ·  스피커 " + (room.speakerMuted ? "✓" : "확인 필요");
            TextView audioView = text(audio, 11, true);
            audioView.setTextColor(room.micMuted && room.speakerMuted
                    ? Color.rgb(102, 205, 145) : Color.rgb(222, 190, 98));
            card.addView(audioView, top(5));

            TextView requestGuard = text("스피커 요청  "
                    + (VoiceRoomStore.managerActive(this) && room.enabled
                    ? "자동 차단/거절 보호 중" : "전체 시작 시 자동 보호"), 11, true);
            requestGuard.setTextColor(VoiceRoomStore.managerActive(this) && room.enabled
                    ? Color.rgb(102, 205, 145) : Color.rgb(155, 161, 173));
            card.addView(requestGuard, top(5));
        }

        if (room.liveCheckPassed && !room.enabled) {
            TextView warning = text("보이스룸은 켜져 있지만 48시간 자동 재개설은 꺼져 있어.", 10, false);
            warning.setTextColor(Color.rgb(222, 190, 98));
            card.addView(warning, top(5));
        }

        if (room.nextCheckAt > 0L && room.liveCheckPassed) {
            TextView next = text("다음 확인  " + date(room.nextCheckAt), 11, false);
            next.setTextColor(Color.rgb(155, 161, 173));
            card.addView(next, top(5));
        }
        if (room.lastError != null && !room.lastError.isEmpty()) {
            TextView error = text(room.lastError, 11, false);
            error.setTextColor(Color.rgb(235, 126, 126));
            card.addView(error, top(5));
        }
        if (room.lastDiagnostic != null && !room.lastDiagnostic.isEmpty()
                && ("ERROR".equals(room.status) || "PROBE_ERROR".equals(room.status)
                || "MANUAL_ERROR".equals(room.status))) {
            TextView diag = text("진단  " + room.lastDiagnostic, 10, false);
            diag.setTextColor(Color.rgb(142, 151, 170));
            card.addView(diag, top(5));
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button toggle = compactButton(room.enabled ? "관리 끄기" : "관리 켜기", Color.rgb(65, 69, 80));
        toggle.setOnClickListener(v -> {
            VoiceRoomStore.Room current = VoiceRoomStore.get(this, room.id);
            if (current == null) return;
            current.enabled = !current.enabled;
            if (current.enabled && current.liveCheckPassed && current.nextCheckAt <= 0L) {
                current.nextCheckAt = System.currentTimeMillis() + 3_000L;
            }
            VoiceRoomStore.update(this, current);
            VoiceRoomScheduler.scheduleNext(this);
            toast(current.enabled
                    ? "이 방의 자동 재점검/재개설과 스피커 요청 보호를 사용해."
                    : "보룸은 유지하고 자동 재개설/요청 보호만 껐어.");
            refreshUi();
        });
        actions.addView(toggle, weight());

        String probeLabel = room.safeProbePassed ? "안전 ✓" : "안전 점검";
        Button probe = compactButton(probeLabel, Color.rgb(73, 91, 126));
        probe.setOnClickListener(v -> startSafeProbe(room));
        LinearLayout.LayoutParams probeLp = weight();
        probeLp.leftMargin = dp(6);
        actions.addView(probe, probeLp);

        String liveLabel;
        if ("MANUAL_RUNNING".equals(room.status)) liveLabel = "점검 중…";
        else if (room.liveCheckPassed && (!room.micMuted || !room.speakerMuted)) liveLabel = "오디오 확인";
        else liveLabel = room.liveCheckPassed ? "실제 ✓" : "실제 점검";
        Button check = compactButton(liveLabel, Color.rgb(58, 91, 151));
        check.setEnabled(!VoiceRoomStore.hasFreshPending(this));
        check.setOnClickListener(v -> confirmImmediateCheck(room));
        LinearLayout.LayoutParams checkLp = weight();
        checkLp.leftMargin = dp(6);
        actions.addView(check, checkLp);

        card.addView(actions, top(10));
        return card;
    }

    private void startSafeProbe(VoiceRoomStore.Room room) {
        if (VoiceRoomStore.managerActive(this)) {
            toast("안전 점검은 전체 자동관리를 잠깐 중단한 뒤 실행해줘.");
            return;
        }
        if (!preflightForDirectCheck()) return;
        if (VoiceRoomStore.hasFreshPending(this)) {
            toast("이미 다른 점검을 처리 중이야.");
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("안전 인식 점검")
                .setMessage("‘" + room.title + "’ 방을 열고 보이스룸 생성 화면 또는 기존 활성 상태까지만 확인해. 새 보이스룸 이름 입력/생성은 하지 않아.")
                .setNegativeButton("취소", null)
                .setPositiveButton("점검 시작", (d, w) -> launchSafeProbeFromForeground(room))
                .show();
    }

    private boolean preflightForDirectCheck() {
        if (!VoiceRoomAccessibilityService.isEnabled(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("접근성 설정 필요")
                    .setMessage("점검에는 ‘보이스룸 자동화’ 접근성 서비스가 필요해.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("설정 열기", (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .show();
            return false;
        }
        if (getPackageManager().getLaunchIntentForPackage("com.kakao.talk") == null) {
            toast("카카오톡이 설치되어 있지 않아.");
            return false;
        }
        return true;
    }

    private void launchSafeProbeFromForeground(VoiceRoomStore.Room room) {
        VoiceRoomStore.Room current = VoiceRoomStore.get(this, room.id);
        if (current == null) {
            toast("등록된 방을 찾지 못했어.");
            return;
        }
        if (!validRoomLinkOrMarkError(current, true)) return;

        long now = System.currentTimeMillis();
        current.status = "PROBE_OPENING_KAKAO";
        current.stageStartedAt = now;
        current.lastError = "";
        current.lastDiagnostic = "";
        VoiceRoomStore.update(this, current);
        VoiceRoomStore.setPending(this, current.id, VoiceRoomStore.MODE_PROBE, VoiceRoomStore.ENTRY_UNKNOWN);
        VoiceRoomStore.setLastStatus(this, current.title + " · 전경 안전 점검 시작");

        Exception last = null;
        if (KakaoUiPolicy.isOpenChatUrl(current.roomUrl)) {
            try {
                VoiceRoomStore.updatePendingEntry(this, VoiceRoomStore.ENTRY_DEEPLINK);
                Intent deepLink = new Intent(Intent.ACTION_VIEW, Uri.parse(current.roomUrl.trim()));
                deepLink.setPackage("com.kakao.talk");
                startActivity(deepLink);
                toast("안전 인식 점검을 시작했어.");
                return;
            } catch (Exception e) {
                last = e;
            }
        }

        try {
            VoiceRoomStore.updatePendingEntry(this, VoiceRoomStore.ENTRY_LAUNCHER);
            Intent launcher = getPackageManager().getLaunchIntentForPackage("com.kakao.talk");
            if (launcher == null) throw new IllegalStateException("카카오톡 런처 없음");
            startActivity(launcher);
            toast("안전 인식 점검을 시작했어.");
            return;
        } catch (Exception e) {
            last = e;
        }

        VoiceRoomStore.clearPending(this);
        current.status = "PROBE_ERROR";
        current.stageStartedAt = 0L;
        current.lastError = "카카오톡 실행 실패 · " + shortError(last);
        VoiceRoomStore.update(this, current);
        VoiceRoomStore.setLastStatus(this, current.title + " · " + current.lastError);
        refreshUi();
    }

    private void confirmImmediateCheck(VoiceRoomStore.Room room) {
        if (!preflightForDirectCheck()) return;
        if (VoiceRoomStore.hasFreshPending(this)) {
            toast("이미 다른 점검을 처리 중이야.");
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("실제 보이스룸 점검")
                .setMessage("‘" + room.title + "’ 방을 지금 즉시 확인해. 보이스룸이 없으면 새로 만들고, 이미 켜져 있으면 실제 활성 상태를 확인해. 이어서 카카오 내부 마이크·스피커를 안전 상태로 맞출 수 있는지도 확인해. 전체 자동관리 ON/OFF는 바꾸지 않아.")
                .setNegativeButton("취소", null)
                .setPositiveButton("지금 실행", (d, w) -> startManualLiveCheck(room))
                .show();
    }

    private void startManualLiveCheck(VoiceRoomStore.Room room) {
        VoiceRoomStore.Room current = VoiceRoomStore.get(this, room.id);
        if (current == null) return;
        if (!validRoomLinkOrMarkError(current, false)) return;
        if (VoiceRoomStore.hasFreshPending(this)) {
            toast("이미 다른 점검을 처리 중이야.");
            return;
        }

        VoiceRoomScheduler.cancel(this);
        long now = System.currentTimeMillis();
        current.status = "MANUAL_RUNNING";
        current.stageStartedAt = now;
        current.lastError = "";
        current.lastDiagnostic = "";
        VoiceRoomStore.update(this, current);
        VoiceRoomStore.setLastStatus(this, current.title + " · 수동 실제 점검 즉시 실행");

        Intent wake = new Intent(this, WakeActivity.class);
        wake.putExtra(VoiceRoomScheduler.EXTRA_ROOM_ID, current.id);
        wake.putExtra(VoiceRoomScheduler.EXTRA_MANUAL, true);
        wake.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(wake);
            toast("실제 점검을 지금 시작해.");
        } catch (Exception e) {
            current.status = "MANUAL_ERROR";
            current.stageStartedAt = 0L;
            current.lastError = "실제 점검 실행 실패 · " + shortError(e);
            VoiceRoomStore.update(this, current);
            VoiceRoomStore.setLastStatus(this, current.title + " · " + current.lastError);
            VoiceRoomScheduler.scheduleNext(this);
            refreshUi();
        }
    }

    private boolean validRoomLinkOrMarkError(VoiceRoomStore.Room room, boolean probe) {
        if (room.roomUrl == null || room.roomUrl.trim().isEmpty()
                || KakaoUiPolicy.isOpenChatUrl(room.roomUrl)) return true;
        room.status = probe ? "PROBE_ERROR" : "MANUAL_ERROR";
        room.lastError = "오픈채팅 링크 형식이 올바르지 않아. open.kakao.com 링크를 사용해줘.";
        room.stageStartedAt = 0L;
        VoiceRoomStore.update(this, room);
        refreshUi();
        return false;
    }

    private String shortError(Exception error) {
        if (error == null) return "원인 미확인";
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        message = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (message.length() > 100) message = message.substring(0, 100);
        return error.getClass().getSimpleName() + ": " + message;
    }

    private void showRoomDialog(VoiceRoomStore.Room existing) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        wrap.setPadding(pad, dp(6), pad, 0);

        EditText title = new EditText(this);
        title.setHint("오픈채팅방 이름");
        title.setSingleLine(true);
        title.setText(existing == null ? "" : existing.title);
        wrap.addView(title);

        EditText url = new EditText(this);
        url.setHint("open.kakao.com 오픈채팅 링크 (선택, 권장)");
        url.setSingleLine(true);
        url.setText(existing == null ? "" : existing.roomUrl);
        wrap.addView(url, top(8));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "방 추가" : "방 수정")
                .setView(wrap)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", null);
        if (existing != null) builder.setNeutralButton("삭제", null);
        AlertDialog dialog = builder.create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String t = title.getText().toString().trim();
                String u = url.getText().toString().trim();
                if (t.isEmpty()) {
                    title.setError("방 이름을 입력해줘.");
                    return;
                }
                if (!u.isEmpty() && !KakaoUiPolicy.isOpenChatUrl(u)) {
                    url.setError("https://open.kakao.com/... 링크를 넣어줘.");
                    return;
                }
                if (existing == null) {
                    VoiceRoomStore.add(this, t, u);
                } else {
                    VoiceRoomStore.Room current = VoiceRoomStore.get(this, existing.id);
                    if (current == null) return;
                    boolean identityChanged = !t.equals(current.title) || !u.equals(current.roomUrl);
                    current.title = t;
                    current.roomUrl = u;
                    if (identityChanged) {
                        current.safeProbePassed = false;
                        current.liveCheckPassed = false;
                        current.verifiedAt = 0L;
                        current.startedAt = 0L;
                        current.nextCheckAt = 0L;
                        current.failures = 0;
                        current.status = "NEW";
                        current.stageStartedAt = 0L;
                        current.lastError = "";
                        current.lastDiagnostic = "";
                        current.micMuted = false;
                        current.speakerMuted = false;
                        current.audioCheckedAt = 0L;
                    }
                    VoiceRoomStore.update(this, current);
                }
                VoiceRoomScheduler.scheduleNext(this);
                dialog.dismiss();
                refreshUi();
            });
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle("방 삭제")
                        .setMessage("‘" + existing.title + "’ 자동관리 설정을 삭제할까?")
                        .setNegativeButton("취소", null)
                        .setPositiveButton("삭제", (d, w) -> {
                            VoiceRoomStore.remove(this, existing.id);
                            VoiceRoomScheduler.scheduleNext(this);
                            dialog.dismiss();
                            refreshUi();
                        }).show());
            }
        });
        dialog.show();
    }

    private void startManager() {
        if (!preflightForDirectCheck()) return;
        if (VoiceRoomStore.hasFreshPending(this)) {
            toast("진행 중인 점검이 끝난 뒤 시작해줘.");
            return;
        }

        List<VoiceRoomStore.Room> rooms = VoiceRoomStore.list(this);
        boolean anyEnabled = false;
        boolean audioNeedsAttention = false;
        StringBuilder unverified = new StringBuilder();
        for (VoiceRoomStore.Room room : rooms) {
            if (!room.enabled) continue;
            anyEnabled = true;
            if (!room.liveCheckPassed) {
                if (unverified.length() > 0) unverified.append(", ");
                unverified.append(room.title);
            } else if (!room.micMuted || !room.speakerMuted) {
                audioNeedsAttention = true;
            }
        }
        if (!anyEnabled) {
            toast("자동관리할 방을 먼저 추가하거나 관리 ON으로 켜줘.");
            return;
        }
        if (unverified.length() > 0) {
            new AlertDialog.Builder(this)
                    .setTitle("실제 점검이 먼저 필요해")
                    .setMessage("다음 관리 ON 방은 아직 실제 생성/활성 검증을 통과하지 않았어:\n\n"
                            + unverified + "\n\n각 방의 ‘실제 점검’을 1회 성공시킨 뒤 전체 시작을 눌러줘.")
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }

        long now = System.currentTimeMillis();
        for (VoiceRoomStore.Room room : rooms) {
            if (!room.enabled || !room.liveCheckPassed) continue;
            if (room.nextCheckAt <= 0L) {
                room.nextCheckAt = now + 3_000L;
                VoiceRoomStore.update(this, room);
            }
        }
        VoiceRoomStore.setManagerActive(this, true);
        VoiceRoomStore.setLastStatus(this, audioNeedsAttention
                ? "자동관리/스피커 요청 보호 시작 · 일부 방 오디오 상태는 다음 점검에서 재확인"
                : "검증된 방 자동관리 시작 · 스피커 요청 자동 차단/거절 보호 활성");
        VoiceRoomScheduler.scheduleNext(this);
        refreshUi();
    }

    private void stopManager() {
        VoiceRoomStore.setManagerActive(this, false);
        VoiceRoomStore.clearPending(this);
        VoiceRoomScheduler.cancel(this);
        VoiceRoomStore.setLastStatus(this,
                "보이스룸 자동관리/스피커 요청 보호 중단 · 현재 보룸 자체는 종료하지 않음");
        refreshUi();
    }

    private void scheduleQuickAutoTest() {
        if (!VoiceRoomStore.managerActive(this)) {
            toast("먼저 관리할 방을 관리 ON으로 두고 ‘전체 시작’을 켜줘.");
            return;
        }
        if (VoiceRoomStore.hasFreshPending(this)) {
            toast("현재 작업이 끝난 뒤 테스트해줘.");
            return;
        }
        VoiceRoomStore.Room target = null;
        for (VoiceRoomStore.Room room : VoiceRoomStore.list(this)) {
            if (room.enabled && room.liveCheckPassed) {
                target = room;
                break;
            }
        }
        if (target == null) {
            toast("관리 ON + 실제 활성 ✓ 방이 필요해.");
            return;
        }
        target.nextCheckAt = System.currentTimeMillis() + 20_000L;
        VoiceRoomStore.update(this, target);
        VoiceRoomStore.setLastStatus(this,
                target.title + " · 20초 후 화면 OFF 자동점검 예약 · 지금 화면을 꺼도 돼");
        VoiceRoomScheduler.scheduleNext(this);
        toast("20초 안에 화면을 꺼둬. 자동으로 깨워 점검하는지 확인해.");
        refreshUi();
    }

    private void recoverInterruptedDirectCheckOnForeground() {
        String pendingId = VoiceRoomStore.pendingRoomId(this);
        if (pendingId.isEmpty()) return;
        boolean probe = VoiceRoomStore.isProbePending(this);
        boolean manual = VoiceRoomStore.isManualPending(this);
        if (!probe && !manual) return;
        long age = System.currentTimeMillis() - VoiceRoomStore.pendingAt(this);
        if (age < 2_000L) return;

        VoiceRoomStore.Room room = VoiceRoomStore.get(this, pendingId);
        VoiceRoomStore.clearPending(this);
        if (room == null) return;
        room.stageStartedAt = 0L;
        room.status = probe ? "PROBE_ERROR" : "MANUAL_ERROR";
        room.lastError = probe
                ? "안전 점검이 사용자 화면 복귀로 중단됨"
                : "실제 점검이 사용자 화면 복귀로 중단됨";
        VoiceRoomStore.update(this, room);
        VoiceRoomStore.setLastStatus(this, room.title + " · 점검 중단 정리 완료");
        VoiceRoomScheduler.scheduleNext(this);
    }

    private void recoverStalePendingOnForeground() {
        String pendingId = VoiceRoomStore.pendingRoomId(this);
        if (pendingId.isEmpty() || VoiceRoomStore.hasFreshPending(this)) return;
        boolean probe = VoiceRoomStore.isProbePending(this);
        boolean manual = VoiceRoomStore.isManualPending(this);
        VoiceRoomStore.Room room = VoiceRoomStore.get(this, pendingId);
        VoiceRoomStore.clearPending(this);
        if (room == null) return;
        room.stageStartedAt = 0L;
        if (probe) {
            room.status = "PROBE_ERROR";
            room.lastError = "이전 안전 점검이 응답 없이 종료되어 자동 복구됨";
        } else if (manual) {
            room.status = "MANUAL_ERROR";
            room.lastError = "이전 실제 점검이 응답 없이 종료되어 자동 복구됨";
        } else {
            room.failures += 1;
            room.status = "ERROR";
            room.lastError = "이전 자동화 작업이 응답 없이 종료되어 자동 복구됨";
            room.nextCheckAt = System.currentTimeMillis() + KakaoUiPolicy.retryDelayMs(room.failures);
        }
        VoiceRoomStore.update(this, room);
        VoiceRoomStore.setLastStatus(this, room.title + " · 중단된 작업 자동 복구");
        VoiceRoomScheduler.scheduleNext(this);
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < 31) {
            toast("이 Android 버전에서는 별도 정확 알람 설정이 필요하지 않아.");
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void openKakao() {
        Intent i = getPackageManager().getLaunchIntentForPackage("com.kakao.talk");
        if (i == null) toast("카카오톡이 설치되어 있지 않아.");
        else startActivity(i);
    }

    private String remainingLabel(VoiceRoomStore.Room room) {
        if (room.startedAt <= 0L) return "시작시간 확인 전";
        long remain = room.startedAt + VoiceRoomStore.VOICE_ROOM_LIFETIME_MS - System.currentTimeMillis();
        if (remain <= 0L) return "종료 확인 중";
        long h = remain / 3_600_000L;
        long m = (remain % 3_600_000L) / 60_000L;
        return h + "시간 " + String.format(Locale.KOREA, "%02d", m) + "분";
    }

    private String statusLabel(String status) {
        if (status == null) return "대기";
        switch (status) {
            case "ACTIVE": return "보룸 활성";
            case "ACTIVE_UNKNOWN_START": return "보룸 활성 · 시작시간 확인 중";
            case "AUDIO_GUARD_CREATED":
            case "AUDIO_GUARD_EXISTING": return "보룸 활성 · 오디오 보호 확인 중";
            case "CREATING": return "재개설 준비 중";
            case "CREATING_NAMED": return "이름 입력 완료 · 생성 중";
            case "CREATING_CONFIRMING": return "생성 확인 중";
            case "MANUAL_RUNNING": return "실제 점검 시작 중";
            case "OPENING_KAKAO": return "카카오톡 여는 중";
            case "OPENING_ROOM": return "방 진입 중";
            case "ROOM_VERIFIED": return "대상 방 확인됨";
            case "ROOM_MENU": return "하단 + 메뉴 확인 중";
            case "VOICE_MENU": return "보이스룸 화면 확인 중";
            case "WAITING_UNLOCK": return "잠금 해제 대기";
            case "ERROR": return "오류 · 재시도 예정";
            case "MANUAL_ERROR": return "실제 점검 실패";
            case "CHECK_DUE": return "점검 대기";
            case "PROBE_OPENING_KAKAO": return "안전 점검 · 카카오톡 여는 중";
            case "PROBE_OPENING_ROOM": return "안전 점검 · 방 진입 중";
            case "PROBE_ROOM_VERIFIED": return "안전 점검 · 대상 방 확인됨";
            case "PROBE_ROOM_MENU": return "안전 점검 · 하단 + 확인 중";
            case "PROBE_VOICE_MENU": return "안전 점검 · 보이스룸 화면 확인 중";
            case "PROBE_OK": return "안전 점검 성공";
            case "PROBE_ERROR": return "안전 점검 실패";
            case "NEW": return "검증 필요";
            default: return "대기";
        }
    }

    private int statusColor(String status) {
        if ("ACTIVE".equals(status) || "ACTIVE_UNKNOWN_START".equals(status)
                || "PROBE_OK".equals(status)) {
            return Color.rgb(90, 220, 145);
        }
        if ("ERROR".equals(status) || "WAITING_UNLOCK".equals(status)
                || "PROBE_ERROR".equals(status) || "MANUAL_ERROR".equals(status)) {
            return Color.rgb(235, 137, 102);
        }
        return Color.rgb(164, 177, 205);
    }

    private String date(long value) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(new Date(value));
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sp);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private TextView pill(String value, int color) {
        TextView v = text(value, 11, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), dp(5), dp(10), dp(5));
        v.setBackground(round(color, 99));
        return v;
    }

    private Button button(String value, int color) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(dp(48));
        b.setBackground(round(color, 12));
        return b;
    }

    private Button compactButton(String value, int color) {
        Button b = button(value, color);
        b.setMinHeight(dp(42));
        b.setTextSize(11);
        b.setPadding(dp(7), 0, dp(7), 0);
        return b;
    }

    private LinearLayout card(int color) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(14), dp(14), dp(14));
        l.setBackground(round(color, 16));
        return l;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LinearLayout.LayoutParams top(int value) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(value);
        return lp;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
