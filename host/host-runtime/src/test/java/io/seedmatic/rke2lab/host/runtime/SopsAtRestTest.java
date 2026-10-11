package io.seedmatic.rke2lab.host.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real sops against a throwaway age key: what is sealed opens again, and plaintext is refused.
 */
class SopsAtRestTest {

  private static final String PLAIN =
      """
      github:
        app:
          appId: "1"
          # sops:encrypted
          privateKeyPem: dummy-key
      """;

  @TempDir Path dir;
  private SopsAtRest sops;

  @BeforeEach
  void throwawayKey() throws IOException, InterruptedException {
    assumeTrue(onPath("sops") && onPath("age-keygen"), "needs sops and age-keygen on PATH");
    final Path key = dir.resolve("key.txt");
    new ProcessBuilder("age-keygen", "-o", key.toString())
        .redirectErrorStream(true)
        .start()
        .waitFor();
    final Matcher recipient = Pattern.compile("age1[a-z0-9]+").matcher(Files.readString(key));
    assertTrue(recipient.find());
    Files.writeString(
        dir.resolve(".sops.yaml"),
        "creation_rules:\n"
            + "  - path_regex: '.*'\n"
            + "    encrypted_comment_regex: 'sops:encrypted'\n"
            + "    age: "
            + recipient.group()
            + "\n");
    sops = new SopsAtRest(Map.of("SOPS_AGE_KEY_FILE", key.toString()));
  }

  @Test
  void aSealedDocumentOpensAgainAndOnlyTheMarkedLeafIsEncrypted() throws IOException {
    final Path secrets = dir.resolve(".secrets");
    final String sealed = sops.seal(secrets, PLAIN);
    assertFalse(sealed.contains("dummy-key"), "the marked leaf must not be in clear");
    assertTrue(sealed.contains("appId: \"1\""), "an unmarked leaf stays readable");
    Files.writeString(secrets, sealed);
    assertTrue(sops.open(secrets).contains("privateKeyPem: dummy-key"));
  }

  @Test
  void aRepositoryWithoutItsOwnSopsYamlNeverSealsUnderAParents() throws IOException {
    final Path repo = Files.createDirectories(dir.resolve("worktree"));
    Files.createDirectories(repo.resolve(".git"));
    assertThrows(IllegalStateException.class, () -> sops.seal(repo.resolve(".secrets"), PLAIN));
  }

  @Test
  void aPlaintextFileIsRefused() throws IOException {
    final Path secrets = dir.resolve(".secrets");
    Files.writeString(secrets, PLAIN);
    assertThrows(IllegalStateException.class, () -> sops.open(secrets));
  }

  private static boolean onPath(String tool) {
    for (final String entry : System.getenv().getOrDefault("PATH", "").split(":")) {
      if (Files.isExecutable(Path.of(entry, tool))) {
        return true;
      }
    }
    return false;
  }
}
