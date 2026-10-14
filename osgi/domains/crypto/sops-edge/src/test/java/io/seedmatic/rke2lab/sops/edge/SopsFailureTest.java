package io.seedmatic.rke2lab.sops.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real sops failing on a document it quotes: a YAML scalar where it expects a mapping makes it
 * answer {@code cannot unmarshal !!str `<value>`}, the value whole when it is short. The failure
 * this edge raises carries none of it — not in its message, its {@code toString} or any cause.
 */
class SopsFailureTest {

  private static final String MARKER = "LEAK7f3a";

  @TempDir Path dir;
  private Path policy;

  @BeforeEach
  void throwawayPolicy() throws IOException, InterruptedException {
    assumeTrue(onPath("sops") && onPath("age-keygen"), "needs sops and age-keygen on PATH");
    final Path key = dir.resolve("key.txt");
    new ProcessBuilder("age-keygen", "-o", key.toString())
        .redirectErrorStream(true)
        .start()
        .waitFor();
    final Matcher recipient = Pattern.compile("age1[a-z0-9]+").matcher(Files.readString(key));
    assertTrue(recipient.find());
    policy = dir.resolve(".sops.yaml");
    Files.writeString(
        policy, "creation_rules:\n  - path_regex: '.*'\n    age: " + recipient.group() + "\n");
  }

  @Test
  void aFailedEncryptQuotesNothingOfThePlaintext() {
    final SopsEncryptionException failure =
        assertThrows(
            SopsEncryptionException.class,
            () -> new ProcessBuilderSopsEncryptor().encryptYaml(MARKER + "\n", policy, "x.yaml"));

    assertTrue(failure.getMessage().contains("unparseable input"), failure.getMessage());
    for (Throwable link = failure; link != null; link = link.getCause()) {
      assertFalse(String.valueOf(link.getMessage()).contains(MARKER), "a message quotes sops");
      assertFalse(link.toString().contains(MARKER), "a toString quotes sops");
    }
  }

  @Test
  void theStderrJoinsTheMessageOnlyOnTheOperatorsOptIn() {
    System.setProperty(SopsProcess.STDERR_OPT_IN, "true");
    try {
      final SopsEncryptionException failure =
          assertThrows(
              SopsEncryptionException.class,
              () -> new ProcessBuilderSopsEncryptor().encryptYaml(MARKER + "\n", policy, "x.yaml"));
      assertTrue(failure.getMessage().contains(MARKER), "the opted-in run shows sops' stderr");
    } finally {
      System.clearProperty(SopsProcess.STDERR_OPT_IN);
    }
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
