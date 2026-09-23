package io.seedmatic.rke2lab.clusterpki.contract;

import io.seedmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.List;

/**
 * The operator's admin credentials for the WORKLOAD clusters — one {@link AdminCredentials} per
 * cluster the management plane greenfields, each minted from THAT cluster's own {@code client-ca}
 * in {@link WorkloadClusterCas} and paired with its own {@code server-ca} chain.
 *
 * <p>It exists because a workload cluster's CA hierarchy is a SIBLING of the management one, not a
 * child: the management cluster's admin certificate opens nothing on a workload apiserver. So
 * {@link ClusterPkiCoordinate#ADMIN_CREDENTIALS} (the seal's own cluster, ONE) needed the
 * list-shaped twin that {@link WorkloadClusterCas} already was — the same asymmetry {@code
 * MANAGEMENT_CLUSTER_CAS} / {@code WORKLOAD_CLUSTER_CAS} resolves for the CA sets, applied to the
 * admin leaves.
 *
 * <p>Filed {@link ClusterPkiCoordinate#WORKLOAD_ADMIN_CREDENTIALS} SEALED (each entry carries an
 * admin private key) and OPERATOR_ONLY — unlike the CA sets it has NO in-cluster consumer: CAPI
 * mints its own {@code <cluster>-kubeconfig} Secret for a workload cluster from the BYO-CA we hand
 * it. Its one reader is the host, which adds a context per entry to the operator kubeconfig.
 *
 * <p>Minted ADDITIVELY like the CA sets: on a re-grow an entry is KEPT so the operator's kubeconfig
 * does not churn a fresh certificate every run. See
 * docs/architecture/cluster-api/deterministic-cluster-access.adoc.
 */
@SeedContract("workload-admin-credentials")
public record WorkloadAdminCredentials(List<Entry> entries) {

  public WorkloadAdminCredentials {
    entries = entries == null ? List.of() : List.copyOf(entries);
  }

  /** One workload cluster's admin credentials, keyed by its CAPI {@code Cluster} name. */
  public record Entry(String clusterName, AdminCredentials credentials) {}
}
