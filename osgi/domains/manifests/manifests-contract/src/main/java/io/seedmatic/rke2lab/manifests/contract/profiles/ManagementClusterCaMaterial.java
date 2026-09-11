package io.seedmatic.rke2lab.manifests.contract.profiles;

import io.seedmatic.rke2lab.manifests.contract.profiles.WorkloadClusterCasMaterial.Pair;
import java.util.Objects;

/**
 * The MANAGEMENT cluster's own four BYO-CA pairs published to synth-time layers via {@code
 * ManifestSynthesisContext} — the manifests-side MIRROR of the {@code cluster-pki} {@code
 * ManagementClusterCa} the seal minted from the mgmt node bundle and filed SEALED (Reach
 * IN_CLUSTER), revealed in-container by the manifests scion. A blind mirror sharing the sealed
 * record's EXACT component shape ({@code serverCa}, {@code clientCa}, {@code etcdServerCa}, {@code
 * etcdPeerCa}, each a {@link Pair}), so the codec decodes the sealed record straight into this with
 * no {@code cluster-pki} type crossing into manifests — the same discipline {@link
 * WorkloadClusterCasMaterial} / {@link OperatorPkiMaterial} follow.
 *
 * <p>Reuses {@link WorkloadClusterCasMaterial.Pair} — the identical pair shape. NAMELESS (unlike a
 * workload entry): {@code ClusterApiManagementManifestsUnit} stamps the mgmt cluster name (from
 * {@code bootstrapIdentity}) and renders the four {@code <mgmt>-{ca,cca,etcd,peer-etcd}} Secrets so
 * CAPRKE2 adopts the running control plane with its LIVE CA. Absence — no mgmt CA revealed (a
 * secret-blind render) — is carried as an empty {@code Optional<ManagementClusterCaMaterial>} on
 * the context, never a placeholder.
 */
public record ManagementClusterCaMaterial(
    Pair serverCa, Pair clientCa, Pair etcdServerCa, Pair etcdPeerCa) {

  public ManagementClusterCaMaterial {
    Objects.requireNonNull(serverCa, "serverCa");
    Objects.requireNonNull(clientCa, "clientCa");
    Objects.requireNonNull(etcdServerCa, "etcdServerCa");
    Objects.requireNonNull(etcdPeerCa, "etcdPeerCa");
  }
}
