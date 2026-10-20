package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
public class PhotoStatusRefreshTest {
  private View find(View view, String value) {
    if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = find(group.getChildAt(i), value);
        if (found != null) return found;
      }
    }
    return null;
  }

  private Object field(Object object, String name) throws Exception {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(object);
  }

  private void set(Object object, String name, Object value) throws Exception {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(object, value);
  }

  private AccessibilityNodeInfo root() {
    AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain();
    root.setPackageName(KakaoNotificationListener.KAKAO_PACKAGE);
    root.setClassName("android.widget.FrameLayout");
    root.setBoundsInScreen(new Rect(0, 0, 1080, 2400));
    AccessibilityNodeInfo title = AccessibilityNodeInfo.obtain();
    title.setPackageName(KakaoNotificationListener.KAKAO_PACKAGE);
    title.setClassName("android.widget.TextView");
    title.setViewIdResourceName("com.kakao.talk:id/title");
    title.setVisibleToUser(true);
    title.setText("시험 방");
    title.setBoundsInScreen(new Rect(0, 30, 800, 110));
    AccessibilityNodeInfo editor = AccessibilityNodeInfo.obtain();
    editor.setPackageName(KakaoNotificationListener.KAKAO_PACKAGE);
    editor.setViewIdResourceName("com.kakao.talk:id/message");
    editor.setClassName("android.widget.EditText");
    editor.setEditable(true);
    editor.setEnabled(true);
    editor.setVisibleToUser(true);
    Shadows.shadowOf(root).addChild(title);
    Shadows.shadowOf(root).addChild(editor);
    return root;
  }

  private void foreground(PhotoUiService helper, AccessibilityNodeInfo root) {
    AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
    Shadows.shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION);
    Shadows.shadowOf(window).setId(7);
    Shadows.shadowOf(window).setFocused(true);
    Shadows.shadowOf(window).setActive(true);
    Shadows.shadowOf(window).setRoot(root);
    Shadows.shadowOf(helper).setWindows(Collections.singletonList(window));
  }

  private void selected(PhotoKeyboardService keyboard) throws Exception {
    PhotoKeyboardSession session = (PhotoKeyboardSession) field(keyboard, "session");
    session.invalidate(true);
    RoomMediaStore.Media media =
        new RoomMediaStore.Media("content://photos/test", "image/png", "test.png");
    set(keyboard, "shown", session.select("test-room", media));
    set(keyboard, "preview", Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
  }

  @Test
  public void uncheckedActionAndStatusRefreshKeepConfirmationAndNeverStartSchedule()
      throws Exception {
    PhotoUiService helper = Robolectric.buildService(PhotoUiService.class).create().get();
    helper.onServiceConnected();
    foreground(helper, root());
    PhotoKeyboardService keyboard =
        Robolectric.buildService(PhotoKeyboardService.class).create().get();
    try {
      selected(keyboard);
      View view = keyboard.onCreateInputView();
      View learn = find(view, "자동 전송 준비 · 전송 버튼 학습 (1회)");
      assertNotNull(learn);
      assertTrue(learn.isEnabled());
      learn.performClick();
      assertEquals(
          "현재 카카오톡 방과 사진을 먼저 확인하세요.", org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
      CheckBox confirm = (CheckBox) find(view, "현재 카카오톡 방과 사진을 확인했습니다");
      confirm.setChecked(true);
      PhotoKeyboardService.uiStatusChanged();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertTrue(((CheckBox) find(view, "현재 카카오톡 방과 사진을 확인했습니다")).isChecked());
      assertNull(find(view, "저장한 일정으로 반복 시작"));
      assertFalse(PhotoKeyboardService.hasActiveLoop());
    } finally {
      keyboard.onDestroy();
      helper.onDestroy();
    }
  }

  @Test
  public void initialMissingRootRecoversLearningControlsWhenChatWindowAppears() throws Exception {
    PhotoUiService helper = Robolectric.buildService(PhotoUiService.class).create().get();
    helper.onServiceConnected();
    PhotoKeyboardService keyboard =
        Robolectric.buildService(PhotoKeyboardService.class).create().get();
    try {
      selected(keyboard);
      View view = keyboard.onCreateInputView();
      assertFalse(find(view, "자동 전송 준비 · 전송 버튼 학습 (1회)").isEnabled());
      set(keyboard, "status", "이전 연결 실패 안내");
      PhotoKeyboardService.uiStatusChanged();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertNotNull(find(view, "이전 연결 실패 안내"));
      ((CheckBox) find(view, "현재 카카오톡 방과 사진을 확인했습니다")).setChecked(true);
      foreground(helper, root());
      AccessibilityEvent changed =
          AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED);
      helper.onAccessibilityEvent(changed);
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150));
      assertTrue(find(view, "자동 전송 준비 · 전송 버튼 학습 (1회)").isEnabled());
      assertNull(find(view, "이전 연결 실패 안내"));
      assertTrue(((CheckBox) find(view, "현재 카카오톡 방과 사진을 확인했습니다")).isChecked());
      assertFalse(PhotoKeyboardService.hasActiveLoop());
    } finally {
      keyboard.onDestroy();
      helper.onDestroy();
    }
  }

  @Test
  public void missingCurrentRootRemovesScheduleControlsEvenAfterSuccessfulLearning()
      throws Exception {
    PhotoUiService helper = Robolectric.buildService(PhotoUiService.class).create().get();
    helper.onServiceConnected();
    AccessibilityNodeInfo root = root();
    foreground(helper, root);
    PhotoUiWorkflow workflow = (PhotoUiWorkflow) field(helper, "workflow");
    PhotoUiWorkflow.Anchor anchor = PhotoUiService.anchor(root);
    workflow.learn(anchor);
    workflow.observe(
        new PhotoUiWorkflow.Button(33, "photo.Activity", "layout", "send", "Button", "전송"));
    assertTrue(workflow.manualClick(33, "send"));
    workflow.returned(anchor);
    PhotoKeyboardService keyboard =
        Robolectric.buildService(PhotoKeyboardService.class).create().get();
    try {
      selected(keyboard);
      View view = keyboard.onCreateInputView();
      assertNotNull(find(view, "10초 후 자동전송 1회 시험"));
      assertNull(find(view, "자동 전송 준비 · 전송 버튼 학습 (1회)"));
      Shadows.shadowOf(helper).setWindows(Collections.emptyList());
      Method update = PhotoUiService.class.getDeclaredMethod("setStatus", String.class);
      update.setAccessible(true);
      update.invoke(helper, "자동화 준비 확인 완료 · 이전 기록");
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertNull(find(view, "10초 후 자동전송 1회 시험"));
      assertNull(find(view, "자동화 준비 확인 완료 · 이전 기록"));
      assertFalse(PhotoUiService.ready());
      assertFalse(find(view, "자동 전송 준비 · 전송 버튼 학습 (1회)").isEnabled());
      assertFalse(PhotoKeyboardService.hasActiveLoop());
    } finally {
      keyboard.onDestroy();
      helper.onDestroy();
    }
  }

  private void assertBoundedHeight() {
    PhotoKeyboardService keyboard =
        Robolectric.buildService(PhotoKeyboardService.class).create().get();
    try {
      View root = keyboard.onCreateInputView();
      // InputMethodService replaces the root's parameters with MATCH_PARENT/WRAP_CONTENT.
      root.setLayoutParams(new FrameLayout.LayoutParams(-1, -2));
      int width = keyboard.getResources().getDisplayMetrics().widthPixels;
      int height = keyboard.getResources().getDisplayMetrics().heightPixels;
      root.measure(
          View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
      assertTrue(root.getMeasuredHeight() > 0);
      assertTrue(root.getMeasuredHeight() <= height * 2 / 5);
    } finally {
      keyboard.onDestroy();
    }
  }

  @Test
  @Config(qualifiers = "w360dp-h800dp")
  public void portraitKeyboardLeavesChatVisibleAfterFrameworkReparentsRoot() {
    assertBoundedHeight();
  }

  @Test
  @Config(qualifiers = "w800dp-h360dp-land")
  public void landscapeKeyboardLeavesChatVisibleAfterFrameworkReparentsRoot() {
    assertBoundedHeight();
  }
}
