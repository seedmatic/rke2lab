package io.seedmatic.rke2lab.clusterpki.contract;

import io.seedmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The cluster's age IDENTITY ({@code rke2lab-cluster-age-<n>}, from the operator's keyring) — what
 * the node needs to decrypt {@link ClusterCaBundle}. Secret: the seal WHEN files it in the cellar
 * {@link ClusterPkiCoordinate#CLUSTER_AGE_KEY} SEALED ({@code Sensitivity.SEALED}, CellarCipher at
 * rest — so the bundle's decryption key never sits in the clear beside it), and the host GROW
 * reveals it and poses it on the instance's {@code user.rke2lab.sops-age-key} devlxd key.
 *
 * <p>The SAME cluster age identity Flux already uses for its gitops sops-age Secret — one identity,
 * two consumers (Flux in-cluster, sops-nix on the node). A {@code type=dual-realm} record. {@link
 * SeedContract} binds it to the {@code cluster-age-key} coordinate for the codec's decode guard.
 * See docs/architecture/cluster-api/deterministic-cluster-access.adoc.
 */
@SeedContract("cluster-age-key")
public record ClusterAgeKey(String identity) {}
