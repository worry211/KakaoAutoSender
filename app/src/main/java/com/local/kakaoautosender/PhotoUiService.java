package com.local.kakaoautosender;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Locally learned, narrow photo-confirmation automation. No coordinate taps or chat-text capture.
 */
public final class PhotoUiService extends AccessibilityService {
  private static WeakReference<PhotoUiService> instance = new WeakReference<>(null);
  private final PhotoUiWorkflow workflow = new PhotoUiWorkflow();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final HashMap<Integer, String> activities = new HashMap<>();
  private PowerManager.WakeLock wake;
  private boolean scanQueued;
  private long deadlineElapsed;
  private final Runnable scan = this::inspect;
  private String status = "사진 전송 버튼을 아직 학습하지 않았습니다.";
  private String availabilityMessage = "";
  private final android.content.BroadcastReceiver tourScreen =
      new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, android.content.Intent i) {
          PhotoTourController.screenChanged(c);
        }
      };
  private boolean tourReceiverRegistered;

  private void setStatus(String value) {
    status = value;
    PhotoKeyboardService.uiStatusChanged();
    PhotoKeyboardSetupActivity.uiStatusChanged();
  }

  @Override
  protected void onServiceConnected() {
    instance = new WeakReference<>(this);
    PhotoTourController.recoverOrphan(this);
    android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
    if (info != null) {
      info.flags |=
          android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
              | android.accessibilityservice.AccessibilityServiceInfo
                  .FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
      info.notificationTimeout = 0;
      setServiceInfo(info);
    }
    if (!tourReceiverRegistered) {
      android.content.IntentFilter f =
          new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF);
      f.addAction(android.content.Intent.ACTION_SCREEN_ON);
      f.addAction(android.content.Intent.ACTION_USER_PRESENT);
      if (android.os.Build.VERSION.SDK_INT >= 33)
        registerReceiver(tourScreen, f, RECEIVER_NOT_EXPORTED);
      else registerReceiver(tourScreen, f);
      tourReceiverRegistered = true;
    }
  }

  static boolean connected() {
    return instance.get() != null;
  }

  static boolean waiting() {
    PhotoUiService s = instance.get();
    return s != null && s.workflow.waiting();
  }

  static boolean autoWaiting() {
    PhotoUiService s = instance.get();
    return s != null && s.workflow.autoWaiting();
  }

  static boolean ready() {
    return readiness().canSchedule;
  }

  static PhotoUiReadiness readiness() {
    PhotoUiService service = instance.get();
    return PhotoUiReadiness.evaluate(
        service != null,
        service == null ? PhotoUiWorkflow.Phase.IDLE : service.workflow.phase(),
        service == null ? null : service.workflow.anchor(),
        service == null ? null : service.currentAnchor());
  }

  static void reconcileChat() {
    PhotoUiService service = instance.get();
    if (service == null || service.workflow.phase() != PhotoUiWorkflow.Phase.READY) return;
    PhotoUiWorkflow.Anchor current = service.currentAnchor();
    if (current != null && !service.workflow.anchor().same(current))
      service.fail("카카오톡 입력창이 새로 연결되었습니다. 현재 방에서 사진 전송 준비를 다시 시작할 수 있습니다.");
  }

  static String status() {
    return readiness().message;
  }

  static String learn() {
    PhotoUiService s = instance.get();
    if (s == null) return "접근성의 사진 전송 보조를 먼저 켜세요.";
    PhotoUiWorkflow.Anchor anchor = s.currentAnchor();
    if (anchor == null) {
      String reason = "현재 채팅방 제목과 입력창을 확인할 수 없어 준비를 시작하지 않았습니다.";
      s.fail(reason);
      return reason;
    }
    s.workflow.learn(anchor);
    s.setStatus("자동화 준비 확인 중 · 사진을 이미 보냈다면 다시 보내지 마세요. 전송 동작 감지와 사진 도착은 별개입니다.");
    s.timeout(45_000L);
    return "";
  }

  static boolean begin() {
    PhotoUiService s = instance.get();
    if (s == null || !s.workflow.begin(s.currentAnchor())) return false;
    PowerManager power = (PowerManager) s.getSystemService(POWER_SERVICE);
    if (power != null) {
      s.wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KakaoMacro:PhotoUi");
      s.wake.acquire(18_000L);
    }
    s.timeout(15_000L);
    return true;
  }

  static void cancel() {
    PhotoUiService s = instance.get();
    if (s == null) return;
    s.workflow.reset();
    s.releaseWake();
    s.main.removeCallbacks(s.scan);
    s.scanQueued = false;
    s.setStatus("사진 자동화 준비가 해제되었습니다. 이미 보낸 사진을 다시 보낼 필요는 없습니다.");
  }

  static void releaseAfterReturn() {
    PhotoUiService service = instance.get();
    if (service != null) service.releaseWake();
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    if (event == null) return;
    if (workflow.waiting() && android.os.SystemClock.elapsedRealtime() >= deadlineElapsed) {
      fail("사진 전송 확인 시간이 지나 예약 중지");
      return;
    }
    if (event.getEventType() != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        && !KakaoNotificationListener.KAKAO_PACKAGE.contentEquals(
            event.getPackageName() == null ? "" : event.getPackageName())) return;
    if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
      String className = String.valueOf(event.getClassName());
      if (className.startsWith(KakaoNotificationListener.KAKAO_PACKAGE + ".")) {
        if (activities.size() > 20) activities.clear();
        activities.put(event.getWindowId(), className);
        PhotoTourController.windowEvent();
      }
    }
    if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
      AccessibilityNodeInfo source = event.getSource();
      String controlId = clickedSendId(source);
      if (workflow.autoWaiting()
          && (source == null
              || !workflow.expectedAutoClick(event.getWindowId(), source.getViewIdResourceName())
                  && !workflow.expectedAutoClick(event.getWindowId(), controlId))) {
        fail("사진 전송 도중 다른 조작이 감지되어 예약 중지 · 중복 재시도 없음");
        return;
      }
      if (source != null
          && (workflow.manualClick(event.getWindowId(), source.getViewIdResourceName())
              || workflow.manualClick(event.getWindowId(), controlId)))
        setStatus("전송 버튼 누름 감지 · 채팅방 복귀 확인 중 · 다시 보내지 마세요.");
    }
    if (!scanQueued) {
      scanQueued = true;
      main.postDelayed(scan, 100L);
    }
  }

  private void inspect() {
    scanQueued = false;
    try {
      PhotoUiWorkflow.Phase phase = workflow.phase();
      PhotoTourController.observeChat(currentAnchor());
      phase = workflow.phase();
      if (phase == PhotoUiWorkflow.Phase.IDLE) {
        notifyAvailability(currentAnchor());
        return;
      }
      if (workflow.waiting() && android.os.SystemClock.elapsedRealtime() >= deadlineElapsed) {
        fail("사진 전송 확인 시간이 지나 예약 중지");
        return;
      }
      AccessibilityNodeInfo root = currentChatRoot();
      if (!kakao(root)) {
        if (phase == PhotoUiWorkflow.Phase.READY) notifyAvailability(null);
        return;
      }
      PhotoUiWorkflow.Anchor current = anchor(root);
      if (workflow.returnedWithoutClick(current)) {
        fail("채팅방으로 돌아왔지만 전송 동작을 감지하지 못해 자동화 준비를 완료하지 못했습니다. 사진이 도착했다면 다시 보내지 마세요.");
        return;
      }
      if (phase == PhotoUiWorkflow.Phase.LEARN_RETURN
          || phase == PhotoUiWorkflow.Phase.AUTO_RETURN) {
        boolean automatic = workflow.returned(current);
        if (workflow.phase() == PhotoUiWorkflow.Phase.READY) {
          if (!automatic) releaseWake();
          setStatus("자동화 준비 확인 완료 · 전송 버튼 누름과 채팅방 복귀 감지 · 실제 사진 도착은 카카오톡에서 확인");
          PhotoKeyboardDiagnostics.recordResult(this, status);
          if (!automatic) PhotoKeyboardService.uiLearned();
          if (!automatic) PhotoTourController.learned(workflow.anchor(), workflow.template());
          if (automatic) {
            AccessibilityNodeInfo editor = findEditor(root, workflow.anchor().editorId);
            if (editor != null) {
              editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
              editor.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            PhotoKeyboardService.uiReturned();
          }
          return;
        }
      }
      if (phase == PhotoUiWorkflow.Phase.READY) {
        if (current != null && !workflow.ready(current)) fail("채팅방이 바뀌어 사진 예약과 버튼 학습 중지");
        else notifyAvailability(current);
        return;
      }
      if (workflow.anchor() == null || root.getWindowId() == workflow.anchor().window) return;
      ArrayList<AccessibilityNodeInfo> candidates = new ArrayList<>();
      for (AccessibilityNodeInfo node : nodes(root))
        if (sendButton(node) && !label(node).isEmpty()) candidates.add(node);
      if (candidates.size() != 1) return;
      AccessibilityNodeInfo send = candidates.get(0);
      String activity = activities.get(root.getWindowId());
      if (activity == null) return;
      PhotoUiWorkflow.Button button =
          new PhotoUiWorkflow.Button(
              root.getWindowId(),
              activity,
              layout(root),
              send.getViewIdResourceName(),
              String.valueOf(send.getClassName()),
              label(send));
      if (phase == PhotoUiWorkflow.Phase.LEARNING) workflow.observe(button);
      else if (phase == PhotoUiWorkflow.Phase.AUTO_PREVIEW)
        synchronized (DeliveryGate.LOCK) {
          if (android.os.SystemClock.elapsedRealtime() >= deadlineElapsed) {
            fail("사진 전송 확인 시간이 지나 예약 중지");
            return;
          }
          if (!PhotoKeyboardService.allowFinalPhotoClick()) {
            fail("중지 또는 설정 변경으로 사진 예약을 중지했습니다.");
            return;
          }
          AccessibilityNodeInfo fresh = currentChatRoot();
          if (!kakao(fresh)
              || fresh.getWindowId() != root.getWindowId()
              || !layout(fresh).equals(button.layout)
              || !send.refresh()
              || !sendButton(send)
              || !label(send).equals(button.label)
              || android.os.SystemClock.elapsedRealtime() >= deadlineElapsed) {
            fail("사진 전송 직전 화면이 바뀌어 예약 중지");
            return;
          }
          if (!workflow.claim(button)) return;
          setStatus("학습한 사진 전송 버튼 실행 · 원래 채팅방 확인 중");
          if (!send.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            fail("사진 전송 버튼 실행이 거절되어 예약 중지");
        }
    } catch (RuntimeException error) {
      fail("카카오톡 화면 정보를 확인할 수 없어 사진 예약 중지");
    }
  }

  private void timeout(long duration) {
    deadlineElapsed = android.os.SystemClock.elapsedRealtime() + duration;
    long revision = workflow.revision();
    main.postDelayed(
        () -> {
          if (workflow.revision() == revision && workflow.waiting())
            fail("전송 동작 또는 채팅방 복귀를 감지하지 못해 자동화 준비를 중지했습니다. 사진이 도착했다면 다시 보내지 마세요. 자동 재시도는 하지 않습니다.");
        },
        duration);
  }

  private void notifyAvailability(PhotoUiWorkflow.Anchor current) {
    String message =
        PhotoUiReadiness.evaluate(true, workflow.phase(), workflow.anchor(), current).message;
    if (!message.equals(availabilityMessage)) {
      availabilityMessage = message;
      PhotoKeyboardService.uiStatusChanged();
      PhotoKeyboardSetupActivity.uiStatusChanged();
    }
  }

  private void fail(String reason) {
    workflow.reset();
    releaseWake();
    PhotoKeyboardService.stopLoop(this, null, reason);
    setStatus(reason);
    PhotoKeyboardDiagnostics.recordResult(this, reason);
  }

  private void releaseWake() {
    if (wake != null && wake.isHeld()) wake.release();
    wake = null;
  }

  @Override
  public void onInterrupt() {
    fail("접근성 연결이 중단되어 사진 예약 중지");
  }

  @Override
  public void onDestroy() {
    PhotoTourController.stop(this, "접근성 서비스 종료로 사진 순회를 중지했습니다.");
    if (tourReceiverRegistered) {
      unregisterReceiver(tourScreen);
      tourReceiverRegistered = false;
    }
    fail("접근성 서비스가 종료되어 사진 예약 중지");
    if (instance.get() == this) instance.clear();
    main.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

  private PhotoUiWorkflow.Anchor currentAnchor() {
    try {
      AccessibilityNodeInfo root = currentChatRoot();
      return kakao(root) ? anchor(root) : null;
    } catch (RuntimeException error) {
      return null;
    }
  }

  private static boolean kakao(AccessibilityNodeInfo root) {
    return root != null
        && KakaoNotificationListener.KAKAO_PACKAGE.contentEquals(
            root.getPackageName() == null ? "" : root.getPackageName());
  }

  private AccessibilityNodeInfo currentChatRoot() {
    return rootForWindows(getWindows(), getRootInActiveWindow());
  }

  static PhotoUiWorkflow.Anchor tourAnchor() {
    PhotoUiService s = instance.get();
    return s == null ? null : s.currentAnchor();
  }

  static String bindTour(PhotoTourBindings.Binding b, PhotoUiWorkflow.Anchor current) {
    PhotoUiService s = instance.get();
    if (s == null || !b.matches(current) || !tourEditorEmpty()) return "현재 방 또는 빈 입력창을 확인할 수 없습니다.";
    s.workflow.bindVerifiedRoute(current, b.button);
    s.setStatus("저장한 방과 전송 버튼 준비를 확인했습니다.");
    return "";
  }

  static boolean tourEditorEmpty() {
    PhotoUiService s = instance.get();
    if (s == null) return false;
    try {
      AccessibilityNodeInfo root = s.currentChatRoot();
      PhotoUiWorkflow.Anchor a = root == null ? null : anchor(root);
      AccessibilityNodeInfo editor = a == null ? null : findEditor(root, a.editorId);
      // Only test emptiness; never retain, log, alter or transmit the draft.
      return editor != null
          && !editor.isPassword()
          && (editor.isShowingHintText()
              || editor.getText() != null && editor.getText().length() == 0);
    } catch (RuntimeException e) {
      return false;
    }
  }

  static void focusTourEditor() {
    PhotoUiService s = instance.get();
    if (s == null) return;
    try {
      AccessibilityNodeInfo root = s.currentChatRoot();
      PhotoUiWorkflow.Anchor a = root == null ? null : anchor(root);
      AccessibilityNodeInfo editor = a == null ? null : findEditor(root, a.editorId);
      if (editor != null) {
        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        editor.performAction(AccessibilityNodeInfo.ACTION_CLICK);
      }
    } catch (RuntimeException e) {
      PhotoTourController.stop(s, "입력창을 열지 못해 사진 순회 중지");
    }
  }

  static AccessibilityNodeInfo rootForWindows(
      List<AccessibilityWindowInfo> windows, AccessibilityNodeInfo activeRoot) {
    if (windows == null || windows.isEmpty()) return kakao(activeRoot) ? activeRoot : null;
    AccessibilityWindowInfo focused = null, active = null;
    for (AccessibilityWindowInfo window : windows) {
      if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
      if (window.isFocused()) {
        if (focused != null) return null;
        focused = window;
      }
      if (window.isActive()) {
        if (active != null) return null;
        active = window;
      }
    }
    if (focused != null && active != null && focused.getId() != active.getId()) return null;
    AccessibilityWindowInfo foreground = focused != null ? focused : active;
    AccessibilityNodeInfo root = foreground == null ? null : foreground.getRoot();
    return kakao(root) ? root : null;
  }

  private static AccessibilityNodeInfo findEditor(AccessibilityNodeInfo root, String id) {
    AccessibilityNodeInfo found = null;
    for (AccessibilityNodeInfo node : chromeNodes(root))
      if (node.isVisibleToUser() && node.isEditable() && id.equals(node.getViewIdResourceName())) {
        if (found != null) return null;
        found = node;
      }
    return found;
  }

  static PhotoUiWorkflow.Anchor anchor(AccessibilityNodeInfo root) {
    ArrayList<AccessibilityNodeInfo> chrome = chromeNodes(root);
    AccessibilityNodeInfo editor = null;
    for (AccessibilityNodeInfo node : chrome)
      if (node.isVisibleToUser()
          && node.isEnabled()
          && node.isEditable()
          && node.getViewIdResourceName() != null) {
        if (editor != null) return null;
        editor = node;
      }
    if (editor == null) return null;
    Rect bounds = new Rect();
    root.getBoundsInScreen(bounds);
    ArrayList<String> headers = new ArrayList<>();
    for (AccessibilityNodeInfo node : chrome) {
      String id = node.getViewIdResourceName();
      if (id == null || node.isEditable() || !node.isVisibleToUser()) continue;
      String name = id.toLowerCase(Locale.ROOT);
      if (!(name.endsWith(":id/title")
              || name.endsWith(":id/chat_title")
              || name.endsWith(":id/room_title")
              || name.endsWith(":id/room_name")
              || name.endsWith(":id/chatroom_title")
              || name.endsWith(":id/toolbar_title"))
          || insideConversationList(node)) continue;
      Rect location = new Rect();
      node.getBoundsInScreen(location);
      if (location.centerY() > bounds.top + bounds.height() / 3) continue;
      CharSequence text =
          node.getText(); // only title/toolbar nodes; never composer or message content
      if (text == null || text.length() == 0) text = node.getContentDescription();
      if (text != null && text.length() > 0 && text.length() <= 256) headers.add(id + "=" + text);
    }
    if (headers.isEmpty()) return null;
    Collections.sort(headers);
    return new PhotoUiWorkflow.Anchor(
        root.getWindowId(), editor.getViewIdResourceName(), digest(String.join("|", headers)));
  }

  static String label(AccessibilityNodeInfo node) {
    String text = node.getText() == null ? "" : node.getText().toString().trim();
    String description =
        node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim();
    for (String value : new String[] {text, description}) {
      String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[\\s()0-9]", "");
      if (normalized.equals("전송") || normalized.equals("보내기") || normalized.equals("send"))
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s", "");
    }
    return "";
  }

  private static boolean insideConversationList(AccessibilityNodeInfo node) {
    AccessibilityNodeInfo parent = node.getParent();
    for (int depth = 0; parent != null && depth < 12; depth++, parent = parent.getParent()) {
      String type = String.valueOf(parent.getClassName());
      if (type.contains("RecyclerView") || type.endsWith("ListView") || type.endsWith("ScrollView"))
        return true;
    }
    return parent != null;
  }

  static boolean sendButton(AccessibilityNodeInfo node) {
    String id = node.getViewIdResourceName();
    String className = String.valueOf(node.getClassName());
    return id != null
        && !node.isEditable()
        && node.isVisibleToUser()
        && node.isEnabled()
        && node.isClickable()
        && (className.endsWith("Button")
            || id.toLowerCase(Locale.ROOT).contains("send")
            || id.toLowerCase(Locale.ROOT).contains("confirm"));
  }

  // Android may report the label/icon inside a clickable control as the event source.
  // Only resolve its actual send-control ancestor; never infer a click from a window return.
  static String clickedSendId(AccessibilityNodeInfo source) {
    AccessibilityNodeInfo node = source;
    for (int depth = 0; node != null && depth < 8; depth++, node = node.getParent()) {
      if (!kakao(node)) return null;
      if (sendButton(node) && !label(node).isEmpty()) return node.getViewIdResourceName();
      if (node.isClickable()) return null; // a different clickable control is a hard boundary
    }
    return null;
  }

  static String layout(AccessibilityNodeInfo root) {
    ArrayList<String> ids = new ArrayList<>();
    for (AccessibilityNodeInfo node : nodes(root))
      if (node.getViewIdResourceName() != null)
        ids.add(node.getViewIdResourceName() + ":" + node.getClassName());
    Collections.sort(ids);
    return digest(String.join("|", ids));
  }

  private static String digest(String value) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
      return out.toString();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static ArrayList<AccessibilityNodeInfo> nodes(AccessibilityNodeInfo root) {
    return nodes(root, false);
  }

  private static ArrayList<AccessibilityNodeInfo> chromeNodes(AccessibilityNodeInfo root) {
    return nodes(root, true);
  }

  private static ArrayList<AccessibilityNodeInfo> nodes(
      AccessibilityNodeInfo root, boolean skipLists) {
    ArrayList<AccessibilityNodeInfo> result = new ArrayList<>();
    result.add(root);
    for (int i = 0; i < result.size(); i++) {
      AccessibilityNodeInfo node = result.get(i);
      String type = String.valueOf(node.getClassName());
      if (skipLists
          && (type.contains("RecyclerView")
              || type.endsWith("ListView")
              || type.endsWith("ScrollView"))) continue;
      if (node.getChildCount() > 600) throw new IllegalStateException("Ambiguous large UI tree");
      for (int j = 0; j < node.getChildCount(); j++) {
        AccessibilityNodeInfo child = node.getChild(j);
        if (child != null) {
          if (result.size() >= 600) throw new IllegalStateException("Ambiguous large UI tree");
          result.add(child);
        }
      }
    }
    return result;
  }
}
