package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import java.lang.reflect.Field;
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
  private View find(View view, String text) {
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = find(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private void field(Object object, String name, Object value) throws Exception {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(object, value);
  }

  @Test
  public void learningRejectionDoesNotRebuildAndClearRecipientConfirmation() throws Exception {
    PhotoKeyboardService keyboard =
        Robolectric.buildService(PhotoKeyboardService.class).create().get();
    try {
      Field sessionField = PhotoKeyboardService.class.getDeclaredField("session");
      sessionField.setAccessible(true);
      PhotoKeyboardSession session = (PhotoKeyboardSession) sessionField.get(keyboard);
      session.invalidate(true);
      RoomMediaStore.Media media =
          new RoomMediaStore.Media("content://photos/test", "image/png", "test.png");
      field(keyboard, "shown", session.select("test-room", media));
      field(keyboard, "preview", Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
      View view = keyboard.onCreateInputView();
      CheckBox confirm = (CheckBox) find(view, "현재 카카오톡 방과 사진을 확인했습니다");
      View learn = find(view, "자동 전송 준비 · 전송 버튼 학습 (1회)");
      assertNotNull(confirm);
      assertNotNull(learn);
      learn.performClick();
      assertSame(confirm, find(view, "현재 카카오톡 방과 사진을 확인했습니다"));
      confirm.setChecked(true);
      learn.performClick(); // No accessibility service: visible rejection, no reset loop.
      assertSame(confirm, find(view, "현재 카카오톡 방과 사진을 확인했습니다"));
      assertTrue(confirm.isChecked());
      assertTrue(contains(view, "접근성의 사진 전송 보조를 먼저 켜세요."));
      assertEquals(
          "접근성의 사진 전송 보조를 먼저 켜세요.", org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
      assertFalse(PhotoKeyboardService.hasActiveLoop());
    } finally {
      keyboard.onDestroy();
    }
  }

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
