package io.seedmatic.rke2lab.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.seedmatic.rke2lab.host.runtime.SopsAtRest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code github.app} block is what the readers want; its siblings must survive verbatim. The
 * splice is pure; the sealing is proved against a real sops and a throwaway age key.
 */
class DotSecretsWriterTest {

  @TempDir Path dir;

  private static Map<String, String> app() {
    final Map<String, String> leaves = new LinkedHashMap<>();
    leaves.put("appId", "1");
    leaves.put("privateKeyPem", "-----BEGIN-----\nAAA\n-----END-----\n");
    return leaves;
  }

  private static String upsert(String existing) {
    return DotSecretsWriter.splice(
        existing, List.of("github", "app"), app(), Set.of("privateKeyPem"));
  }

  /** A sops bound to a throwaway key, under a creation rule written as {@code rule}. */
  private SopsAtRest throwawaySops(String rule) throws IOException, InterruptedException {
    assumeTrue(onPath("sops") && onPath("age-keygen"), "needs sops and age-keygen on PATH");
    final Path key = dir.resolve("key.txt");
    new ProcessBuilder("age-keygen", "-o", key.toString())
        .redirectErrorStream(true)
        .start()
        .waitFor();
    final Matcher recipient = Pattern.compile("age1[a-z0-9]+").matcher(Files.readString(key));
    assertTrue(recipient.find());
    this.recipient = recipient.group();
    rule(rule);
    return new SopsAtRest(Map.of("SOPS_AGE_KEY_FILE", key.toString()));
  }

  private String recipient;

  /** Rewrite the creation rule, same throwaway recipient. */
  private void rule(String rule) throws IOException {
    Files.writeString(
        dir.resolve(".sops.yaml"),
        "creation_rules:\n  - path_regex: '.*'\n    " + rule + "\n    age: " + recipient + "\n");
  }

