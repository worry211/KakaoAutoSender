package com.local.kakaoautosender;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;

public class RoomRoutingTest {
  @Test public void canonicalTitleCollapsesUnicodeWhitespaceVariants() {
    assertTrue(RoomRouting.sameTitle("  같은\u00a0방  ", "같은 방"));
    assertTrue(RoomRouting.sameTitle("ＡＢＣ 방", "ABC 방"));
    assertTrue(RoomRouting.sameTitle("방\u200B이름", "방이름"));
  }

  @Test public void identityFingerprintIsOrderIndependent() {
    String a = RoomRouting.identityFingerprint(Arrays.asList("b", "a", "a"));
    String b = RoomRouting.identityFingerprint(Arrays.asList("a", "b"));
    assertEquals(a, b);
    assertEquals(6, RoomRouting.shortCode(a).length());
  }

  @Test public void differentRoomIdentitiesRemainDistinctEvenWithSameVisibleTitle() {
    KakaoNotificationListener.SessionEntry first = new KakaoNotificationListener.SessionEntry(
        "token-a", "", "같은 제목", 3, 100L,
        RoomRouting.identityFingerprint(Arrays.asList("shortcut:one")), true);
    KakaoNotificationListener.SessionEntry second = new KakaoNotificationListener.SessionEntry(
        "token-b", "", "같은 제목", 3, 200L,
        RoomRouting.identityFingerprint(Arrays.asList("shortcut:two")), true);
    ArrayList<KakaoNotificationListener.SessionEntry> result =
        KakaoNotificationListener.dedupeCandidateEntries(Arrays.asList(first, second));
    assertEquals(2, result.size());
    assertNotEquals(result.get(0).identityFingerprint, result.get(1).identityFingerprint);
  }

  @Test public void samePhysicalIdentityCollapsesAcrossDifferentObservedTitles() {
    String identity = RoomRouting.identityFingerprint(Arrays.asList("shortcut:same-room"));
    KakaoNotificationListener.SessionEntry oldEntry = new KakaoNotificationListener.SessionEntry(
        "token-old", "", "잘못 보인 이름", 2, 100L, identity, true);
    KakaoNotificationListener.SessionEntry newEntry = new KakaoNotificationListener.SessionEntry(
        "token-new", "", "실제 방 제목", 3, 200L, identity, true);
    ArrayList<KakaoNotificationListener.SessionEntry> result =
        KakaoNotificationListener.dedupeCandidateEntries(Arrays.asList(oldEntry, newEntry));
    assertEquals(1, result.size());
    assertEquals("실제 방 제목", result.get(0).suggestedRoom);
  }

  @Test public void runtimeFingerprintNeverPretendsToBePersistentIdentity() {
    String a = RoomRouting.runtimeFingerprint("notification-key-a");
    String b = RoomRouting.runtimeFingerprint("notification-key-b");
    assertFalse(a.isEmpty());
    assertNotEquals(a, b);
  }
}
