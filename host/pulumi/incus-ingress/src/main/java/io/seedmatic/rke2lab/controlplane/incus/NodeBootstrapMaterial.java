package io.seedmatic.rke2lab.controlplane.incus;

import java.util.Optional;

/**
 * The per-node bootstrap material the host GROW lays into the guest through the uniform cloud-init
 * channel — the values the scenario fetched + revealed, opaque here. {@link InstanceGrow} renders
 * them into the instance's {@code cloud-init.user-data} as a {@code write_files} cloud-config; the
 * NixOS node-base's oneshots consume the written files ({@code sops-install-secrets} the CA bundle,
 * {@code rke2-server} the bootstrap manifests). Each is absent only on a run whose producer did not
 * file it — the guest units are tolerant (rke2 self-signs its CA; a node with no bootstrap set
 * seeds nothing), so every field is {@link Optional}.
 *
 * <p>A HOST-ONLY handoff (ClusterSeedScenario → InstanceGrow, both host, in-JVM) — NOT a
 * seam/contract type, so it lives here beside {@link InstanceGrow} and carries live values, no
 * codec. The per-node IDENTITY scalars ride separately on {@code GrowIdentityView} (the plan),
 * rendered into the same cloud-config's {@code node.env}.
 */
public record NodeBootstrapMaterial(
    Optional<String> sopsAgeKey,
    Optional<String> clusterCaBundle,
    Optional<String> serverManifests) {

  /** No revealed material — a standalone/offline grow, or a producer that did not file. */
  public static NodeBootstrapMaterial none() {
    return new NodeBootstrapMaterial(Optional.empty(), Optional.empty(), Optional.empty());
  }
}
