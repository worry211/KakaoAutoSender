package com.local.kakaoautosender;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Serialized photo traversal with an optional, explicitly paired physical-panel-off lease. */
final class PhotoTourController {
  enum Phase {
    IDLE,
    REGISTER_OPEN,
    REGISTER_INPUT,
    REGISTER_READY,
    REGISTER_SAVED,
    WAIT_DUE,
    ROUTE_OPEN,
    ROUTE_INPUT,
    SENDING,
    SCREEN_WAIT
  }

  private static final Handler main = new Handler(Looper.getMainLooper());
  private static Context app;
  private static volatile Phase phase = Phase.IDLE;
  private static PhotoTourQueue queue;
  private static PhotoTourQueue.Job job;
  private static final ArrayDeque<String> registrations = new ArrayDeque<>();
  private static String alias = "", loopRun = "", lastAlias = "";
  private static String registrationRun = "";
  private static KakaoNotificationListener.ReplyTarget route;
  private static PhotoUiWorkflow.Anchor before, registrationAnchor, lastAnchor;
  private static long openedAt, epoch;
  private static boolean windowObserved;
  private static boolean checkingLicense;
  private static boolean panelOff;

  private PhotoTourController() {}

  static boolean active() {
    return phase != Phase.IDLE;
  }

  static boolean matchesRun(String run) {
    return queue != null && active() && queue.run.equals(run);
  }

  private static boolean panelReady() {
    return !panelOff || (queue != null && PanelOffManager.healthy(queue.run));
  }

  static boolean ownsKeyboard() {
    return active() && phase != Phase.REGISTER_READY;
  }

  static boolean canConfirmRoom() {
    return phase == Phase.REGISTER_READY
        && app != null
        && PhotoTourBindings.firstTemplate(app) != null;
  }

  static String confirmRoom(boolean confirmed) {
    if (!confirmed) return "현재 카카오톡 방과 사진을 먼저 확인하세요.";
    if (!canConfirmRoom()
        || registrationAnchor == null
        || !registrationAnchor.same(PhotoUiService.tourAnchor())) return "현재 방 연결을 확인하지 못했습니다.";
    PhotoTourBindings.Binding template = PhotoTourBindings.firstTemplate(app);
    learned(registrationAnchor, template.button);
    return phase == Phase.REGISTER_SAVED ? "" : status(app);
  }

  static void stopActive(String reason) {
    if (active() && app != null) stop(app, reason);
  }

  static void stopRun(Context c, String run) {
    if (active()
        && (queue == null
            ? !registrationRun.isEmpty() && registrationRun.equals(run)
            : queue.run.equals(run))) stop(c, "사용자가 사진 여러 방 전송을 중지했습니다.");
  }

