package io.seedmatic.rke2lab.host.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.SecretsGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The host realisation of {@link SecretsGateway} — the flat host owns {@code .secrets} (the {@code
 * ConfigLoader} family reads it) and publishes this READ door into the framework it grew, so an
 * in-container scion can rehydrate an anchor through the seam without any {@code .secrets} logic
 * crossing a realm boundary.
 *
 * <p>Read opens the encrypted-at-rest {@code .secrets} YAML in memory through {@link SopsAtRest},
 * navigates it by dotted path and returns the subtree as JSON. Writing {@code .secrets} is the
 * operator's hand — the one App key is seeded by hand once — so this seam is read-only.
 */
public final class DotSecretsGateway implements SecretsGateway {

  private static final SeedCodec CODEC = new SeedCodec();

  private final Path secretsFile;
  private final SopsAtRest sops;

  public DotSecretsGateway() {
    this(Path.of(".secrets"), new SopsAtRest());
  }

  public DotSecretsGateway(Path secretsFile, SopsAtRest sops) {
    this.secretsFile = secretsFile;
    this.sops = sops;
  }

  @Override
  public Optional<String> read(String dottedPath) {
    if (!Files.isReadable(secretsFile)) {
      return Optional.empty();
    }
    JsonNode node = CODEC.reading().readYaml(sops.open(secretsFile));
    for (final String part : dottedPath.split("\\.")) {
      node = node == null ? null : node.path(part);
    }
    if (node == null || node.isMissingNode() || node.isNull()) {
      return Optional.empty();
    }
    return Optional.of(CODEC.encode(node));
  }
}
