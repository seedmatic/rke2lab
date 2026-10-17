package io.seedmatic.rke2lab.ndh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.seedmatic.rke2lab.clusterpki.contract.SopsEncryptionException;
import io.seedmatic.rke2lab.sops.process.SopsProcess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The v2 inventory shape, read the way ndh means it. The two generations are deliberately written
 * NEWEST FIRST in the document, so a reader that took document order instead of sorting the dated
 * slots would fail here. The store is written envelope, and the decryptor stands in for sops by
 * stripping the envelope.
 */
class DefaultNdhKeystoreReaderTest {

  private static final String V2 =
      """
      authorities:
        mammoth-skate-tls:
          type: ecdsa-sha2-nistp256
          domain: mammoth-skate.ts.net
          slots:
            26-10-05: { public: new-auth-pub, private: new-auth-priv, ca_crt: NEW-CA-CRT }
            26-05-29: { public: old-auth-pub, private: old-auth-priv, ca_crt: OLD-CA-CRT }
        leaked-tls:
          type: ecdsa-sha2-nistp256
          slots:
            26-10-05: { private: new-leaked-priv, ca_crt: NEW-LEAKED-CRT }
            26-05-29: { private: old-leaked-priv, ca_crt: OLD-LEAKED-CRT, compromised: published }
        all-leaked:
          type: ecdsa-sha2-nistp256
          slots:
            26-05-29: { private: p, ca_crt: c, compromised: published }
      keys:
        rke2-cluster:
          type: ssh-ed25519
          slots:
            26-10-05: { public: new-key-pub, private: new-key-priv }
            26-05-29: { public: old-key-pub, private: old-key-priv }
        settled:
          type: ssh-ed25519
          slots:
            26-05-29: { public: only-pub, private: only-priv }
        flat-v1:
          type: ssh-ed25519
          public: v1-pub
          private: v1-priv
      """;

  private static final String ENVELOPE = "sops:\n  version: 3.13.3\n";

  @TempDir Path dir;

  private DefaultNdhKeystoreReader reader(final String yaml) throws IOException {
    final Path file = dir.resolve("keys.yaml");
    Files.writeString(file, yaml + ENVELOPE);
    return new DefaultNdhKeystoreReader(file, envelope -> envelope.replace(ENVELOPE, ""));
  }

  @Test
  void aKeyIsReadAtItsNewestGeneration() throws IOException {
    final DefaultNdhKeystoreReader keystore = reader(V2);
    assertEquals("new-key-priv", keystore.sshPrivate("rke2-cluster"));
    assertEquals("new-key-pub", keystore.sshPublic("rke2-cluster"));
  }

  @Test
  void anAuthoritySignsWithItsOldestGenerationAndPresentsThatRoot() throws IOException {
    final DefaultNdhKeystoreReader keystore = reader(V2);
    assertEquals("old-auth-priv", keystore.authorityPrivate("mammoth-skate-tls"));
    assertEquals("OLD-CA-CRT", keystore.authorityCert("mammoth-skate-tls"));
  }

  @Test
  void aCompromisedOldestSlotNeverSignsTheNewerOneDoesAtOnce() throws IOException {
    final DefaultNdhKeystoreReader keystore = reader(V2);
    assertEquals("new-leaked-priv", keystore.authorityPrivate("leaked-tls"));
    assertEquals("NEW-LEAKED-CRT", keystore.authorityCert("leaked-tls"));
  }

  @Test
  void whenEverySlotIsCompromisedNothingSigns() throws IOException {
    final IllegalStateException raised =
        assertThrows(IllegalStateException.class, () -> reader(V2).authorityPrivate("all-leaked"));
    assertTrue(raised.getMessage().contains("compromised"), raised.getMessage());
  }

  @Test
  void aSettledEntryHasOneGenerationAndItIsRead() throws IOException {
    final DefaultNdhKeystoreReader keystore = reader(V2);
    assertEquals("only-priv", keystore.sshPrivate("settled"));
    assertEquals("only-pub", keystore.sshPublic("settled"));
  }

  @Test
  void theDomainStaysAtTheAuthorityLevel() throws IOException {
    assertEquals("mammoth-skate.ts.net", reader(V2).authorityDomain("mammoth-skate-tls"));
  }

  @Test
  void theV1FlatShapeIsRefusedAndSaysSo() throws IOException {
    final IllegalStateException raised =
        assertThrows(IllegalStateException.class, () -> reader(V2).sshPrivate("flat-v1"));
    assertTrue(raised.getMessage().contains("v1 flat"), raised.getMessage());
  }

  @Test
  void aMissingEntryIsRefused() throws IOException {
    final IllegalStateException raised =
        assertThrows(IllegalStateException.class, () -> reader(V2).sshPublic("absent"));
    assertTrue(raised.getMessage().contains("keys.absent"), raised.getMessage());
  }

  @Test
  void aStorePlaintextAtRestIsRefusedBeforeAnyDecryption() throws IOException {
    final Path file = dir.resolve("keys.yaml");
    Files.writeString(file, V2);
    final DefaultNdhKeystoreReader keystore =
        new DefaultNdhKeystoreReader(
            file,
            envelope -> {
              throw new AssertionError("a plaintext store must not reach the decryptor");
            });
    final IllegalStateException raised =
        assertThrows(IllegalStateException.class, () -> keystore.sshPublic("rke2-cluster"));
    assertTrue(raised.getMessage().contains("plaintext at rest"), raised.getMessage());
  }

  @Test
  void anAbsentStoreIsNotPresent() {
    assertFalse(
        new DefaultNdhKeystoreReader(dir.resolve("nowhere.yaml"), envelope -> envelope).present());
  }

  @Test
  void aStoreThatDoesNotOpenFailsThroughTheOneRunnerWithoutItsStderr() throws IOException {
    assumeTrue(onPath("sops"), "needs sops on PATH");
    // A sops entry that is not a mapping: the real sops refuses it on stderr ("Found sops entry
    // that is not a mapping"); the failure must name the operation and its class, not that text.
    final Path file = dir.resolve("broken.yaml");
    Files.writeString(file, "a: b\nsops: not-a-mapping\n");
    final DefaultNdhKeystoreReader keystore =
        new DefaultNdhKeystoreReader(file, new SopsProcess(Map.of(), Optional.empty()));

    final SopsEncryptionException failure =
        assertThrows(SopsEncryptionException.class, () -> keystore.authorityDomain("any"));
    assertTrue(
        failure.getMessage().startsWith("sops --decrypt exited with code"), failure.getMessage());
    assertFalse(failure.getMessage().contains("Found sops entry"), "no stderr in the message");
  }

  private static boolean onPath(String tool) {
    for (String entry : System.getenv("PATH").split(":")) {
      if (Files.isExecutable(Path.of(entry, tool))) {
        return true;
      }
    }
    return false;
  }
}
