package com.local.kakaoautosender;

import static org.junit.Assert.*;

import org.junit.Test;

public class PhotoUiWorkflowTest {
  private final PhotoUiWorkflow.Anchor room =
      new PhotoUiWorkflow.Anchor(10, "editor", "header-hash");
  private final PhotoUiWorkflow.Button manual =
      new PhotoUiWorkflow.Button(11, "photo.Activity", "layout", "send", "ImageButton", "전송");

  private PhotoUiWorkflow ready() {
    PhotoUiWorkflow workflow = new PhotoUiWorkflow();
    workflow.learn(room);
    workflow.observe(manual);
    assertTrue(workflow.manualClick(11, "send"));
    assertFalse(workflow.returned(room));
    assertTrue(workflow.ready(room));
    return workflow;
  }

  @Test
  public void manualLearningRequiresMatchingClickAndReturnToExactChatWindow() {
    PhotoUiWorkflow workflow = new PhotoUiWorkflow();
    workflow.learn(room);
    workflow.observe(manual);
    assertFalse(workflow.ready(room));
    assertFalse(workflow.manualClick(12, "send"));
    assertFalse(workflow.manualClick(11, "other"));
    assertTrue(workflow.manualClick(11, "send"));
    assertFalse(workflow.returned(new PhotoUiWorkflow.Anchor(20, "editor", "header-hash")));
    assertFalse(workflow.ready(room));
    workflow.returned(room);
    assertTrue(workflow.ready(room));
  }

  @Test
  public void sameNameInAnotherWindowAndChangedHeaderCannotArm() {
    PhotoUiWorkflow workflow = ready();
    assertFalse(workflow.begin(new PhotoUiWorkflow.Anchor(99, "editor", "header-hash")));
    assertFalse(workflow.begin(new PhotoUiWorkflow.Anchor(10, "editor", "different")));
    assertFalse(workflow.begin(new PhotoUiWorkflow.Anchor(10, "another-editor", "header-hash")));
    assertTrue(workflow.begin(room));
  }

  @Test
  public void doesNotLearnOrPressChatComposerAsPhotoPreview() {
    PhotoUiWorkflow workflow = new PhotoUiWorkflow();
    workflow.learn(room);
    workflow.observe(
        new PhotoUiWorkflow.Button(10, "photo.Activity", "layout", "send", "ImageButton", "전송"));
    assertFalse(workflow.manualClick(10, "send"));
    workflow = ready();
    workflow.begin(room);
    assertFalse(
        workflow.claim(
            new PhotoUiWorkflow.Button(
                10, "photo.Activity", "layout", "send", "ImageButton", "전송")));
  }

  @Test
  public void onlyExactLearnedActivityLayoutAndButtonCanBeClickedOnce() {
    PhotoUiWorkflow workflow = ready();
    workflow.begin(room);
    assertFalse(
        workflow.claim(
            new PhotoUiWorkflow.Button(
                22, "other.Activity", "layout", "send", "ImageButton", "전송")));
    assertFalse(
        workflow.claim(
            new PhotoUiWorkflow.Button(
                22, "photo.Activity", "changed", "send", "ImageButton", "전송")));
    assertFalse(
        workflow.claim(
            new PhotoUiWorkflow.Button(
                22, "photo.Activity", "layout", "wrong", "ImageButton", "전송")));
    assertFalse(
        workflow.claim(
            new PhotoUiWorkflow.Button(22, "photo.Activity", "layout", "send", "TextView", "전송")));
    assertFalse(
        workflow.claim(
            new PhotoUiWorkflow.Button(
                22, "photo.Activity", "layout", "send", "ImageButton", "다음")));
    PhotoUiWorkflow.Button fresh =
        new PhotoUiWorkflow.Button(22, "photo.Activity", "layout", "send", "ImageButton", "전송");
    assertTrue(workflow.claim(fresh));
    assertFalse(workflow.claim(fresh));
    assertTrue(workflow.expectedAutoClick(22, "send"));
    assertFalse(workflow.expectedAutoClick(11, "send"));
    assertFalse(workflow.expectedAutoClick(22, "other"));
    assertTrue(workflow.returned(room));
    assertFalse(workflow.returned(room));
    assertTrue(workflow.ready(room));
  }

  @Test
  public void previewReturnWithoutObservedClickCannotCompleteLearningOrTriggerRetry() {
    PhotoUiWorkflow workflow = new PhotoUiWorkflow();
    workflow.learn(room);
    assertFalse(workflow.returnedWithoutClick(room));
    workflow.observe(manual);
    assertFalse(
        workflow.returnedWithoutClick(new PhotoUiWorkflow.Anchor(99, "editor", "header-hash")));
    assertTrue(workflow.returnedWithoutClick(room));
    assertFalse(workflow.ready(room));
    assertFalse(workflow.begin(room));
    assertFalse(workflow.claim(manual));
    workflow.reset();
    assertFalse(workflow.returnedWithoutClick(room));
  }

  @Test
  public void observedSendAndReturnDoesNotReportMissingClick() {
    PhotoUiWorkflow workflow = new PhotoUiWorkflow();
    workflow.learn(room);
    workflow.observe(manual);
    assertTrue(workflow.manualClick(11, "send"));
    assertFalse(workflow.returnedWithoutClick(room));
    workflow.returned(room);
    assertTrue(workflow.ready(room));
    assertFalse(workflow.returnedWithoutClick(room));
  }

  @Test
  public void resetInvalidatesLearningAutomaticClicksAndOldTimeoutRevisions() {
    PhotoUiWorkflow workflow = ready();
    workflow.begin(room);
    long revision = workflow.revision();
    workflow.reset();
    assertTrue(workflow.revision() > revision);
    assertFalse(workflow.claim(manual));
    assertFalse(workflow.ready(room));
    assertFalse(workflow.waiting());
  }
}
