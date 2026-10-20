package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PhotoStatusRefreshTest {
  private boolean contains(View view, String value) {
    if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return true;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++)
        if (contains(group.getChildAt(i), value)) return true;
    }
    return false;
  }

  @Test
  public void visibleKeyboardReplacesWaitingStatusWithoutReopeningOrResending() throws Exception {
    PhotoUiService helper = Robolectric.buildService(PhotoUiService.class).create().get();
    helper.onServiceConnected();
    PhotoKeyboardService keyboard =
        Robolectric.buildService(PhotoKeyboardService.class).create().get();
    try {
      View view = keyboard.onCreateInputView();
      Method update = PhotoUiService.class.getDeclaredMethod("setStatus", String.class);
      update.setAccessible(true);
      String waiting = "전송 동작 확인 중";
      String complete = "전송 버튼 누름과 채팅방 복귀 감지";
      update.invoke(helper, waiting);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertTrue(contains(view, waiting));
      update.invoke(helper, complete);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertTrue(contains(view, complete));
      assertFalse(contains(view, waiting));
      assertFalse(PhotoKeyboardService.hasActiveLoop());
    } finally {
      keyboard.onDestroy();
      helper.onDestroy();
    }
  }
}
