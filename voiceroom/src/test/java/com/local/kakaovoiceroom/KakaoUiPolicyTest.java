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
        assertFalse(KakaoUiPolicy.roomTitleMatches("1", "1 공지"));
    }

    @Test
    public void voiceRoomNameIsNeverBlankAndFitsKakaoLimitByCodePoint() {
        assertEquals("보이스룸", KakaoUiPolicy.voiceRoomName("   "));
        assertEquals("1", KakaoUiPolicy.voiceRoomName("1"));
        String result = KakaoUiPolicy.voiceRoomName("12345678901234567890123456789012345");
        assertEquals(30, result.codePointCount(0, result.length()));

        String emojiResult = KakaoUiPolicy.voiceRoomName("가나다라마바사아자차카타파하ABCDEFGHIJKLMNO😀😀😀😀😀");
        assertTrue(emojiResult.codePointCount(0, emojiResult.length()) <= 30);
        if (!emojiResult.isEmpty()) {
            assertFalse(Character.isHighSurrogate(emojiResult.charAt(emojiResult.length() - 1)));
        }
    }

    @Test
    public void retryBackoffIsProgressiveAndCapped() {
        assertEquals(60_000L, KakaoUiPolicy.retryDelayMs(1));
        assertEquals(180_000L, KakaoUiPolicy.retryDelayMs(2));
        assertEquals(600_000L, KakaoUiPolicy.retryDelayMs(3));
        assertEquals(1_800_000L, KakaoUiPolicy.retryDelayMs(4));
        assertEquals(3_600_000L, KakaoUiPolicy.retryDelayMs(5));
        assertEquals(3_600_000L, KakaoUiPolicy.retryDelayMs(100));
    }
}
