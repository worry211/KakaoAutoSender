package com.local.kakaoautosender;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.Date;
import java.util.Locale;

/** Durable attempt ledger; deliberately never restores a keyboard connection after restart. */
final class PhotoLoopStore {
  private PhotoLoopStore() {}

  private static SharedPreferences p(Context c) {
    return c.getSharedPreferences("photo_loop_v1", 0);
  }

  static void arm(Context c, String run, String room, long next) {
    if (!p(c).edit()
        .putString("run", run)
        .putString("source", room)
        .putBoolean("running", true)
        .putLong("next", next)
        .putString("status", "현재 확인한 채팅방의 사진 예약 대기")
        .commit()) throw new IllegalStateException("Photo reservation persistence failed");
  }

  static boolean reserve(Context c, String run, long expected, int limit) {
    SharedPreferences prefs = p(c);
    if (!prefs.getBoolean("running", false)
        || !run.equals(prefs.getString("run", ""))
        || expected != prefs.getLong("next", 0)) return false;
    String room = prefs.getString("source", "");
    String key = LocalDate.now() + "|" + room;
    int count = prefs.getInt("attempt|" + key, 0);
    if (limit > 0 && count >= limit) return false;
    // Before touching Kakao: a crash/ambiguous response can never replay this reservation.
    SharedPreferences.Editor edit = prefs.edit();
    for (String saved : prefs.getAll().keySet())
      if (saved.startsWith("attempt|") && !saved.startsWith("attempt|" + LocalDate.now() + "|"))
        edit.remove(saved);
    return edit.putLong("next", 0)
        .putInt("attempt|" + key, count + 1)
        .putString("status", "사진 전달 요청 처리 중 · 수신 확인 전")
        .commit();
  }

  static int attemptsToday(Context c, String room) {
    return p(c).getInt("attempt|" + LocalDate.now() + "|" + room, 0);
  }

  static void accepted(Context c, String run, long next) {
    if (!run.equals(p(c).getString("run", ""))) return;
    if (!p(c).edit()
        .putLong("next", next)
        .putString(
            "status", next > 0 ? "사진 전송 절차 실행 · 실제 도착은 카카오톡에서 확인" : "사진 입력 수락 · 최종 전송·복귀 확인 대기")
        .commit()) throw new IllegalStateException("Photo outcome persistence failed");
  }

  static void stop(Context c, String reason) {
    p(c).edit()
        .putBoolean("running", false)
        .putLong("next", 0)
        .putString("status", reason)
        .commit();
  }

  static boolean recordedRunning(Context c) {
    return p(c).getBoolean("running", false);
  }

  static boolean matchesRun(Context c, String run) {
    return run.equals(p(c).getString("run", ""));
  }

  static String summary(Context c) {
    SharedPreferences prefs = p(c);
    long next = prefs.getLong("next", 0);
    String status = prefs.getString("status", "사진 키보드 예약 실행 기록 없음");
    if (prefs.getBoolean("running", false) && !PhotoKeyboardService.hasActiveLoop())
      return "입력 연결 또는 앱 실행이 끝났습니다. 채팅방에서 예약을 다시 시작하세요. 자동 복구하지 않습니다.";
    String room = prefs.getString("source", "");
    return (prefs.getBoolean("running", false) ? "현재 예약: " : "최근 예약 기록: ")
        + status
        + (next > 0
            ? "\n다음 요청: "
                + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(new Date(next))
            : "")
        + (room.isEmpty() ? "" : "\n오늘 사진 요청 시도: " + attemptsToday(c, room) + "회");
  }
}
