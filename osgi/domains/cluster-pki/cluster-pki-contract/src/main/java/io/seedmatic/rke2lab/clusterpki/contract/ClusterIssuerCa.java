package io.seedmatic.rke2lab.clusterpki.contract;

import io.seedmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The cluster-issuer CA — a dedicated, low-privilege CA (the sixth, minted under the same
 * intermediate as the five rke2 leaf CAs, rooted at the ndh {@code mammoth-skate-tls} root) whose
 * private key is delivered in-cluster so a cert-manager {@code CA} {@code ClusterIssuer} ({@code
 * rke2lab-ca}) signs every in-cluster leaf (the flox webhook serving cert, CAPI, future webhooks).
 * Every such leaf then chains to our own root — the two-tier convergence that dissolves the
 * per-webhook self-signed islands.
 *
 * <p>Two PEM blocks: {@code caCertChainPem} is the FULL CHAIN (cluster-issuer CA + intermediate +
 * root), so an issued leaf presents a complete path to the root; {@code caKeyPem} is the CA private
 * key cert-manager signs with. Secret (it carries the CA private key): the seal WHEN files it
 * {@link ClusterPkiCoordinate#CLUSTER_ISSUER_CA} SEALED ({@code Sensitivity.SEALED}). It is
 * delivered into {@code kube-system} (cert-manager's cluster-resource-namespace) via the durable
 * NODE_BOOTSTRAP lane — never onto the Flux-reconciled branch, so a secret-blind in-cluster render
 * never strips it. Deliberately distinct from the rke2 CAs: cert-manager never sees an rke2 CA
 * private key, so a compromise mints only app leaves, never kube/etcd client certs.
 *
 * <p>A {@code type=dual-realm} record: minted + filed OSGi-side by the seal scion, fetched
 * host-side (the GROW poses it on devlxd) and mirrored manifests-side (to render the {@code
 * ClusterIssuer} + its key Secret). {@link SeedContract} binds it to the {@code cluster-issuer-ca}
 * coordinate for the codec's decode guard. See
 * docs/architecture/cluster-api/deterministic-cluster-access.adoc.
 */
@SeedContract("cluster-issuer-ca")
public record ClusterIssuerCa(String caCertChainPem, String caKeyPem) {}
