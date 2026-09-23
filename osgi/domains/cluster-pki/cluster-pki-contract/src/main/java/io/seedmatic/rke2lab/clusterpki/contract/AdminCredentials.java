package io.seedmatic.rke2lab.clusterpki.contract;

import io.seedmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The operator's admin credentials, endpoint-INDEPENDENT: an admin client certificate ({@code
 * CN=rke2lab-admin, O=system:masters}) minted from the deterministic cluster {@code client-ca}, its
 * private key, and the {@code server-ca} chain that verifies kube-apiserver — the chain ends at the
 * ndh {@code mammoth-skate-tls} root, so the operator trusts it natively. Three PEM blocks, no
 * endpoint: the server URL is a per-consumer fact (the operator reaches the node over its netplan
 * LAN address or the kube-vip VIP; in-cluster consumers reach it over the VIP), so a consumer wraps
 * these three blocks around the endpoints IT can reach — see {@link OperatorKubeconfig}, which
 * carries them as one cluster's component. The seal only mints what is stable across re-grows.
 *
 * <p>Secret (it carries the admin private key): the seal WHEN files it in the cellar {@link
 * ClusterPkiCoordinate#ADMIN_CREDENTIALS} SEALED ({@code Sensitivity.SEALED}, CellarCipher at
 * rest). The host reveals it after the grow and writes the operator kubeconfig to {@code
 * kubeconfigRef} ({@code .local.d/kubeconfig.yaml}) — the path the readiness probe reads — with one
 * context per way in; the manifests layer reveals it again to render the in-cluster {@code
 * <cluster>-kubeconfig} Secret with the VIP endpoint alone. A {@code type=dual-realm} record:
 * minted + filed OSGi-side by the seal scion, fetched host-side and manifests-side. {@link
 * SeedContract} binds it to the {@code admin-credentials} coordinate for the codec's decode guard.
 * See docs/architecture/cluster-api/deterministic-cluster-access.adoc.
 */
@SeedContract("admin-credentials")
public record AdminCredentials(String clientCertPem, String clientKeyPem, String caCertPem) {}
