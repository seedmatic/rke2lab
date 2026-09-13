// @codebase
package io.seedmatic.rke2lab.manifests.contract.profiles;

/**
 * Cross-cutting identity slice published to synth-time domains via {@link
 * io.seedmatic.rke2lab.manifests.ManifestSynthesisContext}. Replaces the kpt-setter {@code
 * ${cluster-name}} / {@code ${cluster-env}} / {@code ${node-name}} placeholders the deprecated
 * branch carried through {@code apply-setters} — those values now resolve at synth time because the
 * rendered objects ship straight to the rke2 addon controller (no kpt round trip).
 *
 * <p>Mirrors the canonical accessors on {@link
 * io.seedmatic.rke2lab.manifests.contract.node.NodeEnvContext} (which env contributors already
 * use), but deliberately exposes only the identity subset — bootstrap paths and per-package config
 * stay in their owning domains.
 *
 * <p>Incus cluster configuration: The Incus remote name ({@code incusRemoteName}) is configured per
 * cluster and may differ from the cluster name (e.g., cluster "bioskop" uses remote
 * "bioskop-nixos"). All clusters share a single CAPN identity ({@code "capn"}) for Cluster API
 * Provider Incus authentication. Manifest units read from operator environment paths ({@code
 * .secrets}, {@code ~/.config/incus}) using the configured remote name and the constant {@code
 * "capn"} identity.
 *
 * <p>Add new fields here as Stage B / multi-cluster work surfaces them (cluster fqdn, region, peer
 * cluster set, etc.). The default instance backs unit tests and ephemeral synth runs that don't go
 * through seed-master.
 */
public record BootstrapIdentity(
    String clusterName,
    int clusterId,
    String clusterToken,
    String clusterDomain,
    String clusterEnv,
    String nodeName,
    int nodeId,
    String nodeKind,
    String incusRemoteName,
    String nodeHostname,
    String nodeMdnsFqdn) {

  /** Sentinel used when seed-master hasn't supplied identity (tests, ephemeral runs). */
  public static final String UNKNOWN = "unknown";

  private static final BootstrapIdentity DEFAULT = builder().build();

  public BootstrapIdentity {
    clusterName = blankToUnknown(clusterName);
    clusterToken = blankToUnknown(clusterToken);
    clusterDomain =
        (clusterDomain == null || clusterDomain.isBlank()) ? "cluster.local" : clusterDomain;
    clusterEnv = blankToUnknown(clusterEnv);
    nodeName = blankToUnknown(nodeName);
    nodeKind = blankToUnknown(nodeKind);
    incusRemoteName = blankToUnknown(incusRemoteName);
    nodeHostname = blankToUnknown(nodeHostname);
    nodeMdnsFqdn = blankToUnknown(nodeMdnsFqdn);
  }

  /**
   * Default instance used by {@link
   * io.seedmatic.rke2lab.manifests.ManifestSynthesisContext#current()} when nothing was bound —
   * live callers always override via the synth request.
   */
  public static BootstrapIdentity unknown() {
    return DEFAULT;
  }

  /**
   * The cluster identity slug — the {@code clusterName} ({@code <host>-<role>}, e.g. {@code
   * bioskop-mgmt}), the SINGLE SOURCE OF TRUTH for the rendered-branch name ({@code
   * manifests/<clusterSlug>}) and its render-worktree leaf. The branch is PER-CLUSTER, not per-node
   * (every node of a cluster shares one GitOps branch), so it carries no node suffix. Mirrors
   * {@code ManifestsRunbookInput.Identity#clusterId()} (the door side of the same identity) so a
   * unit rendering the branch reference (e.g. {@code FluxRootManifestsUnit}) names the EXACT branch
   * the manifests scion pushes. (Distinct from the numeric {@link #clusterId()} record component.)
   */
  public String clusterSlug() {
    return clusterName;
  }

  /**
   * Effective cluster name: a null/blank/{@link #UNKNOWN} identity adopts {@code fallback} (the
   * caller's reserved default, e.g. the test cluster) so a bare survey derives a valid slice, not
   * an overflow. The domain that knows the default passes it — the identity owns only the guard.
   */
  public String clusterNameOrDefault(final String fallback) {
    final String c = clusterName();
    return (c == null || c.isBlank() || UNKNOWN.equals(c)) ? fallback : c;
  }

  public String nodeNameOrDefault(final String fallback) {
    final String n = nodeName();
    return (n == null || n.isBlank() || UNKNOWN.equals(n)) ? fallback : n;
  }

  public static Builder builder() {
    return new Builder();
  }

  private static String blankToUnknown(String value) {
    return (value == null || value.isBlank()) ? UNKNOWN : value;
  }

  /**
   * The recommended construction path: names each field so the cluster/node identity values can't
   * be positionally swapped (two ints + seven strings).
   */
  public static final class Builder {
    private String clusterName = UNKNOWN;
    private int clusterId = 0;
    private String clusterToken = UNKNOWN;
    private String clusterDomain = "cluster.local";
    private String clusterEnv = UNKNOWN;
    private String nodeName = UNKNOWN;
    private int nodeId = 0;
    private String nodeKind = UNKNOWN;
    private String incusRemoteName = UNKNOWN;
    private String nodeHostname = UNKNOWN;
    private String nodeMdnsFqdn = UNKNOWN;

    private Builder() {}

    public Builder clusterName(final String v) {
      this.clusterName = v;
      return this;
    }

    public Builder clusterId(final int v) {
      this.clusterId = v;
      return this;
    }

    public Builder clusterToken(final String v) {
      this.clusterToken = v;
      return this;
    }

    public Builder clusterDomain(final String v) {
      this.clusterDomain = v;
      return this;
    }

    public Builder clusterEnv(final String v) {
      this.clusterEnv = v;
      return this;
    }

    public Builder nodeName(final String v) {
      this.nodeName = v;
      return this;
    }

    public Builder nodeId(final int v) {
      this.nodeId = v;
      return this;
    }

    public Builder nodeKind(final String v) {
      this.nodeKind = v;
      return this;
    }

    public Builder incusRemoteName(final String v) {
      this.incusRemoteName = v;
      return this;
    }

    public Builder nodeHostname(final String v) {
      this.nodeHostname = v;
      return this;
    }

    public Builder nodeMdnsFqdn(final String v) {
      this.nodeMdnsFqdn = v;
      return this;
    }

    public BootstrapIdentity build() {
      return new BootstrapIdentity(
          clusterName,
          clusterId,
          clusterToken,
          clusterDomain,
          clusterEnv,
          nodeName,
          nodeId,
          nodeKind,
          incusRemoteName,
          nodeHostname,
          nodeMdnsFqdn);
    }
  }
}
