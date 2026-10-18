package io.seedmatic.rke2lab.clusterpki.core.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The age recipients the cluster CA bundle is sealed FOR — read from the repo's {@code .sops.yaml}
 * {@code creation_rules}, the single source of truth for "who decrypts the repo's secrets". Both
 * recipients live there in the clear (they are PUBLIC keys): the operator's own age key AND the
 * cluster's, {@code rke2lab-cluster-age-<n>}. Reading them here keeps the seal consistent with the
 * set that already governs {@code .secrets}.
 *
 * <p>⚠️ The cluster recipient and the node's {@link
 * io.seedmatic.rke2lab.clusterpki.contract.ClusterAgeKey} no longer come from one key: the
 * recipient is what {@code .sops.yaml} names, the identity is what the operator hands over from the
 * keyring. They agree because the operator names a new identity's public in {@code .sops.yaml} and
 * re-keys before the grow that uses it; a grow handed another identity seals a bundle its node
 * cannot open.
 */
public final class SopsRecipients {

  private static final Path DEFAULT_PATH = Path.of(".sops.yaml");

  private SopsRecipients() {}

  /**
   * The deduped age recipients across all {@code creation_rules}, from the default {@code
   * .sops.yaml}.
   */
  public static List<String> fromDefault() {
    return from(DEFAULT_PATH);
  }

  public static List<String> from(Path sopsYaml) {
    if (!Files.isReadable(sopsYaml)) {
      throw new IllegalStateException(
          "no .sops.yaml at " + sopsYaml + " — cannot resolve recipients");
    }
    final JsonNode root;
    try {
      root = new SeedCodec().reading().readYaml(Files.readString(sopsYaml));
    } catch (Exception ex) {
      throw new IllegalStateException("failed to parse " + sopsYaml, ex);
    }
    final List<String> recipients = new ArrayList<>();
    for (JsonNode rule : root.path("creation_rules")) {
      final JsonNode age = rule.get("age");
      if (age == null || !age.isTextual()) {
        continue;
      }
      // The age scalar is a comma-separated list, often folded across lines (whitespace +
      // newlines).
      for (String recipient : age.asText().split(",")) {
        final String trimmed = recipient.strip();
        if (!trimmed.isEmpty() && !recipients.contains(trimmed)) {
          recipients.add(trimmed);
        }
      }
    }
    if (recipients.isEmpty()) {
      throw new IllegalStateException("no age recipients found in " + sopsYaml + " creation_rules");
    }
    return List.copyOf(recipients);
  }
}
