package io.github.muntashirakon.adb;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Runs the actual packaged JNI and pairing AES/HKDF on Android, without real credentials. */
@RunWith(AndroidJUnit4.class)
public class WirelessPairingDeviceTest {
  @Test
  public void nativePairingCipherRoundTripAndTamperRejection() {
    PairingAuthCtx alice =
        PairingAuthCtx.createAlice("test-password".getBytes(StandardCharsets.UTF_8));
    PairingAuthCtx bob = PairingAuthCtx.createBob("test-password".getBytes(StandardCharsets.UTF_8));
    assertNotNull(alice);
    assertNotNull(bob);
    try {
      assertTrue(alice.initCipher(bob.getMsg()));
      assertTrue(bob.initCipher(alice.getMsg()));
      byte[] payload = "fixture-peer-info".getBytes(StandardCharsets.UTF_8);
      assertArrayEquals(payload, bob.decrypt(alice.encrypt(payload)));
      assertArrayEquals(payload, alice.decrypt(bob.encrypt(payload)));
      byte[] tampered = alice.encrypt(payload);
      tampered[0] ^= 1;
      assertNull(bob.decrypt(tampered));
    } finally {
      alice.destroy();
      bob.destroy();
    }
  }

  @Test
  public void mismatchedPasswordCannotAuthenticatePeerInfo() {
    PairingAuthCtx alice = PairingAuthCtx.createAlice(new byte[] {1, 2, 3});
    PairingAuthCtx bob = PairingAuthCtx.createBob(new byte[] {4, 5, 6});
    assertNotNull(alice);
    assertNotNull(bob);
    try {
      assertTrue(alice.initCipher(bob.getMsg()));
      assertTrue(bob.initCipher(alice.getMsg()));
      assertNull(bob.decrypt(alice.encrypt(new byte[] {10, 20})));
    } finally {
      alice.destroy();
      bob.destroy();
    }
  }

  @Test
  public void invalidPeerMessageThenRepeatedDestroyDoesNotFreeTwice() {
    io.github.muntashirakon.crypto.spake2.Spake2Context ctx =
        new io.github.muntashirakon.crypto.spake2.Spake2Context(
            io.github.muntashirakon.crypto.spake2.Spake2Role.Alice, new byte[] {1}, new byte[] {2});
    ctx.generateMessage(new byte[] {3});
    try {
      ctx.processMessage(new byte[] {1});
      fail("must reject invalid message");
    } catch (IllegalStateException expected) {
    } finally {
      ctx.destroy();
      ctx.destroy();
    }
    assertTrue(ctx.isDestroyed());
  }
}
