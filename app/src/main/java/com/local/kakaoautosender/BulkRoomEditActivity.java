package com.local.kakaoautosender;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class BulkRoomEditActivity extends Activity {
    static final String EXTRA_ROOMS = "rooms";

    private static final int BG = Color.rgb(9, 11, 16);
    private static final int SURFACE = Color.rgb(18, 23, 34);
    private static final int FIELD = Color.rgb(15, 20, 30);
    private static final int BORDER = Color.rgb(45, 55, 75);
    private static final int TEXT = Color.rgb(238, 242, 249);
    private static final int MUTED = Color.rgb(154, 166, 188);
    private static final int ACCENT = Color.rgb(86, 112, 255);
    private static final int AMBER = Color.rgb(243, 190, 91);

    private final ArrayList<String> rooms = new ArrayList<>();
    private CheckBox applyMessage;
    private CheckBox applySchedule;
    private CheckBox applyLimit;
    private CheckBox applyEnabled;
    private EditText messageInput;
    private EditText intervalInput;
    private EditText timesInput;
    private EditText limitInput;
    private CheckBox unlimitedCheck;
    private CheckBox enabledCheck;
    private RadioButton intervalRadio;
    private RadioButton timesRadio;
    private LinearLayout intervalBox;
    private LinearLayout timesBox;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ArrayList<String> incoming = getIntent().getStringArrayListExtra(EXTRA_ROOMS);
        if (incoming != null) {
            for (String room : incoming) {
                if (room != null && !room.trim().isEmpty() && MultiRoomStore.get(this, room) != null && !rooms.contains(room)) {
                    rooms.add(room);
                }
            }
        }
        if (rooms.isEmpty()) { finish(); return; }
        setContentView(buildUi());
        loadFirstProfileAsTemplate();
        syncApplyState();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(42));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(text("여러 방 일괄 편집", 26, true, TEXT));
        titleBox.addView(text(rooms.size() + "개 방 선택됨", 12, false, MUTED), topWrap(5));
        header.addView(titleBox, weight());
        header.addView(pill("BULK", Color.rgb(29, 38, 65), Color.rgb(180, 195, 255)));
        root.addView(header);

        LinearLayout info = card(Color.rgb(14, 18, 27), Color.rgb(35, 44, 61), 16);
        info.addView(text("체크한 항목만 선택한 모든 방에 적용됩니다.", 13, true, TEXT));
        info.addView(text("사진 첨부와 카카오 방 연결 정보는 방마다 고유하므로 일괄 변경하지 않습니다.", 11, false, Color.rgb(127, 141, 166)), top(6));
        root.addView(info, top(18));

        root.addView(section("메시지", "여러 방에 같은 문구를 한 번에 적용합니다."), top(24));
        LinearLayout messageCard = card(SURFACE, BORDER, 18);
        applyMessage = check("메시지 일괄 적용");
        messageCard.addView(applyMessage);
        messageInput = edit("선택한 방에 적용할 메시지", true);
        messageCard.addView(messageInput, top(10));
        root.addView(messageCard, top(9));

        root.addView(section("전송 스케줄", "간격 또는 지정 시각을 선택한 모든 방에 적용합니다."), top(24));
        LinearLayout scheduleCard = card(SURFACE, BORDER, 18);
        applySchedule = check("스케줄 일괄 적용");
        scheduleCard.addView(applySchedule);

        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.HORIZONTAL);
        intervalRadio = radio("간격 반복");
        timesRadio = radio("매일 지정 시각");
        modes.addView(intervalRadio, new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
        modes.addView(timesRadio, new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
        scheduleCard.addView(modes, top(10));

        intervalBox = new LinearLayout(this);
        intervalBox.setOrientation(LinearLayout.VERTICAL);
        intervalInput = edit("간격(분)", false);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalBox.addView(intervalInput);
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        int[] values = {1, 5, 10, 30, 60};
        String[] labels = {"1분", "5분", "10분", "30분", "1시간"};
        for (int i = 0; i < values.length; i++) {
            final int value = values[i];
            Button b = miniButton(labels[i]);
            b.setOnClickListener(v -> intervalInput.setText(String.valueOf(value)));
            LinearLayout.LayoutParams lp = weight();
            if (i > 0) lp.leftMargin = dp(5);
            presets.addView(b, lp);
        }
        intervalBox.addView(presets, top(8));
        scheduleCard.addView(intervalBox, top(10));

        timesBox = new LinearLayout(this);
        timesBox.setOrientation(LinearLayout.VERTICAL);
        timesInput = edit("예: 09:00, 13:30, 20:00", false);
        timesBox.addView(timesInput);
        scheduleCard.addView(timesBox, top(10));
        modes.setOnCheckedChangeListener((g, id) -> updateScheduleVisibility());
        root.addView(scheduleCard, top(9));

        root.addView(section("하루 전송 제한", "기존 카운트는 유지하고 제한값만 변경합니다."), top(24));
        LinearLayout limitCard = card(SURFACE, BORDER, 18);
        applyLimit = check("하루 최대 횟수 일괄 적용");
        limitCard.addView(applyLimit);
        LinearLayout limitRow = new LinearLayout(this);
        limitRow.setOrientation(LinearLayout.HORIZONTAL);
        limitRow.setGravity(Gravity.CENTER_VERTICAL);
        limitInput = edit("하루 최대 횟수", false);
        limitInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        limitRow.addView(limitInput, weight());
        unlimitedCheck = check("무제한");
        LinearLayout.LayoutParams unlimitedLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        unlimitedLp.leftMargin = dp(12);
        limitRow.addView(unlimitedCheck, unlimitedLp);
        limitCard.addView(limitRow, top(10));
        root.addView(limitCard, top(9));

        root.addView(section("사용 상태", "선택한 방을 한 번에 켜거나 일시정지할 수 있습니다."), top(24));
        LinearLayout enabledCard = card(SURFACE, BORDER, 18);
        applyEnabled = check("사용 여부 일괄 적용");
        enabledCard.addView(applyEnabled);
        enabledCheck = check("자동전송 사용");
        enabledCard.addView(enabledCheck, top(8));
        root.addView(enabledCard, top(9));

        applyMessage.setOnCheckedChangeListener((b, c) -> syncApplyState());
        applySchedule.setOnCheckedChangeListener((b, c) -> syncApplyState());
        applyLimit.setOnCheckedChangeListener((b, c) -> syncApplyState());
        applyEnabled.setOnCheckedChangeListener((b, c) -> syncApplyState());
        unlimitedCheck.setOnCheckedChangeListener((b, c) -> {
            limitInput.setEnabled(applyLimit.isChecked() && !c);
        });

        LinearLayout action = card(Color.rgb(23, 29, 42), BORDER, 18);
        action.addView(text("일괄 변경", 12, true, Color.rgb(151, 165, 193)));
        Button save = primaryButton(rooms.size() + "개 방에 적용");
        save.setOnClickListener(v -> confirmApply());
        action.addView(save, top(10));
        Button cancel = secondaryButton("취소");
        cancel.setOnClickListener(v -> finish());
        action.addView(cancel, top(8));
        root.addView(action, top(28));
        return scroll;
    }

    private void loadFirstProfileAsTemplate() {
        MultiRoomStore.Profile first = MultiRoomStore.get(this, rooms.get(0));
        if (first == null) return;
        messageInput.setText(first.message);
        intervalInput.setText(String.valueOf(first.intervalMinutes));
        timesInput.setText(first.dailyTimes);
        if (first.fixedTimes()) timesRadio.setChecked(true); else intervalRadio.setChecked(true);
        unlimitedCheck.setChecked(first.unlimited());
        limitInput.setText(first.unlimited() ? "8" : String.valueOf(first.dailyLimit));
        enabledCheck.setChecked(first.enabled);
        updateScheduleVisibility();
    }

    private void syncApplyState() {
        boolean message = applyMessage != null && applyMessage.isChecked();
        boolean schedule = applySchedule != null && applySchedule.isChecked();
        boolean limit = applyLimit != null && applyLimit.isChecked();
        boolean enabled = applyEnabled != null && applyEnabled.isChecked();
        if (messageInput != null) { messageInput.setEnabled(message); messageInput.setAlpha(message ? 1f : 0.48f); }
        if (intervalRadio != null) intervalRadio.setEnabled(schedule);
        if (timesRadio != null) timesRadio.setEnabled(schedule);
        if (intervalInput != null) intervalInput.setEnabled(schedule);
        if (timesInput != null) timesInput.setEnabled(schedule);
        if (unlimitedCheck != null) unlimitedCheck.setEnabled(limit);
        if (limitInput != null) limitInput.setEnabled(limit && !unlimitedCheck.isChecked());
        if (enabledCheck != null) enabledCheck.setEnabled(enabled);
    }

    private void updateScheduleVisibility() {
        boolean fixed = timesRadio != null && timesRadio.isChecked();
        if (intervalBox != null) intervalBox.setVisibility(fixed ? View.GONE : View.VISIBLE);
        if (timesBox != null) timesBox.setVisibility(fixed ? View.VISIBLE : View.GONE);
    }

    private void confirmApply() {
        if (!applyMessage.isChecked() && !applySchedule.isChecked() && !applyLimit.isChecked() && !applyEnabled.isChecked()) {
            toast("적용할 항목을 하나 이상 체크해 주세요.");
            return;
        }
        if (applySchedule.isChecked() && timesRadio.isChecked() && !MultiRoomStore.hasValidTimes(timesInput.getText().toString())) {
            toast("지정 시각을 HH:mm 형식으로 하나 이상 입력해 주세요.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(rooms.size() + "개 방 일괄 변경")
                .setMessage("체크한 설정만 선택한 방에 적용합니다. 사진과 방 연결 정보는 유지됩니다.")
                .setPositiveButton("적용", (d, w) -> applyNow())
                .setNegativeButton("취소", null)
                .show();
    }

    private void applyNow() {
        MultiRoomStore.BulkPatch patch = new MultiRoomStore.BulkPatch();
        patch.applyMessage = applyMessage.isChecked();
        patch.message = messageInput.getText().toString();
        patch.applySchedule = applySchedule.isChecked();
        patch.scheduleMode = timesRadio.isChecked() ? MultiRoomStore.MODE_TIMES : MultiRoomStore.MODE_INTERVAL;
        patch.intervalMinutes = Math.max(SendScheduler.MIN_INTERVAL_MINUTES, parseInt(intervalInput.getText().toString(), 60));
        patch.dailyTimes = MultiRoomStore.canonicalTimes(timesInput.getText().toString());
        patch.applyDailyLimit = applyLimit.isChecked();
        patch.dailyLimit = unlimitedCheck.isChecked() ? 0 : Math.max(1, parseInt(limitInput.getText().toString(), 8));
        patch.applyEnabled = applyEnabled.isChecked();
        patch.enabled = enabledCheck.isChecked();

        int changed = MultiRoomStore.applyBulkPatch(this, rooms, patch);
        if (Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false)) SendScheduler.scheduleNext(this);
        Prefs.setStatus(this, "일괄 설정 변경 · " + changed + "개 방");
        toast(changed + "개 방 설정을 변경했습니다.");
        setResult(RESULT_OK);
        finish();
    }

    private int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (Exception ignored) { return fallback; }
    }

    private CheckBox check(String label) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(TEXT);
        c.setTextSize(13);
        return c;
    }

    private RadioButton radio(String label) {
        RadioButton r = new RadioButton(this);
        r.setText(label);
        r.setTextColor(TEXT);
        r.setTextSize(13);
        return r;
    }

    private TextView section(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(title, 16, true, TEXT));
        box.addView(text(subtitle, 11, false, Color.rgb(118, 129, 149)), topWrap(3));
        return box;
    }

    private EditText edit(String hint, boolean multi) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(102, 114, 137));
        e.setTextColor(TEXT);
        e.setTextSize(14);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setBackground(roundStroke(FIELD, BORDER, 12));
        e.setSingleLine(!multi);
        if (multi) {
            e.setGravity(Gravity.TOP | Gravity.START);
            e.setMinLines(4);
        }
        return e;
    }

    private Button primaryButton(String label) { return button(label, ACCENT, Color.WHITE, ACCENT); }
    private Button secondaryButton(String label) { return button(label, Color.rgb(27, 34, 49), TEXT, BORDER); }
    private Button miniButton(String label) {
        Button b = button(label, Color.rgb(27, 34, 49), Color.rgb(195, 205, 226), BORDER);
        b.setTextSize(11);
        b.setMinHeight(0);
        b.setPadding(dp(5), dp(8), dp(5), dp(8));
        return b;
    }

    private Button button(String label, int fill, int color, int stroke) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(color);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        b.setBackground(roundStroke(fill, stroke, 12));
        return b;
    }

    private LinearLayout card(int fill, int stroke, int radius) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(15), dp(15), dp(15), dp(15));
        l.setBackground(roundStroke(fill, stroke, radius));
        return l;
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private TextView pill(String label, int fill, int color) {
        TextView t = text(label, 10, true, color);
        t.setPadding(dp(9), dp(5), dp(9), dp(5));
        t.setGravity(Gravity.CENTER);
        t.setBackground(roundStroke(fill, fill, 999));
        return t;
    }

    private GradientDrawable roundStroke(int fill, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setStroke(dp(1), stroke);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(margin);
        return lp;
    }

    private LinearLayout.LayoutParams topWrap(int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(margin);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
