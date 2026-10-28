package io.seedmatic.rke2lab.manifests.bdd.versions;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The GitHub token a standalone run brings in its environment, for the release queries only (the
 * anonymous quota is 60 an hour, a report spends about a dozen). Read, handed to the bumper's
 * {@code Authorization} header, and never printed.
 */
final class EnvironmentToken {

  static final String VARIABLE = "GITHUB_TOKEN";

  private final UnaryOperator<String> environment;

  EnvironmentToken(final UnaryOperator<String> environment) {
    this.environment = environment;
  }

  Optional<String> githubToken() {
    return Optional.ofNullable(environment.apply(VARIABLE))
        .map(String::strip)
        .filter(t -> !t.isEmpty());
  }

  @Override
  public String toString() {
    return "EnvironmentToken[" + VARIABLE + "]";
  }
}
