package io.seedmatic.rke2lab.clusterpki.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A vcluster's PKI is sealed by the grow that seals its HOST cluster, and by no other. */
class VclusterDeclarationTest {

  @Test
  void ccr_is_sealed_with_nikopol_wrkld_and_by_no_other_grow() {
    assertEquals(List.of("ccr"), VclusterDeclaration.fleet().hostedBy(List.of("nikopol-wrkld")));
    assertEquals(
        List.of("ccr"),
        VclusterDeclaration.fleet().hostedBy(List.of("bioskop-wrkld", "nikopol-wrkld")));
    assertEquals(
        List.of(), VclusterDeclaration.fleet().hostedBy(List.of("bioskop-wrkld", "nikopol-mgmt")));
    assertEquals(List.of(), VclusterDeclaration.fleet().hostedBy(List.of()));
  }

  @Test
  void its_secret_and_namespace_are_the_ones_vcluster_reads() {
    final VclusterDeclaration.Vcluster ccr = VclusterDeclaration.fleet().named("ccr").orElseThrow();
    assertEquals("vcluster-ccr", ccr.namespace());
    assertEquals("nikopol-wrkld", ccr.hostCluster());
  }
}
