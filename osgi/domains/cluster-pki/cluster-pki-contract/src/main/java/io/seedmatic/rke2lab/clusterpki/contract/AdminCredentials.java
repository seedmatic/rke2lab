package io.seedmatic.rke2lab.clusterpki.contract;

import io.seedmatic.rke2lab.seed.broker.port.SeedContract;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * The operator's admin credentials, endpoint-INDEPENDENT: an admin client certificate ({@code
 * CN=rke2lab-admin, O=system:masters}) minted from the deterministic cluster {@code client-ca}, its
 * private key, and the {@code server-ca} chain that verifies kube-apiserver — the chain ends at the
 * ndh {@code mammoth-skate-tls} root, so the operator trusts it natively. Three PEM blocks, no
 * endpoint: the server URL is a per-consumer fact (the operator reaches the node over its netplan
 * LAN address or the kube-vip VIP; in-cluster consumers reach it over the VIP), so {@link
 * #kubeconfig(String, List)} wraps these three blocks around one or MORE supplied endpoints. The
 * seal only mints what is stable across re-grows.
 *
 * <p>NOTE: {@code manifests-contract}'s {@code OperatorPkiMaterial} carries the same three PEMs
 * across the manifests seam and duplicates this template for its single-endpoint case — one
 * kubeconfig renderer in two realms, worth collapsing when a third appears.
 *
 * <p>Secret (it carries the admin private key): the seal WHEN files it in the cellar {@link
 * ClusterPkiCoordinate#ADMIN_CREDENTIALS} SEALED ({@code Sensitivity.SEALED}, CellarCipher at
 * rest). The host reveals it after the grow and writes the operator kubeconfig to {@code
 * kubeconfigRef} ({@code .local.d/kubeconfig.yaml}) with the mDNS endpoint — the path the readiness
 * probe reads; the manifests layer reveals it again to render the in-cluster {@code
 * <cluster>-kubeconfig} Secret with the VIP endpoint. A {@code type=dual-realm} record: minted +
 * filed OSGi-side by the seal scion, fetched host-side and manifests-side. {@link SeedContract}
 * binds it to the {@code admin-credentials} coordinate for the codec's decode guard. See
 * docs/architecture/cluster-api/deterministic-cluster-access.adoc.
 */
@SeedContract("admin-credentials")
public record AdminCredentials(String clientCertPem, String clientKeyPem, String caCertPem) {

  /**
   * One way IN to the cluster these credentials open: a context name and the endpoint it dials. A
   * cluster has several, because reachability is per-consumer and they are not equivalent — the
   * management plane answers on its netplan LAN address (fastest, and the one that keeps answering
   * while the cluster is half-born, measured through a cold start) and on its kube-vip VIP (slower,
   * via the tailnet, but it survives the node being replaced). A cattle cluster has only the VIP.
   */
  public record Access(String contextName, String server) {}

  /**
   * Render a kubeconfig around these credentials with one context per {@link Access} — the {@code
   * server-ca} chain as each cluster's CA (natively trusted, rooted at mammoth-skate-tls), the
   * admin client cert + key as the one user. The FIRST access is {@code current-context}. The three
   * PEM blocks ride base64 as kube's {@code *-data} fields.
   *
   * <p>The endpoints are the caller's choice — these credentials are endpoint-INDEPENDENT, which is
   * exactly why several contexts can share them.
   */
  public String kubeconfig(String clusterName, List<Access> accesses) {
    if (accesses.isEmpty()) {
      throw new IllegalArgumentException("a kubeconfig needs at least one access: " + clusterName);
    }
    final Base64.Encoder b64 = Base64.getEncoder();
    final String ca = b64.encodeToString(caCertPem.getBytes(StandardCharsets.UTF_8));
    final String cert = b64.encodeToString(clientCertPem.getBytes(StandardCharsets.UTF_8));
    final String key = b64.encodeToString(clientKeyPem.getBytes(StandardCharsets.UTF_8));
    // The user name is <cluster>-admin, unique per cluster: kubeconfig users/clusters/contexts are
    // global lists merged BY NAME, so a shared "rke2lab-admin" would collapse across clusters and
    // both contexts would point at one (wrong) user cert. The context names are the caller's for
    // the
    // same reason — two contexts on ONE cluster must not collide either.
    final String user = clusterName + "-admin";
    final StringBuilder clusters = new StringBuilder();
    final StringBuilder contexts = new StringBuilder();
    for (final Access access : accesses) {
      clusters.append(
          """
            - name: %s
              cluster:
                server: %s
                certificate-authority-data: %s
          """
              .formatted(access.contextName(), access.server(), ca));
      contexts.append(
          """
            - name: %s
              context:
                cluster: %s
                user: %s
          """
              .formatted(access.contextName(), access.contextName(), user));
    }
    return """
        apiVersion: v1
        kind: Config
        clusters:
        %susers:
          - name: %s
            user:
              client-certificate-data: %s
              client-key-data: %s
        contexts:
        %scurrent-context: %s
        """
        .formatted(clusters, user, cert, key, contexts, accesses.getFirst().contextName());
  }
}
