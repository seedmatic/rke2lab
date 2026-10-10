package io.seedmatic.rke2lab.manifests.cli.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.NestedSteps;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioStage;
import com.tngtech.jgiven.annotation.ScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.ScenarioModel;
import io.seedmatic.rke2lab.manifests.ingress.BumpLevel;
import io.seedmatic.rke2lab.manifests.ingress.Component;
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
import java.util.Hashtable;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The manifests-cli {@code versions} root scenario — the host runbook for the version bump, spoken
 * in the same gardening register as {@code ManifestsCliScenario} and {@code ClusterSeedScenario}: a
 * single crossing that sows the {@code manifests-versions} coordinate through the broker and grafts
 * the reaped scion under this step, so the bump's per-component report — and, on apply, the bumps,
 * the staging and the bot commit — ride the host runbook the CLI renders.
 *
 * <p>Why a scion and not a bare host bumper: the bump reads {@code Worktree}/{@code
 * AuthTokenContact}/{@code NdhKeystoreReader}, all registered with their (non-seam) {@code
 * type=contract} bundle's class copy — a host-side {@code awaitService} on the flat copy never
 * matches (the structural breakage the synthesis CLI already dodges). Sowing through the broker
 * (the ONE {@code seed.broker.port} membrane) grows {@link
 * io.seedmatic.rke2lab.manifests.bdd.versions.VersionBumpScenario} in-container, where its
 * {@code @OsgiService} collaborators resolve bundle-side.
 */
@SeedScenario
@SeedRuntime
public class VersionsCliScenario
    extends ScenarioTestBase<
        VersionsCliScenario.Given, VersionsCliScenario.When, VersionsCliScenario.Then>
    implements SeedReceiver<VersionsCliRun>, ConnectionReceiver, CellarReceiver<ScenarioCellar> {

  /** The inbound channel {@code Main} seeds the {@link VersionsCliRun} through; single-sourced. */
  @RegisterExtension
  public static final SessionSeed<VersionsCliRun> SEED =
      new SessionSeed<>(VersionsCliRun.class, "manifests-versions-run");

  private final Scenario<Given, When, Then> scenario = createScenario();

  @MonotonicNonNull private VersionsCliRun run;
  @MonotonicNonNull private OsgiConnection connection;
  @MonotonicNonNull private ScenarioCellar cellar;

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveSeed(VersionsCliRun run) {
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
  void the_component_versions_are_bumped() {
    final VersionsCliRun seedRun =
        Objects.requireNonNull(run, "the VersionsCliRun was not seeded before the scenario ran");
    final OsgiConnection world =
        Objects.requireNonNull(
            connection, "the OsgiConnection was not received before the scenario ran");
    final ScenarioCellar tx =
        Objects.requireNonNull(
            cellar, "the ScenarioCellar was not injected before the scenario ran");
    final ScenarioModel hostScenario = getScenario().getScenarioModel();
    final ReportModel hostTree = getScenario().getModel();
    given().i_have_access_to_the_open_gardening(seedRun, world, tx);
    when().the_component_versions_are_bumped(hostScenario, hostTree);
    then().the_runbook_is_reaped();
  }

  /** Given: open the gardening over the world extension's connection; hold the bump policy. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState Gardening gardening;
    @ProvidedScenarioState Cellar cellar;
    @ProvidedScenarioState BumpLevel level;
    @ProvidedScenarioState boolean apply;
    @ProvidedScenarioState Optional<Component> component;

    public Given i_have_access_to_the_open_gardening(
        @Hidden VersionsCliRun run, @Hidden OsgiConnection world, @Hidden ScenarioCellar cellar) {
      this.gardening = Gardening.over(world);
      this.cellar = cellar;
      this.level = run.level();
      this.apply = run.apply();
      this.component = run.component();
      // A standalone bump has no persistent commissioner — the offline EphemeralCellar backs the
      // ROOT drain, exactly as the manifests synthesis CLI does.
      world.context().registerService(OpaqueCellar.class, new EphemeralCellar(), new Hashtable<>());
      return self();
    }
  }

  /** When: sow the bump coordinate with the policy FACET and graft the reaped scion. */
  public static class When extends Stage<When> {

    @ScenarioState Gardening gardening;
    @ScenarioState Cellar cellar;
    @ScenarioState BumpLevel level;
    @ScenarioState boolean apply;
    @ScenarioState Optional<Component> component;

    @ScenarioStage SowAndGraftStage sowAndGraft;

    @NestedSteps
    @As("the component versions are bumped")
    public When the_component_versions_are_bumped(
        @Hidden ScenarioModel hostScenario, @Hidden ReportModel hostTree) {
      // Fill the bump policy onto the neutral FACET role — the host names no manifests field; the
      // VersionsAmendReflector binds this subtree onto ManifestVersionsBumpInput.BumpFacet at the
      // amend door. component is included only when present (absent => every component).
      final ObjectNode facet = JsonNodeFactory.instance.objectNode();
      facet.put("level", level.slug());
      facet.put("apply", apply);
      component.ifPresent(id -> facet.put("component", id.slug()));
      final Map<String, JsonNode> amendments = Map.of(Amendment.FACET, facet);
      sowAndGraft
          .sowing("manifests-versions", gardening, hostScenario, hostTree, amendments)
          .the_scion_is_sown_and_grafted();
      return self();
    }
  }

  /** Then: the scion grew — the graft already propagated a FAILED bump as a throw. */
  public static class Then extends Stage<Then> {

    @As("the runbook is reaped")
    public Then the_runbook_is_reaped() {
      return self();
    }
  }
}
