package io.seedmatic.rke2lab.manifests.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.manifests.contract.ManifestsRunbookInput.DebugFacet;
import io.seedmatic.rke2lab.manifests.contract.ManifestsRunbookInput.DeliveryFacet;
import io.seedmatic.rke2lab.manifests.contract.ManifestsRunbookInput.Facets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The {@link Facets} compact constructor coalesces absent sub-facets — the regression for the
 * decode NPE a partial {@code rke2lab:manifests:} yaml caused: jackson decodes a record component
 * absent from the yaml (an operator config that omits {@code delivery:}) to {@code null} through
 * the CANONICAL constructor, which is the compact one, so a consumer read {@code
 * facets().delivery().push()} and hit a {@code NullPointerException} at "the manifests are
 * cultivated". Testing the compact constructor directly is testing exactly what jackson triggers.
 */
class ManifestsRunbookInputFacetsTest {

  @Test
  void the_compact_constructor_defaults_every_absent_sub_facet() {
    // What jackson hands the canonical constructor for a yaml that carries none of the sub-maps.
    final Facets coalesced = new Facets(null, null, null, null, null);

    assertNotNull(coalesced.debug(), "an absent debug sub-map defaults, never null");
    assertNotNull(coalesced.delivery(), "an absent delivery sub-map defaults, never null");
    // The safe delivery default: render + commit locally, never push until the operator opts in.
    assertFalse(coalesced.delivery().push(), "delivery defaults to push OFF");
    // An absent incusTargets list is an empty list, never null — a bare survey / the standalone
    // CLI, which declares no fleet and therefore owns no child.
    assertTrue(coalesced.incusTargets().isEmpty(), "an absent incusTargets defaults to empty");
    assertTrue(
        coalesced.clusterFleet().isEmpty(),
        "no declared host means NO fleet — absent, not an empty fleet that would own things");
    // An absent network coalesces to unknown() — a BLANK parent, never a plausible bridge name: the
    // render then poses a device with an empty parent, visibly wrong in a manifest, where a default
    // would silently attach a node to the wrong bridge.
    assertTrue(
        coalesced.network().isEmpty(),
        "an absent network is Optional.empty() — absence lives in the TYPE, never in a blank string");
  }

  @Test
  void a_present_sub_facet_is_kept_verbatim() {
    final Facets partial =
        new Facets(
            DebugFacet.builder().mesh(true).build(),
            new DeliveryFacet(true),
            List.of("bioskop", "nikopol"),
            Optional.of("bioskop"),
            Optional.of(new ManifestsRunbookInput.NetworkFacet("fabric-br")));

    assertEquals(true, partial.delivery().push(), "a present delivery is kept, not defaulted");
    assertTrue(partial.debug().mesh().enabled(), "a present debug is kept verbatim");
    assertEquals(2, partial.incusTargets().size(), "a present incusTargets is kept");
    // The fleet is DERIVED from the pair, and the owner rule narrows it: the root plane renders its
    // own host's workload plus the one cross-host hop that births a sub-plane.
    assertEquals(
        List.of("bioskop-wrkld", "nikopol-mgmt"),
        partial.clusterFleet().orElseThrow().ownedBy("bioskop-mgmt").stream()
            .map(ClusterCoordinate::clusterName)
            .toList(),
        "the fleet derives the children the ROOT plane owns");
    // The network rides INSIDE the facet, which is what makes it recorded on the branch and
    // replayed
    // by an in-cluster render — the reason it stopped being an amendment role of its own.
    assertEquals(
        "fabric-br",
        partial.network().orElseThrow().fabricBridgeParent(),
        "a present network is kept verbatim");
  }

  /**
   * The boundary refuses a present-but-meaningless bridge. This is the guard whose absence was paid
   * for on 2026-09-28: a blank parent travelled as if it were a value and the render published
   * {@code parent=} for every node, a tree Flux would have applied over a correct live one. Absence
   * is {@code Optional.empty()}; a {@code NetworkFacet} that EXISTS always names a bridge.
   */
  @Test
  void a_blank_bridge_parent_is_refused_at_construction() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ManifestsRunbookInput.NetworkFacet(""),
        "a blank fabricBridgeParent must be refused, not carried as a sentinel for absence");
    assertThrows(
        NullPointerException.class,
        () -> new ManifestsRunbookInput.NetworkFacet(null),
        "a null fabricBridgeParent must be refused too");
  }

  @Test
  void declared_hosts_without_a_root_are_LOUD_not_an_empty_fleet() {
    // The migration symptom: a branch recorded before rootIncusHost existed decodes hosts but no
    // root. Answering "no fleet" there would render NO child while Flux prunes the children it had
    // already applied — a silent teardown. Three values, and this is the third: absent (no hosts)
    // is fine, present is fine, half-present is BROKEN.
    final Facets halfDecoded =
        new Facets(null, null, List.of("bioskop", "nikopol"), Optional.empty(), null);

    final IllegalStateException loud =
        assertThrows(IllegalStateException.class, halfDecoded::clusterFleet);
    assertTrue(
        loud.getMessage().contains("Re-render from the HOST once"),
        "the failure must name the remedy, not just the missing key");
  }
}
