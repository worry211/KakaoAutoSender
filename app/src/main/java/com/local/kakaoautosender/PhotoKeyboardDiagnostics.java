package com.local.kakaoautosender;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.inputmethod.EditorInfo;
import java.util.ArrayList;

/** Stores capability/status only, never editor text, recipient, file URI, or keyboard input. */
final class PhotoKeyboardDiagnostics {
  private PhotoKeyboardDiagnostics() {}

  static void recordEditor(Context context, EditorInfo editor) {
    if (editor == null || !KakaoNotificationListener.KAKAO_PACKAGE.equals(editor.packageName))
      return;
    ArrayList<String> images = new ArrayList<>();
    for (String mime : PhotoKeyboardSession.mimeTypes(editor)) {
      if (mime != null && (mime.matches("image/[a-zA-Z0-9.+_*-]{1,64}") || mime.equals("*/*")))
        if (images.size() < 10 && !images.contains(mime)) images.add(mime);
    }
    context
        .getSharedPreferences("photo_keyboard_diagnostics", 0)
        .edit()
        .putLong("elapsed", SystemClock.elapsedRealtime())
        .putInt(
            "boot",
            Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1))
        .putString("types", String.join(", ", images))
        .apply();
  }

  static void recordResult(Context context, String result) {
    context
        .getSharedPreferences("photo_keyboard_diagnostics", 0)
        .edit()
        .putString("result", result)
        .apply();
  }

  static String summary(Context context) {
    android.content.SharedPreferences prefs =
        context.getSharedPreferences("photo_keyboard_diagnostics", 0);
    long elapsed = prefs.getLong("elapsed", -1);
    int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    if (elapsed < 0
        || boot < 0
        || prefs.getInt("boot", -2) != boot
        || SystemClock.elapsedRealtime() < elapsed
        || SystemClock.elapsedRealtime() - elapsed > 900_000L)
      return "사진 키보드 확인 전 · 카카오톡 채팅 입력창에서 사진 키보드를 여세요.";
    String types = prefs.getString("types", "");
    return (types.isEmpty() ? "최근 입력창: 사진 키보드 입력 미지원" : "최근 입력창이 알린 사진 형식: " + types)
        + "\n일반·오픈채팅마다 다를 수 있습니다. 지원 형식 감지는 수신 확인이 아닙니다."
        + "\n"
        + prefs.getString("result", "아직 사진 전달 요청 없음");
  }
}
