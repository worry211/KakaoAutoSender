package com.local.kakaoautosender;

import static org.junit.Assert.*;

import org.junit.Test;

public class PanelLeaseTest {
  @Test
  public void neverAllowsBeforeOffResponse() {
    PanelLease lease = new PanelLease("a");
    assertFalse(lease.allows(0));
    lease.acknowledge("a", 100, false);
    assertFalse(lease.allows(100));
  }

  @Test
  public void staleSessionCannotAuthorizeNewRun() {
    PanelLease lease = new PanelLease("new");
    assertFalse(lease.acknowledge("old", 100, true));
    assertFalse(lease.allows(100));
    assertTrue(lease.acknowledge("new", 100, true));
    assertTrue(lease.allows(100));
  }

  @Test
  public void expiredOrBackwardsClockBlocks() {
    PanelLease lease = new PanelLease("a");
    lease.acknowledge("a", 10000, true);
    assertFalse(lease.allows(9999));
    assertTrue(lease.allows(14500));
    assertFalse(lease.allows(14501));
  }

  @Test
  public void stopInvalidatesLateAcknowledgement() {
    PanelLease lease = new PanelLease("a");
    lease.acknowledge("a", 100, true);
    lease.end();
    assertFalse(lease.acknowledge("a", 200, true));
    assertFalse(lease.allows(200));
  }
}
