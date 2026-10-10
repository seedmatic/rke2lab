package io.seedmatic.rke2lab.plan.cli.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.tngtech.jgiven.report.model.ReportModel;
import io.seedmatic.rke2lab.dataplan.ingress.DataplanIngressCoordinate;
import io.seedmatic.rke2lab.netplan.ingress.NetplanIngressCoordinate;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.seedmatic.rke2lab.seed.bdd.EphemeralCellar;
import io.seedmatic.rke2lab.seed.broker.internal.CodecCellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The export's cellar, as the plan CLI wires it: a run's transactional overlay over the
 * EphemeralCellar, the black hole. The export reads the harvest back WITHIN the run (read your
 * writes, before the drain), and publishing a parcel to make the scions harvest persists nothing a
 * later run could see.
 *
 * <p>This exercises the overlay the scenario reads from; the graft that carries a scion's store
 * into it is exercised end to end by the export itself, whose output is byte-identical to the one
 * the soil read produced.
 */
class ExportHarvestCellarTest {

  private static final Parcel PARCEL = new Parcel("rke2lab", "plan");

  private static ScenarioCellar run() {
    final ReportModel model = new ReportModel();
    return new ScenarioCellar(
        () -> model, () -> new CodecCellar(new EphemeralCellar(), null), Optional.of("tx"));
  }

  private static JsonNode tree(String value) {
    return JsonNodeFactory.instance.objectNode().put("harvested", value);
  }

  @Test
  void the_export_reads_each_harvest_back_within_its_run_over_the_ephemeral_cellar() {
    for (final SeedCoordinate harvest :
        List.of(NetplanIngressCoordinate.PROJECTION, DataplanIngressCoordinate.LAYOUT)) {
      final ScenarioCellar run = run();
      run.store(PARCEL, harvest, tree(harvest.slug()));

      assertEquals(
          Optional.of(tree(harvest.slug())),
          run.fetch(PARCEL, harvest, JsonNode.class),
          harvest.slug() + " is read back within the run that filed it");
    }
  }

  @Test
  void a_later_run_finds_nothing_the_export_harvested() {
    for (final SeedCoordinate harvest :
        List.of(NetplanIngressCoordinate.PROJECTION, DataplanIngressCoordinate.LAYOUT)) {
      run().store(PARCEL, harvest, tree(harvest.slug()));

      assertEquals(
          Optional.empty(),
          run().fetch(PARCEL, harvest, JsonNode.class),
          "the durable side is the black hole: the parcel made the scions harvest, nothing more");
    }
  }
}
