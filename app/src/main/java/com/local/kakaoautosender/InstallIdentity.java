package com.local.kakaoautosender;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class InstallIdentity {
  private static final String ID = "km_install_v2", VAULT = "km_session_v2";

  private static KeyStore store() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    return ks;
  }

  static synchronized String publicKey() throws Exception {
    AppIntegrity.requireAuthentic();
    KeyStore ks = store();
    if (!ks.containsAlias(ID)) {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
      gen.initialize(
          new KeyGenParameterSpec.Builder(
                  ID, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
              .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
              .setDigests(KeyProperties.DIGEST_SHA256)
              .build());
      gen.generateKeyPair();
    }
    return encode(store().getCertificate(ID).getPublicKey().getEncoded());
  }

  static String sign(String value) throws Exception {
    AppIntegrity.requireAuthentic();
    publicKey();
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign((java.security.PrivateKey) store().getKey(ID, null));
    s.update(value.getBytes(StandardCharsets.UTF_8));
    return encode(rawSignature(s.sign()));
  }

  // Android emits ASN.1 DER; WebCrypto ECDSA expects fixed-width IEEE P1363.
  static byte[] rawSignature(byte[] der) {
    byte[] raw = new byte[64];
    int pos = 2;
    if (der[0] != 0x30 || der[pos++] != 0x02) throw new IllegalArgumentException("DER");
    int r = der[pos++] & 255;
    int rSkip = r > 32 ? r - 32 : 0;
    System.arraycopy(der, pos + rSkip, raw, 32 - (r - rSkip), r - rSkip);
    pos += r;
    if (der[pos++] != 0x02) throw new IllegalArgumentException("DER");
    int n = der[pos++] & 255;
    int skip = n > 32 ? n - 32 : 0;
    System.arraycopy(der, pos + skip, raw, 64 - (n - skip), n - skip);
    return raw;
  }

  static String sha(String value) throws Exception {
    byte[] bytes =
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder out = new StringBuilder();
    for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return out.toString();
  }

  private static SecretKey vault() throws Exception {
    AppIntegrity.requireAuthentic();
    KeyStore ks = store();
    if (!ks.containsAlias(VAULT)) {
      KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      g.init(
          new KeyGenParameterSpec.Builder(
                  VAULT, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setKeySize(256)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      g.generateKey();
    }
    return (SecretKey) store().getKey(VAULT, null);
  }

  static void saveTokens(Context c, String access, String refresh) throws Exception {
    AppIntegrity.initialize(c);
    AppIntegrity.requireAuthentic();
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, vault());
    byte[] encrypted = cipher.doFinal((access + "\n" + refresh).getBytes(StandardCharsets.UTF_8));
    if (!c.getSharedPreferences("entitlement_v2", 0)
        .edit()
        .putString("tokens", encode(cipher.getIV()) + "." + encode(encrypted))
        .commit()) throw new java.io.IOException("storage");
  }

  static String[] tokens(Context c) {
    try {
      AppIntegrity.initialize(c);
      AppIntegrity.requireAuthentic();
      String value = c.getSharedPreferences("entitlement_v2", 0).getString("tokens", "");
      String[] parts = value.split("\\.");
      if (parts.length != 2) return new String[] {"", ""};
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, vault(), new GCMParameterSpec(128, decode(parts[0])));
      return new String(cipher.doFinal(decode(parts[1])), StandardCharsets.UTF_8).split("\n", -1);
    } catch (Exception e) {
      return new String[] {"", ""};
    }
  }

  static String encode(byte[] b) {
    return Base64.encodeToString(b, Base64.NO_WRAP);
  }

  static byte[] decode(String s) {
    return Base64.decode(s, Base64.NO_WRAP);
  }
}
