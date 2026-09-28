package io.seedmatic.rke2lab.manifests.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.manifests.contract.ManifestsRunbookInput.DebugFacet;
import io.seedmatic.rke2lab.manifests.contract.ManifestsRunbookInput.DeliveryFacet;
import io.seedmatic.rke2lab.manifests.contract.ManifestsRunbookInput.Facets;
import java.util.List;
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
    final Facets coalesced = new Facets(null, null, null, null);

    assertNotNull(coalesced.debug(), "an absent debug sub-map defaults, never null");
    assertNotNull(coalesced.delivery(), "an absent delivery sub-map defaults, never null");
    // The safe delivery default: render + commit locally, never push until the operator opts in.
    assertFalse(coalesced.delivery().push(), "delivery defaults to push OFF");
    // An absent workloadTargets list is an empty list, never null — a mgmt cluster with no
    // workloads.
    assertTrue(
        coalesced.workloadTargets().isEmpty(), "an absent workloadTargets defaults to empty");
    // An absent network coalesces to unknown() — a BLANK parent, never a plausible bridge name: the
    // render then poses a device with an empty parent, visibly wrong in a manifest, where a default
    // would silently attach a node to the wrong bridge.
    assertNotNull(coalesced.network(), "an absent network sub-map defaults, never null");
    assertEquals(
        "",
        coalesced.network().fabricBridgeParent(),
        "an absent network defaults to a BLANK parent, not a plausible bridge name");
  }

  @Test
  void a_present_sub_facet_is_kept_verbatim() {
    final Facets partial =
        new Facets(
            DebugFacet.builder().mesh(true).build(),
            new DeliveryFacet(true),
            List.of(new WorkloadTarget("bioskop", "wrkld")),
            new ManifestsRunbookInput.NetworkFacet("fabric-br"));

    assertEquals(true, partial.delivery().push(), "a present delivery is kept, not defaulted");
    assertTrue(partial.debug().mesh().enabled(), "a present debug is kept verbatim");
    assertEquals(1, partial.workloadTargets().size(), "a present workloadTargets is kept");
    assertEquals(
        "bioskop-wrkld",
        partial.workloadTargets().get(0).clusterName(),
        "the target's clusterName is <host>-<role>");
    // The network rides INSIDE the facet, which is what makes it recorded on the branch and
    // replayed
    // by an in-cluster render — the reason it stopped being an amendment role of its own.
    assertEquals(
        "fabric-br", partial.network().fabricBridgeParent(), "a present network is kept verbatim");
  }
}
