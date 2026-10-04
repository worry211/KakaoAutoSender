package com.local.kakaoautosender;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import org.junit.Test;

public class InstallSignatureTest {
    @Test public void androidDerConvertsToVerifiableWebCryptoP1363() throws Exception {
        KeyPairGenerator gen=KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));KeyPair pair=gen.generateKeyPair();
        byte[] text="KM1\nPOST\n/api/v1/heartbeat\n1234567890\nnonce\nbody\ntoken".getBytes(StandardCharsets.UTF_8);
        // Exercise variable-width/leading-zero DER integers from randomized signatures.
        for(int i=0;i<100;i++) {
            Signature signer=Signature.getInstance("SHA256withECDSA");signer.initSign(pair.getPrivate());signer.update(text);
            byte[] raw=InstallIdentity.rawSignature(signer.sign());assertEquals(64,raw.length);
            Signature verify=Signature.getInstance("SHA256withECDSAinP1363Format");verify.initVerify(pair.getPublic());verify.update(text);
            assertTrue(verify.verify(raw));
        }
    }
}
