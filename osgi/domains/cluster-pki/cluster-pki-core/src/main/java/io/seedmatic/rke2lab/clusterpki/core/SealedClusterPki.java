package io.seedmatic.rke2lab.clusterpki.core;

import io.seedmatic.rke2lab.clusterpki.contract.AdminCredentials;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterAgeKey;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterCaBundle;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterIssuerCa;
import io.seedmatic.rke2lab.clusterpki.contract.ManagementClusterCa;

/**
 * What {@link ClusterSeal} produces in one act: the sops-sealed CA {@link ClusterCaBundle} (filed
 * PLAIN by the scion — it is already sealed), the {@link ClusterAgeKey} identity that decrypts it
 * on the node (filed SEALED), the operator's {@link AdminCredentials} (filed SEALED — it carries
 * the admin private key), the {@link ClusterIssuerCa} (filed SEALED — it carries the CA private key
 * delivered in-cluster for cert-manager), and the management cluster's own {@link
 * ManagementClusterCa} (filed SEALED, Reach IN_CLUSTER — the render exposes it as the mgmt-adoption
 * BYO-CA Secrets). The scion stores each at its {@code ClusterPkiCoordinate} case.
 */
public record SealedClusterPki(
    ClusterCaBundle bundle,
    ClusterAgeKey ageKey,
    AdminCredentials adminCredentials,
    ClusterIssuerCa clusterIssuerCa,
    ManagementClusterCa managementCas) {}
