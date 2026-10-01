package io.seedmatic.rke2lab.manifests.cli.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState.Resolution;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.seedmatic.rke2lab.host.runtime.ExecutionEnvironment;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.ConnectionReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.SeedRuntime;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioGraft;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.seed.bdd.EphemeralCellar;
import io.seedmatic.rke2lab.seed.bdd.SeedReceiver;
import io.seedmatic.rke2lab.seed.bdd.SessionSeed;
import io.seedmatic.rke2lab.seed.bdd.sow.Gardening;
import io.seedmatic.rke2lab.seed.broker.port.Amendment;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.EnclosureGate;
import io.seedmatic.rke2lab.seed.broker.port.OpaqueCellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.SecretsGateway;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The manifests-cli {@code publish} root scenario — {@code synthesize} PLUS delivery: it renders
 * the manifests into the SOIL and commits + pushes the rendered {@code manifests/<cluster>} branch,
 * reusing the SAME in-container delivery {@code ManifestSynthesisScenario} the grow drives. It is
 * NOT a new operation — the render+push lives in OSGi; this host just sows the same broker
 * coordinates the grow does, minus the Pulumi envelope (see the seed-outcluster {@code
 * ClusterSeedScenario} auth sub-graph).
 *
 * <p>Two sows in order, sharing the run's transactional {@link ScenarioCellar} and {@link Parcel}
 * (so the sealed anchors flow between them):
 *
 * <ol>
 *   <li>{@code ghapp} — rehydrates the one org-owned GitHub App's credentials from {@code .secrets}
 *       through the {@link SecretsGateway} this scenario registers (resolved container-blind via
 *       {@link ExecutionEnvironment}: OPERATOR here).
 *   <li>{@code manifests} — renders into the SOIL and, with the FACET's {@code delivery.push}
 *       armed, mints a FRESH WRITER token on demand from those credentials (no seal) and commits
 *       (signed) + pushes {@code manifests/<cluster>}.
 * </ol>
 *
 * <p>The push authenticates as the GitHub App (the identity baked for this automation), exactly as
 * the grow's first render does — publish is the steady-state twin of that bootstrap render.
 */
