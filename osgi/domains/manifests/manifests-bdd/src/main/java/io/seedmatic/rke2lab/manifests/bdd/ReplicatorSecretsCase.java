package io.seedmatic.rke2lab.manifests.bdd;

import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The cellar case the {@code replicator-secrets} seal files the SOURCE-secret material SEALED at,
 * and the manifests synthesis reveals it from. Both ends live in the manifests domain (the seal
 * {@link ReplicatorSecretsSealScenario} and the reveal in {@code ManifestSynthesisScenario}), so
 * one shared coordinate addresses the store and the fetch — the same rule as every other sealed
 * case: the reader names the OWNER's coordinate. The cellar matches store/fetch by slug.
 */
public enum ReplicatorSecretsCase implements SeedCoordinate {
  REPLICATOR_SECRETS;

  @Override
  public String slug() {
    return "replicator-secrets";
  }

  @Override
  public String domain() {
    return "manifests";
  }
}
