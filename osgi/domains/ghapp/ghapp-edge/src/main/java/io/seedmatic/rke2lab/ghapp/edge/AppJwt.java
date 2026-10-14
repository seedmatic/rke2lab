package io.seedmatic.rke2lab.ghapp.edge;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.seedmatic.rke2lab.seed.broker.codec.WireCodec;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

/**
 * Signs the App JWT and reads the App's PEM private key — the crypto behind "being the App". The
 * GitHub App key is PKCS#1 ({@code BEGIN RSA PRIVATE KEY}); {@link #readPrivateKey} also accepts
 * PKCS#8 ({@code BEGIN PRIVATE KEY}). BouncyCastle parses the PEM; the JDK {@code SHA256withRSA}
 * signs. The JWT is short (≈10 min) and used at once to mint an installation token.
 */
final class AppJwt {

  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

  private AppJwt() {}

  static PrivateKey readPrivateKey(String pem) {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    try (PEMParser parser = new PEMParser(new StringReader(pem))) {
      final Object parsed = parser.readObject();
      final JcaPEMKeyConverter converter =
          new JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME);
      if (parsed instanceof PEMKeyPair keyPair) {
        return converter.getKeyPair(keyPair).getPrivate();
      }
      if (parsed instanceof PrivateKeyInfo info) {
        return converter.getPrivateKey(info);
      }
      throw new IllegalArgumentException(
          "unsupported App private-key PEM: " + (parsed == null ? "empty" : parsed.getClass()));
    } catch (IOException e) {
      throw new UncheckedIOException("could not read the App private key PEM", e);
    }
  }

  /**
   * A ≈10-minute RS256 JWT: {@code iss=appId}, {@code iat=now-60s} (clock skew), {@code
   * exp=now+600s}.
   */
  static String issue(String appId, PrivateKey key, Instant now, WireCodec wire) {
    final ObjectNode header = JsonNodeFactory.instance.objectNode();
    header.put("alg", "RS256").put("typ", "JWT");
    final ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload
        .put("iat", now.getEpochSecond() - 60)
        .put("exp", now.getEpochSecond() + 600)
        .put("iss", appId);
    try {
      final String signingInput =
          encode(wire.writeJson(header).getBytes(StandardCharsets.UTF_8))
              + "."
              + encode(wire.writeJson(payload).getBytes(StandardCharsets.UTF_8));
      final Signature rsa = Signature.getInstance("SHA256withRSA");
      rsa.initSign(key);
      rsa.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput + "." + encode(rsa.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("could not sign the App JWT", e);
    }
  }

  private static String encode(byte[] bytes) {
    return URL.encodeToString(bytes);
  }
}
