package io.seedmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState.Resolution;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.json.ScenarioJsonWriter;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.ScenarioModel;
import com.tngtech.jgiven.report.model.StepModel;
import io.seedmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.seedmatic.rke2lab.controlplane.config.ConfigLoader;
import io.seedmatic.rke2lab.controlplane.config.Rke2labConfig;
import io.seedmatic.rke2lab.host.runtime.GrowProfile;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.seedmatic.rke2lab.pulumi.edge.PulumiCellar;
import io.seedmatic.rke2lab.seed.bdd.sow.Gardening;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.internal.CodecCellar;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.OpaqueCellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.SeedBroker;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A throwaway run's whole runbook, step by step: the seed's own list of WHENs played under the
 * throwaway profile, every step named. The skips are a deny-list, so a crossing added to the seed
 * tomorrow would play for a throwaway cluster too; here it changes this list and turns this test
 * red, and whoever adds it must say whether a throwaway cluster takes it.
 */
class ThrowawayRunbookTest {

  private static final Parcel PARCEL = new Parcel("rke2lab", "nikopol");
  private static final String NO_FLEET_IDENTITY =
      "skipped by the throwaway profile: a throwaway cluster takes no fleet identity";
  private static final String READY_CHECKED_BY_UP =
      "skipped by the throwaway profile: a throwaway cluster's readiness is checked by"
          + " rke2lab-throwaway up";

  /** What a throwaway run reads, in its order: the seed's crossings and the closing THEN. */
  private static final List<String> THROWAWAY_RUNBOOK =
      List.of(
          "the worktree is surveyed",
          "the netplan is projected",
          "the parcel's state is fetched",
          NO_FLEET_IDENTITY,
          NO_FLEET_IDENTITY,
          NO_FLEET_IDENTITY,
          NO_FLEET_IDENTITY,
          NO_FLEET_IDENTITY,
          "the instance is provisioned",
          "the instance grows",
          "the operator kubeconfig is published",
          READY_CHECKED_BY_UP,
          READY_CHECKED_BY_UP,
          READY_CHECKED_BY_UP,
          "the harvest is stored",
          "the run fails if any crossing failed");

  /** Every sow reaps a passing one-step runbook, so the kept crossings graft as in a run. */
  private static final class PassingBroker implements SeedBroker {
    final List<String> soils = new ArrayList<>();
    private final SeedCodec codec = new SeedCodec();

    @Override
    public SeedEnvelope sow(SeedCoordinate wanted, Cellar cellar, SeedEnvelope seed) {
      soils.add(wanted.domain() + ":" + wanted.slug());
      return SeedEnvelope.of(wanted, codec.encode(Map.of("runbook", passingRunbook())));
    }

    @Override
    public boolean serves(SeedCoordinate wanted) {
      return false;
    }
  }

  public static class ScionStage extends Stage<ScionStage> {
    @As("the scion plays")
    public ScionStage the_scion_plays() {
      return self();
    }
  }

  private static String passingRunbook() {
    final ReportModel model = new ReportModel();
    model.setClassName(ScionStage.class.getSimpleName());
    final Scenario<ScionStage, ScionStage, ScionStage> scenario = Scenario.create(ScionStage.class);
    scenario.setModel(model);
    scenario.startScenario("the scion plays");
    scenario.getGivenStage().the_scion_plays();
    try {
      scenario.finished();
    } catch (Throwable unexpected) {
      throw new AssertionError(unexpected);
    }
    return new ScenarioJsonWriter(model).toString();
  }

  /** An empty durable backend: nothing was filed before this run. */
  private static final class EmptyCellar implements OpaqueCellar {
    @Override
    public void store(Parcel parcel, SeedEnvelope envelope) {}

    @Override
    public List<SeedEnvelope> fetch(Parcel parcel) {
      return List.of();
    }

    @Override
    public Optional<SeedEnvelope> fetch(Parcel parcel, SeedCoordinate coordinate) {
      return Optional.empty();
    }

    @Override
    public Optional<SeedEnvelope> withdraw(Parcel parcel, SeedCoordinate coordinate) {
      return Optional.empty();
    }

    @Override
    public List<Parcel> neighbours(Parcel parcel) {
      return List.of();
    }
  }

  /** What the seed's GIVEN provides, for a throwaway stack on nikopol. */
  public static class ThrowawayRun {
    @ProvidedScenarioState Gardening gardening;
    @ProvidedScenarioState PulumiCellar cellarRealisation;
    @ProvidedScenarioState Parcel parcel = PARCEL;
    @ProvidedScenarioState Cellar cellar;
    @ProvidedScenarioState BootstrapConfig config;
    @ProvidedScenarioState GrowProfile profile = GrowProfile.THROWAWAY;

    @ProvidedScenarioState(resolution = Resolution.NAME)
    JsonNode imageScalars = JsonNodeFactory.instance.objectNode();

    @ProvidedScenarioState(resolution = Resolution.NAME)
    JsonNode workloadClusterNames = JsonNodeFactory.instance.arrayNode();

    @ProvidedScenarioState(resolution = Resolution.NAME)
    JsonNode incusIdentityHostCreds = JsonNodeFactory.instance.objectNode();
  }

  @Test
  void a_throwaway_run_reads_exactly_its_expected_steps() throws Throwable {
    final PassingBroker broker = new PassingBroker();
    final ThrowawayRun run = new ThrowawayRun();
    run.gardening = new Gardening((OsgiConnection) null, broker);
    run.cellarRealisation = new PulumiCellar(Optional.empty(), () -> false, line -> {});
    run.cellar = new CodecCellar(new EmptyCellar(), null);
    run.config =
        BootstrapConfig.from(
            Rke2labConfig.from(
                ConfigLoader.ofNestedRoot(
                    Map.of(
                        "cluster", Map.of("host", "nikopol", "role", "mgmt"),
                        "incus",
                            Map.of("project", "throwaway-nikopol", "configDir", "/tmp/incus")))));

    final ReportModel model = new ReportModel();
    model.setClassName(ClusterSeedScenario.class.getName());
    final Scenario<ClusterSeedScenario.Given, ClusterSeedScenario.When, ClusterSeedScenario.Then>
        scenario =
            Scenario.create(
                ClusterSeedScenario.Given.class,
                ClusterSeedScenario.When.class,
                ClusterSeedScenario.Then.class);
    scenario.setModel(model);
    scenario.getExecutor().readScenarioState(run);
    scenario.startScenario("a throwaway cluster grows");
    final ScenarioModel played = scenario.getScenarioModel();
    ClusterSeedScenario.grows(scenario.getWhenStage(), played, model);
    scenario
        .getThenStage()
        .the_harvest_is_stored()
        .the_run_fails_if_any_crossing_failed(played, model);
    scenario.finished();

    final List<String> steps =
        played.getScenarioCases().get(0).getSteps().stream().map(StepModel::getName).toList();
    assertEquals(THROWAWAY_RUNBOOK, steps);
    assertEquals(
        List.of(
            "worktree:runbook",
            "netplan:runbook",
            "incus-provision:amend",
            "incus-provision:runbook"),
        broker.soils,
        "a throwaway run sows only the worktree, netplan and incus crossings");
  }
}
