package io.seedmatic.rke2lab.doctor.contract;

import java.util.Optional;

/**
 * The single source of truth for a checkpoint's IDENTITY, consumed by BOTH the BDD layer (the
 * scenario id / {@link io.seedmatic.rke2lab.doctor.contract.ConsultationReport#checkpointId()}) and
 * the Pulumi resources (the resource {@code name} → URN). The resource name is DERIVED from the
 * slug ({@code "seed-" + slug}), so the correspondence between the two layers is structural rather
 * than hand-maintained — this guards against the clusterApi/cluster-api silent-failure pattern
 * documented in CLAUDE.md.
 *
 * <p>Identity is slug + resourceName + scenarioTitle — ONLY identity, never topology. The
 * cluster→systemd dependency edge lives on the resource {@code dependsOn}, not here.
 */
public enum Checkpoint {
  SYSTEMD_ADAPTER("systemd-adapter", "systemd adapter becomes reachable"),
  CLUSTER_READINESS("cluster-readiness", "cluster becomes ready"),
  INCUS_PROVISION("incus-provision", "instance is provisioned on incus");

  private final String slug;
  private final String scenarioTitle;

  Checkpoint(String slug, String scenarioTitle) {
    this.slug = slug;
    this.scenarioTitle = scenarioTitle;
  }

  /** The kebab-case identity used as the BDD scenario id. */
  public String slug() {
    return slug;
  }

  /** The Pulumi resource name, derived from the slug so it cannot drift from the BDD identity. */
  public String resourceName() {
    return "seed-" + slug;
  }

  /** The human title the stage passes to JGiven's {@code startScenario(...)}. */
  public String scenarioTitle() {
    return scenarioTitle;
  }

  /**
   * Resolve the checkpoint a {@link
   * io.seedmatic.rke2lab.doctor.contract.ConsultationReport#checkpointId()} names; unknown → empty.
   */
  public static Optional<Checkpoint> fromSlug(String slug) {
    for (Checkpoint checkpoint : values()) {
      if (checkpoint.slug.equals(slug)) {
        return Optional.of(checkpoint);
      }
    }
    return Optional.empty();
  }
}
