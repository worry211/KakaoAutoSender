package com.local.kakaoautosender;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppIntegrityTest {
  @Test
  public void releaseCertificateFingerprintMustMatchExactly() {
    assertTrue(
        AppIntegrity.certificateMatches(
            "7fbfc166fa8a8cc397e17d13ca912f52c37776f3a5f6267261c626ced88ef2d5"));
    assertTrue(
        AppIntegrity.certificateMatches(
            "7FBFC166FA8A8CC397E17D13CA912F52C37776F3A5F6267261C626CED88EF2D5"));
    assertFalse(AppIntegrity.certificateMatches("00"));
    assertFalse(AppIntegrity.certificateMatches(null));
  }
}