@SeedScenario
@SeedRuntime
public class PublishCliScenario
    extends ScenarioTestBase<
        PublishCliScenario.Given, PublishCliScenario.When, PublishCliScenario.Then>
    implements SeedReceiver<ManifestsCliRun>, ConnectionReceiver, CellarReceiver<ScenarioCellar> {

  /** The inbound channel {@code Main} seeds the run through; single-sourced (its own key). */
  @RegisterExtension
  public static final SessionSeed<ManifestsCliRun> SEED =
      new SessionSeed<>(ManifestsCliRun.class, "manifests-publish-run");

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
  void the_manifests_are_published() {
    final ManifestsCliRun seedRun =
        Objects.requireNonNull(run, "the ManifestsCliRun was not seeded before the scenario ran");
    final OsgiConnection world =
        Objects.requireNonNull(
            connection, "the OsgiConnection was not received before the scenario ran");
    final ScenarioCellar tx =
        Objects.requireNonNull(
            cellar, "the ScenarioCellar was not injected before the scenario ran");
    given().i_have_access_to_the_open_gardening(seedRun, world, tx);
    when().the_app_credentials_are_sealed_and_the_manifests_are_delivered();
    then().the_branch_is_delivered();
  }

  /**
   * The GIVEN opens the gardening over the world extension's connection, publishes the run's Parcel
   * + the enclosure-resolved SecretsGateway (the ghapp scion's {@code .secrets} door), and the
   * EphemeralCellar backend for the ROOT drain.
   */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState Gardening gardening;
    @ProvidedScenarioState Cellar cellar;
    @ProvidedScenarioState String materializationRoot;
    @ProvidedScenarioState ManifestsCliRun.Identity identity;

    // Two JsonNode fields — jGiven's type-based injection is ambiguous between them, so both
    // resolve
    // by NAME (the same discipline the cluster-pki seal stages use for their twin PEM fields).
    @ProvidedScenarioState(resolution = Resolution.NAME)
    JsonNode facet;

    @ProvidedScenarioState(resolution = Resolution.NAME)
    JsonNode renderMode;

    public Given i_have_access_to_the_open_gardening(
        @Hidden ManifestsCliRun run, @Hidden OsgiConnection world, @Hidden ScenarioCellar cellar) {
      this.gardening = Gardening.over(world);
      this.cellar = cellar;
      // publish REQUIRES a plot + identity: without them the delivery worktree never prepares and
      // the push is a silent no-op. Main always provides both (the render worktree it LOCATES from
      // the cluster, the cluster/node from args or their host defaults) — so these guard an
      // internal
      // misuse, never a user input.
      this.materializationRoot =
          run.materializationRoot()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "publish needs a render worktree (SOIL) — none was seeded"));
      this.identity =
          run.identity()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "publish needs a cluster/node identity — none was seeded"));
      this.facet = run.facet();
      this.renderMode = run.renderMode();
      // The Parcel keys the run's cellar — the same plot the three scions store/fetch their sealed
      // anchors under (App credentials, WRITER token). Ephemeral + single-run, so a synthetic
      // coordinate from the cluster identity suffices; it is an addressing key, not a Pulumi stack.
      world
          .context()
          .registerService(
              Parcel.class, new Parcel("rke2lab", identity.clusterName()), new Hashtable<>());
      // The secrets door the ghapp scion rehydrates the App credentials through — resolved
      // container-blind: OPERATOR (this CLI runs on the operator's host) → ndh OAuth client ahead
      // of
      // the operator's .secrets.
      final ExecutionEnvironment executionEnvironment = new ExecutionEnvironment(System.getenv());
      world
          .context()
          .registerService(
              SecretsGateway.class, executionEnvironment.secretsGateway(), new Hashtable<>());
      // The ambient enclosure gate the render's scion resolves — IN_CLUSTER under Tekton (the
      // KUBERNETES_SERVICE_HOST signal), so deliveryPlan skips the sops-encrypted key-store and
      // reveals the signing key from the mounted Secret's env. Published like the SecretsGateway.
      world
          .context()
          .registerService(
              EnclosureGate.class, executionEnvironment.enclosureGate(), new Hashtable<>());
      // The offline durable backend for the ROOT drain (no persistent commissioner — no Pulumi);
      // the transactional cellar serves reads during the run, its end drain lands here + is
      // dropped.
      world.context().registerService(OpaqueCellar.class, new EphemeralCellar(), new Hashtable<>());
      return self();
    }
  }

  /** The WHEN sows ghapp → auth → manifests in order, sharing the run's cellar. */
  public static class When extends Stage<When> {

    @ScenarioState Gardening gardening;
    @ScenarioState Cellar cellar;
    @ScenarioState String materializationRoot;
    @ScenarioState ManifestsCliRun.Identity identity;

    @ScenarioState(resolution = Resolution.NAME)
    JsonNode facet;

    @ScenarioState(resolution = Resolution.NAME)
    JsonNode renderMode;

    @ProvidedScenarioState String ghappRunbook;
    @ProvidedScenarioState String manifestsRunbook;

    @As("the app credentials are sealed and the manifests are delivered")
    public When the_app_credentials_are_sealed_and_the_manifests_are_delivered() {
      // (1) rehydrate the App credentials from .secrets — they ride the shared cellar so the
      // manifests delivery below reveals them and mints a FRESH WRITER token on demand (no seal, no
      // staleable durable token). No amendment (the scion falls back to its own door defaults).
      this.ghappRunbook = gardening.sow("ghapp", Map.of(), cellar);
      // (2) render into the SOIL + deliver: a COMPLETE manifests input — mandatory FACET (with
      // delivery.push armed), SOIL, IDENTITY, and the RENDER_MODE that carries the verb intent
      // (init/update/edit) the synthesis resolves the facet against HEAD with.
      final Map<String, JsonNode> amendments = new LinkedHashMap<>();
      amendments.put(Amendment.FACET, facet);
      amendments.put(Amendment.SOIL, TextNode.valueOf(materializationRoot));
      amendments.put(Amendment.IDENTITY, identityNode(identity));
      amendments.put(Amendment.RENDER_MODE, renderMode);
      this.manifestsRunbook = gardening.sow("manifests", amendments, cellar);
      return self();
    }

    private JsonNode identityNode(ManifestsCliRun.Identity identity) {
      final ObjectNode node = JsonNodeFactory.instance.objectNode();
      node.put("clusterName", identity.clusterName());
      node.put("nodeName", identity.nodeName());
      return node;
    }
  }

  /** The THEN asserts EACH scion passed — a broken ghapp silently skips the push otherwise. */
  public static class Then extends Stage<Then> {

    @ScenarioState String ghappRunbook;
    @ScenarioState String manifestsRunbook;

    @As("the rendered branch is delivered")
    public Then the_branch_is_delivered() {
      final ScenarioGraft graft = new ScenarioGraft();
      graft.assertPassed(ghappRunbook, "the github app rehydrate");
      graft.assertPassed(manifestsRunbook, "the manifests render + delivery");
      return self();
    }
  }
}
