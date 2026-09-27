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
    final int dash = clusterName.indexOf('-');
    final String role = dash < 0 ? "" : clusterName.substring(dash + 1);
    return switch (role) {
      case "wrkld" -> WRKLD;
      default -> MGMT;
    };
  }

  /**
   * The manifest-domain policy this role publishes — the structural domain set resolved against
   * {@code catalog}. One domain is role-exclusive: Cluster API is MGMT-only (CAPI reconciles
   * clusters from the management cluster). Everything else — including {@code tailscale} (the
   * per-cluster tailnet substrate: operator, subnet-router Connector, funnel public door) and
   * {@code cicd} (each cluster renders its own branch via its own Tekton pipeline) — is a
   * structural capability every cluster carries.
   *
   * <p>{@code mesh} (Headscale/Headplane) was WRKLD-only and was REMOVED on 2026-09-27 — see {@link
   * #enabledDomainIds} for what settled it.
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
            catalog.tailscale(),
            catalog.cicd());
    // REMOVED 2026-09-27: `catalog.mesh()` (Headscale/Headplane) was WRKLD's exclusive domain.
    // Hibernated at the fabric renumbering, then deleted outright — units, registrar, the domain id
    // and the four flox envs. What settled it was not the pause but its CONTENT: the unit carried
    // kpt setters with no substitution pass left in the synthesis, so `${cluster-lan-headscale-
    // inetaddr}` would have rendered verbatim as a DNS value, and the addresses had moved to the
    // fabric pool. Waking it meant redesigning it, so there was nothing to keep warm. Git holds it.
    //
    // ⚠️ `FloxEnvFolder.MESH` and the `mesh` DEBUG facet SURVIVE, and conflating either with this
    // domain is the standing trap: tailscale/tailnet live in the mesh FOLDER and are gated by that
    // facet, while belonging to the tailscale domain, which is live. The folder is not the owner.
    //
    // Why: self-hosting the mesh is only worth it if it REPLACES Tailscale SaaS, because keeping
    // both leaves the fleet dependent on the service anyway. And a full replacement is a larger
    // piece than it looks — headscale would become the bootstrap and external-access transport, so
    // it needs a public door (the DDNS + Bbox forward the catalog already declares, moved onto the
    // cluster ingress), and it has NO Funnel, so the one funnel in use — the Pipelines-as-Code
    // webhook — needs a replacement door of its own. Until that is decided, a half-migrated mesh is
    // the worst of the three states: two control planes, neither authoritative.
    //
    // What is NOT a reason to migrate: declarativeness. The tailnet's tags, grants, ssh rules,
    // auto-approvers, auth keys, split-DNS AND service definitions are all reconciled from ndh's
    // catalog by `manage-tailnet` — five control-plane operations, no console step left. That gap
    // closed on 2026-09-27; it is no longer an argument for a second control plane.
    return switch (this) {
      case MGMT -> concat(base, catalog.clusterApi());
      case WRKLD -> base;
    };
  }

  private static List<String> concat(final List<String> base, final String extra) {
    final java.util.ArrayList<String> ids = new java.util.ArrayList<>(base);
    ids.add(extra);
    return List.copyOf(ids);
  }
}
