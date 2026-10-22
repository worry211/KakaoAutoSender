package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
public class PhotoFeatureVisibilityTest {
  private Context c;

  @Before
  public void setup() {
    c = RuntimeEnvironment.getApplication();
    PhotoTourController.stop(c, "reset");
    Prefs.p(c).edit().clear().commit();
  }

  private View find(View root, String text) {
    if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        View v = find(((ViewGroup) root).getChildAt(i), text);
        if (v != null) return v;
      }
    return null;
  }

  @Test
  public void setupShowsPermissionActionsAndBothPhotoPathsWithoutExpandingAdvancedSettings() {
    var activity = Robolectric.buildActivity(PhotoKeyboardSetupActivity.class).create();
    try {
      View root = activity.get().findViewById(android.R.id.content);
      for (String label :
          new String[] {
            "사진 여러 방 전송·공통 설정",
            "3. 사진 전송 보조 접근성 설정",
            "4. 정확한 예약 시간 설정",
            "5. 중지 알림 표시 허용",
            "6. 화면 끄기 최초 연결 설정",
            "사진 예약 전체 중지·화면 복구",
            "문제 확인용 상태 복사"
          }) {
        View v = find(root, label);
        assertNotNull(label, v);
        for (View current = v;
            current != null;
            current = current.getParent() instanceof View ? (View) current.getParent() : null)
          assertEquals(label, View.VISIBLE, current.getVisibility());
      }
    } finally {
      activity.destroy();
    }
  }

  @Test
  public void tourKeepsCommonSettingsPreparationTestRepeatAndStopAvailableInItsHierarchy() {
    var activity = Robolectric.buildActivity(PhotoTourActivity.class).create();
    try {
      View root = activity.get().findViewById(android.R.id.content);
      for (String label :
          new String[] {
            "1. 선택 방 공통 사진·시간 설정",
            "2. 준비 안 된 선택 방 연결",
            "3. 선택 방 1회씩 시험 · 10초 후",
            "저장한 일정으로 선택 방 반복 시작",
            "사진 여러 방 전송 전체 중지",
            "방 추가·연결·삭제 관리"
          }) assertNotNull(label, find(root, label));
      find(root, "3. 선택 방 1회씩 시험 · 10초 후").performClick();
      assertFalse(PhotoTourController.active());
    } finally {
      activity.destroy();
    }
  }

  @Test
  public void supportReportDoesNotCopyImageUrisMessagesOrLicenseKeys() {
    MultiRoomStore.Profile p = new MultiRoomStore.Profile("fixture");
    p.message = "PRIVATE_MESSAGE_MARKER";
    MultiRoomStore.upsert(c, p);
    RoomMediaStore.set(c, p.room, "content://PRIVATE_PHOTO_MARKER", "image/png", "photo.png");
    c.getSharedPreferences("entitlement_v2", 0)
        .edit()
        .putString("license_id", "PRIVATE_LICENSE_MARKER")
        .apply();
    String report = PhotoSupportReport.build(c);
    assertTrue(report.contains(BuildConfig.VERSION_NAME));
    for (String marker :
        new String[] {"PRIVATE_MESSAGE_MARKER", "PRIVATE_PHOTO_MARKER", "PRIVATE_LICENSE_MARKER"})
      assertFalse(report.contains(marker));
  }
}
