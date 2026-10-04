package com.local.kakaovoiceroom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KakaoUiPolicyTest {
    @Test
    public void acceptsOfficialOpenChatHttpsLinksOnly() {
        assertTrue(KakaoUiPolicy.isOpenChatUrl("https://open.kakao.com/o/abc123"));
        assertTrue(KakaoUiPolicy.isOpenChatUrl("https://open.kakao.com/o/AbC_123"));
        assertFalse(KakaoUiPolicy.isOpenChatUrl("http://open.kakao.com/o/abc123"));
        assertFalse(KakaoUiPolicy.isOpenChatUrl("https://example.com/o/abc123"));
        assertFalse(KakaoUiPolicy.isOpenChatUrl("https://open.kakao.com.evil.example/o/abc123"));
        assertFalse(KakaoUiPolicy.isOpenChatUrl("https://open.kakao.com"));
        assertFalse(KakaoUiPolicy.isOpenChatUrl("javascript:alert(1)"));
    }

    @Test
    public void roomTitleMatchesKakaoParticipantCountSuffix() {
        assertTrue(KakaoUiPolicy.roomTitleMatches("1", "1 1"));
        assertTrue(KakaoUiPolicy.roomTitleMatches("거래방", "거래방 23"));
        assertTrue(KakaoUiPolicy.roomTitleMatches("거래방", "거래방"));
        assertFalse(KakaoUiPolicy.roomTitleMatches("거래방", "다른 거래방 23"));
        assertFalse(KakaoUiPolicy.roomTitleMatches("1", "11"));
    }

    @Test
    public void voiceRoomNameIsNeverBlankAndFitsKakaoLimit() {
        assertEquals("보이스룸", KakaoUiPolicy.voiceRoomName("   "));
        assertEquals("1", KakaoUiPolicy.voiceRoomName("1"));
        String result = KakaoUiPolicy.voiceRoomName("12345678901234567890123456789012345");
        assertEquals(30, result.codePointCount(0, result.length()));
    }

    @Test
    public void retryBackoffIsBoundedAndIncreasing() {
        long one = KakaoUiPolicy.retryDelayMs(1);
        long two = KakaoUiPolicy.retryDelayMs(2);
        long five = KakaoUiPolicy.retryDelayMs(5);
        long huge = KakaoUiPolicy.retryDelayMs(100);
        assertTrue(one > 0L);
        assertTrue(two >= one);
        assertTrue(five >= two);
        assertEquals(huge, KakaoUiPolicy.retryDelayMs(999));
    }
}
