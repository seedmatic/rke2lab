package io.seedmatic.rke2lab.plan.cli.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioStage;
import com.tngtech.jgiven.annotation.ScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.ScenarioModel;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.ConnectionReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.SeedRuntime;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.seed.bdd.EphemeralCellar;
import io.seedmatic.rke2lab.seed.bdd.SeedReceiver;
import io.seedmatic.rke2lab.seed.bdd.SessionSeed;
import io.seedmatic.rke2lab.seed.bdd.SowAndGraftStage;
import io.seedmatic.rke2lab.seed.bdd.sow.Gardening;
import io.seedmatic.rke2lab.seed.broker.port.Amendment;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.OpaqueCellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import java.util.Hashtable;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The plan-cli root scenario — the host runbook for the {@code plan <plane> export} verbs, spoken
 * in the same gardening register as {@code ClusterSeedScenario} but plan-only and Pulumi-free. It
 * is a single sow, parameterised by the {@link io.seedmatic.rke2lab.plan.cli.Plane} the run
 * carries: open the gardening, sow the plane's coordinate through the broker, reap the runbook. Its
 * side effect is the domain scion writing its export file into the SOIL — the host reads that file
 * back and renders it (YAML/JSON per plane).
 *
 * <p>Why a scenario and not a flat dump: each plane's export type ({@code ClusterNetworkBlueprint},
 * {@code DataplanLayout}) lives in a {@code type=contract} bundle — a bundle-realm type the flat
 * host cannot reference (the realm-boundary law forbids it). Sowing through the broker (the ONE
 * system-exported {@code seed.broker.port} membrane) grows the domain scion in-container, where the
 * type is reachable; only host-neutral JSON crosses back.
 */
@SeedScenario
@SeedRuntime
public class PlanCliScenario
    extends ScenarioTestBase<PlanCliScenario.Given, PlanCliScenario.When, PlanCliScenario.Then>
    implements SeedReceiver<PlanCliRun>, ConnectionReceiver, CellarReceiver<ScenarioCellar> {

  /** The inbound channel the CLI seeds the {@link PlanCliRun} through; single-sourced. */
  @RegisterExtension
  public static final SessionSeed<PlanCliRun> SEED =
      new SessionSeed<>(PlanCliRun.class, "plan-cli-run");

  private final Scenario<Given, When, Then> scenario = createScenario();

  @MonotonicNonNull private PlanCliRun run;
  @MonotonicNonNull private OsgiConnection connection;
  @MonotonicNonNull private ScenarioCellar cellar;

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveSeed(PlanCliRun run) {
    this.run = run;
  }

  @Override
  public void receiveConnection(OsgiConnection connection) {
    this.connection = connection;
  }

  @Override
  public void receiveCellar(ScenarioCellar cellar) {
    this.cellar = cellar;
  }

  @Test
  void the_plan_is_exported() {
    final PlanCliRun seedRun =
        Objects.requireNonNull(run, "the PlanCliRun was not seeded before the scenario ran");
    final OsgiConnection world =
        Objects.requireNonNull(
            connection, "the OsgiConnection was not received before the scenario ran");
    final ScenarioCellar tx =
        Objects.requireNonNull(
            cellar, "the ScenarioCellar was not injected before the scenario ran");
    given().i_have_access_to_the_open_gardening(seedRun, world, tx);
    when().the_plan_is_sown(getScenario().getScenarioModel(), getScenario().getModel());
    then().the_harvest_is_handed_back(seedRun);
  }

  /**
   * The GIVEN opens the gardening over the world extension's connection, publishes the run's {@link
   * Parcel}, and holds the run's soil + the plane's coordinate. The parcel is published
   * synchronously, before any sow, the way the seed host does: a scion harvests only when the run
   * publishes one, and the export reads that harvest back.
   */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState Gardening gardening;
    @ProvidedScenarioState Cellar cellar;
    @ProvidedScenarioState Parcel parcel;
    @ProvidedScenarioState Optional<String> materializationRoot;
    @ProvidedScenarioState String coordinate;

    public Given i_have_access_to_the_open_gardening(
        @Hidden PlanCliRun run, @Hidden OsgiConnection world, @Hidden ScenarioCellar cellar) {
      // Open OVER the connection the world extension owns (class scope) — no second Felix booted.
      this.gardening = Gardening.over(world);
      this.cellar = cellar;
      this.materializationRoot = run.materializationRoot();
      this.coordinate = run.plane().coordinate();
      // Publish the run's durable backend for the ROOT drain ScenarioCellarExtension performs at
      // the end. plan-cli is a standalone export with no persistent commissioner (no Pulumi), so
      // the backend is the offline EphemeralCellar, the black hole: the scion's harvest lives in
      // the
      // run's transactional overlay, is read back before the drain, and persists nowhere.
      world.context().registerService(OpaqueCellar.class, new EphemeralCellar(), new Hashtable<>());
      this.parcel = new Parcel("rke2lab", "plan");
      world.context().registerService(Parcel.class, parcel, new Hashtable<>());
      return self();
    }
  }

  /**
   * The WHEN sows the plane's coordinate and GRAFTS the reaped scion into this run's trunk, through
   * the same {@link SowAndGraftStage} every host crossing uses. The graft is what brings the
   * scion's harvest into this run's cellar overlay, and it propagates the scion's verdict: a failed
   * export fails the run.
   */
  public static class When extends Stage<When> {

    @ScenarioStage SowAndGraftStage sowAndGraft;

    @ScenarioState Gardening gardening;
    @ScenarioState Optional<String> materializationRoot;
    @ScenarioState String coordinate;

    @As("the plan is sown")
    public When the_plan_is_sown(@Hidden ScenarioModel hostScenario, @Hidden ReportModel hostTree) {
      // The only amendment the CLI carries is the SOIL — the plot the scion writes its own export
      // file into (nothing reads it), and the runbook input's only component.
      final Map<String, JsonNode> amendments =
          materializationRoot
              .map(root -> Map.<String, JsonNode>of(Amendment.SOIL, TextNode.valueOf(root)))
              .orElseGet(Map::of);
      sowAndGraft
          .sowing(coordinate, gardening, hostScenario, hostTree, amendments)
          .the_scion_is_sown_and_grafted("the plan is sown");
      return self();
    }
  }

  /**
   * The THEN hands the harvest the scion filed back to the CLI, read from the run's own cellar —
   * the CLI renders what the scion DERIVED. The scion's verdict was already enforced by the graft.
   */
  public static class Then extends Stage<Then> {

    @ScenarioState Cellar cellar;
    @ScenarioState Parcel parcel;

    @As("the harvest is handed back")
    public Then the_harvest_is_handed_back(@Hidden PlanCliRun run) {
      final SeedCoordinate harvest = run.plane().harvest();
      run.reaped()
          .accept(
              cellar
                  .fetch(parcel, harvest, JsonNode.class)
                  .orElseThrow(
                      () ->
                          new AssertionError(
                              "the "
                                  + run.plane().coordinate()
                                  + " scion filed no harvest at "
                                  + harvest.slug()
                                  + " in this run")));
      return self();
    }
  }
}
