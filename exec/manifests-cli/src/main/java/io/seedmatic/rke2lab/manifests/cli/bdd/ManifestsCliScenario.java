package io.seedmatic.rke2lab.manifests.cli.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The manifests-cli root scenario — the host runbook for the {@code synthesize} verb, spoken in the
 * same gardening register as {@code ClusterSeedScenario} but manifests-only and Pulumi-free. It is
 * a single sow: open the gardening, sow the {@code manifests} coordinate through the broker, reap
 * the runbook.
 *
 * <p>Why a scenario and not a bare {@code broker.sow} from {@code Main}: the sow rides the run's
 * transactional {@link ScenarioCellar}, which only {@code ScenarioCellarExtension} can inject
 * (there is no host-usable {@link Cellar} outside a scenario — the other impls are testkit). And
 * crucially it is why the CLI can reach the synthesis at all: {@code ManifestSynthesisService} is
 * registered with the manifests-core bundle's copy of the (non-seam) {@code manifests-contract}
 * class, so a host-side {@code awaitService(ManifestSynthesisService.class)} on the flat copy never
 * matches — the OLD boot+awaitService CLI was structurally broken. Sowing through the broker (the
 * ONE system-exported {@code seed.broker.port} membrane) grows {@code ManifestSynthesisScenario}
 * in-container, where its {@code @OsgiService} resolves the service bundle-side.
 */
@SeedScenario
@SeedRuntime
public class ManifestsCliScenario
    extends ScenarioTestBase<
        ManifestsCliScenario.Given, ManifestsCliScenario.When, ManifestsCliScenario.Then>
    implements SeedReceiver<ManifestsCliRun>, ConnectionReceiver, CellarReceiver<ScenarioCellar> {

  /** The inbound channel {@code Main} seeds the {@link ManifestsCliRun} through; single-sourced. */
  @RegisterExtension
  public static final SessionSeed<ManifestsCliRun> SEED =
      new SessionSeed<>(ManifestsCliRun.class, "manifests-cli-run");

  private final Scenario<Given, When, Then> scenario = createScenario();

  @MonotonicNonNull private ManifestsCliRun run;
  @MonotonicNonNull private OsgiConnection connection;
  @MonotonicNonNull private ScenarioCellar cellar;

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveSeed(ManifestsCliRun run) {
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
  void the_manifests_are_synthesized() {
    final ManifestsCliRun seedRun =
        Objects.requireNonNull(run, "the ManifestsCliRun was not seeded before the scenario ran");
    final OsgiConnection world =
        Objects.requireNonNull(
            connection, "the OsgiConnection was not received before the scenario ran");
    final ScenarioCellar tx =
        Objects.requireNonNull(
            cellar, "the ScenarioCellar was not injected before the scenario ran");
    given().i_have_access_to_the_open_gardening(seedRun, world, tx);
    when().the_manifests_are_sown(getScenario().getScenarioModel(), getScenario().getModel());
  }

  /**
   * The GIVEN opens the gardening over the world extension's connection and holds the run's soil.
   */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState Gardening gardening;
    @ProvidedScenarioState Cellar cellar;
    @ProvidedScenarioState Optional<String> materializationRoot;
    @ProvidedScenarioState Optional<ManifestsCliRun.Identity> identity;
    @ProvidedScenarioState JsonNode facet;

    public Given i_have_access_to_the_open_gardening(
        @Hidden ManifestsCliRun run, @Hidden OsgiConnection world, @Hidden ScenarioCellar cellar) {
      // Open OVER the connection the world extension owns (class scope) — no second Felix booted;
      // the
      // extension closes it at afterAll.
      this.gardening = Gardening.over(world);
      this.cellar = cellar;
      this.materializationRoot = run.materializationRoot();
      this.identity = run.identity();
      this.facet = run.facet();
      // Publish the run's durable backend for the ROOT drain ScenarioCellarExtension performs at
      // the
      // end. manifests-cli is a standalone synthesis with no persistent commissioner (no Pulumi),
      // so
      // the backend is the offline EphemeralCellar — the transactional cellar served reads during
      // the
      // run, and its end-of-run drain lands here and is discarded (the scion persists nothing to a
      // cellar; its harvest is the materialised tree).
      world.context().registerService(OpaqueCellar.class, new EphemeralCellar(), new Hashtable<>());
      return self();
    }
  }

  /**
   * The WHEN sows the manifests coordinate and GRAFTS the reaped scion into this run's trunk,
   * through the {@link SowAndGraftStage} every host crossing uses. Nothing here reads the scion's
   * writes back, so the graft is not needed for a value — it is needed for the PATTERN: every
   * crossing grafts, so no crossing can be the one whose writes silently never reach the host. It
   * also carries the scion's steps into the runbook this CLI reaps, and propagates its verdict: a
   * failed synthesis fails the run.
   */
  public static class When extends Stage<When> {

    @ScenarioStage SowAndGraftStage sowAndGraft;

    @ScenarioState Gardening gardening;
    @ScenarioState Optional<String> materializationRoot;
    @ScenarioState Optional<ManifestsCliRun.Identity> identity;
    @ScenarioState JsonNode facet;

    @As("the manifests are sown")
    public When the_manifests_are_sown(
        @Hidden ScenarioModel hostScenario, @Hidden ReportModel hostTree) {
      // The CLI sows a COMPLETE manifests input, honouring the contract the amend door enforces:
      // the
      // mandatory FACET (the CLI's own posture, always), plus the Optional SOIL (the plot; absent →
      // a temp survey) and IDENTITY (the cluster/node; absent → the blank `unknown` cluster). No
      // door default covers a missing FACET — the sower fills it.
      final Map<String, JsonNode> amendments = new LinkedHashMap<>();
      amendments.put(Amendment.FACET, facet);
      materializationRoot.ifPresent(root -> amendments.put(Amendment.SOIL, TextNode.valueOf(root)));
      identity.ifPresent(id -> amendments.put(Amendment.IDENTITY, identityNode(id)));
      sowAndGraft
          .sowing("manifests", gardening, hostScenario, hostTree, amendments)
          .the_scion_is_sown_and_grafted();
      return self();
    }

    private static JsonNode identityNode(ManifestsCliRun.Identity identity) {
      final ObjectNode node = JsonNodeFactory.instance.objectNode();
      node.put("clusterName", identity.clusterName());
      node.put("nodeName", identity.nodeName());
      return node;
    }
  }

  /**
   * No THEN step: the graft enforces the scion's verdict, so an assertion here could only repeat
   * it. The stage exists because the scenario base names three.
   */
  public static class Then extends Stage<Then> {}
}
