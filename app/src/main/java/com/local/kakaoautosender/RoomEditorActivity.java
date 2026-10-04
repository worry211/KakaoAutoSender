package com.local.kakaoautosender;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class RoomEditorActivity extends Activity {
    static final String EXTRA_ROOM = "room";
    private static final int REQUEST_IMAGE = 4102;

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

    private String routeAlias;
    private EditText messageInput;
    private EditText intervalInput;
    private EditText timesInput;
    private EditText dailyLimitInput;
    private CheckBox unlimitedCheck;
    private CheckBox enabledCheck;
    private RadioButton intervalRadio;
    private RadioButton timesRadio;
    private LinearLayout intervalBox;
    private LinearLayout timesBox;
    private TextView connectionStatus;
    private TextView nextPreview;
    private TextView imageStatus;
    private android.widget.ImageView imagePreview;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        routeAlias = getIntent().getStringExtra(EXTRA_ROOM);
        if (routeAlias == null) routeAlias = "";
        routeAlias = routeAlias.trim();
        if (routeAlias.isEmpty()) { finish(); return; }

        MultiRoomStore.ensureMigrated(this);
        if (MultiRoomStore.get(this, routeAlias) == null) { finish(); return; }
        setContentView(buildUi());
        loadProfile();
    }

    @Override protected void onResume() {
        super.onResume();
        KakaoNotificationListener.requestRefresh();
        refreshConnection();
        refreshMedia();
        refreshNextPreview();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(44));
        scroll.addView(root);

        MultiRoomStore.Profile p = MultiRoomStore.get(this, routeAlias);
        String roomName = p == null ? "방 설정" : p.title();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.TOP);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView roomTitle = text(roomName, 25, true, TEXT);
        roomTitle.setMaxLines(2);
        roomTitle.setLetterSpacing(-0.01f);
        titleBox.addView(roomTitle);
        TextView headerSub = text("ROOM AUTOMATION SETTINGS", 9, true, Color.rgb(121, 136, 166));
        headerSub.setLetterSpacing(0.1f);
        titleBox.addView(headerSub, topWrap(5));
        header.addView(titleBox, weight());
        header.addView(pill("ROOM", Color.rgb(29, 38, 65), Color.rgb(180, 195, 255)));
        root.addView(header);

        TextView subtitle = text("메시지, 이미지, 스케줄을 이 방에만 독립적으로 적용합니다.", 12, false, MUTED);
        root.addView(subtitle, top(9));

        LinearLayout connectionCard = card(SURFACE, BORDER, 18);
        LinearLayout connectionTop = new LinearLayout(this);
        connectionTop.setOrientation(LinearLayout.HORIZONTAL);
        connectionTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView connectionLabel = text("연결 상태", 10, true, Color.rgb(124, 139, 168));
        connectionLabel.setLetterSpacing(0.08f);
        connectionTop.addView(connectionLabel, weight());
        connectionTop.addView(pill("SAFE ROUTING", Color.rgb(25, 49, 42), Color.rgb(122, 231, 176)));
        connectionCard.addView(connectionTop);
        connectionStatus = text("", 14, true, TEXT);
        connectionCard.addView(connectionStatus, top(12));
        Button reconnect = secondaryButton("이 방 연결 다시 확인");
        reconnect.setOnClickListener(v -> reconnectRoom());
        connectionCard.addView(reconnect, top(11));
        root.addView(connectionCard, top(22));

        root.addView(section("보낼 메시지", "실제 전송될 텍스트를 입력하세요."), top(26));
        messageInput = edit("자동으로 보낼 메시지", true);
        root.addView(messageInput, top(10));

        root.addView(section("이미지 첨부", "선택 사항 · 지원되는 카카오 답장 액션에서만 전송됩니다."), top(26));
        LinearLayout imageCard = card(SURFACE, BORDER, 18);
        imagePreview = new android.widget.ImageView(this);
        imagePreview.setAdjustViewBounds(true);
        imagePreview.setMaxHeight(dp(190));
        imagePreview.setContentDescription("선택한 사진 미리보기");
        imageCard.addView(imagePreview);
        imageStatus = text("사진 없음", 13, true, Color.rgb(183, 193, 212));
        imageCard.addView(imageStatus, top(8));
        LinearLayout imageButtons = new LinearLayout(this);
        imageButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button choose = primaryButton("사진 선택");
        choose.setOnClickListener(v -> chooseImage());
        imageButtons.addView(choose, weight());
        Button remove = dangerSecondaryButton("사진 제거");
        remove.setOnClickListener(v -> {
            RoomMediaStore.Media old = RoomMediaStore.get(this, routeAlias);
            RoomMediaStore.clear(this, routeAlias);
            releasePersistedReadPermission(old.uri);
            refreshMedia();
            toast("사진 첨부를 제거했습니다.");
        });
        LinearLayout.LayoutParams removeLp = weight();
        removeLp.leftMargin = dp(8);
        imageButtons.addView(remove, removeLp);
        imageCard.addView(imageButtons, top(11));
        TextView imageHelp = text("이미지 첨부를 지원하지 않는 환경에서는 텍스트만 임의로 보내지 않고 전송을 실패 처리해 오동작을 막습니다.", 11, false, Color.rgb(119, 132, 156));
        imageHelp.setLineSpacing(0, 1.14f);
        imageCard.addView(imageHelp, top(9));
        root.addView(imageCard, top(10));

        root.addView(section("전송 스케줄", "간격 반복 또는 매일 지정 시각 중 하나를 선택하세요."), top(26));
        LinearLayout scheduleCard = card(SURFACE, BORDER, 18);
        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.HORIZONTAL);
        intervalRadio = new RadioButton(this);
        intervalRadio.setText("간격 반복");
        intervalRadio.setTextColor(TEXT);
        intervalRadio.setTextSize(13);
        timesRadio = new RadioButton(this);
        timesRadio.setText("매일 지정 시각");
        timesRadio.setTextColor(TEXT);
        timesRadio.setTextSize(13);
        modes.addView(intervalRadio, new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
        modes.addView(timesRadio, new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
        scheduleCard.addView(modes);

        intervalBox = new LinearLayout(this);
        intervalBox.setOrientation(LinearLayout.VERTICAL);
        intervalInput = edit("간격(분) · 최소 1분", false);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalBox.addView(intervalInput);

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        int[] values = {1, 5, 10, 30, 60};
        String[] labels = {"1분", "5분", "10분", "30분", "1시간"};
        for (int i = 0; i < values.length; i++) {
            final int value = values[i];
            Button b = miniButton(labels[i]);
            b.setOnClickListener(v -> {
                intervalInput.setText(String.valueOf(value));
                refreshNextPreview();
            });
            LinearLayout.LayoutParams lp = weight();
            if (i > 0) lp.leftMargin = dp(5);
            presets.addView(b, lp);
        }
        intervalBox.addView(presets, top(8));
        TextView intervalHelp = text("설정한 간격에 3~10초의 안정화 분산을 더해 여러 방이 같은 순간에 몰리지 않도록 합니다.", 11, false, Color.rgb(119, 132, 156));
        intervalBox.addView(intervalHelp, top(8));
        scheduleCard.addView(intervalBox, top(12));

        timesBox = new LinearLayout(this);
        timesBox.setOrientation(LinearLayout.VERTICAL);
        timesInput = edit("예: 09:00, 13:30, 20:00", false);
        timesBox.addView(timesInput);
        TextView timesHelp = text("쉼표나 공백으로 여러 시각을 입력할 수 있습니다. 여러 방이 같은 시각에 겹치면 방 사이를 2~5초씩 나눠 전송합니다.", 11, false, Color.rgb(119, 132, 156));
        timesBox.addView(timesHelp, top(8));
        scheduleCard.addView(timesBox, top(12));

        modes.setOnCheckedChangeListener((group, checkedId) -> {
            updateScheduleVisibility();
            refreshNextPreview();
        });

        nextPreview = text("", 12, true, Color.rgb(164, 182, 255));
        LinearLayout previewCard = card(Color.rgb(14, 18, 27), Color.rgb(35, 44, 61), 13);
        previewCard.addView(nextPreview);
        scheduleCard.addView(previewCard, top(12));
        root.addView(scheduleCard, top(10));

        root.addView(section("사용 범위", "하루 전송 횟수와 이 방의 사용 여부를 관리합니다."), top(26));
        LinearLayout usageCard = card(SURFACE, BORDER, 18);
        LinearLayout limitRow = new LinearLayout(this);
        limitRow.setOrientation(LinearLayout.HORIZONTAL);
        limitRow.setGravity(Gravity.CENTER_VERTICAL);
        dailyLimitInput = edit("하루 최대 횟수", false);
        dailyLimitInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        limitRow.addView(dailyLimitInput, weight());
        unlimitedCheck = new CheckBox(this);
        unlimitedCheck.setText("무제한");
        unlimitedCheck.setTextColor(TEXT);
        unlimitedCheck.setOnCheckedChangeListener((b, checked) -> dailyLimitInput.setEnabled(!checked));
        LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        uLp.leftMargin = dp(12);
        limitRow.addView(unlimitedCheck, uLp);
        usageCard.addView(limitRow);

        enabledCheck = new CheckBox(this);
        enabledCheck.setText("이 방 자동전송 사용");
        enabledCheck.setTextColor(TEXT);
        enabledCheck.setTextSize(13);
        usageCard.addView(enabledCheck, top(12));
        root.addView(usageCard, top(10));

        LinearLayout actionCard = card(SURFACE_2, BORDER, 18);
        TextView actionTitle = text("변경 사항", 11, true, Color.rgb(128, 142, 169));
        actionTitle.setLetterSpacing(0.06f);
        actionCard.addView(actionTitle);
        Button save = primaryButton("설정 저장");
        save.setOnClickListener(v -> saveProfile(true));
        actionCard.addView(save, top(11));
        Button test = positiveSecondaryButton("지금 1회 테스트 전송");
        test.setOnClickListener(v -> testSend());
        actionCard.addView(test, top(8));
        Button back = secondaryButton("대시보드로 돌아가기");
        back.setOnClickListener(v -> finish());
        actionCard.addView(back, top(8));
        root.addView(actionCard, top(26));

        Button delete = dangerSecondaryButton("이 방 삭제");
        delete.setOnClickListener(v -> deleteProfile());
        root.addView(delete, top(10));

        TextView footer = text("설정 저장 전에는 기존 자동전송 설정이 변경되지 않습니다.", 11, false, Color.rgb(105, 118, 141));
        footer.setGravity(Gravity.CENTER);
        root.addView(footer, top(16));
        return scroll;
    }

    private void loadProfile() {
        MultiRoomStore.Profile p = MultiRoomStore.get(this, routeAlias);
        if (p == null) { finish(); return; }
        messageInput.setText(p.message);
        intervalInput.setText(String.valueOf(p.intervalMinutes));
        timesInput.setText(p.dailyTimes);
        if (p.fixedTimes()) timesRadio.setChecked(true); else intervalRadio.setChecked(true);
        unlimitedCheck.setChecked(p.unlimited());
        dailyLimitInput.setText(p.unlimited() ? "8" : String.valueOf(p.dailyLimit));
        dailyLimitInput.setEnabled(!p.unlimited());
        enabledCheck.setChecked(p.enabled);
        updateScheduleVisibility();
        refreshConnection();
        refreshMedia();
        refreshNextPreview();
    }

    private void chooseImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQUEST_IMAGE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = displayName(uri);
        String mime = resolveConcreteImageMime(getContentResolver().getType(uri), name);
        if (mime == null) {
            toast("사진 형식을 확인할 수 없습니다. JPG, PNG, WEBP 같은 일반 이미지로 다시 선택해 주세요.");
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {}
        RoomMediaStore.Media old = RoomMediaStore.get(this, routeAlias);
        RoomMediaStore.set(this, routeAlias, uri.toString(), mime, name);
        if (!old.uri.equals(uri.toString())) releasePersistedReadPermission(old.uri);
        refreshMedia();
        toast("사진을 저장했습니다. 판매 전에는 1회 테스트 전송으로 확인해 주세요.");
    }

    static String resolveConcreteImageMime(String resolverMime, String displayName) {
        String direct = normalizeConcreteImageMime(resolverMime);
        if (direct != null) return direct;
        String name = displayName == null ? "" : displayName.trim();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return normalizeConcreteImageMime(MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension));
    }

    private static String normalizeConcreteImageMime(String mime) {
        if (mime == null) return null;
        String value = mime.trim().toLowerCase(Locale.ROOT);
        if (!value.startsWith("image/") || value.contains("*") || value.length() <= "image/".length()) return null;
        return value;
    }

    private void releasePersistedReadPermission(String rawUri) {
        if (rawUri == null || rawUri.trim().isEmpty()) return;
        try {
            Uri uri = Uri.parse(rawUri);
            if ("content".equals(uri.getScheme())) {
                getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (Throwable ignored) {}
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        String tail = uri.getLastPathSegment();
        return tail == null || tail.trim().isEmpty() ? "선택한 사진" : tail;
    }

    private void refreshMedia() {
        if (imageStatus == null) return;
        RoomMediaStore.Media media = RoomMediaStore.get(this, routeAlias);
        if (!media.hasImage()) {
            imagePreview.setImageDrawable(null);
            imagePreview.setVisibility(View.GONE);
            imageStatus.setText("사진 없음 · 텍스트만 전송");
            imageStatus.setTextColor(Color.rgb(174, 180, 191));
        } else {
            imagePreview.setVisibility(View.VISIBLE);
            final String expectedUri = media.uri;
            new Thread(() -> {
                android.graphics.Bitmap bitmap = null;
                try {
                    android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    Uri uri = Uri.parse(expectedUri);
                    try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                        android.graphics.BitmapFactory.decodeStream(in, null, options);
                    }
                    options.inSampleSize = 1;
                    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 320) options.inSampleSize *= 2;
                    options.inJustDecodeBounds = false;
                    try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                        bitmap = android.graphics.BitmapFactory.decodeStream(in, null, options);
                    }
                } catch (Exception ignored) { }
                final android.graphics.Bitmap result = bitmap;
                runOnUiThread(() -> {
                    if (!isDestroyed() && RoomMediaStore.get(this, routeAlias).uri.equals(expectedUri)) imagePreview.setImageBitmap(result);
                });
            }, "photo-preview").start();
            String name = media.name.trim().isEmpty() ? "선택한 사진" : media.name;
            imageStatus.setText("● 사진 첨부 · " + name);
            imageStatus.setTextColor(Color.rgb(164, 182, 255));
        }
    }

    private void updateScheduleVisibility() {
        boolean fixed = timesRadio != null && timesRadio.isChecked();
        if (intervalBox != null) intervalBox.setVisibility(fixed ? View.GONE : View.VISIBLE);
        if (timesBox != null) timesBox.setVisibility(fixed ? View.VISIBLE : View.GONE);
    }

    private boolean saveProfile(boolean notify) {
        MultiRoomStore.Profile p = MultiRoomStore.get(this, routeAlias);
        if (p == null) return false;

        p.message = messageInput.getText().toString();
        p.enabled = enabledCheck.isChecked();
        p.dailyLimit = unlimitedCheck.isChecked() ? 0
                : Math.max(1, parseInt(dailyLimitInput.getText().toString(), 8));

        if (timesRadio.isChecked()) {
            String raw = timesInput.getText().toString().trim();
            if (!MultiRoomStore.hasValidTimes(raw)) {
                if (notify) toast("시간을 HH:mm 형식으로 하나 이상 입력해 주세요.");
                return false;
            }
            p.scheduleMode = MultiRoomStore.MODE_TIMES;
            p.dailyTimes = MultiRoomStore.canonicalTimes(raw);
            timesInput.setText(p.dailyTimes);
        } else {
            p.scheduleMode = MultiRoomStore.MODE_INTERVAL;
            p.intervalMinutes = Math.max(SendScheduler.MIN_INTERVAL_MINUTES,
                    parseInt(intervalInput.getText().toString(), 60));
            intervalInput.setText(String.valueOf(p.intervalMinutes));
        }

        boolean active = Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false);
        if (active && p.enabled && RoomMediaStore.hasPayload(this, p)) {
            p.nextAt = MultiRoomStore.computeNextAt(p, System.currentTimeMillis());
        } else if (!p.enabled || !RoomMediaStore.hasPayload(this, p)) {
            p.nextAt = 0L;
        }

        MultiRoomStore.upsert(this, p);
        if (active) SendScheduler.scheduleNext(this);
        Prefs.setStatus(this, "방 설정 저장: " + p.title() + " · " + MultiRoomStore.scheduleSummary(p));
        refreshNextPreview();
        if (notify) {
            toast("설정을 저장했습니다.");
            if (!p.fixedTimes() && p.intervalMinutes < 5 && p.unlimited()) {
                Toast.makeText(this, "1~4분 무제한 반복은 카카오 정책이나 환경에 따라 제한될 수 있습니다.", Toast.LENGTH_LONG).show();
            }
        }
        return true;
    }

    private void testSend() {
        if (!saveProfile(false)) return;
        MultiRoomStore.Profile p = MultiRoomStore.get(this, routeAlias);
        if (!RoomMediaStore.hasPayload(this, p)) {
            toast("보낼 메시지를 입력해 주세요.");
            return;
        }
        if (!KakaoNotificationListener.hasLiveSession(routeAlias)) {
            toast("이 방 연결이 없습니다. 방에서 새 메시지를 받은 뒤 ‘연결 다시 확인’을 눌러 주세요.");
            return;
        }
        RoomMediaStore.Media media = RoomMediaStore.get(this, routeAlias);
        String mediaLine = media.hasImage() ? "\n사진: " + (media.name.isEmpty() ? "첨부됨" : media.name) : "";
        new AlertDialog.Builder(this)
                .setTitle("1회 테스트 전송")
                .setMessage(p.title() + "\n\n" + p.message + mediaLine)
                .setPositiveButton("전송", (d, w) -> {
                    LicenseManager.runAuthorized(this, () -> {
                        boolean ok = KakaoMessageSender.send(this, routeAlias, p.message, media);
                        String error = KakaoMessageSender.lastError();
                        Prefs.setStatus(this, ok ? "수동 전송 성공: " + p.title()
                                : "수동 전송 실패: " + p.title() + " · " + error);
                        toast(ok ? "테스트 전송에 성공했습니다." : "전송 실패: " + error);
                        refreshConnection();
                    });
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void reconnectRoom() {
        MultiRoomStore.Profile p = MultiRoomStore.get(this, routeAlias);
        if (p == null) return;
        KakaoNotificationListener.requestRefresh();
        ArrayList<KakaoNotificationListener.SessionEntry> all = KakaoNotificationListener.candidateSessionEntries();
        ArrayList<KakaoNotificationListener.SessionEntry> matches = new ArrayList<>();
        for (KakaoNotificationListener.SessionEntry e : all) {
            if (e.suggestedRoom != null && e.suggestedRoom.trim().equalsIgnoreCase(p.actualRoomName)) matches.add(e);
        }
        if (matches.isEmpty()) {
            toast("이 방의 새 알림이 없습니다. 실제 방에서 메시지를 하나 받은 뒤 다시 확인해 주세요.");
            return;
        }
        if (matches.size() > 1) {
            new AlertDialog.Builder(this)
                    .setTitle("같은 이름의 방이 여러 개 감지되었습니다")
                    .setMessage("잘못된 방 연결을 막기 위해 자동 선택하지 않았습니다. 대상 방에서 새 메시지를 받은 직후 다시 시도해 주세요.")
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }
        boolean ok = KakaoNotificationListener.bindRecentTokenToRoom(this, matches.get(0).token, routeAlias);
        toast(ok ? "방 연결을 다시 확인했습니다." : "알림 세션이 만료되었습니다.");
        refreshConnection();
    }

    private void deleteProfile() {
        MultiRoomStore.Profile p = MultiRoomStore.get(this, routeAlias);
        String title = p == null ? "이 방" : p.title();
        new AlertDialog.Builder(this)
                .setTitle(title + " 삭제")
                .setMessage("메시지, 사진, 시간 설정, 카카오 연결 정보가 모두 삭제됩니다. 이 작업은 되돌릴 수 없습니다.")
                .setPositiveButton("삭제", (d, w) -> {
                    RoomMediaStore.Media old = RoomMediaStore.get(this, routeAlias);
                    MultiRoomStore.remove(this, routeAlias);
                    RoomMediaStore.clear(this, routeAlias);
                    releasePersistedReadPermission(old.uri);
                    KakaoNotificationListener.unbindRoom(this, routeAlias);
                    if (Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false)) SendScheduler.scheduleNext(this);
                    toast("방을 삭제했습니다.");
                    finish();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void refreshConnection() {
        if (connectionStatus == null) return;
        boolean live = KakaoNotificationListener.hasLiveSession(routeAlias);
        boolean stored = KakaoNotificationListener.hasStoredBinding(this, routeAlias);
        connectionStatus.setText(live ? "●  연결됨 · 지금 전송 가능"
                : stored ? "●  복구 대기 · 새 알림이 오면 자동 복구"
                : "●  연결 필요 · 이 방에서 새 메시지를 받아 주세요");
        connectionStatus.setTextColor(live ? GREEN : stored ? AMBER : RED);
    }

    private void refreshNextPreview() {
        if (nextPreview == null) return;
        MultiRoomStore.Profile p = MultiRoomStore.get(this, routeAlias);
        if (p == null) return;
        MultiRoomStore.Profile temp = p.copy();
        if (timesRadio != null && timesRadio.isChecked()) {
            String raw = timesInput == null ? p.dailyTimes : timesInput.getText().toString();
            if (!MultiRoomStore.hasValidTimes(raw)) {
                nextPreview.setText("다음 전송 · 지정 시각을 입력해 주세요");
                return;
            }
            temp.scheduleMode = MultiRoomStore.MODE_TIMES;
            temp.dailyTimes = MultiRoomStore.canonicalTimes(raw);
        } else {
            temp.scheduleMode = MultiRoomStore.MODE_INTERVAL;
            temp.intervalMinutes = Math.max(1, parseInt(intervalInput == null ? "" : intervalInput.getText().toString(), p.intervalMinutes));
        }
        long next = MultiRoomStore.computeNextAt(temp, System.currentTimeMillis());
        if (!temp.fixedTimes()) next += ReliabilityTiming.intervalJitterMillis(temp, next);
        nextPreview.setText("다음 전송 기준  ·  " + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(new Date(next)));
    }

    private int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
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

    private LinearLayout section(String title, String detail) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(title, 18, true, Color.rgb(226, 232, 244)));
        box.addView(text(detail, 11, false, Color.rgb(118, 129, 149)), topWrap(3));
        return box;
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
        v.setLineSpacing(0, 1.08f);
        if (bold) v.setTypeface(v.getTypeface(), Typeface.BOLD);
        return v;
    }

    private EditText edit(String hint, boolean multiline) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(102, 114, 137));
        e.setTextColor(TEXT);
        e.setTextSize(14);
        e.setBackground(roundStroke(FIELD, BORDER, 12));
        e.setPadding(dp(14), dp(13), dp(14), dp(13));
        if (multiline) {
            e.setMinLines(5);
            e.setGravity(Gravity.TOP);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        }
        return e;
    }

    private Button primaryButton(String label) {
        Button b = button(label, ACCENT, ACCENT, TEXT);
        b.setElevation(dp(3));
        return b;
    }

    private Button secondaryButton(String label) {
        return button(label, Color.rgb(30, 36, 50), Color.rgb(55, 65, 84), Color.rgb(214, 221, 235));
    }

    private Button positiveSecondaryButton(String label) {
        return button(label, Color.rgb(25, 52, 43), Color.rgb(42, 91, 68), Color.rgb(156, 237, 192));
    }

    private Button dangerSecondaryButton(String label) {
        return button(label, Color.rgb(60, 35, 42), Color.rgb(104, 51, 61), Color.rgb(249, 183, 188));
    }

    private Button miniButton(String label) {
        Button b = button(label, Color.rgb(30, 36, 50), Color.rgb(55, 65, 84), Color.rgb(214, 221, 235));
        b.setTextSize(10);
        b.setMinHeight(dp(40));
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
        b.setMinHeight(dp(50));
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

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + .5f);
    }
}
