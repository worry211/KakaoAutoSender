package com.local.kakaoautosender;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import org.json.JSONArray;

/** A single entry for selecting rooms, common settings, first registration and tour controls. */
public final class PhotoTourActivity extends Activity {
  private final LinkedHashSet<String> selected = new LinkedHashSet<>();
  private final Handler main = new Handler(Looper.getMainLooper());
  private LinearLayout rooms;
  private TextView state;
  private TextView readiness;
  private CheckBox off;
  private final PhotoSetupActionGate actions = new PhotoSetupActionGate();
  private final ArrayList<Button> setupButtons = new ArrayList<>();
  private boolean resumed;
  private boolean usePanelOff;
  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          if (!resumed) return;
          state.setText(PhotoTourController.status(PhotoTourActivity.this));
          readiness.setText(
              actions.pending()
                  ? "서버 확인 중 · 전체 중지로 취소할 수 있습니다."
                  : PhotoTourReadiness.summary(PhotoTourActivity.this, selected, usePanelOff));
          boolean editing =
              !PhotoTourController.active() && !PanelOffManager.engaged() && !actions.pending();
          for (Button b : setupButtons) b.setEnabled(editing);
          off.setEnabled(editing);
          for (int i = 0; i < rooms.getChildCount(); i++) rooms.getChildAt(i).setEnabled(editing);
          main.postDelayed(this, 2000L);
        }
      };

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    PremiumChrome.applyWindow(this);
    try {
      JSONArray a = new JSONArray(Prefs.p(this).getString("photo_tour_selection_v1", "[]"));
      for (int i = 0; i < a.length(); i++) selected.add(a.getString(i));
    } catch (Exception ignored) {
    }
    ScrollView scroll = new ScrollView(this);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    int pad = Math.round(18 * getResources().getDisplayMetrics().density);
    body.setPadding(pad, pad, pad, pad);
    body.setBackgroundColor(Color.rgb(10, 14, 22));
    scroll.addView(body);
    PhotoSettingsLayout.apply(this, scroll);
    text(body, "사진 여러 방 전송 · 최종 검증 후보", 23);
    state = text(body, "", 14);
    readiness = text(body, "", 15);
    text(body, "방 선택 → 공통 사진·시간 설정 → 각 방 최초 준비 → 1회 시험 또는 반복", 15);
    text(
        body,
        "순회 때 휴대폰에서 카카오톡 방이 차례로 열립니다. 새 알림 연결과 준비된 방만 사용합니다. 첫 전송 버튼 준비는 사진 한 장을 보냅니다. 이후 방은 현재 방"
            + " 확인만으로 연결할 수 있습니다.",
        13);
    text(
        body,
        "기본 모드는 화면 꺼짐/잠금 중 대기합니다. 아래 패널 OFF 모드는 무선 디버깅 연결 후 화면 패널만 끄고 순회하는 시험 기능입니다. 전원 버튼으로 잠그면"
            + " 중지합니다. 사진만 보내며 저장 문구는 첨부하지 않습니다.",
        13);
    button(
        body,
        "화면 끄기 최초 설정 · PC·USB 불필요",
        () -> startActivity(new Intent(this, PanelOffActivity.class)));
    off = new CheckBox(this);
    off.setTextColor(Color.WHITE);
    off.setText("시작할 때 화면 패널을 끄고 순회 · 최초 설정 필요");
    off.setOnCheckedChangeListener(
        (v, checked) -> {
          actions.cancel();
          usePanelOff = checked;
        });
    body.addView(off);
    rooms = new LinearLayout(this);
    rooms.setOrientation(LinearLayout.VERTICAL);
    body.addView(rooms);
    button(body, "모든 방 선택", () -> selectAll(true));
    button(body, "선택 해제", () -> selectAll(false));
    setupButtons.add(
        button(
            body,
            "1. 선택 방 공통 사진·시간 설정",
            () -> {
              if (selected.isEmpty()) {
                toast("먼저 방을 선택하세요.");
                return;
              }
              startActivity(
                  new Intent(this, BulkRoomEditActivity.class)
                      .putStringArrayListExtra(
                          BulkRoomEditActivity.EXTRA_ROOMS, new ArrayList<>(selected)));
            }));
    setupButtons.add(button(body, "2. 준비 안 된 선택 방 연결", () -> authorized(false, false)));
    setupButtons.add(button(body, "3. 선택 방 1회씩 시험 · 10초 후", () -> authorized(true, true)));
    setupButtons.add(button(body, "저장한 일정으로 선택 방 반복 시작", () -> authorized(true, false)));
    button(
        body,
        "사진 여러 방 전송 전체 중지",
        () -> {
          actions.cancel();
          PhotoTourController.stop(this, "사용자가 사진 여러 방 전송을 중지했습니다.");
          PanelOffManager.end("사용자가 화면 복구를 요청했습니다.");
          state.setText(PhotoTourController.status(this));
        });
    button(
        body,
        "키보드·접근성 설정",
        () -> startActivity(new Intent(this, PhotoKeyboardSetupActivity.class)));
    button(body, "돌아가기", this::finish);
    setContentView(scroll);
  }

  private void authorized(boolean start, boolean once) {
    if (selected.isEmpty()) {
      toast("먼저 방을 선택하세요.");
      return;
    }
    ArrayList<String> requested = new ArrayList<>(selected);
    boolean requestedOff = usePanelOff;
    LicenseManager.runAuthorized(
        this,
        actions.guard(
            () -> {
              if (!resumed || isFinishing()) return;
              String error =
                  start
                      ? PhotoTourController.start(this, requested, once, requestedOff)
                      : PhotoTourController.prepare(this, requested);
              if (!error.isEmpty()) toast(error);
              state.setText(PhotoTourController.status(this));
            }));
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (!AppIntegrity.isAuthentic(this)) {
      AppIntegrity.trip(this);
      finish();
      return;
    }
    if (!LicenseManager.isUsable(this)) {
      LicenseManager.route(this);
      return;
    }
    rooms.removeAllViews();
    java.util.HashSet<String> existing = new java.util.HashSet<>();
    for (MultiRoomStore.Profile p : MultiRoomStore.list(this)) {
      existing.add(p.room);
      CheckBox box = new CheckBox(this);
      box.setTextColor(Color.WHITE);
      box.setText(
          p.title()
              + " · "
              + (PhotoTourBindings.get(this, p.room) == null ? "최초 준비 필요" : "준비 저장됨")
              + (RoomMediaStore.get(this, p.room).hasImage() ? "" : " · 사진 없음"));
      box.setChecked(selected.contains(p.room));
      box.setOnCheckedChangeListener(
          (b, checked) -> {
            actions.cancel();
            if (checked) selected.add(p.room);
            else selected.remove(p.room);
            Prefs.p(this)
                .edit()
                .putString("photo_tour_selection_v1", new JSONArray(selected).toString())
                .apply();
          });
      rooms.addView(box);
    }
    selected.retainAll(existing);
    Prefs.p(this)
        .edit()
        .putString("photo_tour_selection_v1", new JSONArray(selected).toString())
        .apply();
    resumed = true;
    main.removeCallbacks(refresh);
    refresh.run();
  }

  @Override
  protected void onPause() {
    actions.cancel();
    resumed = false;
    main.removeCallbacks(refresh);
    super.onPause();
  }

  private TextView text(LinearLayout p, String value, int size) {
    TextView v = new TextView(this);
    v.setText(value);
    v.setTextColor(Color.WHITE);
    v.setTextSize(size);
    v.setPadding(0, 10, 0, 14);
    p.addView(v);
    return v;
  }

  private Button button(LinearLayout p, String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = Math.round(10 * getResources().getDisplayMetrics().density);
    p.addView(b, params);
    return b;
  }

  private void selectAll(boolean value) {
    if (PhotoTourController.active() || PanelOffManager.engaged()) {
      toast("진행 중인 사진 작업을 먼저 중지하세요.");
      return;
    }
    actions.cancel();
    for (int i = 0; i < rooms.getChildCount(); i++)
      ((CheckBox) rooms.getChildAt(i)).setChecked(value);
    readiness.setText(PhotoTourReadiness.summary(this, selected, usePanelOff));
  }

  private void toast(String value) {
    Toast.makeText(this, value, Toast.LENGTH_LONG).show();
  }
}
