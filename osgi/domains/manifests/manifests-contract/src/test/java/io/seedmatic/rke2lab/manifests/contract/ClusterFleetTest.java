package io.seedmatic.rke2lab.manifests.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The owner rule — the piece that replaced an unconditional {@code for (target : targets)}, so its
 * acceptance criterion is exact: applied to the live fleet, the derivation must reproduce what
 * {@code manifests/bioskop-mgmt} ALREADY carries (measured 2026-09-30 on the rendered branch: the
 * children {@code bioskop-wrkld} and {@code nikopol-mgmt}, and no other). A filter that changed
 * today's correct output would be a regression, not a fix.
 */
class ClusterFleetTest {

  private static final ClusterFleet FLEET =
      new ClusterFleet(List.of("bioskop", "nikopol"), "bioskop");

  private static List<String> names(final List<ClusterCoordinate> owned) {
    return owned.stream().map(ClusterCoordinate::clusterName).toList();
  }

  @Test
  void the_root_plane_owns_its_local_workload_and_every_other_host_s_management_plane() {
    // Exactly the two children on manifests/bioskop-mgmt today — the local workload plus the ONE
    // cross-host hop that births a sub-plane.
    assertEquals(List.of("bioskop-wrkld", "nikopol-mgmt"), names(FLEET.ownedBy("bioskop-mgmt")));
  }

  @Test
  void a_sub_plane_owns_its_OWN_host_s_workload_and_nothing_across_a_host() {
    // The un-sterilisation: this used to be an empty list, which is why manifests/nikopol-mgmt was
    // structurally childless. And decisively it does NOT contain bioskop-mgmt — a sub-plane must
    // never claim the root, or the root would have two adopters.
    assertEquals(List.of("nikopol-wrkld"), names(FLEET.ownedBy("nikopol-mgmt")));
  }

  @Test
  void no_plane_owns_a_workload_on_another_host() {
    // The danger the filter exists for: bioskop emitting nikopol-wrkld's CR-set onto an engine it
    // cannot reach. A workload's owner is host-local, always.
    for (final String plane : List.of("bioskop-mgmt", "nikopol-mgmt")) {
      final ClusterCoordinate self = ClusterCoordinate.ofClusterName(plane);
      assertTrue(
          FLEET.ownedBy(plane).stream()
              .filter(owned -> owned.role() == ClusterRole.WRKLD)
              .allMatch(owned -> owned.host().equals(self.host())),
          plane + " must own no workload outside its own host");
    }
  }

  @Test
  void a_workload_cluster_owns_nothing() {
    // It runs no seed-incluster, so it reconciles no child — and renders none.
    assertTrue(FLEET.ownedBy("bioskop-wrkld").isEmpty(), "a workload plane renders no child");
    assertTrue(FLEET.ownedBy("nikopol-wrkld").isEmpty(), "a workload plane renders no child");
  }

  @Test
  void every_cluster_has_exactly_one_owner() {
    // The invariant the whole federation rests on ("each cluster has EXACTLY ONE adopter"). The
    // root is excluded: it is the one cluster that adopts ITSELF, so no plane lists it as a child.
    final List<String> planes = List.of("bioskop-mgmt", "nikopol-mgmt");
    final List<String> everyOwnedCluster =
        planes.stream().flatMap(plane -> names(FLEET.ownedBy(plane)).stream()).toList();

    assertEquals(
        List.of("bioskop-wrkld", "nikopol-mgmt", "nikopol-wrkld"),
        everyOwnedCluster.stream().sorted().toList(),
        "every cluster but the root is owned, and each appears once");
    assertEquals(
        everyOwnedCluster.size(),
        everyOwnedCluster.stream().distinct().count(),
        "no cluster is claimed by two planes");
  }

  @Test
  void every_cluster_names_exactly_one_adopter_and_only_the_root_names_itself() {
    // The rule a plane reads to decide what it acts on. The root's self-adoption is NOT a special
    // case in the reader: it falls out of the root naming itself.
    assertEquals("bioskop-mgmt", FLEET.adopterOf("bioskop-mgmt"), "the root adopts itself");
    assertEquals(
        "bioskop-mgmt", FLEET.adopterOf("bioskop-wrkld"), "a workload names its own plane");
    assertEquals("bioskop-mgmt", FLEET.adopterOf("nikopol-mgmt"), "a sub-plane names the ROOT");
    assertEquals(
        "nikopol-mgmt", FLEET.adopterOf("nikopol-wrkld"), "a workload names its OWN host's plane");
  }

