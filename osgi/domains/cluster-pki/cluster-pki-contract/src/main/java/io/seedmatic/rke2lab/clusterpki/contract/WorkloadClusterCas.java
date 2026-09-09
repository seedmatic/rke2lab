package io.seedmatic.rke2lab.clusterpki.contract;

import io.seedmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.List;

/**
 * The workload clusters' deterministic CA sets — one CAPRKE2 bring-your-own-CA hierarchy per
 * workload cluster the management cluster greenfields, minted by the seal at grow time (each rooted
 * at the ndh {@code mammoth-skate-tls} root, a SIBLING of the mgmt CA — independent keys, so a
 * compromise of one cluster's CA never reaches another). Filed {@link
 * ClusterPkiCoordinate#WORKLOAD_CLUSTER_CAS} SEALED (it carries the CA private keys), minted
 * ADDITIVELY: on a re-grow the seal keeps the entries already present (stable across re-grows) and
 * mints only the workload clusters newly appearing in {@code workloadTargets}.
 *
 * <p>Each {@link Entry} carries the four CAs CAPRKE2 looks up by name ({@code <cluster>-ca}, {@code
 * <cluster>-cca}, {@code <cluster>-etcd}, {@code <cluster>-peer-etcd}); the manifests scion reveals
 * this record and renders those four {@code cluster.x-k8s.io/secret} Secrets into {@code
 * rke2lab-<cluster>} on the NODE_BOOTSTRAP lane, so CAPRKE2 delivers OUR CA to the workload node
 * via its cloud-init instead of self-generating a random one. Each {@link Pair#certChainPem()} is
 * the FULL chain (leaf CA + intermediate + mammoth-skate root), identical to the mgmt node's {@code
 * server/tls/*-ca.crt}; {@link Pair#keyPem()} is the CA private key.
 *
 * <p>A blind mirror — its manifests-side twin {@code WorkloadClusterCasMaterial} shares its exact
 * component shape, so the codec decodes this straight into the mirror with no {@code cluster-pki}
 * type crossing into manifests. See docs/architecture/cluster-api/deterministic-cluster-access.adoc
 * and the caprke2-byo-ca-secret-contract memory.
 */
@SeedContract("workload-cluster-cas")
public record WorkloadClusterCas(List<Entry> entries) {

  public WorkloadClusterCas {
    entries = entries == null ? List.of() : List.copyOf(entries);
  }

  /**
   * One workload cluster's four BYO-CA pairs, keyed by the CAPI {@code Cluster} name so the
   * manifests scion matches it to a {@code workloadTargets} entry. {@code serverCa} → {@code
   * <cluster>-ca} (apiserver), {@code clientCa} → {@code <cluster>-cca}, {@code etcdServerCa} →
   * {@code <cluster>-etcd}, {@code etcdPeerCa} → {@code <cluster>-peer-etcd}.
   */
  public record Entry(
      String clusterName, Pair serverCa, Pair clientCa, Pair etcdServerCa, Pair etcdPeerCa) {}

  /** A CA cert chain + its private key, both PEM. */
  public record Pair(String certChainPem, String keyPem) {}
}
