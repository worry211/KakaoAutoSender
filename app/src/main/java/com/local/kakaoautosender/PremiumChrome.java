package com.local.kakaoautosender;

import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Small global presentation layer for the commercial build. No business logic lives here. */
final class PremiumChrome {
  private static final int BG = Color.rgb(9, 11, 16);
  private static final int NAV = Color.rgb(8, 10, 14);
  private static final Map<View, Boolean> POLISHED =
      Collections.synchronizedMap(new WeakHashMap<>());

  private PremiumChrome() {}

  static void applyWindow(Activity activity) {
    if (activity == null) return;
    Window w = activity.getWindow();
    w.setStatusBarColor(BG);
    w.setNavigationBarColor(NAV);
    if (Build.VERSION.SDK_INT >= 29) {
      w.setStatusBarContrastEnforced(false);
      w.setNavigationBarContrastEnforced(false);
    }
    if (Build.VERSION.SDK_INT >= 30) {
      WindowInsetsController c = w.getInsetsController();
      if (c != null) c.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
    } else {
      w.getDecorView().setSystemUiVisibility(0);
    }
  }

  static void polish(Activity activity) {
    if (activity == null || activity.getWindow() == null) return;
    applyWindow(activity);
    View root = activity.getWindow().getDecorView();
    if (root != null) polishTree(root, activity.getResources().getDisplayMetrics().density);
  }

  private static void polishTree(View v, float density) {
    if (POLISHED.put(v, Boolean.TRUE) == null) {
      if (v instanceof ScrollView) {
        v.setOverScrollMode(View.OVER_SCROLL_NEVER);
        ((ScrollView) v).setFillViewport(true);
      }
      if (v instanceof TextView) ((TextView) v).setIncludeFontPadding(false);
      if (v instanceof EditText) {
        v.setElevation(dp(density, 1));
        ((EditText) v).setSelectAllOnFocus(false);
      }
      if (v instanceof Button) polishButton((Button) v, density);
    }
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) polishTree(g.getChildAt(i), density);
    }
  }

  private static void polishButton(Button button, float density) {
    button.setAllCaps(false);
    button.setHapticFeedbackEnabled(true);
    button.setLetterSpacing(0.01f);
    button.setMinHeight(dp(density, 48));
    button.setStateListAnimator(buttonAnimator(dp(density, 2), dp(density, 0)));
    button.setOnTouchListener(
        (v, event) -> {
          switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
              v.animate().scaleX(0.985f).scaleY(0.985f).alpha(0.92f).setDuration(70).start();
              break;
            case android.view.MotionEvent.ACTION_CANCEL:
            case android.view.MotionEvent.ACTION_UP:
              v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start();
              break;
            default:
              break;
          }
          return false;
        });
  }

  private static StateListAnimator buttonAnimator(float normal, float pressed) {
    StateListAnimator animator = new StateListAnimator();
    ObjectAnimator down = ObjectAnimator.ofFloat(null, "elevation", pressed);
    down.setDuration(70);
    ObjectAnimator up = ObjectAnimator.ofFloat(null, "elevation", normal);
    up.setDuration(140);
    animator.addState(new int[] {android.R.attr.state_pressed}, down);
    animator.addState(new int[] {}, up);
    return animator;
  }

  private static int dp(float density, int value) {
    return Math.round(value * density);
  }
}
