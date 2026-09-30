package io.seedmatic.rke2lab.manifests.contract;

import java.util.ArrayList;
import java.util.List;

/**
 * The fleet, derived from the ONE thing the operator declares — the Incus hosts:
 *
 * <pre>{@code
 * incusTargets:
 *   - bioskop
 *   - nikopol
 * }</pre>
 *
 * <p>{@link ClusterRole} is a closed two-value enum, so enumerating {@code {host, role}} pairs was
 * redundant: the host list already carries the whole fleet ({@code bioskop-mgmt}, {@code
 * bioskop-wrkld}, {@code nikopol-mgmt}, {@code nikopol-wrkld}). What the pairs could NOT express is
 * the decisive part — which plane OWNS each cluster — and that is this type's job.
 *
 * <h2>The owner rule</h2>
 *
 * <ul>
 *   <li>{@code owner(<host>-wrkld) = <host>-mgmt} — strictly HOST-LOCAL. A workload's frequent
 *       machine lifecycle never crosses a host: the plane drives its own local Incus engine.
 *   <li>{@code owner(<host>-mgmt) = the ROOT plane} — the one cross-host hop (mgmt-of-mgmt), used
 *       only to birth a sub-plane's single control node.
 *   <li>A workload cluster owns nothing: it runs no {@code seed-incluster}, so it renders no child.
 * </ul>
 *
 * <p>That is the two-tier topology the seeding spec prefers, and the 2026-09-29 probes are why:
 * from bioskop's cluster {@code nixos.nikopol} is unreachable while {@code nixos.bioskop} is open,
 * and from nikopol's cluster exactly the mirror. A render that emitted a cluster's CR-set onto a
 * plane that cannot reach its engine would produce CRs CAPN can only fail on — so <b>the derivation
 * IS the filter</b>, and it replaces the former unconditional {@code for (target : targets)}.
 *
 * <h2>Why the root is DECLARED and not inferred</h2>
 *
 * <p>The root is the plane {@code seed-master} seeds out-of-band — the single {@code STANDALONE}
 * boot, the one cluster that adopts itself. Nothing in a host list reveals it, and every way of
 * inferring it is a viewpoint waiting to disagree with itself: taking the first entry makes yaml
 * order load-bearing, and deriving it from who is rendering makes an in-cluster re-render compute a
 * DIFFERENT owned set than the operator's render of the same branch — Flux would then prune the
 * children it had just applied. So {@code rootHost} is recorded ONCE by the grow (from {@code
 * rke2lab:cluster.host}, which by definition names the out-of-band-seeded cluster) and travels on
 * the branch with the rest of the facet, exactly as {@code network.fabricBridgeParent} does.
 *
 * <p>This is also what un-sterilises a sub-plane: the child's recorded facet keeps the FULL host
 * list, so {@code nikopol-mgmt}'s own render derives {@code [nikopol-wrkld]} where it used to
 * receive an empty target list and be structurally childless.
 */
public record ClusterFleet(List<String> incusTargets, String rootHost) {

  public ClusterFleet {
    incusTargets = incusTargets != null ? List.copyOf(incusTargets) : List.of();
    if (rootHost == null || rootHost.isBlank()) {
      throw new IllegalArgumentException(
          "the fleet needs its ROOT host — the plane seed-master seeds out-of-band. Absent from"
              + " both the sown amendment and the branch's recorded facet; re-render from the HOST"
              + " once to seed it.");
    }
    if (!incusTargets.contains(rootHost)) {
      throw new IllegalArgumentException(
          "root host '"
              + rootHost
              + "' is not one of the declared incusTargets "
              + incusTargets
              + " — the plane that seeds the fleet must be ON the fleet");
    }
  }

  /**
   * The clusters the plane rendering {@code renderingCluster} must emit a CR-set for — its
   * children, never itself (the self-adoption intent is {@code
   * ClusterApiManagementManifestsUnit}'s).
   *
   * <p>Ordered host-local-first so a render is deterministic across runs.
   */
  public List<ClusterCoordinate> ownedBy(final String renderingCluster) {
    final ClusterCoordinate self = ClusterCoordinate.ofClusterName(renderingCluster);
    if (self.role() != ClusterRole.MGMT) {
      return List.of();
    }
    if (!incusTargets.contains(self.host())) {
      throw new IllegalStateException(
          "cluster '"
              + renderingCluster
              + "' renders on host '"
              + self.host()
              + "', which is absent from the declared incusTargets "
              + incusTargets
              + " — a plane cannot own children on a host the fleet does not declare");
    }
    final List<ClusterCoordinate> owned = new ArrayList<>();
    owned.add(new ClusterCoordinate(self.host(), ClusterRole.WRKLD));
    if (self.host().equals(rootHost)) {
      incusTargets.stream()
          .filter(host -> !host.equals(rootHost))
          .map(host -> new ClusterCoordinate(host, ClusterRole.MGMT))
          .forEach(owned::add);
    }
    return List.copyOf(owned);
  }
}
