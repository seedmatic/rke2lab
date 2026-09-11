package io.seedmatic.rke2lab.clusterpki.contract;

import io.seedmatic.rke2lab.seed.broker.port.SeedContract;

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
 * owns the mgmt cluster name (its {@code bootstrapIdentity}), which the seal does not hold. A blind
 * mirror ({@code ManagementClusterCaMaterial}) shares this exact component shape so the codec
 * decodes straight across, no {@code cluster-pki} type crossing into manifests. See
 * docs/architecture/cluster-api/management-workload-topology.adoc and the
 * caprke2-byo-ca-secret-contract memory.
 */
@SeedContract("management-cluster-cas")
public record ManagementClusterCa(
    WorkloadClusterCas.Pair serverCa,
    WorkloadClusterCas.Pair clientCa,
    WorkloadClusterCas.Pair etcdServerCa,
    WorkloadClusterCas.Pair etcdPeerCa) {}
