package io.seedmatic.rke2lab.clusterpki.contract;

import io.seedmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The cluster's age IDENTITY ({@code rke2lab-cluster-age-<n>}, from the operator's keyring) — what
 * the node needs to decrypt {@link ClusterCaBundle}. Secret: the seal WHEN files it in the cellar
 * {@link ClusterPkiCoordinate#CLUSTER_AGE_KEY} SEALED ({@code Sensitivity.SEALED}), and the host
 * GROW reveals it and writes it into the node's cloud-init.
 *
 * <p>⚠️ SEALED is not protection here. The cellar's cipher is {@code PassphraseCellarCipher}, whose
 * passphrase is a constant committed in this public repository, so a sealed identity is as readable
 * as {@code .pulumi-state} itself: whoever can read the stack state can read this key. It keeps the
 * value out of a casual read of the state, nothing more.
 *
 * <p>The SAME cluster age identity Flux already uses for its gitops sops-age Secret — one identity,
 * two consumers (Flux in-cluster, sops-nix on the node). A {@code type=dual-realm} record. {@link
 * SeedContract} binds it to the {@code cluster-age-key} coordinate for the codec's decode guard.
 * See docs/architecture/cluster-api/deterministic-cluster-access.adoc.
 */
@SeedContract("cluster-age-key")
public record ClusterAgeKey(String identity) {}
