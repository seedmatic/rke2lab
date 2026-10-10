package io.seedmatic.rke2lab.fabric.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.junit5.ScenarioTest;
import com.tngtech.jgiven.report.json.ScenarioJsonWriter;
import com.tngtech.jgiven.report.model.ReportModel;
import io.seedmatic.rke2lab.dataplan.ingress.DataplanIngressCoordinate;
import io.seedmatic.rke2lab.fabric.contract.FabricRunbookInput;
import io.seedmatic.rke2lab.netplan.ingress.NetplanIngressCoordinate;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.testkit.InMemoryCellar;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The push token reaches the push and nothing else a run keeps. The delivery is played as a real
 * jGiven scenario over a transactional cellar whose write set lives on the SAME report model, so
 * one serialised runbook carries both the step report and the cellar's writes — and a SENTINEL
 * token is looked for in it.
 *
 * <p>A sentinel, not a provider-shaped token: a {@code ghs_}-shaped literal in a public
 * repository's test source is exactly what push protection flags. What is proven is that the VALUE
 * the mint returned is nowhere in what the run records; its shape is irrelevant to that.
 */
class FabricTokenStaysOutOfTheRunTest
    extends ScenarioTest<
        FabricTokenStaysOutOfTheRunTest.Given,
        FabricDeliveryScenario.When,
        FabricTokenStaysOutOfTheRunTest.Then> {

  private static final String SENTINEL = "SENTINEL-MINTED-PUSH-TOKEN-5c";
  private static final String FETCH_SENTINEL = "SENTINEL-MINTED-FETCH-TOKEN";
  private static final Parcel PARCEL = new Parcel("rke2lab", "plan");

  @TempDir Path tmp;

  /** The runbook input the delivery's WHEN expects. */
  public static class Given extends Stage<Given> {
    @ProvidedScenarioState FabricRunbookInput facet;

    public Given a_plot_for_the_delivery(Path root) {
      this.facet = new FabricRunbookInput(Optional.of(root.toString()));
      return self();
    }
  }

  /** No THEN step: the assertions read the runbook the steps produced. */
  public static class Then extends Stage<Then> {}

  /**
   * The runbook as it stands: the steps played so far and the cellar's write set. jGiven only adds
   * the scenario to the report model when it ENDS, so serialising the live model mid-test yields a
   * runbook with no steps at all — the grep would then pass on nothing. This copy holds both.
   */
  private ReportModel runbookSoFar() {
    final ReportModel live = getScenario().getModel();
    final ReportModel copy = new ReportModel();
    copy.addScenarioModel(getScenario().getScenarioModel());
    copy.setTagMap(new LinkedHashMap<>(live.getTagMap()));
    return copy;
  }

  @Test
  void the_token_reaches_the_push_and_neither_the_report_nor_the_cellar() {
    final ScenarioCellar cellar =
        new ScenarioCellar(() -> getScenario().getModel(), InMemoryCellar::new, Optional.of("tx"));
    cellar.store(
        PARCEL,
        NetplanIngressCoordinate.PROJECTION,
        JsonNodeFactory.instance.objectNode().put("addressing", "fixture"));
    cellar.store(
        PARCEL,
        DataplanIngressCoordinate.LAYOUT,
        JsonNodeFactory.instance.objectNode().put("pool", "fixture"));
    final RecordingWorktrees worktrees = new RecordingWorktrees();

    given().a_plot_for_the_delivery(tmp);
    when()
        .the_harvested_plan_is_read(cellar, Optional.of(PARCEL))
        .and()
        .the_delivery_is_written(
            Optional.of(worktrees),
            Optional.of(new FakeKeystore()),
            Optional.of(FETCH_SENTINEL),
            Optional.of(SENTINEL));

    final String runbook = new ScenarioJsonWriter(runbookSoFar()).toString();

    assertEquals(Optional.of(SENTINEL), worktrees.made.pushedWith, "the token DID reach the push");
    assertEquals(
        Optional.of(FETCH_SENTINEL), worktrees.fetchedWith, "the fetch token DID reach the fetch");
    assertTrue(
        runbook.contains("delivery is written"), "the runbook records the step that held it");
    assertTrue(
        runbook.contains(NetplanIngressCoordinate.PROJECTION.slug()),
        "the runbook carries the cellar's write set too, so the grep covers it");
    assertFalse(runbook.contains(SENTINEL), "the token is nowhere in the report or the cellar");
    assertFalse(runbook.contains(FETCH_SENTINEL), "nor is the fetch token");
    assertFalse(PARCEL.toString().contains(SENTINEL), "nor in the run's parcel");
  }
}
