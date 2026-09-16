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
 *
 * <p>The {@code manifestsBranchRef} + {@code githubAccessToken} feed the node's boot-time {@code
 * nix run <branch>#install-rke2-config} (the UNIFORM per-cluster rke2 config install; see {@code
 * nixos/rke2.nix} {@code rke2lab-rke2-config}): the ref is the {@code manifests/<cluster>} branch
 * to fetch, the token is a FRESH github token minted at the grow (never stored — an ephemeral token
 * in the durable cellar would be reused stale). Rendered into {@code /run/rke2lab/rke2-config.env}
 * ({@code RKE2LAB_MANIFESTS_REF}) and {@code /run/rke2lab/nix-github.conf} (an {@code access-tokens
 * = github.com=…} line, root-only; nix reads it via {@code NIX_CONFIG=!include} so the token value
 * stays in the file). The standalone twin only — a CAPRKE2 workload node instead gets its rke2
 * config + join token from CAPRKE2's own {@code serverConfig}/token, so it needs neither field.
 */
public record NodeBootstrapMaterial(
    Optional<String> sopsAgeKey,
    Optional<String> clusterCaBundle,
    Optional<String> serverManifests,
    Optional<String> manifestsBranchRef,
    Optional<String> githubAccessToken) {

  /** No revealed material — a standalone/offline grow, or a producer that did not file. */
  public static NodeBootstrapMaterial none() {
    return new NodeBootstrapMaterial(
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }
}
