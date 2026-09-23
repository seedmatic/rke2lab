package io.seedmatic.rke2lab.clusterpki.contract;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * The operator's kubeconfig as a document: every cluster the seal opened, and every way IN to each
 * of them. It renders to the YAML {@code kubectl} merges by name — a {@code clusters} entry and a
 * {@code contexts} entry per ACCESS, one {@code users} entry per CLUSTER.
 *
 * <p>Why the document is its own record rather than a method on {@link AdminCredentials}: a
 * kubeconfig spanning several clusters cannot hang off ONE cluster's material. Each cluster has its
 * own CA hierarchy (a workload cluster's is a SIBLING of the management one — independent keys, so
 * a compromise of one never reaches another), hence its own {@code certificate-authority-data} and
 * its own admin certificate. So the credentials are a COMPONENT of a cluster's entry here, and
 * {@link AdminCredentials} stays what its name says: three PEM blocks, no rendering.
 *
 * <p>NOTE: {@code manifests-contract}'s {@code OperatorPkiMaterial} still duplicates a
 * single-endpoint variant of this template on the other side of the manifests seam — one renderer
 * in two realms, worth collapsing when a third appears.
 */
public record OperatorKubeconfig(List<ClusterAccess> clusters) {

  public OperatorKubeconfig {
    clusters = List.copyOf(clusters);
    if (clusters.isEmpty()) {
      throw new IllegalArgumentException("a kubeconfig that opens no cluster is not a kubeconfig");
    }
  }

  /**
   * One way IN to a cluster: a context name and the endpoint it dials. A cluster has SEVERAL
   * because reachability is per-consumer and the ways in are not equivalent — the management plane
   * answers on its netplan LAN address (fastest, and the one that keeps answering while the cluster
   * is half-born) and on its kube-vip VIP (slower, over the tailnet, but it survives the node being
   * replaced). A cattle cluster has only the VIP: its nodes take an ordinary router lease under a
   * name the provider mints, so neither a predicted LAN address nor an mDNS name describes it.
   */
  public record Access(String contextName, String server) {}

  /**
   * One cluster, its admin credentials, and the ways in they open. Refuses an empty access list:
   * credentials with nowhere to dial are an incompletely-built entry, and rendering them would
   * silently produce a kubeconfig naming a cluster no context reaches.
   */
  public record ClusterAccess(
      String clusterName, AdminCredentials credentials, List<Access> accesses) {

    public ClusterAccess {
      accesses = List.copyOf(accesses);
      if (accesses.isEmpty()) {
        throw new IllegalArgumentException("no way in declared for cluster " + clusterName);
      }
    }
  }

  /**
   * Render the document. The FIRST access of the FIRST cluster is {@code current-context}, so the
   * caller's order is its statement of which way in to prefer.
   *
   * <p>The user name is {@code <cluster>-admin}, one per cluster: kubeconfig {@code users}/{@code
   * clusters}/{@code contexts} are global lists merged BY NAME, so a shared {@code rke2lab-admin}
   * would collapse across clusters and every context would authenticate with one (wrong)
   * certificate. Within a cluster the ONE user is shared by all its contexts — the credentials are
   * endpoint-independent, which is precisely why several contexts can share them.
   */
  public String render() {
    final Base64.Encoder b64 = Base64.getEncoder();
    final StringBuilder clusterBlocks = new StringBuilder();
    final StringBuilder userBlocks = new StringBuilder();
    final StringBuilder contextBlocks = new StringBuilder();
    for (final ClusterAccess cluster : clusters) {
      final AdminCredentials credentials = cluster.credentials();
      final String user = cluster.clusterName() + "-admin";
      final String ca = encode(b64, credentials.caCertPem());
      for (final Access access : cluster.accesses()) {
        clusterBlocks.append(
            """
              - name: %s
                cluster:
                  server: %s
                  certificate-authority-data: %s
            """
                .formatted(access.contextName(), access.server(), ca));
        contextBlocks.append(
            """
              - name: %s
                context:
                  cluster: %s
                  user: %s
            """
                .formatted(access.contextName(), access.contextName(), user));
      }
      userBlocks.append(
          """
            - name: %s
              user:
                client-certificate-data: %s
                client-key-data: %s
          """
              .formatted(
                  user,
                  encode(b64, credentials.clientCertPem()),
                  encode(b64, credentials.clientKeyPem())));
    }
    return """
        apiVersion: v1
        kind: Config
        clusters:
        %susers:
        %scontexts:
        %scurrent-context: %s
        """
        .formatted(
            clusterBlocks,
            userBlocks,
            contextBlocks,
            clusters.getFirst().accesses().getFirst().contextName());
  }

  private static String encode(Base64.Encoder b64, String pem) {
    return b64.encodeToString(pem.getBytes(StandardCharsets.UTF_8));
  }
}
