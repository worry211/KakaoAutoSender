package com.local.kakaoautosender;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.Button;

/** Consistent action hierarchy and visibly distinct unavailable states on photo screens. */
final class PhotoControls {
  static void style(Button button, String label) {
    float density = button.getResources().getDisplayMetrics().density;
    boolean stop = label.contains("중지") || label.contains("복구");
    boolean primary =
        label.contains("시작")
            || label.contains("시험")
            || label.contains("공통")
            || label.contains("학습")
            || label.contains("최초 페어링")
            || label.contains("자체 연결");
    int base = Color.rgb(31, 41, 60), border = Color.rgb(58, 73, 99);
    if (stop) {
      base = Color.rgb(66, 32, 42);
      border = Color.rgb(117, 54, 72);
    } else if (primary) {
      base = Color.rgb(87, 102, 246);
      border = Color.rgb(118, 131, 255);
    }
    StateListDrawable background = new StateListDrawable();
    background.addState(
        new int[] {-android.R.attr.state_enabled},
        shape(Color.rgb(19, 25, 37), Color.rgb(36, 46, 65), density));
    background.addState(
        new int[] {android.R.attr.state_pressed},
        shape(stop ? Color.rgb(91, 40, 54) : Color.rgb(56, 68, 145), border, density));
    background.addState(new int[] {}, shape(base, border, density));
    button.setBackgroundTintList(null);
    button.setBackground(background);
    button.setTextColor(
        new ColorStateList(
            new int[][] {{-android.R.attr.state_enabled}, {}},
            new int[] {
              Color.rgb(115, 126, 150), stop ? Color.rgb(255, 219, 229) : Color.rgb(241, 245, 255)
            }));
    button.setTextSize(14);
    button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    button.setMinHeight(Math.round(48 * density));
    button.setMinWidth(0);
    button.setPadding(
        Math.round(12 * density),
        Math.round(10 * density),
        Math.round(12 * density),
        Math.round(10 * density));
  }

  private static GradientDrawable shape(int fill, int border, float density) {
    GradientDrawable result = new GradientDrawable();
    result.setColor(fill);
    result.setCornerRadius(12 * density);
    result.setStroke(Math.max(1, Math.round(density)), border);
    return result;
  }
}
