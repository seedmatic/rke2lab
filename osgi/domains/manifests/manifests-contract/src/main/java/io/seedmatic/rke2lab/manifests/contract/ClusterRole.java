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
   * The role for a declared token ({@code mgmt} / {@code wrkld}) — the pair of {@link #token()},
   * and the ONE place that mapping lives.
   *
   * <p>⚠️ Exhaustive and LOUD. This used to be a {@code default -> MGMT} catch-all, described as
   * the posture a bare survey renders, and the catch-all is what made a real defect invisible: the
   * netplan projection passed HOSTS where cluster names were expected ({@code bioskop}, {@code
   * nikopol}), every one of them read as MGMT, and half the clusters silently vanished from the
   * blueprint. A caller that genuinely has no cluster must say so by naming a role, not by handing
   * over a string this method cannot read.
   */
  public static ClusterRole ofToken(final String role) {
    return switch (role) {
      case "mgmt" -> MGMT;
      case "wrkld" -> WRKLD;
      default ->
          throw new IllegalArgumentException(
              "unknown cluster role '" + role + "' — expected one of mgmt, wrkld");
    };
  }

  /**
   * Parse the role from a {@code <host>-<role>} clusterName — the suffix after the first dash. A
   * blank / dashless / unknown name falls back to {@link #MGMT}, the base control posture a bare
   * survey renders.
   *
   * <p>⚠️ That fallback is a KNOWN hazard, deliberately left in place for now. It is what let the
   * netplan projection pass hosts ({@code bioskop}, {@code nikopol}) where cluster names were
   * expected — each read as MGMT, half the clusters gone from the blueprint without a word. Making
   * it loud was attempted on 2026-09-27 and reverted: the synthesis scion has more than one caller
   * that legitimately holds no cluster name, so hardening this needs those audited one by one, not
   * a switch flipped underneath them.
   *
   * <p>★ A caller that KNOWS its role must use {@link #ofToken} instead, which is exhaustive and
   * fails on anything it cannot read. Prefer it: it is the path that cannot hide a mistake.
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
