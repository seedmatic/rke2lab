package io.seedmatic.rke2lab.manifests.contract.profiles;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The workload clusters' deterministic BYO-CA sets published to synth-time layers via {@code
 * ManifestSynthesisContext} — the manifests-side MIRROR of the {@code cluster-pki} {@code
 * WorkloadClusterCas} the seal minted (rooted at mammoth-skate) and filed SEALED, revealed
 * in-container by the manifests scion. A blind mirror sharing the sealed record's EXACT component
 * shape ({@code entries[].{clusterName, serverCa, clientCa, etcdServerCa, etcdPeerCa}}, each a
 * {@link Pair}), so the codec decodes the sealed record straight into this without any {@code
 * cluster-pki} type crossing into manifests — the same discipline {@link OperatorPkiMaterial} /
 * {@link ClusterIssuerCaMaterial} follow.
 *
 * <p>{@code ClusterApiWorkloadManifestsUnit} looks up the {@link Entry} for each workload target
 * and renders its four CAPRKE2 BYO-CA Secrets ({@code <cluster>-{ca,cca,etcd,peer-etcd}}, type
 * {@code cluster.x-k8s.io/secret}) into {@code rke2lab-<cluster>} on the NODE_BOOTSTRAP lane.
 * Absence — no workload CA sealed (a bare survey / secret-blind render) — is carried as an empty
 * {@code Optional<WorkloadClusterCasMaterial>} on the context, never a placeholder.
 */
public record WorkloadClusterCasMaterial(List<Entry> entries) {

  public WorkloadClusterCasMaterial {
    entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
  }

  /** The CA set for {@code clusterName}, if this material carries one. */
  public Optional<Entry> forCluster(String clusterName) {
    return entries.stream().filter(entry -> entry.clusterName().equals(clusterName)).findFirst();
  }

  /**
   * One workload cluster's four BYO-CA pairs, keyed by the CAPI {@code Cluster} name. {@code
   * serverCa} → {@code <cluster>-ca}, {@code clientCa} → {@code <cluster>-cca}, {@code
   * etcdServerCa} → {@code <cluster>-etcd}, {@code etcdPeerCa} → {@code <cluster>-peer-etcd}.
   */
  public record Entry(
      String clusterName, Pair serverCa, Pair clientCa, Pair etcdServerCa, Pair etcdPeerCa) {}

  /** A CA cert chain + its private key, both PEM. */
  public record Pair(String certChainPem, String keyPem) {}
}
