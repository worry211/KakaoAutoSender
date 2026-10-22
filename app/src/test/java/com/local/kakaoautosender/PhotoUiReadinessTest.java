package com.local.kakaoautosender;

import static org.junit.Assert.*;

import org.junit.Test;

public class PhotoUiReadinessTest {
  private final PhotoUiWorkflow.Anchor room = new PhotoUiWorkflow.Anchor(1, "editor", "header");

  @Test
  public void learnedHistoryWithoutCurrentRootNeverAdvertisesReadiness() {
    PhotoUiReadiness state =
        PhotoUiReadiness.evaluate(true, PhotoUiWorkflow.Phase.READY, room, null);
    assertFalse(state.canSchedule);
    assertFalse(state.canLearn);
    assertFalse(state.message.contains("준비 완료"));
  }

  @Test
  public void onlyExactCurrentRoomCanSchedule() {
    assertTrue(
        PhotoUiReadiness.evaluate(true, PhotoUiWorkflow.Phase.READY, room, room).canSchedule);
    for (PhotoUiWorkflow.Anchor different :
        new PhotoUiWorkflow.Anchor[] {
          new PhotoUiWorkflow.Anchor(2, "editor", "header"),
          new PhotoUiWorkflow.Anchor(1, "different", "header"),
          new PhotoUiWorkflow.Anchor(1, "editor", "other")
        })
      assertFalse(
          PhotoUiReadiness.evaluate(true, PhotoUiWorkflow.Phase.READY, room, different)
              .canSchedule);
  }

  @Test
  public void pendingWorkflowBlocksNewLearningAndScheduling() {
    for (PhotoUiWorkflow.Phase phase :
        new PhotoUiWorkflow.Phase[] {
          PhotoUiWorkflow.Phase.LEARNING, PhotoUiWorkflow.Phase.LEARN_RETURN,
          PhotoUiWorkflow.Phase.AUTO_PREVIEW, PhotoUiWorkflow.Phase.AUTO_RETURN
        }) {
      PhotoUiReadiness state = PhotoUiReadiness.evaluate(true, phase, room, room);
      assertFalse(state.canSchedule);
      assertFalse(state.canLearn);
    }
  }

  @Test
  public void disconnectedServiceDoesNotUseStoredHistory() {
    PhotoUiReadiness state =
        PhotoUiReadiness.evaluate(false, PhotoUiWorkflow.Phase.READY, room, room);
    assertFalse(state.canSchedule);
    assertFalse(state.canLearn);
  }

  @Test
  public void freshKnownRoomAllowsLearningOnly() {
    PhotoUiReadiness state =
        PhotoUiReadiness.evaluate(true, PhotoUiWorkflow.Phase.IDLE, null, room);
    assertTrue(state.canLearn);
    assertFalse(state.canSchedule);
  }
}
