package com.local.kakaoautosender;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Photo-only IME with explicit manual handoff and schedules bound to one learned chat window. */
public final class PhotoKeyboardService extends InputMethodService {
  private static volatile WeakReference<PhotoKeyboardService> instance = new WeakReference<>(null);
  private final PhotoKeyboardSession session = new PhotoKeyboardSession();
  private final PhotoLoopSession loop = new PhotoLoopSession();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ThreadPoolExecutor photos =
      new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
  private Future<?> pendingLoad;
  private PhotoKeyboardSession.Ticket active;
  private LinearLayout panel;
  private TextView uiStatus;
  private TextView actionStatus;
  private boolean recipientConfirmed;
  private boolean advancedExpanded;
  private boolean screenOffRequested;
  private String displayedAvailability = "";
  private String status = "카카오톡 채팅 입력창에서 사진을 선택하세요.";
  private PhotoKeyboardSession.Ticket shown;
  private Bitmap preview;
  private boolean receiverRegistered;
  private boolean preparing;
  private String learnedSource;
  private RoomMediaStore.Media learnedMedia;
  private final BroadcastReceiver screenOff =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
            cancel("휴대폰 시간 또는 시간대가 바뀌어 사진 예약을 중지했습니다.");
            return;
          }
          session.invalidate(false);
          clearPreview();
          PhotoLoopSession.Plan plan = loop.current();
          if (plan == null || !plan.screenOff) {
            PhotoUiService.cancel();
            cancel("잠금·화면 꺼짐으로 선택과 사진 예약을 중지했습니다.");
          } else {
            status = "화면 꺼짐 시도 · Android가 연결을 끊거나 화면 확인을 막으면 중지합니다.";
            render();
          }
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    instance = new WeakReference<>(this);
    IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
    filter.addAction(Intent.ACTION_TIME_CHANGED);
    filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(screenOff, filter);
    receiverRegistered = true;
  }

  static void cancelPending() {
    PhotoTourController.stopActive("사용자가 전체 사진 전송을 중지했습니다.");
    PhotoKeyboardService service = instance.get();
    if (service == null) return;
    service.session.invalidate(false);
    service.main.post(() -> service.cancel("중지되었습니다. 사진 선택은 유지됩니다. 현재 방을 다시 확인하세요."));
  }

  static boolean hasActiveLoop() {
    PhotoKeyboardService service = instance.get();
    return service != null && service.loop.active();
  }

  static void stopLoop(Context context, String run, String reason) {
    synchronized (DeliveryGate.LOCK) {
      PhotoKeyboardService service = instance.get();
      PhotoLoopSession.Plan plan = service == null ? null : service.loop.current();
      if (run != null
          && (plan == null ? !PhotoLoopStore.matchesRun(context, run) : !run.equals(plan.run)))
        return;
      if (PhotoTourController.active()) {
        PhotoTourController.stop(context, reason);
        return;
      }
      if (run == null) PanelOffManager.end("사용자가 사진 작업을 중지했습니다. 패널 복구 요청됨");
      if (service != null) {
        service.loop.stop(context, reason);
        service.main.post(
            () -> {
              service.status = reason;
              service.render();
            });
      } else PhotoLoopStore.stop(context, reason);
      PhotoLoopAlarm.cancel(context);
      PhotoUiService.cancel();
    }
  }

  static boolean allowFinalPhotoClick() {
    PhotoKeyboardService service = instance.get();
    PhotoLoopSession.Plan plan = service == null ? null : service.loop.current();
    return plan != null
        && plan.uiPending
        && service.loop.uiGate(service, plan).isEmpty()
        && (!PhotoTourController.active() || PhotoTourController.finalGate(plan.run));
  }

  static void showForTour() {
    PhotoKeyboardService s = instance.get();
    if (s != null && android.os.Build.VERSION.SDK_INT >= 28) s.requestShowSelf(0);
  }

  static boolean prepareTourPhoto(String room) {
    PhotoKeyboardService s = instance.get();
    if (s == null || !s.isInputViewShown() || s.getCurrentInputConnection() == null) return false;
    s.prepare(room, RoomMediaStore.get(s, room));
    return true;
  }

  static String startTourOnce(MultiRoomStore.Profile p, RoomMediaStore.Media media) {
    PhotoKeyboardService s = instance.get();
    if (s == null) return "사진 키보드가 연결되지 않았습니다. 사진 키보드를 기본 입력기로 선택하세요.";
    try {
      if (!PhotoUiService.tourEditorEmpty()) return "메시지 입력창에 작성 중인 문구가 있어 전송하지 않았습니다.";
      String error =
          s.loop.armTourNow(
              s,
              p,
              media,
              s.getCurrentInputEditorInfo(),
              s.getCurrentInputConnection(),
              PhotoUiService.ready(),
              System.currentTimeMillis());
      if (!error.isEmpty()) return error;
      PhotoTourController.bindLoop(s.loop.current().run);
      s.learnedSource = p.room;
      s.learnedMedia = media;
      s.session.invalidate(false);
      s.clearPreview();
      PhotoLoopAlarm.schedule(s, s.loop.current());
      s.heartbeat(s.loop.current());
      s.render();
      return "";
    } catch (RuntimeException e) {
      return "사진 순회 입력 또는 예약을 시작하지 못했습니다.";
    }
  }

  static void uiReturned() {
    PhotoKeyboardService service = instance.get();
    if (service != null) service.rebindReturnedChat(service.loop.current(), 0);
  }

  static void uiStatusChanged() {
    PhotoKeyboardService service = instance.get();
    if (service != null)
      service.main.post(
          () -> {
            service.render();
          });
  }

  static void uiLearned() {
    PhotoKeyboardService service = instance.get();
    if (service != null)
      service.main.post(
          () -> {
            if (!service.loop.active()) service.render();
          });
  }

  private void rebindReturnedChat(PhotoLoopSession.Plan plan, int attempt) {
    if (plan == null || loop.current() != plan || !plan.uiPending) return;
    if (PhotoUiService.ready()
        && loop.completeUi(
            this,
            plan,
            getCurrentInputEditorInfo(),
            getCurrentInputConnection(),
            System.currentTimeMillis())) {
      PhotoUiService.releaseAfterReturn();
      status = "사진 전송 버튼 실행 완료 · 실제 도착은 확인이 필요합니다.";
      if (loop.active()) {
        try {
          PhotoLoopAlarm.schedule(this, plan);
        } catch (RuntimeException error) {
          stopLoop(this, plan.run, "다음 사진 예약을 등록하지 못해 중지");
        }
      } else PhotoLoopAlarm.cancel(this);
      if (plan.once) PhotoTourController.completed(plan.source.room, plan.run);
      render();
    } else if (!loop.active()) {
      PhotoLoopAlarm.cancel(this);
      PhotoUiService.cancel();
      render();
    } else if (attempt < 15) main.postDelayed(() -> rebindReturnedChat(plan, attempt + 1), 150L);
    else stopLoop(this, plan.run, "원래 채팅방의 사진 입력 연결을 다시 확인하지 못해 중지");
  }

  static void dispatchLoop(String run, long when, Runnable done) {
    PhotoKeyboardService service = instance.get();
    if (service == null) {
      done.run();
      return;
    }
    service.dispatch(run, when, done);
  }

  private void dispatch(String run, long when, Runnable done) {
    PhotoLoopSession.Plan plan = loop.current();
    if (plan == null
        || run == null
        || !run.equals(plan.run)
        || plan.next != when
        || plan.inFlight
        || plan.uiPending) {
      done.run();
      return;
    }
    if (System.currentTimeMillis() < when) {
      PhotoLoopAlarm.schedule(this, plan);
      done.run();
      return;
    }
    if (PhotoTourController.active() && !PhotoTourController.finalGate(run)) {
      stopLoop(this, run, "순회 방 연결이 변경되어 사진을 보내지 않았습니다.");
      done.run();
      return;
    }
    // Doze may defer the periodic heartbeat. Renew an expired local lease before the
    // final gate instead of treating every long screen-off wait as a permanent failure.
    if (AppIntegrity.isAuthentic(this) && !LicenseManager.isUsable(this)) {
      LicenseManager.checkAsync(
          this,
          verification -> {
            if (loop.current() != plan) {
              done.run();
              return;
            }
            if (!verification.valid) {
              stopLoop(this, run, "예약 시 라이선스 확인 실패 · " + verification.message);
              done.run();
              return;
            }
            dispatch(run, when, done);
          });
      return;
    }
    String failure =
        loop.preflight(this, plan, getCurrentInputEditorInfo(), getCurrentInputConnection());
    if (!failure.isEmpty()) {
      stopLoop(this, run, failure);
      done.run();
      return;
    }
    if (!loop.reserve(this, plan, run, when, System.currentTimeMillis())) {
      if (!loop.active()) {
        PhotoLoopAlarm.cancel(this);
        PhotoUiService.cancel();
        status = PhotoLoopStore.summary(this);
        render();
      }
      done.run();
      return;
    }
    try {
      photos.execute(
          () -> {
            String error = null;
            try {
              PhotoShareRequest.create(this, plan.media);
            } catch (java.io.IOException | RuntimeException failureToRead) {
              error = "예약한 사진을 읽을 수 없어 중지 · 사진을 다시 선택하세요.";
            }
            String reason = error;
            main.post(
                () -> {
                  try {
                    if (loop.current() != plan) return;
                    if (reason != null) {
                      stopLoop(this, run, reason);
                      return;
                    }
                    if (!PhotoUiService.begin()) {
                      stopLoop(this, run, "현재 채팅방 화면과 학습한 전송 버튼을 확인할 수 없어 중지");
                      return;
                    }
                    String result =
                        loop.deliver(
                            this,
                            plan,
                            getCurrentInputEditorInfo(),
                            getCurrentInputConnection(),
                            System.currentTimeMillis());
                    if (!result.isEmpty()) {
                      stopLoop(this, run, result);
                      return;
                    }
                    status = "사진 입력 수락 · 학습한 전송 화면을 확인하고 있습니다.";
                    render();
                  } finally {
                    done.run();
                  }
                });
          });
    } catch (RejectedExecutionException busy) {
      stopLoop(this, run, "사진 저장소 작업이 끝나지 않아 예약 중지");
      done.run();
    }
  }

  @Override
  public View onCreateInputView() {
    ScrollView scroll = new ScrollView(this);
    panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(16), dp(12), dp(16), dp(12));
    panel.setBackgroundColor(Color.rgb(18, 23, 34));
    scroll.addView(panel);
    FrameLayout frame = new FrameLayout(this);
    frame.setBackgroundColor(Color.rgb(18, 23, 34));
    frame.addView(scroll, new FrameLayout.LayoutParams(-1, keyboardHeightPx()));
    render();
    return frame;
  }

  @Override
  public boolean onEvaluateFullscreenMode() {
    return false;
  }

  @Override
  public void onStartInput(EditorInfo editor, boolean restarting) {
    super.onStartInput(editor, restarting);
    session.invalidate(false);
    clearPreview();
    if (editor == null || !KakaoNotificationListener.KAKAO_PACKAGE.equals(editor.packageName)) {
      PhotoUiService.cancel();
      cancel("카카오톡 입력 연결이 종료되었습니다. 현재 방에서 다시 준비하세요.");
    } else if (!expectedUiTransition() && !keepLoopWhileHidden()) {
      cancel("입력창이 연결되었습니다. 현재 방과 보낼 사진을 확인하세요.");
    }
  }

  @Override
  public void onStartInputView(EditorInfo editor, boolean restarting) {
    super.onStartInputView(editor, restarting);
    clearPreview();
    session.invalidate(true);
    PhotoKeyboardDiagnostics.recordEditor(this, editor);
    PhotoUiService.reconcileChat();
    status = "사진 출처의 방 이름은 받는 방이 아닙니다. 현재 카카오톡 방을 직접 확인하세요.";
    render();
    main.post(this::restoreSelectedPhoto);
    main.postDelayed(PhotoTourController::inputReady, 200L);
  }

  @Override
  public void onFinishInputView(boolean finishingInput) {
    session.invalidate(false);
    clearPreview();
    if (!expectedUiTransition() && !mayKeepScreenOff() && !keepLoopWhileHidden())
      cancel("입력 연결이 유지되지 않아 사진 예약을 중지했습니다.");
    super.onFinishInputView(finishingInput);
  }

  @Override
  public void onFinishInput() {
    session.invalidate(false);
    clearPreview();
    if (!expectedUiTransition()) cancel("Android가 입력 연결을 종료해 사진 예약을 중지했습니다.");
    super.onFinishInput();
  }

  @Override
  public void onWindowHidden() {
    session.invalidate(false);
    clearPreview();
    if (!expectedUiTransition() && !mayKeepScreenOff() && !keepLoopWhileHidden())
      cancel("입력 연결이 유지되지 않아 사진 예약을 중지했습니다.");
    super.onWindowHidden();
  }

  @Override
  public void onDestroy() {
    session.invalidate(false);
    if (loop.active()) stopLoop(this, null, "사진 키보드 서비스 종료 · 채팅방에서 다시 시작하세요.");
    PhotoUiService.cancel();
    if (instance.get() == this) instance.clear();
    if (receiverRegistered) unregisterReceiver(screenOff);
    main.removeCallbacksAndMessages(null);
    photos.shutdownNow();
    clearPreview();
    super.onDestroy();
  }

  private void cancel(String reason) {
    if (loop.active()) stopLoop(this, null, reason);
    session.invalidate(false);
    clearPreview();
    status = reason;
    render();
  }

  private boolean mayKeepScreenOff() {
    PhotoLoopSession.Plan plan = loop.current();
    android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
    return plan != null && plan.screenOff && power != null && !power.isInteractive();
  }

  private boolean keepLoopWhileHidden() {
    PhotoLoopSession.Plan current = loop.current();
    return current != null
        && PhotoUiService.ready()
        && loop.preflight(this, current, getCurrentInputEditorInfo(), getCurrentInputConnection())
            .isEmpty();
  }

  private boolean expectedUiTransition() {
    PhotoLoopSession.Plan plan = loop.current();
    return PhotoUiService.waiting() || (plan != null && plan.uiPending && PhotoUiService.ready());
  }

  private void clearPreview() {
    shown = null;
    active = null;
    preparing = false;
    if (pendingLoad != null) pendingLoad.cancel(true);
    pendingLoad = null;
    photos.purge();
    // ImageView may retain the previous bitmap until render removes it. Let GC own its lifetime.
    preview = null;
    recipientConfirmed = false;
    advancedExpanded = false;
    screenOffRequested = false;
  }

  private void rememberSelection(PhotoKeyboardSession.Ticket ticket) {
    learnedSource = ticket.room;
    learnedMedia = ticket.media;
    PhotoKeyboardSelection.save(this, ticket.room, ticket.media);
  }

  private void restoreSelectedPhoto() {
    if (active != null
        || loop.active()
        || PhotoTourController.active()
        || PhotoUiService.waiting()
        || !isInputViewShown()
        || !DeliveryGate.allowed(this)
        || !PhotoKeyboardSession.unlocked(this)) return;
    PhotoKeyboardSelection.Selected selected = PhotoKeyboardSelection.load(this);
    if (selected != null
        && PhotoKeyboardSession.rejection(getCurrentInputEditorInfo(), selected.media.mime)
            .isEmpty()) prepare(selected.room, selected.media);
  }

  private void render() {
    if (panel == null) return;
    panel.removeAllViews();
    PhotoUiReadiness availability = PhotoUiService.readiness();
    if (!availability.message.equals(displayedAvailability)) {
      displayedAvailability = availability.message;
      status = ""; // A recovered/changed connection must not retain an obsolete action error.
    }
    text("카톡매크로 · 사진", 17);
    if (PhotoTourController.active()) text(PhotoTourController.status(this), 12);
    uiStatus = text(availability.message, 12);
    actionStatus = text(status, 12);
    if (loop.active()) {
      text(PhotoLoopStore.summary(this), 12);
      text("현재 방 유지 · 다른 앱/방 이동 또는 입력 연결 종료 시 중지", 11);
      button("키보드 접기 · 예약 유지", () -> requestHideSelf(0));
      button("사진 예약 즉시 중지", () -> stopLoop(this, null, "사용자가 사진 예약을 중지했습니다."));
      return;
    }
    if (PhotoTourController.ownsKeyboard()) {
      button("사진 여러 방 전송 중지", () -> PhotoTourController.stop(this, "사용자가 사진 순회를 중지했습니다."));
      return;
    }
    if (PhotoUiService.waiting()) {
      button(
          "준비 취소 · 자동 재전송 없음",
          () -> {
            PhotoTourController.stopActive("사용자가 방 최초 준비를 취소했습니다.");
            PhotoUiService.cancel();
            session.invalidate(isInputViewShown());
            clearPreview();
            status = "준비를 취소했습니다. 이미 전달한 사진의 도착 여부는 카카오톡에서 확인하세요.";
            render();
          });
      return;
    }
    PhotoKeyboardSession.Ticket ticket = shown != null && session.current(shown) ? shown : null;
    if (ticket == null && !preparing && !PhotoTourController.active()) {
      PhotoKeyboardSelection.Selected selected = PhotoKeyboardSelection.load(this);
      if (selected != null) {
        learnedSource = selected.room;
        learnedMedia = selected.media;
      }
    }
    PhotoKeyboardSession.Ticket displaying = preparing ? active : ticket;
    String room = displaying != null ? displaying.room : learnedSource;
    RoomMediaStore.Media media = displaying != null ? displaying.media : learnedMedia;
    boolean remembered =
        room != null
            && media != null
            && MultiRoomStore.get(this, room) != null
            && PhotoKeyboardSession.sameMedia(RoomMediaStore.get(this, room), media);
    if (ticket != null && preview != null || remembered) {
      if (ticket != null && preview != null) {
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setImageBitmap(preview);
        panel.addView(image, new LinearLayout.LayoutParams(-1, dp(80)));
      }
      text("선택한 사진: " + media.name, 12);
      text("수신자는 현재 열린 카카오톡 방입니다. 저장 문구는 함께 전달하지 않습니다.", 11);
      if (preparing) text("새 사진 확인 중 · 완료될 때까지 전송·예약을 시작하지 않습니다.", 11);
      if (ticket == null && !preparing) {
        text("사진 선택은 저장되어 있습니다. 전송 확인은 새 입력창에서 다시 받습니다.", 11);
        button("저장한 사진 다시 준비", () -> prepare(room, media));
      }
      CheckBox confirm = new CheckBox(this);
      confirm.setText("현재 카카오톡 방과 사진을 확인했습니다");
      confirm.setTextColor(Color.WHITE);
      confirm.setChecked(recipientConfirmed);
      confirm.setEnabled(!preparing);
      panel.addView(confirm);
      MultiRoomStore.Profile source = MultiRoomStore.get(this, room);
      if (source != null)
        text(
            "일정: "
                + MultiRoomStore.scheduleSummary(source)
                + " · 하루 한도 "
                + (source.dailyLimit == 0 ? "무제한" : source.dailyLimit + "회"),
            12);
      renderManualSend(ticket);
      if (PhotoTourController.canConfirmRoom()) {
        button(
            "현재 방 연결 완료 · 사진 보내지 않음",
            () -> {
              String error = PhotoTourController.confirmRoom(recipientConfirmed);
              if (!error.isEmpty()) showActionStatus(error);
            });
        text("이미 준비한 전송 버튼 기준을 사용합니다. 이번 방은 확인만 하며 사진을 추가 전송하지 않습니다.", 11);
      } else {
        Button learn =
            button(
                availability.canSchedule ? "현재 방 전송 버튼 다시 학습" : "자동 전송 준비 · 전송 버튼 학습 (1회)",
                () -> learnSelected(ticket));
        learn.setEnabled(availability.canLearn && ticket != null);
      }
      if (!availability.canSchedule) text("예약·반복 준비 필요: " + availability.message, 11);
      Button quick =
          button(
              "10초 후 자동전송 1회 시험",
              () ->
                  startConfigured(room, media, recipientConfirmed, true, screenOffRequested, true));
      Button repeat =
          button(
              "저장한 일정으로 반복 시작",
              () ->
                  startConfigured(
                      room, media, recipientConfirmed, false, screenOffRequested, false));
      Button once =
          button(
              "다음 일정에 1회 예약",
              () ->
                  startConfigured(
                      room, media, recipientConfirmed, true, screenOffRequested, false));
      quick.setEnabled(availability.canSchedule && !preparing);
      repeat.setEnabled(availability.canSchedule && !preparing);
      once.setEnabled(availability.canSchedule && !preparing);
      text("버튼이 흐리면 위 준비 상태를 확인하세요. 시험·학습도 실제 사진 한 장을 보냅니다.", 11);
      button(
          "현재 방 연결 다시 확인",
          () -> {
            PhotoUiService.reconcileChat();
            restoreSelectedPhoto();
            render();
          });
      confirm.setOnCheckedChangeListener(
          (view, checked) -> {
            recipientConfirmed = checked;
            // Keep the same controls/scroll position. A rejected action never clears this checkbox.
          });
      button(
          advancedExpanded ? "고급 설정 접기" : "고급 설정",
          () -> {
            advancedExpanded = !advancedExpanded;
            render();
          });
      if (advancedExpanded) {
        CheckBox off = new CheckBox(this);
        off.setText("화면 꺼짐에서 시도 · 지원 미확인 실험");
        off.setTextColor(Color.WHITE);
        off.setChecked(screenOffRequested);
        off.setOnCheckedChangeListener((view, checked) -> screenOffRequested = checked);
        panel.addView(off);
        text("현재 연결이 유지될 때만 시도합니다. 실제 화면 OFF 전송은 미검증이며 잠금을 풀거나 화면을 켜지 않습니다.", 11);
        button("화면 패널 OFF·여러 방 설정", () -> openPhotoTools(PhotoTourActivity.class));
      }
      button(
          "사진 선택 취소",
          () -> {
            PhotoTourController.stopActive("사용자가 방 최초 준비를 취소했습니다.");
            PhotoUiService.cancel();
            session.invalidate(isInputViewShown());
            clearPreview();
            learnedSource = null;
            learnedMedia = null;
            PhotoKeyboardSelection.clear(this);
            status = "선택을 취소했습니다. 이 취소로 사진을 추가 전송하지 않습니다.";
            render();
          });
      button(
          "다른 사진 선택",
          () -> {
            session.invalidate(isInputViewShown());
            clearPreview();
            learnedSource = null;
            learnedMedia = null;
            PhotoKeyboardSelection.clear(this);
            status = "보낼 사진을 선택하세요.";
            render();
          });
    } else {
      boolean found = false;
      if (LicenseManager.isUsable(this) && AppIntegrity.isAuthentic(this)) {
        for (MultiRoomStore.Profile profile : MultiRoomStore.list(this)) {
          RoomMediaStore.Media saved = RoomMediaStore.get(this, profile.room);
          if (!saved.hasImage()) continue;
          found = true;
          String rejection =
              PhotoKeyboardSession.rejection(getCurrentInputEditorInfo(), saved.mime);
          Button row =
              button(
                  "사진 출처: " + profile.title() + "\n" + saved.name,
                  () -> prepare(profile.room, saved));
          row.setEnabled(!preparing && rejection.isEmpty());
          if (!rejection.isEmpty()) text(rejection, 11);
        }
      } else text("앱에서 라이선스를 확인한 뒤 입력창을 다시 여세요.", 12);
      if (!found) text("앱의 방 설정에서 사진을 먼저 선택하세요.", 12);
    }
    button("사진 설정·권한·도움말", () -> openPhotoTools(PhotoKeyboardSetupActivity.class));
    button("사진 여러 방 전송·화면 끄기", () -> openPhotoTools(PhotoTourActivity.class));
    button(
        "기존 키보드로 변경",
        () -> {
          PhotoUiService.cancel();
          cancel("키보드를 변경합니다. 사진 선택과 예약을 중지했습니다.");
          InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
          if (manager != null) manager.showInputMethodPicker();
        });
  }

  private void openPhotoTools(Class<?> activity) {
    try {
      startActivity(new Intent(this, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    } catch (RuntimeException error) {
      showActionStatus("앱의 사진 전송 메뉴에서 설정을 여세요.");
    }
  }

  private void renderManualSend(PhotoKeyboardSession.Ticket ticket) {
    Button send =
        button(
            "현재 방에 사진 전달 · 1회",
            () -> {
              if (ticket == null || !session.current(ticket)) {
                showActionStatus("저장한 사진 다시 준비를 누른 뒤 현재 방을 확인하세요.");
                return;
              }
              if (!recipientConfirmed) {
                showActionStatus("현재 카카오톡 방과 사진을 먼저 확인하세요.");
                return;
              }
              rememberSelection(ticket);
              String result =
                  session.commit(
                      this, ticket, getCurrentInputEditorInfo(), getCurrentInputConnection(), true);
              clearPreview();
              status = result;
              PhotoKeyboardDiagnostics.recordResult(this, result);
              render();
            });
    send.setEnabled(ticket != null);
  }

  private void learnSelected(PhotoKeyboardSession.Ticket ticket) {
    if (!session.current(ticket)) {
      showActionStatus("전송 확인이 만료되었습니다. 저장한 사진을 다시 준비하세요.");
      clearPreview();
      render();
      return;
    }
    if (!recipientConfirmed) {
      showActionStatus("현재 카카오톡 방과 사진을 먼저 확인하세요.");
      return;
    }
    String rejection = PhotoUiService.learn();
    if (!rejection.isEmpty()) {
      showActionStatus(rejection);
      return;
    }
    String result =
        session.commit(
            this, ticket, getCurrentInputEditorInfo(), getCurrentInputConnection(), true);
    boolean accepted = result.startsWith("사진 전달 요청");
    if (!accepted) PhotoUiService.cancel();
    if (accepted) {
      rememberSelection(ticket);
    }
    clearPreview();
    status = accepted ? "사진 전달 요청됨 · 카카오톡에서 전송 또는 취소하세요. 이미 보냈다면 다시 보내지 마세요." : result;
    PhotoKeyboardDiagnostics.recordResult(this, status);
    render();
  }

  private int keyboardHeightPx() {
    android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
    return Math.max(1, Math.min(dp(310), metrics.heightPixels * 2 / 5));
  }

  private void startConfigured(
      String room,
      RoomMediaStore.Media media,
      boolean confirmed,
      boolean once,
      boolean screenOff,
      boolean quickTest) {
    if (preparing) {
      showActionStatus("새 사진 확인이 끝난 뒤 예약을 시작하세요.");
      return;
    }
    String failure;
    try {
      failure =
          quickTest
              ? loop.armQuickTest(
                  this,
                  MultiRoomStore.get(this, room),
                  media,
                  getCurrentInputEditorInfo(),
                  getCurrentInputConnection(),
                  confirmed,
                  PhotoUiService.ready(),
                  System.currentTimeMillis(),
                  screenOff)
              : loop.arm(
                  this,
                  MultiRoomStore.get(this, room),
                  media,
                  getCurrentInputEditorInfo(),
                  getCurrentInputConnection(),
                  once,
                  screenOff,
                  confirmed,
                  PhotoUiService.ready(),
                  System.currentTimeMillis());
    } catch (RuntimeException storageFailure) {
      stopLoop(this, null, "사진 예약을 저장하지 못해 시작하지 않았습니다.");
      showActionStatus("사진 예약을 저장하지 못했습니다. 저장 공간과 앱 상태를 확인하세요.");
      return;
    }
    if (!failure.isEmpty()) {
      showActionStatus(failure);
      return;
    }
    session.invalidate(true);
    clearPreview();
    try {
      PhotoLoopAlarm.schedule(this, loop.current());
      heartbeat(loop.current());
      status = "사진 예약 시작 · 현재 채팅방을 유지하세요.";
    } catch (RuntimeException error) {
      stopLoop(this, null, "사진 예약을 등록하지 못해 중지했습니다.");
    }
    render();
  }

  private void heartbeat(PhotoLoopSession.Plan expected) {
    if (expected == null || loop.current() != expected) return;
    LicenseManager.checkAsync(
        this,
        verification -> {
          if (loop.current() != expected) return;
          if (!verification.valid) {
            stopLoop(this, expected.run, "라이선스 서버 확인 실패로 사진 예약 중지 · " + verification.message);
            return;
          }
          main.postDelayed(() -> heartbeat(expected), LicenseManager.heartbeatMillis(this));
        });
  }

  private void prepare(String room, RoomMediaStore.Media media) {
    if (!PhotoKeyboardSession.unlocked(this) || !DeliveryGate.allowed(this)) {
      cancel("화면 잠금과 라이선스를 확인한 뒤 입력창을 다시 여세요.");
      return;
    }
    PhotoKeyboardSession.Ticket ticket =
        session.selectInCurrentEditor(
            room,
            media,
            getCurrentInputEditorInfo(),
            getCurrentInputConnection(),
            isInputViewShown());
    if (ticket == null) {
      String rejection = PhotoKeyboardSession.rejection(getCurrentInputEditorInfo(), media.mime);
      showActionStatus(
          rejection.isEmpty() ? "카카오톡 입력 연결이 없습니다. 현재 방의 메시지 입력창을 눌러 사진 키보드를 다시 여세요." : rejection);
      return;
    }
    clearPreview();
    active = ticket;
    preparing = true;
    status = "사진을 확인하고 있습니다. 전송하지 않았습니다.";
    render();
    main.postDelayed(
        () -> {
          if (active != ticket) return;
          session.invalidate(true);
          clearPreview();
          status = "전송 확인 시간이 만료되었습니다. 사진 선택은 유지되며, 현재 방 확인 후 다시 준비할 수 있습니다.";
          render();
        },
        PhotoKeyboardSession.CONFIRM_WINDOW_MS);
    try {
      pendingLoad =
          photos.submit(
              () -> {
                if (!session.current(ticket)) return;
                Bitmap bitmap = null;
                String error = null;
                try {
                  PhotoShareRequest.create(
                      this, media); // validate readable URI and concrete MIME off main
                  BitmapFactory.Options options = new BitmapFactory.Options();
                  options.inJustDecodeBounds = true;
                  try (InputStream input =
                      getContentResolver().openInputStream(Uri.parse(media.uri))) {
                    BitmapFactory.decodeStream(input, null, options);
                  }
                  if (options.outWidth <= 0 || options.outHeight <= 0) {
                    throw new IOException("사진을 미리 볼 수 없습니다.");
                  }
                  options.inJustDecodeBounds = false;
                  options.inSampleSize = 1;
                  while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize
                      > 640) {
                    options.inSampleSize *= 2;
                  }
                  try (InputStream input =
                      getContentResolver().openInputStream(Uri.parse(media.uri))) {
                    bitmap = BitmapFactory.decodeStream(input, null, options);
                  }
                  if (bitmap == null) throw new IOException("사진을 미리 볼 수 없습니다.");
                } catch (IOException | RuntimeException failure) {
                  error = "사진을 읽거나 미리 볼 수 없습니다. 앱에서 사진을 다시 선택하세요.";
                }
                final Bitmap ready = bitmap;
                final String failure = error;
                main.post(
                    () -> {
                      if (!session.current(ticket)) {
                        if (ready != null) ready.recycle();
                        return;
                      }
                      preparing = false;
                      if (failure != null) {
                        session.invalidate(true);
                        clearPreview();
                        status = failure;
                        render();
                        return;
                      }
                      shown = ticket;
                      preview = ready;
                      rememberSelection(ticket);
                      status = "받는 방은 자동 선택하지 않습니다. 현재 카카오톡 채팅방을 확인하세요.";
                      render();
                    });
              });
    } catch (RejectedExecutionException busy) {
      session.invalidate(true);
      clearPreview();
      status = "사진 저장소의 응답을 기다리고 있습니다. 잠시 후 저장한 사진을 다시 준비하세요.";
      render();
    }
  }

  private void showActionStatus(String value) {
    status = value;
    if (actionStatus != null) actionStatus.setText(value);
    android.widget.Toast.makeText(this, value, android.widget.Toast.LENGTH_LONG).show();
  }

  private TextView text(String value, int size) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(Color.rgb(228, 234, 245));
    view.setPadding(0, dp(5), 0, dp(5));
    panel.addView(view);
    return view;
  }

  private Button button(String label, Runnable action) {
    Button button = new Button(this);
    button.setText(label);
    button.setAllCaps(false);
    PhotoControls.style(button, label);
    button.setOnClickListener(view -> action.run());
    panel.addView(button, new LinearLayout.LayoutParams(-1, -2));
    return button;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