  @Test
  void the_adopter_of_every_cluster_agrees_with_who_owns_it() {
    // The two derivations must not be able to disagree: X appears in ownedBy(P) iff adopterOf(X) is
    // P — except the root, which adopts itself and is therefore no one's child.
    final List<String> planes = List.of("bioskop-mgmt", "nikopol-mgmt");
    for (final String plane : planes) {
      for (final ClusterCoordinate owned : FLEET.ownedBy(plane)) {
        assertEquals(
            plane,
            FLEET.adopterOf(owned.clusterName()),
            owned.clusterName() + " is owned by " + plane + ", so it must name it as adopter");
      }
    }
    assertEquals(
        "bioskop-mgmt",
        FLEET.adopterOf("bioskop-mgmt"),
        "the root is in no one's ownedBy, and names itself");
  }

  @Test
  void the_fleet_declares_every_host_crossed_with_every_role() {
    // The UNIFORM view: this is what lands on every plane's branch, identically.
    assertEquals(
        List.of("bioskop-mgmt", "bioskop-wrkld", "nikopol-mgmt", "nikopol-wrkld"),
        names(FLEET.all()).stream().sorted().toList());
  }

  @Test
  void every_declared_cluster_has_an_adopter_inside_the_fleet() {
    // The invariant the uniform view rests on: a plane can be handed ANY intention and always
    // decide
    // whether to act, because every declared cluster names an adopter that the fleet also declares.
    for (final ClusterCoordinate c : FLEET.all()) {
      final String adopter = FLEET.adopterOf(c.clusterName());
      assertTrue(
          names(FLEET.all()).contains(adopter),
          c.clusterName() + " names adopter " + adopter + ", which must itself be declared");
    }
  }

  @Test
  void a_single_host_fleet_owns_only_its_workload() {
    final ClusterFleet lone = new ClusterFleet(List.of("bioskop"), "bioskop");
    assertEquals(List.of("bioskop-wrkld"), names(lone.ownedBy("bioskop-mgmt")));
  }

  @Test
  void the_root_must_be_declared_and_must_be_on_the_fleet() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ClusterFleet(List.of("bioskop"), null),
        "an absent root host is loud — inferring it would make the owned set depend on who renders");
    assertThrows(
        IllegalArgumentException.class,
        () -> new ClusterFleet(List.of("bioskop"), "   "),
        "a blank root host is loud too");
    assertThrows(
        IllegalArgumentException.class,
        () -> new ClusterFleet(List.of("nikopol"), "bioskop"),
        "the seeding plane must be ON the fleet it seeds");
  }

  @Test
  void a_plane_on_a_host_the_fleet_does_not_declare_is_loud() {
    // The migration symptom: a branch recorded before incusTargets existed decodes to a fleet that
    // does not mention this cluster's host. Failing names the remedy instead of silently rendering
    // no child and letting Flux prune the children it had applied.
    assertThrows(IllegalStateException.class, () -> FLEET.ownedBy("chiba-mgmt"));
  }

  @Test
  void a_coordinate_refuses_a_cluster_name_where_a_host_belongs() {
    // The confusion that made half the clusters vanish from the netplan projection, now loud at the
    // type's door rather than three layers down.
    assertThrows(
        IllegalArgumentException.class,
        () -> new ClusterCoordinate("bioskop-mgmt", ClusterRole.MGMT));
    assertThrows(IllegalArgumentException.class, () -> ClusterCoordinate.ofClusterName("bioskop"));
    assertThrows(IllegalArgumentException.class, () -> ClusterCoordinate.ofClusterName("bioskop-"));
  }

  @Test
  void a_coordinate_round_trips_through_its_cluster_name() {
    for (final String cluster :
        List.of("bioskop-mgmt", "bioskop-wrkld", "nikopol-mgmt", "nikopol-wrkld")) {
      assertEquals(cluster, ClusterCoordinate.ofClusterName(cluster).clusterName());
    }
  }
}
