package com.local.kakaoautosender;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

public class NotificationLifetimeTest {
  @SuppressWarnings("unchecked")
  private Map<String, KakaoNotificationListener.ReplyTarget> map(String name) throws Exception {
    Field f = KakaoNotificationListener.class.getDeclaredField(name);
    f.setAccessible(true);
    return (Map<String, KakaoNotificationListener.ReplyTarget>) f.get(null);
  }

  private KakaoNotificationListener.ReplyTarget seed(String token, long time) throws Exception {
    KakaoNotificationListener.ReplyTarget t =
        new KakaoNotificationListener.ReplyTarget(
            "room", null, null, time, time, token, "", "", "room", 3, "", new ArrayList<>(), true);
    map("sessions").put("room", t);
    map("recentTargets").put(token, t);
    return t;
  }

  @After
  public void cleanup() {
    KakaoNotificationListener.clearLiveTargets();
  }

  @Test
  public void removalInvalidatesCapturedReply() throws Exception {
    var captured = seed("key", 100);
    assertTrue(KakaoNotificationListener.isCurrentTarget("room", captured));
    KakaoNotificationListener.invalidateNotification("key", 100);
    assertFalse(KakaoNotificationListener.isCurrentTarget("room", captured));
    assertNull(KakaoNotificationListener.findTarget("room"));
  }

  @Test
  public void lateRemovalCannotDeleteNewerSameKeyNotification() throws Exception {
    var newest = seed("key", 200);
    KakaoNotificationListener.invalidateNotification("key", 100);
    assertTrue(KakaoNotificationListener.isCurrentTarget("room", newest));
  }

  @Test
  public void replacementRejectsOldCapturedTarget() throws Exception {
    var old = seed("key", 100);
    var newest = seed("key", 200);
    assertFalse(KakaoNotificationListener.isCurrentTarget("room", old));
    assertTrue(KakaoNotificationListener.isCurrentTarget("room", newest));
  }

  @Test
  public void listenerDisconnectDropsOnlyRuntimeTargets() throws Exception {
    var captured = seed("key", 100);
    KakaoNotificationListener.clearLiveTargets();
    assertFalse(KakaoNotificationListener.isCurrentTarget("room", captured));
    assertTrue(map("recentTargets").isEmpty());
  }
}
