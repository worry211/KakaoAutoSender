package com.local.kakaoautosender;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KakaoMessageSenderTest {
    @Test
    public void mimeMatcher_supportsExactAndImageWildcard() {
        assertTrue(KakaoMessageSender.matchesMime("image/jpeg", "image/jpeg"));
        assertTrue(KakaoMessageSender.matchesMime("image/*", "image/png"));
        assertTrue(KakaoMessageSender.matchesMime("*/*", "image/webp"));
    }

    @Test
    public void mimeMatcher_rejectsWrongOrMissingTypes() {
        assertFalse(KakaoMessageSender.matchesMime("text/plain", "image/jpeg"));
        assertFalse(KakaoMessageSender.matchesMime("", "image/jpeg"));
        assertFalse(KakaoMessageSender.matchesMime("image/*", ""));
    }
}
