package io.seedmatic.rke2lab.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code github.app} block is what the readers want; its siblings must survive verbatim. */
class DotSecretsWriterTest {

  @TempDir Path dir;

  private static Map<String, String> app() {
    final Map<String, String> leaves = new LinkedHashMap<>();
    leaves.put("appId", "1");
    leaves.put("privateKeyPem", "-----BEGIN-----\nAAA\n-----END-----\n");
    return leaves;
  }

  private String upsert(String existing) throws IOException {
    final Path file = dir.resolve(".secrets");
    Files.writeString(file, existing);
    DotSecretsWriter.upsert(file, List.of("github", "app"), app(), Set.of("privateKeyPem"));
    return Files.readString(file);
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
