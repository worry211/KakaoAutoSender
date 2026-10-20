package com.local.kakaoautosender;

import static org.junit.Assert.*;

import org.junit.Test;

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 28)
public class PhotoTourQueueTest {
  private PhotoTourQueue.Job job(String room, long due) {
    MultiRoomStore.Profile p = new MultiRoomStore.Profile(room);
    p.intervalMinutes = 1;
    return new PhotoTourQueue.Job(
        p, new RoomMediaStore.Media("content://photos/" + room, "image/png", room), due);
  }

  @Test
  public void serialOnceQueueConsumesEachRoomExactlyOnce() {
    PhotoTourQueue q = new PhotoTourQueue(true);
    PhotoTourQueue.Job a = job("a", 1000), b = job("b", 1000);
    q.jobs.add(a);
    q.jobs.add(b);
    assertNull(q.claim(999));
    assertSame(a, q.claim(1000));
    assertNull(q.claim(1000));
    q.finish(b, 1000);
    assertSame(a, q.inFlight);
    q.finish(a, 1001);
    assertTrue(a.done);
    assertSame(b, q.claim(1001));
    q.finish(b, 1002);
    assertEquals(0, q.nextAt());
    assertNull(q.claim(900000));
  }

  @Test
  public void overdueRepeatsAdvanceFromNowWithoutCatchUpBurst() {
    PhotoTourQueue q = new PhotoTourQueue(false);
    PhotoTourQueue.Job a = job("a", 1000);
    q.jobs.add(a);
    assertSame(a, q.claim(900000));
    q.finish(a, 900000);
    assertEquals(960000, a.next);
    assertNull(q.claim(900000));
    assertSame(a, q.claim(960000));
  }

  @Test
  public void stopInvalidatesPendingAndDuplicateCompletion() {
    PhotoTourQueue q = new PhotoTourQueue(false);
    PhotoTourQueue.Job a = job("a", 1);
    q.jobs.add(a);
    q.claim(1);
    q.stop();
    q.finish(a, 2);
    assertNull(q.inFlight);
    assertNull(q.claim(100000));
  }

  @Test
  public void navigationNeedsFreshWindowEvidenceAndDoesNotInferDuplicateTitles() {
    PhotoUiWorkflow.Anchor a = new PhotoUiWorkflow.Anchor(1, "editor", "same");
    assertFalse(PhotoTourController.freshNavigation(null, a, false, false));
    assertFalse(PhotoTourController.freshNavigation(a, a, true, false));
    assertTrue(PhotoTourController.freshNavigation(null, a, true, false));
    assertTrue(
        PhotoTourController.freshNavigation(
            a, new PhotoUiWorkflow.Anchor(2, "editor", "same"), true, false));
    assertTrue(PhotoTourController.freshNavigation(a, a, true, true));
  }
}
