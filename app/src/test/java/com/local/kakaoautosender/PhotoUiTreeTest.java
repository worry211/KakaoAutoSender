package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PhotoUiTreeTest {
  private AccessibilityNodeInfo title, editor;

  private AccessibilityNodeInfo node(String id, String type) {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setPackageName(KakaoNotificationListener.KAKAO_PACKAGE);
    node.setViewIdResourceName(KakaoNotificationListener.KAKAO_PACKAGE + ":id/" + id);
    node.setClassName("android.widget." + type);
    node.setVisibleToUser(true);
    node.setEnabled(true);
    return node;
  }

  private void child(AccessibilityNodeInfo root, AccessibilityNodeInfo node) {
    Shadows.shadowOf(root).addChild(node);
  }

  private AccessibilityNodeInfo root() {
    AccessibilityNodeInfo root = node("root", "FrameLayout");
    root.setBoundsInScreen(new Rect(0, 0, 1080, 2400));
    title = node("title", "TextView");
    title.setText("시험 오픈채팅");
    title.setBoundsInScreen(new Rect(0, 40, 1000, 140));
    editor = node("message", "EditText");
    editor.setEditable(true);
    child(root, title);
    child(root, editor);
    return root;
  }

  @Test
  public void anchorTracksHeaderButIgnoresConversationBodyAndDraftText() {
    AccessibilityNodeInfo root = root();
    PhotoUiWorkflow.Anchor original = PhotoUiService.anchor(root);
    assertNotNull(original);
    AccessibilityNodeInfo body = node("message_text", "TextView");
    body.setText("private chat body");
    child(root, body);
    editor.setText("private draft");
    assertTrue(original.same(PhotoUiService.anchor(root)));
    body.setText("new incoming message");
    assertTrue(original.same(PhotoUiService.anchor(root)));
    title.setText("다른 방");
    assertFalse(original.same(PhotoUiService.anchor(root)));
  }

  @Test
  public void missingHeaderAndAmbiguousEditorsRejectAnchor() {
    AccessibilityNodeInfo root = root();
    title.setViewIdResourceName("unknown/header");
    assertNull(PhotoUiService.anchor(root));
    root = root();
    AccessibilityNodeInfo another = node("other", "EditText");
    another.setEditable(true);
    child(root, another);
    assertNull(PhotoUiService.anchor(root));
  }

  @Test
  public void headerBelowToolbarAreaIsNotUsedAsRoomIdentity() {
    AccessibilityNodeInfo root = root();
    title.setBoundsInScreen(new Rect(0, 1000, 500, 1100));
    assertNull(PhotoUiService.anchor(root));
  }

  @Test
  public void onlyVisibleEnabledNonEditableSendControlsAreCandidates() {
    AccessibilityNodeInfo send = node("send_photo", "ImageButton");
    send.setClickable(true);
    send.setContentDescription("전송 (1)");
    assertTrue(PhotoUiService.sendButton(send));
    assertEquals("전송(1)", PhotoUiService.label(send));
    send.setContentDescription("전송 (2)");
    assertNotEquals("전송(1)", PhotoUiService.label(send));
    send.setEnabled(false);
    assertFalse(PhotoUiService.sendButton(send));
    send.setEnabled(true);
    send.setEditable(true);
    assertFalse(PhotoUiService.sendButton(send));
    AccessibilityNodeInfo message = node("message_text", "TextView");
    message.setClickable(true);
    message.setText("전송");
    assertFalse(PhotoUiService.sendButton(message));
    send.setEditable(false);
    send.setContentDescription("다음");
    assertEquals("", PhotoUiService.label(send));
  }

  @Test
  public void sendLabelChildResolvesToObservedControlButOtherControlsDoNot() {
    AccessibilityNodeInfo send = node("send_photo", "Button");
    send.setClickable(true);
    send.setContentDescription("전송 (1)");
    AccessibilityNodeInfo label = node("send_label", "TextView");
    child(send, label);
    assertEquals(send.getViewIdResourceName(), PhotoUiService.clickedSendId(label));
    assertEquals(send.getViewIdResourceName(), PhotoUiService.clickedSendId(send));
    AccessibilityNodeInfo other = node("cancel", "Button");
    other.setClickable(true);
    child(send, other);
    assertNull(PhotoUiService.clickedSendId(other));
    label.setPackageName("other.app");
    assertNull(PhotoUiService.clickedSendId(label));
    assertNull(PhotoUiService.clickedSendId(null));
  }

  @Test
  public void learnedLayoutIgnoresPrivateTextButChangesWhenControlStructureChanges() {
    AccessibilityNodeInfo root = root();
    String layout = PhotoUiService.layout(root);
    title.setText("private title");
    editor.setText("private draft");
    assertEquals(layout, PhotoUiService.layout(root));
    child(root, node("different_control", "Button"));
    assertNotEquals(layout, PhotoUiService.layout(root));
  }

  @Test
  public void messageAttachmentTitlesInsideConversationListAreNotReadAsRoomHeader() {
    AccessibilityNodeInfo root = root();
    PhotoUiWorkflow.Anchor original = PhotoUiService.anchor(root);
    AccessibilityNodeInfo list = node("messages", "ListView");
    AccessibilityNodeInfo attachmentTitle = node("title", "TextView");
    attachmentTitle.setText("private attachment title");
    attachmentTitle.setBoundsInScreen(new Rect(0, 100, 500, 200));
    child(list, attachmentTitle);
    child(root, list);
    assertTrue(original.same(PhotoUiService.anchor(root)));
  }

  @Test
  public void oversizedTreesFailClosedInsteadOfUsingTruncatedSelectors() {
    AccessibilityNodeInfo root = root();
    for (int i = 0; i < 601; i++) child(root, node("item_" + i, "TextView"));
    assertThrows(IllegalStateException.class, () -> PhotoUiService.layout(root));
  }
}
