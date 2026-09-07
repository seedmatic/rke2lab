package io.seedmatic.rke2lab.manifests.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.manifests.contract.ManifestsRunbookInput.DeliveryFacet;
import io.seedmatic.rke2lab.manifests.contract.ManifestsRunbookInput.Facets;
import io.seedmatic.rke2lab.manifests.contract.ManifestsRunbookInput.PublishFacet;
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

    assertNotNull(coalesced.publish(), "an absent publish sub-map defaults, never null");
    assertNotNull(coalesced.debug(), "an absent debug sub-map defaults, never null");
    assertNotNull(coalesced.delivery(), "an absent delivery sub-map defaults, never null");
    // The safe delivery default: render + commit locally, never push until the operator opts in.
    assertFalse(coalesced.delivery().push(), "delivery defaults to push OFF");
    // An absent workloadTargets list is an empty list, never null — a mgmt cluster with no
    // workloads.
    assertTrue(
        coalesced.workloadTargets().isEmpty(), "an absent workloadTargets defaults to empty");
  }

  @Test
  void a_present_sub_facet_is_kept_verbatim() {
    final Facets partial =
        new Facets(
            PublishFacet.defaults(),
            null,
            new DeliveryFacet(true),
            List.of(new WorkloadTarget("bioskop", "wrkld")));

    assertEquals(true, partial.delivery().push(), "a present delivery is kept, not defaulted");
    assertNotNull(partial.debug(), "the omitted debug still defaults");
    assertEquals(1, partial.workloadTargets().size(), "a present workloadTargets is kept");
    assertEquals(
        "bioskop-wrkld",
        partial.workloadTargets().get(0).clusterName(),
        "the target's clusterName is <host>-<role>");
  }
}