  @Test
  void aSiblingSecretThatWouldLoseItsEncryptionStopsTheWrite() throws Exception {
    final SopsAtRest sops = throwawaySops("encrypted_comment_regex: 'sops:encrypted'");
    final Path secrets = dir.resolve(".secrets");
    final DotSecretsWriter writer = new DotSecretsWriter(sops);
    writer.upsert(secrets, List.of("github", "webhook"), Map.of("secret", "w"), Set.of("secret"));
    final byte[] before = Files.readAllBytes(secrets);
    // The rewritten block marks nothing, so only the sibling check can refuse.
    rule("encrypted_regex: '^nothing-matches$'");
    final IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> writer.upsert(secrets, List.of("github", "app"), Map.of("appId", "1"), Set.of()));
    assertTrue(
        refused.getMessage().contains("github.webhook.secret would be written in clear"),
        refused.getMessage());
    assertArrayEquals(
        before, Files.readAllBytes(secrets), "a refused seal leaves the file as it was");
  }

  @Test
  void aSecondWriteOnTheSealedFileKeepsEveryBlock() throws Exception {
    final SopsAtRest sops = throwawaySops("encrypted_comment_regex: 'sops:encrypted'");
    final Path secrets = dir.resolve(".secrets");
    final DotSecretsWriter writer = new DotSecretsWriter(sops);
    writer.upsert(secrets, List.of("github", "webhook"), Map.of("secret", "w"), Set.of("secret"));
    writer.upsert(secrets, List.of("github", "app"), app(), Set.of("privateKeyPem"));
    writer.upsert(secrets, List.of("github", "app"), app(), Set.of("privateKeyPem"));
    final String opened = sops.open(secrets);
    assertEquals(1, opened.split("\n  app:", -1).length - 1, "the block is rewritten, not added");
    assertTrue(opened.contains("secret: w"), opened);
    assertTrue(opened.contains("AAA"), opened);
  }

  @Test
  void aLeafEncryptedOnDiskIsNotDowngradedWhenSuppliedAgainUnmarked() throws Exception {
    final SopsAtRest sops = throwawaySops("encrypted_comment_regex: 'sops:encrypted'");
    final Path secrets = dir.resolve(".secrets");
    final DotSecretsWriter writer = new DotSecretsWriter(sops);
    writer.upsert(secrets, List.of("github", "app"), app(), Set.of("privateKeyPem"));
    final byte[] before = Files.readAllBytes(secrets);
    assertThrows(
        IllegalArgumentException.class,
        () -> writer.upsert(secrets, List.of("github", "app"), app(), Set.of()));
    assertArrayEquals(before, Files.readAllBytes(secrets));
  }

  @Test
  void anInputThatAlreadyLooksLikeCiphertextIsRefused() throws Exception {
    final SopsAtRest sops = throwawaySops("encrypted_comment_regex: 'sops:encrypted'");
    final Path secrets = dir.resolve(".secrets");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DotSecretsWriter(sops)
                .upsert(
                    secrets,
                    List.of("github", "app"),
                    Map.of("privateKeyPem", "ENC[AES256_GCM,data:x,type:str]"),
                    Set.of("privateKeyPem")));
    assertFalse(Files.exists(secrets));
  }

  @Test
  void theWrittenFileIsSealedAndOpensToTheNewBlock() throws Exception {
    final SopsAtRest sops = throwawaySops("encrypted_comment_regex: 'sops:encrypted'");
    final Path secrets = dir.resolve(".secrets");
    new DotSecretsWriter(sops)
        .upsert(secrets, List.of("github", "app"), app(), Set.of("privateKeyPem"));
    final String onDisk = Files.readString(secrets);
    assertFalse(onDisk.contains("AAA"), "the private key must not reach the disk in clear");
    assertTrue(sops.open(secrets).contains("AAA"));
  }

  @Test
  void nothingIsWrittenWhenTheRuleWouldNotEncryptTheMarkedLeaf() throws Exception {
    final SopsAtRest sops = throwawaySops("encrypted_regex: '^nothing-matches$'");
    final Path secrets = dir.resolve(".secrets");
    final DotSecretsWriter writer = new DotSecretsWriter(sops);
    final IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> writer.upsert(secrets, List.of("github", "app"), app(), Set.of("privateKeyPem")));
    assertTrue(
        refused.getMessage().contains("github.app.privateKeyPem would be written in clear"),
        refused.getMessage());
    assertFalse(Files.exists(secrets), "a refused seal writes nothing");
  }

  private static boolean onPath(String tool) {
    for (final String entry : System.getenv().getOrDefault("PATH", "").split(":")) {
      if (Files.isExecutable(Path.of(entry, tool))) {
        return true;
      }
    }
    return false;
  }

  private static final String NEW_APP =
      """
        app:
          appId: "1"
          # sops:encrypted
          privateKeyPem: |
            -----BEGIN-----
            AAA
            -----END-----
      """;

  @Test
  void anExistingSubBlockIsReplacedAndItsSiblingsKept() throws IOException {
    final String before =
        """
        github:
          webhook:
            # sops:encrypted
            secret: w
          app:
            appId: "0"
            privateKeyPem: |
              old
        docker:
          configJson: d
        """;
    assertEquals(
        """
        github:
          webhook:
            # sops:encrypted
            secret: w
        """
            + NEW_APP
            + """
        docker:
          configJson: d
        """,
        upsert(before));
  }

  @Test
  void aMissingSubBlockIsAddedAtTheEndOfItsParent() throws IOException {
    final String before =
        """
        github:
          webhook:
            secret: w
        docker:
          configJson: d
        """;
    assertEquals(
        """
        github:
          webhook:
            secret: w
        """
            + NEW_APP
            + """
        docker:
          configJson: d
        """,
        upsert(before));
  }

  @Test
  void aMissingParentIsCreated() throws IOException {
    assertEquals(
        "docker:\n  configJson: d\ngithub:\n" + NEW_APP, upsert("docker:\n  configJson: d\n"));
  }
}
