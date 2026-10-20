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

/**
 * Optional photo-only IME. No text capture, accessibility control, background sends, or retries.
 */
public final class PhotoKeyboardService extends InputMethodService {
  private static volatile WeakReference<PhotoKeyboardService> instance = new WeakReference<>(null);
  private final PhotoKeyboardSession session = new PhotoKeyboardSession();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ThreadPoolExecutor photos =
      new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
  private Future<?> pendingLoad;
  private PhotoKeyboardSession.Ticket active;
  private LinearLayout panel;
  private String status = "카카오톡 채팅 입력창에서 사진을 선택하세요.";
  private PhotoKeyboardSession.Ticket shown;
  private Bitmap preview;
  private boolean receiverRegistered;
  private boolean preparing;
  private final BroadcastReceiver screenOff =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          cancel("잠금·화면 꺼짐으로 선택을 취소했습니다.");
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    instance = new WeakReference<>(this);
    IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
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
    cancel("입력창이 바뀌었습니다. 보낼 사진을 선택하세요.");
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
    cancel("사진 선택을 취소했습니다.");
    super.onFinishInputView(finishingInput);
  }

  @Override
  public void onFinishInput() {
    cancel("입력 연결이 종료되었습니다.");
    super.onFinishInput();
  }

  @Override
  public void onWindowHidden() {
    cancel("키보드가 닫혀 사진 선택을 취소했습니다.");
    super.onWindowHidden();
  }

  @Override
  public void onDestroy() {
    session.invalidate(false);
    if (instance.get() == this) instance.clear();
    if (receiverRegistered) unregisterReceiver(screenOff);
    main.removeCallbacksAndMessages(null);
    photos.shutdownNow();
    clearPreview();
    super.onDestroy();
  }

  private void cancel(String reason) {
    session.invalidate(false);
    clearPreview();
    status = reason;
    render();
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
    text("카톡매크로 · 사진 키보드", 17);
    text(status, 12);
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

  private void text(String value, int size) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(Color.rgb(228, 234, 245));
    view.setPadding(0, dp(5), 0, dp(5));
    panel.addView(view);
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
