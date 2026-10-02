package io.seedmatic.rke2lab.cellar.cipher.age;

import com.exceptionfactory.jagged.RecipientStanzaReader;
import com.exceptionfactory.jagged.RecipientStanzaWriter;
import com.exceptionfactory.jagged.framework.stream.StandardDecryptingChannelFactory;
import com.exceptionfactory.jagged.framework.stream.StandardEncryptingChannelFactory;
import com.exceptionfactory.jagged.ssh.SshEd25519RecipientStanzaReaderFactory;
import com.exceptionfactory.jagged.ssh.SshEd25519RecipientStanzaWriterFactory;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreCatalog;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import io.seedmatic.rke2lab.seed.broker.port.CellarCipher;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.List;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The age realisation of {@link CellarCipher} — the multi-recipient generalisation the passphrase
 * stand-in named, over the JDK JCA via {@code jagged} (through {@code jagged-wrap}). A cellar
 * SEALED entry is age-encrypted to the {@code rke2-cluster} OpenSSH key DIRECTLY: jagged's
 * ssh-ed25519 recipient support converts the key internally (ed25519→X25519), so there is NO {@code
 * ssh-to-age} tool, NO separate age key, and NO environment. The single source of trust is the ndh
 * key inventory — the same {@code rke2-cluster} key the cluster PKI derives its identity from — so
 * a harvest the seed seals is readable by whoever holds that key (the operator/host on read-back).
 *
 * <p>The key is read through {@link NdhKeystoreReader}: {@link #seal} addresses {@code
 * keys.rke2-cluster.public} (the {@code ssh-ed25519 AAAA…} recipient), {@link #reveal} opens with
 * {@code keys.rke2-cluster.private} (the OpenSSH private). Lazy and fail-loud: the inventory is
 * read only when a seal/reveal needs it, and a missing inventory / key throws rather than degrade
 * to plaintext — enforcing the foundation is this cipher's whole job. An error may name the key
 * entry (a public identifier); it NEVER echoes the private key material.
 *
 * <p><b>Self-identifying</b>, like the passphrase impl: {@link #seal} base64-frames the age binary
 * and prefixes {@link #MARK}, so {@link #reveal} needs no external flag — a payload without the
 * mark (a {@code PLAIN} store, or an already-clear value) is returned verbatim.
 *
 * <p>Published as an {@code immediate} {@code @Component} of the {@link CellarCipher} seam, with a
 * MANDATORY {@link Reference} to {@link NdhKeystoreReader}: the age service exists ONLY where the
 * inventory is reachable (in-container, where the cellar seals), and immediate so it stays active
 * for the bundle's whole life — a consumer that resolves it by get/unget always sees a live
 * instance. Where this service is absent, the seed-broker's {@code PassphraseCellarCipher} stand-in
 * fills in. See docs/architecture/atlas/cellar-secrets.adoc.
 */
@Component(service = CellarCipher.class, immediate = true)
public final class AgeCellarCipher implements CellarCipher {

  /** The ndh inventory entry the cellar seals to / reveals with — its single source of trust. */
  static final String CLUSTER_KEY = NdhKeystoreCatalog.CLUSTER_SSH_KEY.entryName();

  private static final String MARK = "cellar:age:v1:";

  private final NdhKeystoreReader keystore;

  @Activate
  public AgeCellarCipher(@Reference NdhKeystoreReader keystore) {
    this.keystore = keystore;
  }

  @Override
  public String seal(String plaintext) {
    return sealWith(plaintext, List.of(recipientWriter()));
  }

  @Override
  public String reveal(String payload) {
    if (payload == null || !payload.startsWith(MARK)) {
      return payload; // not age-sealed — a PLAIN store, or an already-clear value
    }
    return revealWith(payload, List.of(identityReader()));
  }

  /** The seal recipient — the {@code rke2-cluster} ssh public key. */
  private RecipientStanzaWriter recipientWriter() {
    requirePresent();
    final byte[] publicKey = keystore.sshPublic(CLUSTER_KEY).getBytes(StandardCharsets.UTF_8);
    try {
      return SshEd25519RecipientStanzaWriterFactory.newRecipientStanzaWriter(publicKey);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(
          "invalid ssh recipient for the cellar seal: keys." + CLUSTER_KEY + ".public", e);
    }
  }

  /** The reveal identity — the {@code rke2-cluster} ssh private key. Never echoes the key. */
  private RecipientStanzaReader identityReader() {
    requirePresent();
    final byte[] privateKey = keystore.sshPrivate(CLUSTER_KEY).getBytes(StandardCharsets.UTF_8);
    try {
      return SshEd25519RecipientStanzaReaderFactory.newRecipientStanzaReader(privateKey);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(
          "invalid ssh identity for the cellar reveal: keys." + CLUSTER_KEY + ".private", e);
    }
  }

  private void requirePresent() {
    if (!keystore.present()) {
      throw new IllegalStateException(
          "ndh key inventory absent — the age cellar cipher cannot reach the "
              + CLUSTER_KEY
              + " key");
    }
  }

  /**
   * The crypto core of {@link #seal}, decoupled from the key source so it is directly testable:
   * age-encrypt {@code plaintext} to {@code writers}, then base64-frame the binary under {@link
   * #MARK}.
   */
  static String sealWith(String plaintext, List<RecipientStanzaWriter> writers) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (WritableByteChannel channel =
        new StandardEncryptingChannelFactory()
            .newEncryptingChannel(Channels.newChannel(out), writers)) {
      channel.write(ByteBuffer.wrap(plaintext.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalStateException("cellar age seal failed", e);
    }
    return MARK + Base64.getEncoder().encodeToString(out.toByteArray());
  }

  /**
   * The crypto core of {@link #reveal}, decoupled from the key source: strip {@link #MARK},
   * base64-decode, and age-decrypt with {@code readers}. {@code payload} must carry the mark (the
   * public {@link #reveal} handles the pass-through of an unmarked value).
   */
  static String revealWith(String payload, List<RecipientStanzaReader> readers) {
    final byte[] ciphertext = Base64.getDecoder().decode(payload.substring(MARK.length()));
    try (ReadableByteChannel channel =
        new StandardDecryptingChannelFactory()
            .newDecryptingChannel(
                Channels.newChannel(new ByteArrayInputStream(ciphertext)), readers)) {
      return new String(Channels.newInputStream(channel).readAllBytes(), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalStateException(
          "cellar age reveal failed — wrong identity or tampered payload", e);
    }
  }
}
