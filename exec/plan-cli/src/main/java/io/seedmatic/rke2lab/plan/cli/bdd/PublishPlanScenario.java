package io.seedmatic.rke2lab.plan.cli.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioStage;
import com.tngtech.jgiven.annotation.ScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.ScenarioModel;
import io.seedmatic.rke2lab.host.runtime.ExecutionEnvironment;
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
import io.seedmatic.rke2lab.seed.broker.port.SecretsGateway;
import java.util.Hashtable;
import java.util.Map;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The plan CLI's {@code publish} root scenario: it derives the plan, and the fabric scion delivers
 * it to {@code fabric/plan}. Four crossings, in order, sharing the run's transactional cellar and
 * {@link Parcel}:
 *
 * <ol>
 *   <li>{@code ghapp} — rehydrates the one org-owned App's credentials from {@code .secrets}
 *       through the {@link SecretsGateway} this scenario publishes, and seals them in the run;
 *   <li>{@code netplan} and {@code dataplan} — derive the projection and the layout, and harvest
 *       them;
 *   <li>{@code fabric} — reads both harvests, writes {@code netplan.json} + {@code dataplan.json}
 *       into its linked worktree, commits signed, and pushes with a token minted from the App the
 *       first crossing sealed.
 * </ol>
 *
 * <p>EVERY crossing sows AND grafts, through the same {@link SowAndGraftStage} every host crossing
 * uses. That is not style: the graft is what folds a scion's writes into this run's cellar overlay,
 * and a later sow inherits that overlay. Sown raw, the App the {@code ghapp} scion sealed would
 * stay in its own runbook, the fabric scion would inherit nothing, no token would be minted, and
 * the delivery would skip its push in silence. The graft also propagates each scion's verdict: a
 * failed crossing fails the run.
 *
 * <p>The CLI is the trigger by CHOICE: the operator's run is the only one that publishes a plan, so
 * the CLI decides WHEN and the fabric scion pushes. Nothing in the delivery requires a CLI.
 */
@SeedScenario
@SeedRuntime
public class PublishPlanScenario
    extends ScenarioTestBase<
        PublishPlanScenario.Given, PublishPlanScenario.When, PublishPlanScenario.Then>
    implements SeedReceiver<PublishPlanRun>, ConnectionReceiver, CellarReceiver<ScenarioCellar> {

  /** The inbound channel the CLI seeds the {@link PublishPlanRun} through; single-sourced. */
  @RegisterExtension
  public static final SessionSeed<PublishPlanRun> SEED =
      new SessionSeed<>(PublishPlanRun.class, "plan-publish-run");

  private final Scenario<Given, When, Then> scenario = createScenario();

  @MonotonicNonNull private PublishPlanRun run;
  @MonotonicNonNull private OsgiConnection connection;
  @MonotonicNonNull private ScenarioCellar cellar;

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveSeed(PublishPlanRun run) {
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
  void the_plan_is_published() {
    final PublishPlanRun seedRun =
        Objects.requireNonNull(run, "the PublishPlanRun was not seeded before the scenario ran");
    final OsgiConnection world =
        Objects.requireNonNull(
            connection, "the OsgiConnection was not received before the scenario ran");
    final ScenarioCellar tx =
        Objects.requireNonNull(
            cellar, "the ScenarioCellar was not injected before the scenario ran");
    given().i_have_access_to_the_open_gardening(seedRun, world, tx);
    final ScenarioModel hostScenario = getScenario().getScenarioModel();
    final ReportModel hostTree = getScenario().getModel();
    when()
        .the_github_app_is_rehydrated(hostScenario, hostTree)
        .and()
        .the_network_plan_is_derived(hostScenario, hostTree)
        .and()
        .the_dataset_plan_is_derived(hostScenario, hostTree)
        .and()
        .the_plan_is_delivered(hostScenario, hostTree);
  }

  /**
   * The GIVEN opens the gardening, and publishes — synchronously, before any sow — the run's {@link
   * Parcel} (the ghapp scion awaits it, the others harvest only when there is one), the {@link
   * SecretsGateway} the ghapp scion rehydrates the App through, and the EphemeralCellar backend for
   * the root drain: a publish persists nothing in a cellar; what it delivers is the commit it
   * pushes.
   */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState Gardening gardening;
    @ProvidedScenarioState Cellar cellar;
    @ProvidedScenarioState PublishPlanRun run;

    public Given i_have_access_to_the_open_gardening(
        @Hidden PublishPlanRun run, @Hidden OsgiConnection world, @Hidden ScenarioCellar cellar) {
      this.gardening = Gardening.over(world);
      this.cellar = cellar;
      this.run = run;
      world.context().registerService(OpaqueCellar.class, new EphemeralCellar(), new Hashtable<>());
      world
          .context()
          .registerService(Parcel.class, new Parcel("rke2lab", "plan"), new Hashtable<>());
      // Container-blind: OPERATOR here (the operator's .secrets, ndh OAuth client ahead of it).
      world
          .context()
          .registerService(
              SecretsGateway.class,
              new ExecutionEnvironment(System.getenv()).secretsGateway(),
              new Hashtable<>());
      return self();
    }
  }

  /**
   * The WHEN sows and grafts ghapp → netplan → dataplan → fabric, ONE STEP PER CROSSING. A scion
   * grafts under the host step that is executing, found by its name, so each crossing has its own
   * step and passes that step's own name — four crossings under one step would graft under names no
   * step bears, and fail at the first.
   */
  public static class When extends Stage<When> {

    @ScenarioStage SowAndGraftStage sowAndGraft;

    @ScenarioState Gardening gardening;
    @ScenarioState PublishPlanRun run;

    public When the_github_app_is_rehydrated(
        @Hidden ScenarioModel hostScenario, @Hidden ReportModel hostTree) {
      sowAndGraft
          .sowing("ghapp", gardening, hostScenario, hostTree)
          .the_scion_is_sown_and_grafted("the github app is rehydrated");
      return self();
    }

    public When the_network_plan_is_derived(
        @Hidden ScenarioModel hostScenario, @Hidden ReportModel hostTree) {
      sowAndGraft
          .sowing("netplan", gardening, hostScenario, hostTree, exportSoil())
          .the_scion_is_sown_and_grafted("the network plan is derived");
      return self();
    }

    public When the_dataset_plan_is_derived(
        @Hidden ScenarioModel hostScenario, @Hidden ReportModel hostTree) {
      sowAndGraft
          .sowing("dataplan", gardening, hostScenario, hostTree, exportSoil())
          .the_scion_is_sown_and_grafted("the dataset plan is derived");
      return self();
    }

    public When the_plan_is_delivered(
        @Hidden ScenarioModel hostScenario, @Hidden ReportModel hostTree) {
      sowAndGraft
          .sowing(
              "fabric",
              gardening,
              hostScenario,
              hostTree,
              Map.of(Amendment.SOIL, TextNode.valueOf(run.worktreesRoot())))
          .the_scion_is_sown_and_grafted("the plan is delivered");
      return self();
    }

    private Map<String, JsonNode> exportSoil() {
      return Map.of(Amendment.SOIL, TextNode.valueOf(run.exportSoil()));
    }
  }

  /**
   * No THEN step: each crossing's verdict is enforced by its graft, so an assertion here could only
   * repeat it. The stage exists because the scenario base names three.
   */
  public static class Then extends Stage<Then> {}
}
