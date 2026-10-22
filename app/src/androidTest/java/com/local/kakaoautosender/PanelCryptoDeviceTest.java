package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.net.InetAddress;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.conscrypt.Conscrypt;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Android-native TLSv1.3 exporter exercise; no production network or user pairing. */
@RunWith(AndroidJUnit4.class)
public class PanelCryptoDeviceTest {
  @android.annotation.SuppressLint({"CustomX509TrustManager", "TrustAllX509TrustManager"})
  @Test
  public void packagedConscryptSupportsLocalTls13AndMatchingExportedMaterial() throws Exception {
    Context c = ApplicationProvider.getApplicationContext();
    LocalAdb identity = new LocalAdb(c);
    KeyStore keys = KeyStore.getInstance("PKCS12");
    keys.load(null, null);
    keys.setKeyEntry(
        "fixture",
        identity.getPrivateKey(),
        new char[0],
        new java.security.cert.Certificate[] {identity.getCertificate()});
    KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    km.init(keys, new char[0]);
    SSLContext tls = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider());
    tls.init(
        km.getKeyManagers(),
        new TrustManager[] {
          new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() {
              return new X509Certificate[0];
            }

            @Override
            public void checkClientTrusted(X509Certificate[] c, String a) {}

            @Override
            public void checkServerTrusted(X509Certificate[] c, String a) {}
          }
        },
        new SecureRandom());
    ExecutorService io = Executors.newSingleThreadExecutor();
    try (SSLServerSocket server =
        (SSLServerSocket)
            tls.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      server.setSoTimeout(10000);
      Future<byte[]> peer =
          io.submit(
              () -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                  socket.setSoTimeout(10000);
                  socket.startHandshake();
                  byte[] result =
                      Conscrypt.exportKeyingMaterial(socket, "adb-label-fixture", null, 32);
                  socket.getOutputStream().write(1);
                  socket.getInputStream().read();
                  return result;
                }
              });
      try (SSLSocket socket =
          (SSLSocket) tls.getSocketFactory().createSocket("127.0.0.1", server.getLocalPort())) {
        socket.setSoTimeout(10000);
        socket.startHandshake();
        byte[] material = Conscrypt.exportKeyingMaterial(socket, "adb-label-fixture", null, 32);
        assertEquals(32, material.length);
        socket.getInputStream().read();
        socket.getOutputStream().write(1);
        assertArrayEquals(material, peer.get(15, TimeUnit.SECONDS));
      }
    } finally {
      io.shutdownNow();
    }
  }
}
