package com.local.kakaovoiceroom;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KakaoUiPolicyTest {
    @Test public void exactTitleMatches() {
        assertTrue(KakaoUiPolicy.roomTitleMatches("게임방", "게임방"));
    }

    @Test public void mergedParticipantCountMatches() {
        assertTrue(KakaoUiPolicy.roomTitleMatches("게임방", "게임방 386"));
        assertTrue(KakaoUiPolicy.roomTitleMatches("1", "1 1"));
        assertTrue(KakaoUiPolicy.roomTitleMatches("게임방", "게임방\n1,234"));
    }

    @Test public void similarTitleDoesNotMatch() {
        assertFalse(KakaoUiPolicy.roomTitleMatches("1", "11"));
        assertFalse(KakaoUiPolicy.roomTitleMatches("게임", "게임방 3"));
        assertFalse(KakaoUiPolicy.roomTitleMatches("게임방", "게임방 공지"));
    }

    @Test public void urlValidationIsNarrow() {
        assertTrue(KakaoUiPolicy.isOpenChatUrl("https://open.kakao.com/o/abc"));
        assertFalse(KakaoUiPolicy.isOpenChatUrl("https://example.com/o/abc"));
        assertFalse(KakaoUiPolicy.isOpenChatUrl(""));
    }

    @Test public void retryBackoffIsBounded() {
        assertEquals(60_000L, KakaoUiPolicy.retryDelayMs(1));
        assertEquals(180_000L, KakaoUiPolicy.retryDelayMs(2));
        assertEquals(600_000L, KakaoUiPolicy.retryDelayMs(3));
        assertEquals(3_600_000L, KakaoUiPolicy.retryDelayMs(99));
    }
}
