package io.seedmatic.rke2lab.sops.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * A real sops against a throwaway age key: a Secret sealed under a policy has its values encrypted
 * and its identity readable, opens again with the key sops finds itself, and a path no rule
 * encrypts comes back in the clear — which is why the caller, not the tool, checks the result.
 */
class SopsPolicySealTest {

  private static final String SECRET =
      """
      apiVersion: v1
      kind: Secret
      metadata:
        name: probe
        namespace: x
      stringData:
        token: dummy-token
      """;

  @TempDir Path dir;
  private Path policy;
  private Path key;

  @BeforeEach
  void throwawayKey() throws IOException, InterruptedException {
    assumeTrue(onPath("sops") && onPath("age-keygen"), "needs sops and age-keygen on PATH");
    key = dir.resolve("key.txt");
    new ProcessBuilder("age-keygen", "-o", key.toString())
        .redirectErrorStream(true)
        .start()
        .waitFor();
    final Matcher recipient = Pattern.compile("age1[a-z0-9]+").matcher(Files.readString(key));
    assertTrue(recipient.find());
    policy = dir.resolve(".sops.yaml");
    Files.writeString(
        policy,
        "creation_rules:\n"
            + "  - path_regex: '(^|/)02-secret-[^/]*\\.yml$'\n"
            + "    encrypted_regex: '^(data|stringData)$'\n"
            + "    age: "
            + recipient.group()
            + "\n"
            + "  - path_regex: '.*'\n"
            + "    encrypted_comment_regex: 'sops:encrypted'\n"
            + "    age: "
            + recipient.group()
            + "\n");
  }

  @Test
  void aSecretSealedUnderThePolicyOpensAgainWithTheAmbientKey() {
    final String sealed =
        new ProcessBuilderSopsEncryptor().encryptYaml(SECRET, policy, "a/b/02-secret-probe.yml");
    assertTrue(sealed.contains("token: ENC["), "the stringData value is encrypted");
    assertTrue(sealed.contains("name: probe"), "metadata stays readable for Flux");
    assertFalse(sealed.contains("dummy-token"));

    final String opened =
        new ProcessBuilderSopsDecryptor(
                new SopsProcess(Map.of("SOPS_AGE_KEY_FILE", key.toString())))
            .decryptYaml(sealed);
    assertTrue(opened.contains("token: dummy-token"));
    assertFalse(opened.contains("ENC["));
  }

  @Test
  void aPathNoRuleEncryptsComesBackInTheClear() {
    final String sealed =
        new ProcessBuilderSopsEncryptor().encryptYaml(SECRET, policy, "01-secret-probe.yml");
    assertTrue(sealed.contains("token: dummy-token"), "a valid envelope around a clear value");
  }

  private static boolean onPath(String tool) {
    for (String dirName : System.getenv().getOrDefault("PATH", "").split(":")) {
      if (Files.isExecutable(Path.of(dirName, tool))) {
        return true;
      }
    }
    return false;
  }
}
