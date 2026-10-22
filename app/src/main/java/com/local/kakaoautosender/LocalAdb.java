package com.local.kakaoautosender;

import android.content.Context;
import android.os.Build;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;

/**
 * Localhost only. The pairing code is never stored; the private key is in no-backup app storage.
 */
final class LocalAdb extends AbsAdbConnectionManager {
  private final PrivateKey key;
  private final Certificate certificate;

  LocalAdb(Context c) throws Exception {
    setApi(Build.VERSION.SDK_INT);
    setHostAddress("127.0.0.1");
    setTimeout(8000L, TimeUnit.MILLISECONDS);
    setThrowOnUnauthorised(true);
    File folder = new File(c.getNoBackupFilesDir(), "local-adb");
    if (!folder.isDirectory() && !folder.mkdirs()) throw new java.io.IOException("KEY_STORAGE");
    File privateFile = new File(folder, "identity.pk8"),
        certFile = new File(folder, "identity.crt");
    if (!privateFile.exists() || !certFile.exists()) {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      java.security.KeyPair pair = generator.generateKeyPair();
      X500Name name = new X500Name("CN=KakaoMacro Local Photo Display");
      AlgorithmIdentifier alg =
          new AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE);
      V3TBSCertificateGenerator tbs = new V3TBSCertificateGenerator();
      tbs.setSerialNumber(
          new org.bouncycastle.asn1.ASN1Integer(
              new BigInteger(128, new java.security.SecureRandom())));
      tbs.setSignature(alg);
      tbs.setIssuer(name);
      tbs.setSubject(name);
      tbs.setStartDate(new Time(new Date(System.currentTimeMillis() - 86400000L)));
      tbs.setEndDate(new Time(new Date(System.currentTimeMillis() + 315360000000L)));
      tbs.setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded()));
      org.bouncycastle.asn1.x509.TBSCertificate body = tbs.generateTBSCertificate();
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(pair.getPrivate());
      signer.update(body.getEncoded());
      ASN1EncodableVector sequence = new ASN1EncodableVector();
      sequence.add(body);
      sequence.add(alg);
      sequence.add(new DERBitString(signer.sign()));
      // Android files are private by default; atomic writes avoid a half key after restart.
      atomic(privateFile, pair.getPrivate().getEncoded());
      atomic(certFile, new DERSequence(sequence).getEncoded());
    }
    key =
        KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privateFile.toPath())));
    try (java.io.InputStream in = Files.newInputStream(certFile.toPath())) {
      certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
    }
  }

  private static void atomic(File path, byte[] bytes) throws Exception {
    android.util.AtomicFile file = new android.util.AtomicFile(path);
    java.io.FileOutputStream out = file.startWrite();
    try {
      out.write(bytes);
      file.finishWrite(out);
    } catch (Exception e) {
      file.failWrite(out);
      throw e;
    }
  }

  @Override
  protected PrivateKey getPrivateKey() {
    return key;
  }

  @Override
  protected Certificate getCertificate() {
    return certificate;
  }

  @Override
  protected String getDeviceName() {
    return "KakaoMacro-local-photo";
  }
}
