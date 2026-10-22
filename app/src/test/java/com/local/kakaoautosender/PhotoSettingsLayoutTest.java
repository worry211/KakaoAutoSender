package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Insets;
import android.view.WindowInsets;
import android.widget.ScrollView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 35})
public class PhotoSettingsLayoutTest {
  @Test
  public void systemBarsAndKeyboardInsetsDoNotAccumulateOrCoverControls() {
    try (org.robolectric.android.controller.ActivityController<Activity> controller =
        Robolectric.buildActivity(Activity.class).setup()) {
      Activity a = controller.get();
      ScrollView view = new ScrollView(a);
      PhotoSettingsLayout.apply(a, view);
      a.setContentView(view);
      WindowInsets bars =
          new WindowInsets.Builder()
              .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 32, 0, 48))
              .build();
      view.dispatchApplyWindowInsets(bars);
      view.dispatchApplyWindowInsets(bars);
      assertEquals(32, view.getPaddingTop());
      assertEquals(48, view.getPaddingBottom());
      WindowInsets typing =
          new WindowInsets.Builder(bars)
              .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 320))
              .build();
      view.dispatchApplyWindowInsets(typing);
      assertEquals(320, view.getPaddingBottom());
      view.dispatchApplyWindowInsets(bars);
      assertEquals(48, view.getPaddingBottom());
      assertTrue(view.isFillViewport());
    }
  }
}
