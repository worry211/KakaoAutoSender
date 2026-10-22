package com.local.kakaoautosender;

import android.app.Activity;
import android.os.Build;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ScrollView;

/**
 * Own insets on these two setup screens so status/navigation bars and the IME never cover controls.
 */
final class PhotoSettingsLayout {
  static void apply(Activity activity, ScrollView scroll) {
    scroll.setFillViewport(true);
    scroll.setClipToPadding(true);
    apply(activity, (android.view.View) scroll);
  }

  static void apply(Activity activity, android.view.View root) {
    if (Build.VERSION.SDK_INT < 30) {
      root.setFitsSystemWindows(true);
      return;
    }
    activity.getWindow().setDecorFitsSystemWindows(false);
    activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
    root.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          android.graphics.Insets bars =
              insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
          android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
          view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
          return insets;
        });
  }
}
