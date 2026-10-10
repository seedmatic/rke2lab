package io.seedmatic.rke2lab.netplan.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.netplan.ingress.NetplanIngressCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.testkit.InMemoryCellar;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The netplan export files its projection where the seed host reads the operator's addressing — in
 * a seed run, which publishes a parcel — and files nothing in a plan-CLI run, which publishes none.
 * The projection is the one the export derives from the netplan code, not a file.
 */
class NetplanProjectionHarvestTest {

  private static final Parcel PARCEL = new Parcel("rke2lab", "bioskop-mgmt");

  private static NetplanBlueprintScenario.Then derived() {
    final NetplanBlueprintScenario.When when = new NetplanBlueprintScenario.When();
    when.the_blueprint_metadata_is_derived();
    final NetplanBlueprintScenario.Then then = new NetplanBlueprintScenario.Then();
    then.metadata = Objects.requireNonNull(when.metadata);
    return then;
  }

  @Test
  void a_seed_run_harvests_the_derived_projection() {
    final NetplanBlueprintScenario.Then then = derived();
    final InMemoryCellar cellar = new InMemoryCellar();

    then.the_projection_is_harvested(cellar, Optional.of(PARCEL));

    final Optional<NetplanBlueprintScenario.NetworkBlueprintMetadata> harvested =
        cellar.fetch(
            PARCEL,
            NetplanIngressCoordinate.PROJECTION,
            NetplanBlueprintScenario.NetworkBlueprintMetadata.class);
    assertTrue(harvested.isPresent());
    assertEquals(then.metadata, harvested.orElseThrow());
  }

  @Test
  void a_run_without_a_parcel_harvests_nothing() {
    final InMemoryCellar cellar = new InMemoryCellar();

    derived().the_projection_is_harvested(cellar, Optional.empty());

    assertTrue(
        cellar
            .fetch(
                PARCEL,
                NetplanIngressCoordinate.PROJECTION,
                NetplanBlueprintScenario.NetworkBlueprintMetadata.class)
            .isEmpty());
  }
}
