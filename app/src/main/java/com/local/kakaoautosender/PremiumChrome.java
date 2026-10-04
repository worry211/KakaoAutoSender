package com.local.kakaoautosender;

import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Small global presentation layer for the commercial build. No business logic lives here. */
final class PremiumChrome {
  private static final int BG = Color.rgb(9, 11, 16);
  private static final int NAV = Color.rgb(8, 10, 14);
  private static final int ACCENT = Color.rgb(110, 131, 255);
  private static final int CONTROL_MUTED = Color.rgb(102, 113, 136);
  private static final Map<View, Boolean> POLISHED =
      Collections.synchronizedMap(new WeakHashMap<>());

  private PremiumChrome() {}

  static void applyWindow(Activity activity) {
    if (activity == null) return;
    Window w = activity.getWindow();
    if (w == null) return;
    w.setStatusBarColor(BG);
    w.setNavigationBarColor(NAV);
    if (Build.VERSION.SDK_INT >= 29) {
      w.setStatusBarContrastEnforced(false);
      w.setNavigationBarContrastEnforced(false);
    }

    // ActivityLifecycleCallbacks.onActivityCreated can run before PhoneWindow has created mDecor.
    // Accessing Window#getInsetsController at that moment crashes on current Android framework code.
    View decor = w.peekDecorView();
    if (decor == null) return;

    if (Build.VERSION.SDK_INT >= 30) {
      WindowInsetsController c = w.getInsetsController();
      if (c != null)
        c.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
    } else {
      decor.setSystemUiVisibility(0);
    }
  }

  static void polish(Activity activity) {
    if (activity == null || activity.getWindow() == null) return;
    // getDecorView() here is intentional: polish runs after the Activity is resumed and the decor exists.
    View root = activity.getWindow().getDecorView();
    applyWindow(activity);
    if (root != null) polishTree(root, activity.getResources().getDisplayMetrics().density);
  }

  private static void polishTree(View v, float density) {
    if (POLISHED.put(v, Boolean.TRUE) == null) {
      if (v instanceof ScrollView) polishScroll((ScrollView) v);
      if (v instanceof TextView) polishText((TextView) v);
      if (v instanceof LinearLayout && v.getBackground() != null) {
        v.setElevation(Math.max(v.getElevation(), dp(density, 1)));
      }
      if (v instanceof EditText) polishEdit((EditText) v, density);
      if (v instanceof CompoundButton) polishCompound((CompoundButton) v);
      if (v instanceof Button) polishButton((Button) v, density);
    }
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) polishTree(g.getChildAt(i), density);
    }
  }

  private static void polishScroll(ScrollView scroll) {
    scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
    scroll.setFillViewport(true);
    // Content remains fully scrollable; only the stock settings-style gutter is hidden.
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setHorizontalScrollBarEnabled(false);
    scroll.setFadingEdgeLength(0);
  }

  private static void polishText(TextView text) {
    text.setIncludeFontPadding(false);
  }

  private static void polishEdit(EditText edit, float density) {
    edit.setElevation(dp(density, 1));
    edit.setSelectAllOnFocus(false);
  }

  private static void polishCompound(CompoundButton button) {
    int[][] states = {
      new int[] {android.R.attr.state_checked},
      new int[] {-android.R.attr.state_checked}
    };
    button.setButtonTintList(new ColorStateList(states, new int[] {ACCENT, CONTROL_MUTED}));
    button.setHapticFeedbackEnabled(true);
  }

  private static void polishButton(Button button, float density) {
    button.setAllCaps(false);
    button.setHapticFeedbackEnabled(true);
    button.setMinHeight(dp(density, 48));
    button.setStateListAnimator(buttonAnimator(dp(density, 2), dp(density, 0)));

    CharSequence raw = button.getText();
    String label = raw == null ? "" : raw.toString();
    boolean twoLineTool = label.indexOf('\n') >= 0;
    if (twoLineTool) {
      // Commercial settings cards read better as title/subtitle rows than centered stacked tiles.
      button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      button.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
      button.setPadding(dp(density, 17), dp(density, 10), dp(density, 14), dp(density, 10));
      button.setLineSpacing(0, 1.08f);
      button.setLetterSpacing(0f);
      button.setMinHeight(dp(density, 66));
    } else {
      button.setGravity(Gravity.CENTER);
      button.setLetterSpacing(0.005f);
    }

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
