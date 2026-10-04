package com.local.kakaoautosender;

import static org.junit.Assert.*;

import org.junit.Test;

public class EntitlementPolicyTest {
  private boolean usable(String s, long age, int boot, long expiry, long grace) {
    return EntitlementPolicy.usable(s, 1000 + age, 1000, boot, 4, 100000, expiry, grace);
  }

  @Test
  public void validOnlineLease() {
    assertTrue(usable("ACTIVE", 0, 4, 100100, 600));
  }

  @Test
  public void networkOutageInsideGrace() {
    assertTrue(usable("ACTIVE", 599999, 4, 0, 600));
  }

  @Test
  public void graceBoundaryDisablesUsage() {
    assertFalse(usable("ACTIVE", 600000, 4, 0, 600));
  }

  @Test
  public void graceCannotExceedTenMinutes() {
    assertFalse(usable("ACTIVE", 600000, 4, 0, 999999));
  }

  @Test
  public void shortGraceIsRespected() {
    assertFalse(usable("ACTIVE", 30000, 4, 0, 30));
  }

  @Test
  public void zeroGracePermitsOnlyOnlineDispatchLease() {
    assertTrue(usable("ACTIVE", 9000, 4, 0, 0));
    assertFalse(usable("ACTIVE", 10000, 4, 0, 0));
  }

  @Test
  public void rebootCannotReuseCachedLease() {
    assertFalse(usable("ACTIVE", 0, 5, 0, 600));
  }

  @Test
  public void unknownBootFailsClosed() {
    assertFalse(usable("ACTIVE", 0, -1, 0, 600));
  }

  @Test
  public void monotonicRollbackFailsClosed() {
    assertFalse(usable("ACTIVE", -1, 4, 0, 600));
  }

  @Test
  public void serverAnchoredExpiryBlocksInsideGrace() {
    assertFalse(usable("ACTIVE", 5000, 4, 100005, 600));
  }

  @Test
  public void permanentLicenseHasNoExpiry() {
    assertTrue(usable("ACTIVE", 100, 4, 0, 600));
  }

  @Test
  public void explicitSuspensionNeverGetsGrace() {
    assertFalse(usable("SUSPENDED", 0, 4, 0, 600));
  }

  @Test
  public void explicitRevocationNeverGetsGrace() {
    assertFalse(usable("REVOKED", 0, 4, 0, 600));
  }

  @Test
  public void explicitDeletionNeverGetsGrace() {
    assertFalse(usable("DELETED", 0, 4, 0, 600));
  }

  @Test
  public void explicitExpiryNeverGetsGrace() {
    assertFalse(usable("EXPIRED", 0, 4, 0, 600));
  }

  @Test
  public void updateRequiredNeverGetsGrace() {
    assertFalse(usable("UPDATE_REQUIRED", 0, 4, 0, 600));
  }

  @Test
  public void maintenanceNeverGetsGrace() {
    assertFalse(usable("MAINTENANCE", 0, 4, 0, 600));
  }

  @Test
  public void missingAndMismatchedLicensesFailClosed() {
    assertFalse(usable("NOT_FOUND", 0, 4, 0, 600));
    assertFalse(usable("DEVICE_MISMATCH", 0, 4, 0, 600));
  }

  @Test
  public void exactUsefulKoreanReasons() {
    assertEquals("라이선스가 만료되었습니다.", EntitlementPolicy.message("EXPIRED"));
    assertEquals("라이선스가 정지되었습니다.", EntitlementPolicy.message("SUSPENDED"));
    assertEquals("라이선스가 취소되었습니다.", EntitlementPolicy.message("REVOKED"));
    assertEquals("존재하지 않는 라이선스입니다.", EntitlementPolicy.message("DELETED"));
    assertEquals("계속 사용하려면 앱 업데이트가 필요합니다.", EntitlementPolicy.message("UPDATE_REQUIRED"));
    assertEquals("이미 사용된 라이선스 키입니다.", EntitlementPolicy.message("ALREADY_USED"));
  }
}
