package io.seedmatic.rke2lab.incus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.auth.contract.AuthTokenContact;
import io.seedmatic.rke2lab.auth.contract.AuthTokenSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the comment-preserving {@code .secrets} upsert for the flox launch token (§
 * provisioning-slice #10): the token contact's value is written under the {@code flox} block, an
 * existing value is replaced in place, comments and unrelated keys survive, an environment variable
 * wins over the contact, and a missing contact + empty environment leaves the file untouched. The
 * environment is injected (empty by default here) so the tests are hermetic regardless of the
 * ambient shell. (GitHub is no longer written here — its credential flows from the App via {@code
 * ghapp}, sealed in the cellar.)
 */
class LaunchSecretsWriterTest {

  /** An empty environment — no token variable set, so the contact is the only source. */
  private static final Function<String, Optional<String>> NO_ENV = name -> Optional.empty();

  /** A contact that answers with fixed tokens per source. */
  private static AuthTokenContact contactWith(Map<AuthTokenSource, String> tokens) {
    return source -> Optional.ofNullable(tokens.get(source));
  }

  private static LaunchSecretsWriter writer(
      Optional<AuthTokenContact> contact, Function<String, Optional<String>> env) {
    return new LaunchSecretsWriter(contact, env);
  }

  @Test
  void it_appends_the_flox_block_to_a_bare_file(@TempDir Path tmp) throws IOException {
    final Path secrets = tmp.resolve(".secrets");
    Files.writeString(secrets, "# launch secrets\nregistry:\n  url: 'example.com'\n");

    writer(Optional.of(contactWith(Map.of(AuthTokenSource.FLOXHUB, "flxtok"))), NO_ENV)
        .ensureTokensPresent(secrets);

    final String result = Files.readString(secrets, StandardCharsets.UTF_8);
    assertTrue(result.contains("# launch secrets"), "the leading comment survives");
    assertTrue(result.contains("url: 'example.com'"), "the unrelated key survives");
    assertTrue(result.contains("flox:"), "the flox block is appended");
    assertTrue(result.contains("token: 'flxtok'"), "the flox token is written");
    assertFalse(result.contains("github:"), "no github block is written");
  }

  @Test
  void it_replaces_an_existing_token_preserving_comments(@TempDir Path tmp) throws IOException {
    final Path secrets = tmp.resolve(".secrets");
    Files.writeString(
        secrets,
        """
        flox:
          token: 'stale'  # rotated nightly
        """,
        StandardCharsets.UTF_8);

    writer(Optional.of(contactWith(Map.of(AuthTokenSource.FLOXHUB, "fresh"))), NO_ENV)
        .ensureTokensPresent(secrets);

    final String result = Files.readString(secrets, StandardCharsets.UTF_8);
    assertTrue(result.contains("token: 'fresh'"), "the value is replaced in place");
    assertTrue(result.contains("# rotated nightly"), "the trailing comment is kept");
    assertFalse(result.contains("stale"), "the stale token is gone");
  }

  @Test
  void an_environment_variable_wins_over_the_contact(@TempDir Path tmp) throws IOException {
    final Path secrets = tmp.resolve(".secrets");
    Files.writeString(secrets, "", StandardCharsets.UTF_8);

    writer(
            Optional.of(contactWith(Map.of(AuthTokenSource.FLOXHUB, "from-contact"))),
            name -> "FLOX_TOKEN".equals(name) ? Optional.of("from-env") : Optional.empty())
        .ensureTokensPresent(secrets);

    final String result = Files.readString(secrets, StandardCharsets.UTF_8);
    assertTrue(result.contains("token: 'from-env'"), "the environment token wins");
    assertFalse(result.contains("from-contact"), "the contact is not asked when the env answers");
  }

  @Test
  void a_missing_contact_and_empty_environment_leaves_the_file_untouched(@TempDir Path tmp)
      throws IOException {
    final Path secrets = tmp.resolve(".secrets");
    final String original = "# nothing to upsert\n";
    Files.writeString(secrets, original, StandardCharsets.UTF_8);

    writer(Optional.empty(), NO_ENV).ensureTokensPresent(secrets);

    assertEquals(
        original,
        Files.readString(secrets, StandardCharsets.UTF_8),
        "a blank token leaves the file byte-for-byte untouched");
  }
}
