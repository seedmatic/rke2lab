package io.seedmatic.rke2lab.manifests.bdd;

import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The cellar case the {@code incus-identity} seal files the assembled {@code IncusIdentityMaterial}
 * SEALED at, and the manifests synthesis reveals it from. Both ends live in the manifests domain
 * (the seal {@link IncusIdentitySealScenario} and the reveal in {@code ManifestSynthesisScenario}),
 * so — like {@link ReplicatorSecretsCase} — one shared coordinate addresses the store and the
 * fetch. The cellar matches store/fetch by slug.
 */
public enum IncusIdentityCase implements SeedCoordinate {
  INCUS_IDENTITY;

  @Override
  public String slug() {
    return "incus-identity";
  }

  @Override
  public String domain() {
    return "manifests";
  }
}
