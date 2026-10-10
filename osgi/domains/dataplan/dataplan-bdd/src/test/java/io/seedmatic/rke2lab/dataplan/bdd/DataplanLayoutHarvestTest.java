package io.seedmatic.rke2lab.dataplan.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.dataplan.contract.DataplanCoordinate;
import io.seedmatic.rke2lab.dataplan.contract.DataplanLayout;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.testkit.InMemoryCellar;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The dataplan export files its layout where the fabric delivery reads the dataset tree — in a seed
 * run, which publishes a parcel — and files nothing in a plan-CLI run, which publishes none. The
 * layout is the one the export derives from the dataplan code, not a file.
 */
class DataplanLayoutHarvestTest {

  private static final Parcel PARCEL = new Parcel("rke2lab", "bioskop-mgmt");

  private static DataplanScenario.Then derived() {
    final DataplanScenario.When when = new DataplanScenario.When();
    when.the_layout_is_derived();
    final DataplanScenario.Then then = new DataplanScenario.Then();
    then.layout = Objects.requireNonNull(when.layout);
    return then;
  }

  @Test
  void a_seed_run_harvests_the_derived_layout() {
    final DataplanScenario.Then then = derived();
    final InMemoryCellar cellar = new InMemoryCellar();

    then.the_layout_is_harvested(cellar, Optional.of(PARCEL));

    final Optional<DataplanLayout> harvested =
        cellar.fetch(PARCEL, DataplanCoordinate.LAYOUT, DataplanLayout.class);
    assertTrue(harvested.isPresent());
    assertEquals(then.layout, harvested.orElseThrow());
  }

  @Test
  void a_run_without_a_parcel_harvests_nothing() {
    final InMemoryCellar cellar = new InMemoryCellar();

    derived().the_layout_is_harvested(cellar, Optional.empty());

    assertTrue(cellar.fetch(PARCEL, DataplanCoordinate.LAYOUT, DataplanLayout.class).isEmpty());
  }
}
