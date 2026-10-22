package com.local.kakaoautosender;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PhotoStatusTextTest {
  @Test
  public void sameFailureInDiagnosticsUiAndSchedulerIsDisplayedOnce() {
    assertEquals(
        "지원 형식: image/jpeg\n전송 감지 실패\n오늘 시도: 1회",
        PhotoStatusText.combine("지원 형식: image/jpeg\n전송 감지 실패", "전송 감지 실패", "전송 감지 실패\n오늘 시도: 1회"));
  }

  @Test
  public void distinctOutcomesAndEmptySectionsArePreservedCorrectly() {
    assertEquals(
        "사진 전달 수락\n현재 예약 중지", PhotoStatusText.combine(null, "", "사진 전달 수락\r\n", "현재 예약 중지"));
  }
}
