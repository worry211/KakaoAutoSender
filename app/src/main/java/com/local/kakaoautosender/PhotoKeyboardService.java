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
  private String status = "카카오톡 채팅 입력창에서 사진을 선택하세요.";
  private PhotoKeyboardSession.Ticket shown;
  private Bitmap preview;
  private boolean receiverRegistered;
  private boolean preparing;
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
    PhotoKeyboardService service = instance.get();
    if (service == null) return;
    service.session.invalidate(false);
    service.main.post(() -> service.cancel("중지되었습니다. 입력창을 다시 열어 사진을 선택하세요."));
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
    return plan != null && plan.uiPending && service.loop.uiGate(service, plan).isEmpty();
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
            if (service.uiStatus != null) service.uiStatus.setText(PhotoUiService.status());
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
    scroll.setLayoutParams(new android.view.ViewGroup.LayoutParams(-1, dp(310)));
    render();
    return scroll;
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
    if (editor == null
        || !KakaoNotificationListener.KAKAO_PACKAGE.equals(editor.packageName)
        || !expectedUiTransition()) cancel("입력창이 바뀌었습니다. 보낼 사진을 선택하세요.");
  }

  @Override
  public void onStartInputView(EditorInfo editor, boolean restarting) {
    super.onStartInputView(editor, restarting);
    clearPreview();
    session.invalidate(true);
    PhotoKeyboardDiagnostics.recordEditor(this, editor);
    status = "사진 출처의 방 이름은 받는 방이 아닙니다. 현재 카카오톡 방을 직접 확인하세요.";
    render();
  }

  @Override
  public void onFinishInputView(boolean finishingInput) {
    session.invalidate(false);
    clearPreview();
    if (!expectedUiTransition() && !mayKeepScreenOff()) cancel("키보드가 닫혀 사진 예약을 중지했습니다.");
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
    if (!expectedUiTransition() && !mayKeepScreenOff()) cancel("키보드가 닫혀 사진 예약을 중지했습니다.");
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
  }

  private void render() {
    if (panel == null) return;
    panel.removeAllViews();
    uiStatus = null;
    text("카톡매크로 · 사진 키보드", 17);
    text(status, 12);
    uiStatus = text(PhotoUiService.status(), 12);
    if (loop.active()) {
      text(PhotoLoopStore.summary(this), 12);
      button("사진 예약 즉시 중지", () -> stopLoop(this, null, "사용자가 사진 예약을 중지했습니다."));
      return;
    }
    if (shown != null && session.current(shown) && preview != null) {
      PhotoKeyboardSession.Ticket ticket = shown;
      ImageView image = new ImageView(this);
      image.setAdjustViewBounds(true);
      image.setImageBitmap(preview);
      panel.addView(image, new LinearLayout.LayoutParams(-1, dp(120)));
      text(
          "선택한 사진: "
              + ticket.media.name
              + "\n즉시 전송될 수 있습니다. 입력 중인 문구는 먼저 정리하세요. 앱에 저장한 문구는 전달하지 않습니다.",
          12);
      CheckBox confirm = new CheckBox(this);
      confirm.setText("현재 카카오톡 방과 사진을 확인했습니다");
      confirm.setTextColor(Color.WHITE);
      panel.addView(confirm);
      Button send =
          button(
              "현재 방에 사진 전달 · 1회",
              () -> {
                // The first tap consumes the ticket even if Kakao returns false or throws.
                String result =
                    session.commit(
                        this,
                        ticket,
                        getCurrentInputEditorInfo(),
                        getCurrentInputConnection(),
                        confirm.isChecked());
                clearPreview();
                status = result;
                PhotoKeyboardDiagnostics.recordResult(this, result);
                render();
              });
      send.setEnabled(false);
      confirm.setOnCheckedChangeListener(
          (view, checked) -> send.setEnabled(checked && session.current(ticket)));
      text("예약 전 방 설정에서 간격·매일 시각·하루 한도를 저장하세요. 같은 방의 기존 자동전송은 꺼야 합니다.", 12);
      button(
          "자동 전송 준비 · 전송 버튼 학습 (1회)",
          () -> {
            if (!confirm.isChecked() || !session.current(ticket)) {
              status = "현재 방 확인을 체크하고 다시 누르세요.";
              render();
              return;
            }
            String rejection = PhotoUiService.learn();
            if (!rejection.isEmpty()) {
              status = rejection;
              render();
              return;
            }
            String result =
                session.commit(
                    this, ticket, getCurrentInputEditorInfo(), getCurrentInputConnection(), true);
            boolean accepted = result.startsWith("사진 전달 요청");
            if (!accepted) PhotoUiService.cancel();
            clearPreview();
            status =
                accepted
                    ? "카카오톡에 사진을 전달했습니다. 사진 미리보기가 열려 있다면 카카오톡에서 전송을 마치세요. 이미 보냈다면 다시 누르지 마세요."
                    : result;
            PhotoKeyboardDiagnostics.recordResult(this, status);
            render();
          });
      CheckBox screenOffOption = new CheckBox(this);
      screenOffOption.setText("화면 꺼짐에서도 시도 · 실험 기능");
      screenOffOption.setTextColor(Color.WHITE);
      panel.addView(screenOffOption);
      text("Android가 연결 또는 화면 확인을 막으면 중지합니다. 잠금 해제·강제 화면 켜기를 하지 않으며 화면 꺼짐 성공은 미검증입니다.", 11);
      MultiRoomStore.Profile configured = MultiRoomStore.get(this, ticket.room);
      if (configured != null)
        text(
            "저장한 일정: "
                + MultiRoomStore.scheduleSummary(configured)
                + " · 하루 요청 한도 "
                + (configured.dailyLimit == 0 ? "무제한" : configured.dailyLimit + "회"),
            12);
      button(
          "다음 일정에 1회 예약",
          () -> startLoop(ticket, confirm.isChecked(), true, screenOffOption.isChecked()));
      button(
          "저장한 일정으로 반복 시작",
          () -> startLoop(ticket, confirm.isChecked(), false, screenOffOption.isChecked()));
      button(
          "사진 선택 취소",
          () -> {
            session.invalidate(true);
            clearPreview();
            status = "취소했습니다. 아무 사진도 전달하지 않았습니다.";
            render();
          });
    } else {
      boolean found = false;
      if (LicenseManager.isUsable(this) && AppIntegrity.isAuthentic(this)) {
        for (MultiRoomStore.Profile profile : MultiRoomStore.list(this)) {
          RoomMediaStore.Media media = RoomMediaStore.get(this, profile.room);
          if (!media.hasImage()) continue;
          found = true;
          String rejection =
              PhotoKeyboardSession.rejection(getCurrentInputEditorInfo(), media.mime);
          Button row =
              button(
                  "사진 출처: " + profile.title() + "\n" + media.name,
                  () -> prepare(profile.room, media));
          row.setEnabled(!preparing && rejection.isEmpty());
          if (!rejection.isEmpty()) text(rejection, 11);
        }
      } else text("앱에서 라이선스를 확인한 뒤 입력창을 다시 여세요.", 12);
      if (!found) text("앱의 방 설정에서 사진을 먼저 선택하세요.", 12);
    }
    button(
        "기존 키보드로 변경",
        () -> {
          cancel("키보드를 변경합니다. 사진 선택을 취소했습니다.");
          InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
          if (manager != null) manager.showInputMethodPicker();
        });
  }

  private void startLoop(
      PhotoKeyboardSession.Ticket ticket, boolean confirmed, boolean once, boolean screenOff) {
    if (!session.current(ticket)) {
      status = "사진 선택이 만료되었습니다. 다시 선택하세요.";
      render();
      return;
    }
    String failure =
        loop.arm(
            this,
            MultiRoomStore.get(this, ticket.room),
            ticket.media,
            getCurrentInputEditorInfo(),
            getCurrentInputConnection(),
            once,
            screenOff,
            confirmed,
            PhotoUiService.ready(),
            System.currentTimeMillis());
    if (!failure.isEmpty()) {
      status = failure;
      render();
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
    PhotoKeyboardSession.Ticket ticket = session.select(room, media);
    if (ticket == null) return;
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
          status = "30초가 지나 선택을 취소했습니다. 사진을 다시 선택하세요.";
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
                  if (options.outWidth <= 0 || options.outHeight <= 0)
                    throw new IOException("사진을 미리 볼 수 없습니다.");
                  options.inJustDecodeBounds = false;
                  options.inSampleSize = 1;
                  while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 640)
                    options.inSampleSize *= 2;
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
                      status = "받는 방은 자동 선택하지 않습니다. 현재 카카오톡 채팅방을 확인하세요.";
                      render();
                    });
              });
    } catch (RejectedExecutionException busy) {
      session.invalidate(true);
      clearPreview();
      status = "사진 저장소의 응답을 기다리고 있습니다. 잠시 후 다시 선택하세요.";
      render();
    }
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
    button.setOnClickListener(view -> action.run());
    panel.addView(button, new LinearLayout.LayoutParams(-1, -2));
    return button;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