  static boolean keyboardSelected(Context c) {
    String id =
        android.provider.Settings.Secure.getString(
            c.getContentResolver(), android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
    android.content.ComponentName current =
        id == null ? null : android.content.ComponentName.unflattenFromString(id);
    return new android.content.ComponentName(c, PhotoKeyboardService.class).equals(current);
  }

  static String status(Context c) {
    String saved = Prefs.p(c).getString("photo_tour_status", "사진 여러 방 전송이 시작되지 않았습니다.");
    if (!active() && Prefs.p(c).getBoolean("photo_tour_running", false))
      return "앱 또는 접근성 연결이 종료되어 순회가 중지됐습니다. 자동으로 재개하지 않습니다.";
    return saved;
  }

  private static void status(String value) {
    if (app == null) return;
    Prefs.p(app)
        .edit()
        .putString("photo_tour_status", value)
        .putBoolean("photo_tour_running", active())
        .commit();
    PhotoKeyboardService.uiStatusChanged();
    PhotoTourAlarm.show(app, value, queue == null ? registrationRun : queue.run);
  }

  static String routeError(Context c, String room, KakaoNotificationListener.ReplyTarget target) {
    String error = routeIdentityError(c, room, target);
    if (!error.isEmpty()) return error;
    return KakaoNotificationListener.isCurrentTarget(room, target)
        ? ""
        : "이 방의 새 카카오톡 알림 연결이 필요합니다.";
  }

  static String routeIdentityError(
      Context c, String room, KakaoNotificationListener.ReplyTarget target) {
    if (!Prefs.isRoomConfirmed(c, room)
        || target == null
        || !target.verified
        || !RoomRouting.sameTitle(room, target.label)
        || target.stableIdentityKeys.isEmpty()) return "이 방의 새 카카오톡 알림 연결이 필요합니다.";
    ArrayList<String> aliases = Prefs.aliasesForIdentity(c, target.stableIdentityKeys);
    if (aliases.size() != 1 || !RoomRouting.sameTitle(aliases.get(0), room))
      return "방 식별 정보가 겹치거나 변경됐습니다.";
    if (target.openIntent == null
        || !KakaoNotificationListener.KAKAO_PACKAGE.equals(target.openIntent.getCreatorPackage()))
      return "카카오톡이 만든 방 열기 연결이 없습니다.";
    if (android.os.Build.VERSION.SDK_INT >= 31 && !target.openIntent.isActivity())
      return "직접 채팅방을 여는 알림 연결만 사용할 수 있습니다.";
    return "";
  }

  static String prepare(Context context, List<String> rooms) {
    if (android.os.Build.VERSION.SDK_INT < 28) return "여러 방 사진 순회는 Android 9 이상에서 사용할 수 있습니다.";
    if (active() || PhotoKeyboardService.hasActiveLoop()) return "진행 중인 사진 작업을 먼저 중지하세요.";
    if (PanelOffManager.engaged()) return "화면 끄기 시험 또는 이전 작업을 먼저 중지하세요.";
    if (!PhotoUiService.connected()
        || !PhotoKeyboardSession.unlocked(context)
        || !DeliveryGate.allowed(context)) return "접근성·라이선스와 화면 잠금 해제를 확인하세요.";
    if (!keyboardSelected(context)) return "사진 키보드를 현재 키보드로 선택하세요. 키보드·접근성 설정에서 변경할 수 있습니다.";
    app = context.getApplicationContext();
    String preflight = PhotoTourReadiness.firstRoomError(app, rooms, false);
    if (!preflight.isEmpty()) return preflight;
    registrations.clear();
    for (String room : rooms) {
      MultiRoomStore.Profile p = MultiRoomStore.get(app, room);
      if (p == null || !RoomMediaStore.get(app, room).hasImage()) return "선택한 모든 방에 사진을 먼저 설정하세요.";
      if (!PhotoTourBindings.identityMatches(
              app, PhotoTourBindings.get(app, room), PhotoRoomRoutes.get(app, room))
          && !registrations.contains(room)) registrations.add(room);
    }
    if (registrations.isEmpty()) return "선택한 방은 이미 준비됐습니다. 시험 또는 반복을 시작할 수 있습니다.";
    registrationRun = java.util.UUID.randomUUID().toString();
    return openRegistration();
  }

  private static String openRegistration() {
    if (registrations.isEmpty()) {
      finish("선택한 방의 최초 준비가 끝났습니다. 앱의 사진 여러 방 전송에서 시작하세요.");
      return "";
    }
    alias = registrations.peek();
    phase = Phase.REGISTER_OPEN;
    String error = open(alias);
    if (!error.isEmpty()) {
      finish(error);
      return error;
    }
    status("방 최초 준비 중: " + title(alias) + " · 현재 방 확인 후 전송 준비 버튼을 누르세요.");
    return "";
  }

  static String start(Context context, List<String> rooms, boolean once) {
    return start(context, rooms, once, false);
  }

  static String start(Context context, List<String> rooms, boolean once, boolean usePanelOff) {
    if (android.os.Build.VERSION.SDK_INT < 28) return "여러 방 사진 순회는 Android 9 이상에서 사용할 수 있습니다.";
    if (active() || PhotoKeyboardService.hasActiveLoop()) return "진행 중인 사진 작업을 먼저 중지하세요.";
    if (!PhotoUiService.connected()
        || !PhotoKeyboardSession.unlocked(context)
        || !DeliveryGate.allowed(context)) return "접근성·라이선스와 화면 잠금 해제를 확인하세요.";
    if (!keyboardSelected(context)) return "사진 키보드를 현재 키보드로 선택하세요.";
    if (usePanelOff && !PanelOffManager.connected()) return "화면 끄기 설정에서 무선 연결을 먼저 완료하세요.";
    if (PanelOffManager.engaged()) return "화면 끄기 시험 또는 이전 작업을 먼저 중지하세요.";
    app = context.getApplicationContext();
    String preflight = PhotoTourReadiness.firstRoomError(app, rooms, true);
    if (!preflight.isEmpty()) return preflight;
    PhotoTourQueue candidate = new PhotoTourQueue(once);
    long now = System.currentTimeMillis();
    java.util.HashSet<String> unique = new java.util.HashSet<>();
    for (String room : rooms) {
      if (!unique.add(room)) continue;
      MultiRoomStore.Profile p = MultiRoomStore.get(app, room);
      RoomMediaStore.Media media = RoomMediaStore.get(app, room);
      if (p == null || !media.hasImage() || PhotoTourBindings.get(app, room) == null)
        return "선택한 모든 방의 사진 설정과 최초 준비를 완료하세요.";
      // The separate notification scheduler must never own the same photo room concurrently.
      if (p.enabled && Prefs.p(app).getBoolean(Prefs.KEY_ACTIVE, false))
        return "선택한 사진 방의 기존 자동전송 사용을 끄세요. 다른 방의 문자 예약은 유지할 수 있습니다.";
      candidate.jobs.add(
          new PhotoTourQueue.Job(
              p, media, once ? now + 10_000L : MultiRoomStore.computeNextAt(p, now)));
    }
    if (candidate.jobs.isEmpty()) return "전송할 방을 선택하세요.";
    queue = candidate;
    job = null;
    lastAlias = "";
    lastAnchor = null;
    phase = Phase.WAIT_DUE;
    panelOff = usePanelOff;
    if (panelOff) {
      String error = PanelOffManager.begin(app, queue.run);
      if (!error.isEmpty()) {
        finish(error);
        return error;
      }
    }
    status(
        "사진 순회 시작 · "
            + candidate.jobs.size()
            + "개 방 · "
            + (panelOff ? "패널 OFF 연결 준비 중 · 잠그면 중지" : "화면이 꺼지면 대기합니다."));
    schedule();
    return "";
  }

  private static String open(String room) {
    if (!DeliveryGate.allowed(app) || !PhotoKeyboardSession.unlocked(app))
      return "화면 잠금 또는 라이선스로 방 열기를 중지했습니다.";
    if (!panelReady()) return "패널 OFF 제어 연결이 확인되지 않아 방을 열지 않았습니다.";
    KakaoNotificationListener.ReplyTarget target = PhotoRoomRoutes.get(app, room);
    String error = PhotoRoomRoutes.error(app, room, target);
    if (!error.isEmpty()) return title(room) + ": " + error;
    PhotoTourBindings.Binding binding = PhotoTourBindings.get(app, room);
    if (phase == Phase.ROUTE_OPEN && !PhotoTourBindings.identityMatches(app, binding, target))
      return "저장한 방 식별 정보가 변경됐습니다.";
    route = target;
    alias = room;
    before = PhotoUiService.tourAnchor();
    openedAt = SystemClock.elapsedRealtime();
    windowObserved = false;
    registrationAnchor = null;
    PhotoUiService.cancel();
    long currentEpoch = ++epoch;
    try {
      android.os.Bundle options = null;
      if (android.os.Build.VERSION.SDK_INT >= 34)
        options =
            android.app.ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle();
      target.openIntent.send(app, 0, null, null, null, null, options);
    } catch (android.app.PendingIntent.CanceledException | SecurityException e) {
      PhotoRoomRoutes.forget(room);
      return "방 열기 연결이 만료됐습니다. 새 알림을 연결하세요.";
    }
    main.postDelayed(
        () -> {
          if (epoch == currentEpoch
              && (phase == Phase.REGISTER_OPEN
                  || phase == Phase.REGISTER_INPUT
                  || phase == Phase.REGISTER_READY
                  || phase == Phase.ROUTE_OPEN
                  || phase == Phase.ROUTE_INPUT))
            stop(app, "방 열기 또는 최초 준비 확인 시간 초과 · 사진 자동 재시도 없음");
        },
        phase == Phase.REGISTER_OPEN ? 120_000L : 15_000L);
    return "";
  }

  static void windowEvent() {
    if ((phase == Phase.REGISTER_OPEN || phase == Phase.ROUTE_OPEN)
        && SystemClock.elapsedRealtime() >= openedAt) windowObserved = true;
  }

  static boolean freshNavigation(
      PhotoUiWorkflow.Anchor before,
      PhotoUiWorkflow.Anchor current,
      boolean observed,
      boolean sameKnownRoom) {
    return observed
        && current != null
        && (before == null || !before.same(current) || sameKnownRoom);
  }

  static void observeChat(PhotoUiWorkflow.Anchor current) {
    if (app == null || !windowObserved || current == null) return;
    if (phase != Phase.REGISTER_OPEN && phase != Phase.ROUTE_OPEN) return;
    boolean known = alias.equals(lastAlias) && lastAnchor != null && lastAnchor.same(before);
    if (!freshNavigation(before, current, windowObserved, known)) return;
    if (!PhotoKeyboardSession.unlocked(app) || !DeliveryGate.allowed(app) || !panelReady()) {
      stop(app, "화면 또는 라이선스로 사진 순회 중지");
      return;
    }
    if (phase == Phase.REGISTER_OPEN) {
      registrationAnchor = current;
      phase = Phase.REGISTER_INPUT;
    } else {
      PhotoTourBindings.Binding b = PhotoTourBindings.get(app, alias);
      if (b == null || !b.matches(current)) {
        stop(app, "열린 채팅방이 저장한 방과 달라 사진을 보내지 않았습니다.");
        return;
      }
      String error = PhotoUiService.bindTour(b, current);
      if (!error.isEmpty()) {
        stop(app, error);
        return;
      }
      phase = Phase.ROUTE_INPUT;
    }
    PhotoUiService.focusTourEditor();
    PhotoKeyboardService.showForTour();
  }

  static void inputReady() {
    if (app == null) return;
    if (!panelReady()) {
      stop(app, "패널 OFF 제어 응답 중단 · 사진 입력 중지");
      return;
    }
    if (phase == Phase.REGISTER_INPUT
        && registrationAnchor != null
        && registrationAnchor.same(PhotoUiService.tourAnchor())) {
      if (PhotoKeyboardService.prepareTourPhoto(alias)) phase = Phase.REGISTER_READY;
      else main.postDelayed(PhotoTourController::inputReady, 200L);
    } else if (phase == Phase.ROUTE_INPUT) {
      PhotoTourBindings.Binding b = PhotoTourBindings.get(app, alias);
      if (b == null
          || !b.matches(PhotoUiService.tourAnchor())
          || !PhotoUiService.tourEditorEmpty()) {
        stop(app, "현재 방 또는 빈 메시지 입력창을 확인하지 못해 사진을 보내지 않았습니다.");
        return;
      }
      phase = Phase.SENDING;
      String error = PhotoKeyboardService.startTourOnce(job.profile, job.media);
      if (!error.isEmpty()) {
        stop(app, error);
        return;
      }
      status("사진 전송 절차 실행 중: " + title(alias) + " · 휴대폰을 조작하지 마세요.");
    }
  }

  static void learned(PhotoUiWorkflow.Anchor anchor, PhotoUiWorkflow.Button button) {
    if (phase != Phase.REGISTER_READY
        || app == null
        || registrationAnchor == null
        || !registrationAnchor.same(anchor)
        || route == null
        || button == null) return;
    ArrayList<String> aliases = Prefs.aliasesForIdentity(app, route.stableIdentityKeys);
    if (aliases.size() != 1 || !RoomRouting.sameTitle(aliases.get(0), alias)) {
      stop(app, "방 연결이 변경되어 준비를 저장하지 않았습니다.");
      return;
    }
    String error =
        PhotoTourBindings.save(
            app,
            new PhotoTourBindings.Binding(
                alias, anchor, route.stableIdentityKeys, PhotoTourBindings.version(app), button));
    if (!error.isEmpty()) {
      stop(app, error);
      return;
    }
    phase = Phase.REGISTER_SAVED;
    registrations.poll();
    status("방 준비 완료: " + title(alias));
    long e = ++epoch;
    main.postDelayed(
        () -> {
          if (epoch == e && phase == Phase.REGISTER_SAVED) openRegistration();
        },
        1200L);
  }

  static void bindLoop(String run) {
    if (phase == Phase.SENDING) loopRun = run;
  }

  static boolean finalGate(String run) {
    if (phase != Phase.SENDING
        || queue == null
        || job == null
        || !loopRun.equals(run)
        || route == null
        || !panelReady()) return false;
    PhotoTourBindings.Binding b = PhotoTourBindings.get(app, alias);
    return PhotoRoomRoutes.error(app, alias, route).isEmpty()
        && PhotoTourBindings.identityMatches(app, b, route);
  }

  static void completed(String room, String run) {
    if (phase != Phase.SENDING
        || queue == null
        || job == null
        || !alias.equals(room)
        || !loopRun.equals(run)) return;
    lastAlias = alias;
    lastAnchor = PhotoUiService.tourAnchor();
    loopRun = "";
    registrationRun = "";
    job.result = "전송 절차·방 복귀 확인 · 수신 별도 확인";
    queue.finish(job, System.currentTimeMillis());
    job = null;
    phase = Phase.WAIT_DUE;
    ++epoch;
    status("전송 절차 실행 및 방 복귀 확인: " + title(room) + " · 상대 수신은 별도 확인");
    main.postDelayed(PhotoTourController::schedule, 2000L);
  }

  static void tick(Context context, String run) {
    if (queue == null) {
      recoverOrphan(context);
      return;
    }
    if (!queue.run.equals(run) || phase != Phase.WAIT_DUE) return;
    if (panelOff && !panelReady()) {
      stop(app, "패널 OFF 연결이 확인되지 않아 사진 순회를 중지했습니다.");
      return;
    }
    if (!PhotoKeyboardSession.unlocked(app)) {
      phase = Phase.SCREEN_WAIT;
      status("화면 꺼짐/잠금으로 대기 중 · 화면을 켜고 잠금을 풀면 계속합니다.");
      return;
    }
    if (AppIntegrity.isAuthentic(app) && !LicenseManager.isUsable(app)) {
      if (checkingLicense) return;
      checkingLicense = true;
      String expected = queue.run;
      LicenseManager.checkAsync(
          app,
          verification -> {
            checkingLicense = false;
            if (queue == null || !expected.equals(queue.run) || phase != Phase.WAIT_DUE) return;
            if (verification.valid) tick(app, expected);
            else stop(app, "라이선스 서버 확인 실패로 사진 순회 중지");
          });
      return;
    }
    if (!DeliveryGate.allowed(app)) {
      stop(app, "라이선스 또는 앱 인증 확인 필요로 사진 순회 중지");
      return;
    }
    job = queue.claim(System.currentTimeMillis());
    if (job == null) {
      schedule();
      return;
    }
    MultiRoomStore.Profile saved = MultiRoomStore.get(app, job.profile.room);
    if (saved == null
        || !PhotoLoopSession.sameSettings(saved, job.profile)
        || !PhotoKeyboardSession.sameMedia(RoomMediaStore.get(app, job.profile.room), job.media)) {
      stop(app, "방 설정 또는 사진이 바뀌어 순회를 중지했습니다.");
      return;
    }
    if (job.profile.dailyLimit > 0
        && PhotoLoopStore.attemptsToday(app, job.profile.room) >= job.profile.dailyLimit) {
      job.result = "오늘 요청 한도로 건너뜀";
      queue.finish(job, System.currentTimeMillis());
      if (!queue.once)
        job.next = MultiRoomStore.nextAfterDailyLimit(job.profile, System.currentTimeMillis());
      job = null;
      schedule();
      return;
    }
    phase = Phase.ROUTE_OPEN;
    String error = open(job.profile.room);
    if (!error.isEmpty()) {
      stop(app, error + " · 사진을 자동 재시도하지 않습니다.");
      return;
    }
    status("채팅방 확인 중: " + title(alias));
  }

  private static void schedule() {
    if (queue == null || phase != Phase.WAIT_DUE) return;
    long next = queue.nextAt();
    if (next == 0) {
      finish("선택한 모든 방의 1회 전송 절차가 끝났습니다. 사진의 실제 도착을 확인하세요.");
      return;
    }
    try {
      PhotoTourAlarm.schedule(app, queue.run, Math.max(next, System.currentTimeMillis() + 1500L));
    } catch (RuntimeException e) {
      stop(app, "사진 순회 알람을 등록하지 못해 중지했습니다.");
    }
  }

  static void screenChanged(Context context) {
    if (!active()) return;
    if (!PhotoKeyboardSession.unlocked(context)) {
      if (panelOff) {
        stop(context, "전원 버튼 또는 보안 잠금으로 패널 OFF 사진 순회를 중지했습니다.");
        return;
      }
      if (phase == Phase.SENDING) {
        stop(context, "전송 도중 화면/잠금이 바뀌어 중지 · 도착 여부 확인 후 다시 시작하세요.");
        return;
      }
      if (queue == null) {
        stop(context, "방 준비 중 화면이 꺼져 중지했습니다.");
        return;
      }
      ++epoch;
      queue.inFlight = null;
      job = null;
      route = null;
      phase = Phase.SCREEN_WAIT;
      PhotoTourAlarm.cancel(context);
      status("화면 꺼짐/잠금으로 대기 중 · 화면을 켜고 잠금을 풀면 계속합니다.");
    } else if (phase == Phase.SCREEN_WAIT && queue != null) {
      phase = Phase.WAIT_DUE;
      tick(context, queue.run);
    }
  }

  static void stop(Context context, String reason) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      Context c = context.getApplicationContext();
      main.post(() -> stop(c, reason));
      return;
    }
    if (app == null) app = context.getApplicationContext();
    boolean wasActive = active();
    finish(reason);
    if (wasActive) PhotoKeyboardService.stopLoop(context, null, reason);
  }

  static void recoverOrphan(Context context) {
    if (!active() && Prefs.p(context).getBoolean("photo_tour_running", false))
      stop(context, "앱 또는 서비스 연결이 종료되어 사진 순회를 중지했습니다. 자동 재개하지 않습니다.");
  }

  private static void finish(String reason) {
    ++epoch;
    if (queue != null) queue.stop();
    queue = null;
    job = null;
    route = null;
    registrations.clear();
    alias = "";
    loopRun = "";
    registrationRun = "";
    checkingLicense = false;
    if (panelOff) PanelOffManager.end("사진 순회 종료 · 패널 복구 요청됨");
    panelOff = false;
    phase = Phase.IDLE;
    if (app != null) {
      PhotoTourAlarm.cancel(app);
      status(reason);
    }
  }

  private static String title(String room) {
    MultiRoomStore.Profile p = MultiRoomStore.get(app, room);
    return p == null ? room : p.title();
  }

  static String roomProgress(Context c, String room) {
    MultiRoomStore.Profile p = MultiRoomStore.get(c, room);
    if (p == null) return "삭제된 방";
    String progress = "";
    if (active() && queue == null)
      progress =
          registrations.contains(room)
              ? room.equals(alias) ? "현재 방 최초 준비 중" : "최초 준비 대기"
              : "방 준비 저장됨";
    if (queue != null)
      for (PhotoTourQueue.Job item : queue.jobs) {
        if (!item.profile.room.equals(room)) continue;
        if (item == job) progress = "현재 처리 중";
        else if (item.done) progress = item.result;
        else
          progress =
              "다음 요청 "
                  + new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.KOREA)
                      .format(new java.util.Date(item.next));
        break;
      }
    String error = active() ? "" : PhotoTourReadiness.roomError(c, room, true);
    return p.title()
        + "\n"
        + (progress.isEmpty() ? error.isEmpty() ? "준비됨" : error : progress)
        + " · 오늘 사진 요청 "
        + PhotoLoopStore.attemptsToday(c, room)
        + "회";
  }
}
