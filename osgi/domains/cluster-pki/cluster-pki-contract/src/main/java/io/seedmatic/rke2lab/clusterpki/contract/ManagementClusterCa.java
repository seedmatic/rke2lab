package io.seedmatic.rke2lab.clusterpki.contract;

import io.seedmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.Objects;

/**
 * The MANAGEMENT cluster's OWN deterministic CA set, in render-usable PEM form — the four CAs
 * CAPRKE2 looks up by name to adopt a control plane without rotating its CA ({@code <mgmt>-ca},
 * {@code <mgmt>-cca}, {@code <mgmt>-etcd}, {@code <mgmt>-peer-etcd}). It is the SAME material as
 * {@link ClusterCaBundle} carries, but that one is a sops blob decrypted only node-side; this
 * exposes the individual pairs so the mgmt-adoption CR set can render them as branch Secrets.
 * Minted once by {@link io.seedmatic.rke2lab.clusterpki.core.ClusterSeal} from the mgmt node
 * bundle, filed {@link ClusterPkiCoordinate#MANAGEMENT_CLUSTER_CAS} SEALED (it carries the CA
 * private keys) and kept stable across re-grows (the CA is never re-minted).
 *
 * <p>Reuses {@link WorkloadClusterCas.Pair} (cert chain + key, both PEM) — the identical shape a
 * workload BYO-CA pair has. NAMELESS: unlike a workload entry, no {@code clusterName} — the render
 * owns the mgmt cluster name (its {@code bootstrapIdentity}), which the seal does not hold. The
 * manifests synthesis renders it as it is; it used to go through a manifests-side copy of this
 * record, deleted on 2026-10-10 once the reason for it was found dead. See
 * docs/architecture/cluster-api/management-workload-topology.adoc and the
 * caprke2-byo-ca-secret-contract memory.
 */
@SeedContract("management-cluster-cas")
public record ManagementClusterCa(
    WorkloadClusterCas.Pair serverCa,
    WorkloadClusterCas.Pair clientCa,
    WorkloadClusterCas.Pair etcdServerCa,
    WorkloadClusterCas.Pair etcdPeerCa) {

  // A sealed case missing a field fails at its decode, not as an empty render downstream: the
  // manifests units now embed this record as it is, and an absent PEM would ride into a Secret as
  // nothing, silently. The manifests-side mirror used to carry this guard.
  public ManagementClusterCa {
    Objects.requireNonNull(serverCa, "serverCa");
    Objects.requireNonNull(clientCa, "clientCa");
    Objects.requireNonNull(etcdServerCa, "etcdServerCa");
    Objects.requireNonNull(etcdPeerCa, "etcdPeerCa");
  }
}
