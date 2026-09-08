package io.seedmatic.rke2lab.manifests.contract;

import java.util.List;

/**
 * The cluster's KIND, parsed from the {@code <host>-<role>} clusterName — the single source of
 * which manifest domains a render publishes. A management cluster runs Cluster API on the base
 * control substrate; a workload cluster runs the app stack (mesh, cicd) but never Cluster API.
 *
 * <p>Supersedes the former config-carried {@code publish} facet. Domain membership is STRUCTURAL to
 * a cluster's role, not an operator toggle: a management cluster does not "turn off" Cluster API as
 * an operation, and a workload cluster does not host CAPI at all — so the set follows the role, in
 * code, deterministically from the clusterName (stable across a grow and any in-cluster re-render).
 */
public enum ClusterRole {
  MGMT("mgmt"),
  WRKLD("wrkld");

  private final String token;

  ClusterRole(final String token) {
    this.token = token;
  }

  public String token() {
    return token;
  }

  /**
   * Parse the role from a {@code <host>-<role>} clusterName — the suffix after the first dash. A
   * blank / dashless / unknown name falls back to {@link #MGMT}, the base control posture a bare
   * survey renders.
   */
  public static ClusterRole of(final String clusterName) {
    if (clusterName == null) {
      return MGMT;
    }
    final int dash = clusterName.indexOf('-');
    final String role = dash < 0 ? "" : clusterName.substring(dash + 1);
    return switch (role) {
      case "wrkld" -> WRKLD;
      default -> MGMT;
    };
  }

  /**
   * The manifest-domain policy this role publishes — the structural domain set resolved against
   * {@code catalog}. Only TWO domains are role-exclusive: Cluster API is MGMT-only (CAPI reconciles
   * clusters from the management cluster) and mesh (Headscale/Headplane, the always-live control
   * service) is WRKLD-only. Everything else — including {@code ingress} (the per-cluster
   * public-door capability) and {@code cicd} (each cluster renders its own branch via its own
   * Tekton pipeline) — is a structural capability every cluster carries.
   */
  public ManifestDomainPolicy domainPolicy(final ManifestDomainCatalog catalog) {
    return ManifestDomainPolicy.builder()
        .domainCatalog(catalog)
        .enableOnly(enabledDomainIds(catalog))
        .build();
  }

  private List<String> enabledDomainIds(final ManifestDomainCatalog catalog) {
    final List<String> base =
        List.of(
            catalog.cluster(),
            catalog.runtime(),
            catalog.platform(),
            catalog.gitops(),
            catalog.networking(),
            catalog.storage(),
            catalog.highAvailability(),
            catalog.ingress(),
            catalog.cicd());
    return switch (this) {
      case MGMT -> concat(base, catalog.clusterApi());
      case WRKLD -> concat(base, catalog.mesh());
    };
  }

  private static List<String> concat(final List<String> base, final String extra) {
    final java.util.ArrayList<String> ids = new java.util.ArrayList<>(base);
    ids.add(extra);
    return List.copyOf(ids);
  }
}
