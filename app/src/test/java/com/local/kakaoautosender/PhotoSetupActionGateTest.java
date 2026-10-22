package com.local.kakaoautosender;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class PhotoSetupActionGateTest {
  @Test
  public void stopOrLeavingScreenInvalidatesDelayedAuthorization() {
    PhotoSetupActionGate gate = new PhotoSetupActionGate();
    AtomicInteger starts = new AtomicInteger();
    Runnable old = gate.guard(starts::incrementAndGet);
    assertTrue(gate.pending());
    gate.cancel();
    old.run();
    assertEquals(0, starts.get());
    assertFalse(gate.pending());
    Runnable next = gate.guard(starts::incrementAndGet);
    old.run();
    assertTrue(gate.pending());
    next.run();
    next.run();
    assertEquals(1, starts.get());
    assertFalse(gate.pending());
  }

  @Test
  public void newerRequestCannotExecuteWithOlderRoomSelection() {
    PhotoSetupActionGate gate = new PhotoSetupActionGate();
    StringBuilder sent = new StringBuilder();
    Runnable a = gate.guard(() -> sent.append("a"));
    Runnable b = gate.guard(() -> sent.append("b"));
    a.run();
    b.run();
    assertEquals("b", sent.toString());
  }
}
