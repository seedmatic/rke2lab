package io.seedmatic.rke2lab.ndh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The v2 inventory shape, read the way ndh means it. The two generations are deliberately written
 * NEWEST FIRST in the document, so a reader that took document order instead of sorting the dated
 * slots would fail here.
 */
class DefaultNdhKeystoreReaderTest {

  private static final String V2 =
      """
      authorities:
        mammoth-skate-tls:
          type: ecdsa-sha2-nistp256
          ca_crt: CA-CRT
          domain: mammoth-skate.ts.net
          slots:
            26-10-05: { public: new-auth-pub, private: new-auth-priv }
            26-05-29: { public: old-auth-pub, private: old-auth-priv }
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

  @TempDir Path dir;

  private DefaultNdhKeystoreReader reader(final String yaml) throws IOException {
    final Path file = dir.resolve("keys.yaml");
    Files.writeString(file, yaml);
    return new DefaultNdhKeystoreReader(file);
  }

  @Test
  void aKeyIsReadAtItsNewestGeneration() throws IOException {
    final DefaultNdhKeystoreReader keystore = reader(V2);
    assertEquals("new-key-priv", keystore.sshPrivate("rke2-cluster"));
    assertEquals("new-key-pub", keystore.sshPublic("rke2-cluster"));
  }

  @Test
  void anAuthoritySignsWithItsOldestGeneration() throws IOException {
    assertEquals("old-auth-priv", reader(V2).authorityPrivate("mammoth-skate-tls"));
  }

  @Test
  void aSettledEntryHasOneGenerationAndItIsRead() throws IOException {
    final DefaultNdhKeystoreReader keystore = reader(V2);
    assertEquals("only-priv", keystore.sshPrivate("settled"));
    assertEquals("only-pub", keystore.sshPublic("settled"));
  }

  @Test
  void theCertificateAndDomainStayAtTheAuthorityLevel() throws IOException {
    final DefaultNdhKeystoreReader keystore = reader(V2);
    assertEquals("CA-CRT", keystore.authorityCert("mammoth-skate-tls"));
    assertEquals("mammoth-skate.ts.net", keystore.authorityDomain("mammoth-skate-tls"));
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
  void aStoreStillEncryptedAtRestIsRefused() throws IOException {
    final DefaultNdhKeystoreReader keystore = reader(V2 + "sops:\n  version: 3.9.0\n");
    final IllegalStateException raised =
        assertThrows(IllegalStateException.class, () -> keystore.sshPublic("rke2-cluster"));
    assertTrue(raised.getMessage().contains("sops-encrypted"), raised.getMessage());
  }

  @Test
  void anAbsentStoreIsNotPresent() {
    assertFalse(new DefaultNdhKeystoreReader(dir.resolve("nowhere.yaml")).present());
  }
}
