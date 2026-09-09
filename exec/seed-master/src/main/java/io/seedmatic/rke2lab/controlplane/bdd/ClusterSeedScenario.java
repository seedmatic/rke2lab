package io.seedmatic.rke2lab.controlplane.bdd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.NestedSteps;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioStage;
import com.tngtech.jgiven.annotation.ScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState.Resolution;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.ScenarioModel;
import io.seedmatic.rke2lab.clusterpki.contract.AdminCredentials;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterAgeKey;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterCaBundle;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterPkiCoordinate;
import io.seedmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.seedmatic.rke2lab.controlplane.incus.InstanceGrow;
import io.seedmatic.rke2lab.controlplane.incus.NodeBootstrapMaterial;
import io.seedmatic.rke2lab.host.runtime.ExecutionEnvironment;
import io.seedmatic.rke2lab.incus.ingress.GrowOutcome;
import io.seedmatic.rke2lab.incus.ingress.Growth;
import io.seedmatic.rke2lab.incus.ingress.IncusGrowCoordinate;
import io.seedmatic.rke2lab.incus.ingress.IngressConfig;
import io.seedmatic.rke2lab.incus.ingress.InstanceGrowPlan;
import io.seedmatic.rke2lab.manifests.ingress.PacWebhookFunnel;
import io.seedmatic.rke2lab.manifests.ingress.ServerManifestsBundle;
import io.seedmatic.rke2lab.manifests.ingress.ServerManifestsCoordinate;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.ConnectionReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.SeedRuntime;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioGraft;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.pulumi.edge.PulumiCellar;
import io.seedmatic.rke2lab.pulumi.edge.PulumiDeploymentSeed;
import io.seedmatic.rke2lab.seed.bdd.CellarStage;
import io.seedmatic.rke2lab.seed.bdd.SeedReceiver;
import io.seedmatic.rke2lab.seed.bdd.SessionSeed;
import io.seedmatic.rke2lab.seed.bdd.SowAndGraftStage;
import io.seedmatic.rke2lab.seed.bdd.sow.Gardening;
import io.seedmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.Amendment;
import io.seedmatic.rke2lab.seed.broker.port.AmendmentContributor;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.EnclosureGate;
import io.seedmatic.rke2lab.seed.broker.port.OpaqueCellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.ReadinessDeadlineOverride;
import io.seedmatic.rke2lab.seed.broker.port.ReadinessOverrides;
import io.seedmatic.rke2lab.seed.broker.port.RunGate;
import io.seedmatic.rke2lab.seed.broker.port.SecretsGateway;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Hashtable;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The ClusterSeed root scenario — the host runbook, spoken in the gardening register, composed on
 * the common {@code seed-bdd} stages (link:docs/architecture/osgi/seed-bdd-module-spec.adoc). It is
 * the concrete instance of link:docs/architecture/bdd/bdd.adoc#clusterseed-scenario-map[the
 * ClusterSeed scenario map]: a GIVEN that bootstraps the open gardening, then the thirteen WHENs —
 * the {@code Cellar.fetch} bookend, the nine sow-and-graft crossings (worktree · bbox · cluster-pki
 * · ghapp · replicator-secrets · ghapp-webhook · incus-provision · systemd · cluster) and the
 * host-flat beats interleaved among them (the GROW, the operator kubeconfig, the readiness-budget
 * tuning) — closed by the {@code Cellar.store} THEN.
 *
 * <p>The amorce is two-layered (§ the amorce): {@code Main} — inside {@code Pulumi.run} — captures
 * the {@link RunMode} (the one fact only it can know) and seeds it through the launcher session
 * store; this scenario {@link SeedReceiver receives} it before the GIVEN, which bootstraps
 * everything else from it. Opening the gardening is the GIVEN's work, never a WHEN.
 *
 * <p>Each sow-and-graft WHEN is a {@code @NestedSteps} step named for its crossing; its body sows
 * the soil's runbook through the gardening and grafts the reaped scion under THIS step. TWO live
 * host handles carry the graft, because jGiven appends the current scenario to its {@link
 * ReportModel} only when the scenario FINISHES — so mid-run, inside a WHEN, {@code
 * getModel().getScenarios()} is still empty. The scion STEPS graft into the live current {@code
 * getScenario().getScenarioModel()} (the trunk that already carries the rootstock step — jGiven
 * adds it when it invokes this {@code @NestedSteps} method), while the scion's within-run tags
 * merge into the {@link ReportModel} ({@code getScenario().getModel()}), whose tag map IS live and
 * which {@code Main} reads back via {@code ScenarioGraft.graftedValue}. Fishing the host scenario
 * out of {@code getModel().getScenarios()} mid-run was the "no scenario to graft" defect.
 */
@SeedScenario
@SeedRuntime
public class ClusterSeedScenario
    extends ScenarioTestBase<
        ClusterSeedScenario.Given, ClusterSeedScenario.When, ClusterSeedScenario.Then>
    implements SeedReceiver<SeedRun>, ConnectionReceiver, CellarReceiver<ScenarioCellar> {

  /**
   * The inbound channel the driver ({@code Main}) seeds the {@link SeedRun} through and this
   * scenario receives it from (§ the amorce). It is single-sourced here — the receiver owns the key
   * + type — and referenced by {@code Main} for the seeding end. Registered as a {@link
   * RegisterExtension} so its {@code TestInstancePostProcessor} fires before the test body reads
   * {@link #run}; a field-based registration is needed because the channel carries constructor
   * state (type + key) that {@code @ExtendWith} cannot supply.
   */
  @RegisterExtension
  public static final SessionSeed<SeedRun> SEED = new SessionSeed<>(SeedRun.class, "seed-run");

  /**
   * Installs the live Pulumi deployment on the launcher worker thread before the body, so the GROW
   * beat's {@code com.pulumi} resources resolve (the deployment is a plain ThreadLocal the worker
   * does not inherit). Registered like {@link #SEED} — its {@code beforeEach} fires before the
   * WHEN.
   */
  @RegisterExtension
  public static final PulumiDeploymentSeed DEPLOYMENT = new PulumiDeploymentSeed();

  private final Scenario<Given, When, Then> scenario = createScenario();

  /** Set once by {@link #receiveSeed} (the {@code SessionSeed} post-processor) before the GIVEN. */
  @MonotonicNonNull private SeedRun run;

  /** Set once by {@link #receiveConnection} ({@code BaseWorldExtension}) before the GIVEN. */
  @MonotonicNonNull private OsgiConnection connection;

  /**
   * The run's transactional cellar — injected by {@code ScenarioCellarExtension} before the body
   * (the root's own, carrying the run txId; every scion's crossing inherits its in-flight entries).
   * Threaded into each sow-and-graft crossing as the ambient transaction.
   */
  @MonotonicNonNull private ScenarioCellar cellar;

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveSeed(SeedRun run) {
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
  void the_cluster_seed_grows_to_a_ready_cluster() {
    final SeedRun seedRun =
        Objects.requireNonNull(
            run, "the SeedRun was not seeded before the scenario ran (the driver must seed it)");
    // Two live handles the crossings graft into: the current ScenarioModel (the trunk the scion
    // steps attach to — the ONLY handle carrying the rootstock step mid-run, since jGiven appends
    // the scenario to the ReportModel only at scenario end) and the ReportModel (its live tag map
    // receives the scions' within-run tags, read back by Main via ScenarioGraft.graftedValue).
    final ScenarioModel hostScenario = getScenario().getScenarioModel();
    final ReportModel hostTree = getScenario().getModel();
    final OsgiConnection world =
        Objects.requireNonNull(
            connection, "the OsgiConnection was not received before the scenario ran");
    final ScenarioCellar tx =
        Objects.requireNonNull(
            cellar, "the ScenarioCellar was not injected before the scenario ran");
    given().i_have_access_to_the_open_gardening(seedRun, world, tx);
    when()
        .the_worktree_is_surveyed(hostScenario, hostTree)
        .and()
        .the_parcels_state_is_fetched()
        .and()
        .the_network_reservations_are_settled(hostScenario, hostTree)
        .and()
        .the_cluster_ca_is_sealed(hostScenario, hostTree)
        .and()
        .the_github_app_is_registered(hostScenario, hostTree)
        .and()
        .the_replicator_secrets_are_sealed(hostScenario, hostTree)
        .and()
        .the_github_app_webhook_is_reconciled(hostScenario, hostTree)
        .and()
        .the_instance_is_provisioned(hostScenario, hostTree)
        .and()
        .the_instance_grows()
        .and()
        .the_operator_kubeconfig_is_published()
        .and()
        .the_readiness_budget_is_tuned_to_the_growth()
        .and()
        .the_systemd_adapter_is_launched(hostScenario, hostTree)
        .and()
        .the_cluster_becomes_ready(hostScenario, hostTree);
    then().the_harvest_is_stored().the_run_fails_if_any_crossing_failed(hostScenario, hostTree);
  }

  /**
   * The GIVEN bootstraps the open gardening from the received {@link RunMode}: open the {@link
   * Gardening} (boot Felix, find the gardener), publish the {@link RunGate} into the registry so
   * the scions resolve it, and build the host realisations the WHENs use — the {@link
   * PulumiCellar}, the {@link Parcel}, and the ambient FACET contributors (incus, manifests, and
   * the worktree entry-gate policy). All of it is narration; opening the gardening is a
   * precondition, not a step.
   */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState Gardening gardening;
    // The durable backend realisation (the Pulumi store the working cellar drains to at the
    // boundary). Named for its role, distinct from the working `cellar` below — the When already
    // consumes it as `cellarRealisation`.
    @ProvidedScenarioState PulumiCellar cellarRealisation;
    @ProvidedScenarioState Parcel parcel;

    // The IMAGE amendment subtree the incus crossing sows per-consult. Name-resolved (the When
    // picks
    // it back by field name) — the discipline the former paired worktree subtree also used.
    @ProvidedScenarioState(resolution = Resolution.NAME)
    JsonNode imageScalars;

    // The workload cluster names the cluster-pki seal must pre-seed a deterministic CA for — dug
    // from the manifests FACET (workloadTargets[].{host,role} -> <host>-<role>), offered to the
    // cluster-pki crossing under the neutral WORKLOAD_TARGETS role. Name-resolved like
    // imageScalars.
    @ProvidedScenarioState(resolution = Resolution.NAME)
    JsonNode workloadClusterNames = JsonNodeFactory.instance.arrayNode();

    // The run's provisioning config — the host GROW derives the instance mounts from it (via the
    // dual-realm BootstrapPaths) and builds the provider context from it.
    @ProvidedScenarioState BootstrapConfig config;
    // The run's working cellar — the root's own ScenarioCellar (the seam type Cellar here),
    // injected
    // onto the scenario instance and published for SowAndGraftStage; every sow carries it, so a
    // launched scion inherits its txId + in-flight entries (§ cellar-transactional). Resolved by
    // TYPE: the durable backend is a distinct type (PulumiCellar), no clash.
    @ProvidedScenarioState Cellar cellar;

    public Given i_have_access_to_the_open_gardening(
        @Hidden SeedRun run, @Hidden OsgiConnection world, @Hidden ScenarioCellar cellar) {
      // Open OVER the connection the world extension owns (class scope) — no second Felix booted;
      // the extension closes it at afterAll (the leak the hand-rolled open() left is gone).
      this.gardening = Gardening.over(world);
      this.cellar = cellar;
      // The IMAGE amendment — the seed-image build scalars the incus scion folds into the
      // buildChecksum and the artifact paths it projects for the GROW. A blind subtree: the host
      // names only the neutral IMAGE role's schema fields, no incus type. (The worktree ROOT is no
      // longer sown: the worktree soil harvests it into the cellar and the scion reads it from its
      // own Worktree component; the stable cluster/node identity is contributed as the incus FACET
      // below.)
      final ObjectNode imageScalars = JsonNodeFactory.instance.objectNode();
      imageScalars.put("alias", run.config().imageAlias());
      imageScalars.put("builderBinary", run.config().imageBuilderBinary());
      imageScalars.put("builderHost", run.config().imageBuilderHost());
      this.imageScalars = imageScalars;
      // The host GROW derives the instance mounts + provider context from the run's config.
      this.config = run.config();
      // Publish the ambient RunGate the scions resolve — projected from the run mode.
      // registerService,
      // not a handler: the run-condition is a service the whole run shares (§ RunGate).
      final RunGate runGate = run.runMode()::playsLive;
      gardening.connection().context().registerService(RunGate.class, runGate, new Hashtable<>());

      // The ambient ReadinessOverrides is NOT published here: it is tuned to the grow's cold/warm
      // condition, which is unknown until the grow runs. The host publishes it in the post-grow
      // WHEN
      // "the readiness budget is tuned to the growth", before the readiness scions are sown — the
      // scions read it lazily as they play, so the later registration is the one they see.

      // The two ambient facts a scion needs to STORE its own harvest (§ host-cellar-realisation,
      // every-scion-contributes): the Cellar (the neutral furniture the host lays into Felix) and
      // the
      // current Parcel (the one plot this run cultivates). Both published like the RunGate — a
      // scion
      // resolves them via ScenarioRegistry.require and stores itself; the host never round-trips a
      // harvest back to re-store it. The Cellar stays NEUTRAL (store(Parcel, …)); the current
      // parcel
      // lives BESIDE it as an ambient fact, never inside it (the doctor addresses N parcels).
      this.parcel = run.parcel();
      final Consumer<String> log = line -> {};
      this.cellarRealisation = PulumiCellar.fromEnvironment(runGate, log);
      gardening
          .connection()
          .context()
          .registerService(OpaqueCellar.class, cellarRealisation, new Hashtable<>());
      gardening.connection().context().registerService(Parcel.class, parcel, new Hashtable<>());
      // The secrets door — published into the framework it grew so the ghapp scion can rehydrate
      // its anchor and persist a freshly-registered one through the seam, no .secrets logic
      // crossing
      // a realm. WHICH source backs the door is the ExecutionEnvironment's call, projected from the
      // enclosure it detects: this grow runs OPERATOR, so the chain is ndh's OAuth client (serving
      // `tailscale`, rke2lab's single source of trust for it) ahead of the operator's .secrets
      // (everything else). An in-cluster process would resolve a different chain.
      final ExecutionEnvironment executionEnvironment = new ExecutionEnvironment(System.getenv());
      gardening
          .connection()
          .context()
          .registerService(
              SecretsGateway.class, executionEnvironment.secretsGateway(), new Hashtable<>());
      // The ambient enclosure gate the render's scion resolves to fork keystore-vs-mounted signing
      // (this grow runs OPERATOR — the ndh key-store is at hand). Published like the RunGate.
      gardening
          .connection()
          .context()
          .registerService(
              EnclosureGate.class, executionEnvironment.enclosureGate(), new Hashtable<>());
      // The manifests FACET amendment — the operator config subtree the root read from Pulumi,
      // published as an ambient AmendmentContributor (the same generic FacetContributor the incus
      // FACET uses below). The incus scion that consults the manifests amend holds only the
      // per-consult SOIL + WORKTREE; the assembler merges this FACET in at the door, so `mesh:
      // false` (and the rest of rke2lab:manifests:) reaches the synthesis without any sower
      // carrying
      // a role it does not own.
      final Optional<String> manifestsFacet = run.facet("manifests");
      gardening
          .connection()
          .context()
          .registerService(
              AmendmentContributor.class,
              new FacetContributor(new AmendCoordinate("manifests"), manifestsFacet.orElse("")),
              new Hashtable<>());
      // The workload cluster names the cluster-pki seal pre-seeds a CA for — dug from the SAME
      // manifests FACET (its workloadTargets), offered per-consult to the cluster-pki crossing.
      this.workloadClusterNames = workloadClusterNames(manifestsFacet);
      // The bbox FACET — the router contact (uri + password) the root read from .secrets:lan.bbox
      // (joined into rke2lab:bbox by ConfigLoader's `secret:` meta), published ambient like the
      // manifests FACET. Bbox carries no per-consult amendment, so its crossing sows an empty
      // trigger; the open gardening still opens the amend door (a reflector serves it), where
      // BboxAmendReflector gathers THIS contributor and binds it onto the runbook input. BETA
      // guards the seam: this coordinate must equal the reflector's served AmendCoordinate("bbox").
      gardening
          .connection()
          .context()
          .registerService(
              AmendmentContributor.class,
              new FacetContributor(new AmendCoordinate("bbox"), run.facet("bbox").orElse("")),
              new Hashtable<>());
      // The incus FACET — the stable provisioning identity (cluster/node, automount, netPrefix)
      // the scion combines with the worktree root it reads from its Worktree component. A FACET,
      // not
      // a per-consult ROW: the value never changes across the run, so it is contributed AMBIENT
      // (the
      // assembler merges it at the incus-provision amend door) rather than sown in the trigger.
      // Note
      // NO worktreeRoot — that is the worktree soil's harvest, no longer a host-carried scalar.
      final ObjectNode incusFacet = JsonNodeFactory.instance.objectNode();
      incusFacet.put("clusterName", run.config().clusterName());
      incusFacet.put("nodeName", run.config().nodeName());
      incusFacet.put("automount", run.config().automount());
      incusFacet.put("netPrefix", run.config().netPrefix());
      incusFacet.put("incusProject", run.config().incusProject());
      gardening
          .connection()
          .context()
          .registerService(
              AmendmentContributor.class,
              new FacetContributor(new AmendCoordinate("incus-provision"), incusFacet.toString()),
              new Hashtable<>());

      // The systemd FACET — the same stable cluster/node IDENTITY, contributed AMBIENT for the
      // systemd crossing. The systemd scenario derives the network blueprint from it OSGi-side to
      // compose its dbus-over-TCP probe endpoint (the node's mDNS FQDN paired with the systemd dbus
      // port), so the host names no systemd endpoint — only the neutral identity, JSON on the FACET
      // role.
      final ObjectNode systemdFacet = JsonNodeFactory.instance.objectNode();
      systemdFacet.put("clusterName", run.config().clusterName());
      systemdFacet.put("nodeName", run.config().nodeName());
      gardening
          .connection()
          .context()
          .registerService(
              AmendmentContributor.class,
              new FacetContributor(new AmendCoordinate("systemd"), systemdFacet.toString()),
              new Hashtable<>());
      // The cluster FACET — WHERE the operator kubeconfig is published (kubeconfigRef), contributed
      // AMBIENT for the cluster-readiness crossing. ClusterAmendReflector gathers it at the door
      // and
      // binds it onto ReadinessInput.access, so the fabric8 probe reads the real published path
      // (not
      // the dead /srv/host marker). The host names no cluster type — only the neutral path, JSON on
      // the FACET role.
      final ObjectNode clusterFacet = JsonNodeFactory.instance.objectNode();
      clusterFacet.put(
          "kubeconfigPath", run.config().kubeconfigRef().toAbsolutePath().normalize().toString());
      gardening
          .connection()
          .context()
          .registerService(
              AmendmentContributor.class,
              new FacetContributor(new AmendCoordinate("cluster"), clusterFacet.toString()),
              new Hashtable<>());
      // The entry-gate FACET — the run's GatePolicy (clean-worktree requirement + tolerated paths),
      // contributed AMBIENT the way the incus/manifests FACETs are. The worktree crossing (the
      // first
      // one) enforces it OSGi-side against the WorkingState it harvests, and fails the run there if
      // the ground is unclean beyond tolerance. The host names NO worktree type — only opaque JSON
      // on the FACET role; no host jgit, no host worktree-fact fetch, no host preflight.
      final ObjectNode gateFacet = JsonNodeFactory.instance.objectNode();
      gateFacet.put("cleanWorktreeRequired", run.cleanWorktreeRequired());
      gateFacet.put("flakeLockRequired", run.flakeLockRequired());
      final ArrayNode tolerated = gateFacet.putArray("toleratedPaths");
      run.toleratedWorktreePaths().forEach(tolerated::add);
      gardening
          .connection()
          .context()
          .registerService(
              AmendmentContributor.class,
              new FacetContributor(new AmendCoordinate("worktree"), gateFacet.toString()),
              new Hashtable<>());
      return self();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Dig the workload cluster names (<host>-<role>) out of the manifests FACET's workloadTargets.
    // Absent facet / no targets -> an empty array (a mgmt-only grow seeds no workload CA).
    private static JsonNode workloadClusterNames(Optional<String> manifestsFacetJson) {
      final ArrayNode names = JsonNodeFactory.instance.arrayNode();
      if (manifestsFacetJson.isEmpty()) {
        return names;
      }
      try {
        MAPPER
            .readTree(manifestsFacetJson.orElseThrow())
            .path("workloadTargets")
            .forEach(
                target -> {
                  final String host = target.path("host").asText("");
                  final String role = target.path("role").asText("");
                  if (!host.isBlank() && !role.isBlank()) {
                    names.add(host + "-" + role);
                  }
                });
      } catch (JsonProcessingException ex) {
        throw new IllegalStateException(
            "could not parse the manifests facet for workloadTargets", ex);
      }
      return names;
    }
  }

  /**
   * The thirteen WHENs — the worktree survey (harvest + entry gate), the {@code Cellar.fetch}
   * bookend, the nine sow-and-graft crossings (worktree · bbox · cluster-pki · ghapp ·
   * replicator-secrets · ghapp-webhook · incus-provision · systemd · cluster), and the host-flat
   * beats interleaved among them (the GROW, the operator kubeconfig, the readiness-budget tuning).
   * The closing {@code Cellar.store} is the THEN, not a WHEN.
   */
  public static class When extends Stage<When> {

    @ScenarioStage CellarStage cellar;
    @ScenarioStage SowAndGraftStage sowAndGraft;

    @ScenarioState Gardening gardening;
    @ScenarioState PulumiCellar cellarRealisation;
    // The run's transactional working cellar (the seam type Cellar — the ScenarioCellar the root
    // GIVEN published). The GROW reads the InstanceGrowPlan through THIS, not the durable
    // cellarRealisation: the incus scion's store is a within-run tag (grafted up into the host
    // trunk at the provision crossing), drained to the durable backend only at the run boundary —
    // so mid-run only the transactional overlay carries it (read-your-writes). Resolved by TYPE:
    // Cellar and PulumiCellar are disjoint interfaces, no clash with cellarRealisation.
    @ScenarioState Cellar workingCellar;
    @ScenarioState Parcel parcel;

    // Name-resolved as it was in the Given (the paired worktree subtree is gone; the discipline
    // stays so the field is picked back by name).
    @ScenarioState(resolution = ScenarioState.Resolution.NAME)
    JsonNode imageScalars;

    // The workload cluster names offered to the cluster-pki crossing under the WORKLOAD_TARGETS
    // role — picked back from the Given by name, like imageScalars.
    @ScenarioState(resolution = ScenarioState.Resolution.NAME)
    JsonNode workloadClusterNames;

    @ScenarioState BootstrapConfig config;

    // The WARM fail-fast budget — a live master should answer at once, so a re-run against one that
    // does not is a fault to surface NOW, not a boot to wait out. Short, global (both checkpoints),
    // fixed in code: it OVERRIDES the annotation's patient defaults (both halves present) when the
    // grow read WARM. COLD keeps the config-derived overrides (rke2lab:readiness:), the annotation
    // patient defaults standing under them.
    private static final ReadinessOverrides WARM_FAILFAST =
        new ReadinessOverrides(
            new ReadinessDeadlineOverride(
                Optional.of(Duration.ofSeconds(5)), Optional.of(Duration.ofSeconds(10))),
            Map.of());

    @NestedSteps
    @As("the worktree is surveyed")
    public When the_worktree_is_surveyed(
        @Hidden ScenarioModel hostScenario, @Hidden ReportModel hostTree) {
      // The FIRST crossing: the worktree soil harvests its git facts (root + provenance + working
      // state) into the cellar at WorktreeCoordinate.FACTS. The entry gate reads the working state
      // from it (next crossing), and the GROW reads the root from it — fetch-not-push, no host
      // jgit.
      sowAndGraft
          .sowing("worktree", gardening, hostScenario, hostTree)
          .the_scion_is_sown_and_grafted("the worktree is surveyed");
      return self();
    }

    @NestedSteps
    @As("the parcel's state is fetched")
    public When the_parcels_state_is_fetched() {
      cellar.conserving(cellarRealisation, parcel).the_parcels_state_is_fetched();
      return self();
    }

    @NestedSteps
    @As("the network reservations are settled")
    public When the_network_reservations_are_settled(
        @Hidden ScenarioModel hostScenario, @Hidden ReportModel hostTree) {
      // No per-consult amendment: the router FACET (uri + password) is contributed AMBIENT at the
      // GIVEN. The crossing sows an empty trigger; the open gardening opens the bbox amend door
      // anyway (a reflector serves it), where BboxAmendReflector gathers the ambient FACET and
      // binds
      // it onto the runbook input's defaults.
      sowAndGraft
          .sowing("bbox", gardening, hostScenario, hostTree)
          .the_scion_is_sown_and_grafted("the network reservations are settled");
      return self();
    }

    @NestedSteps
    @As("the cluster CA is sealed")
    public When the_cluster_ca_is_sealed(
        @Hidden ScenarioModel hostScenario, @Hidden ReportModel hostTree) {
      // The cluster-pki seal scion mints the deterministic mgmt CA ONCE per cluster (idempotent on
      // a cellar hit, so the CA is stable across re-grows) and files it for the GROW to pose over
      // cloud-init. Sown BEFORE provisioning so the PKI exists when the instance grows. ONE
      // amendment hands the seal the WORKLOAD_TARGETS role — the workload cluster names it also
      // pre-seeds a deterministic BYO-CA for (additively, sibling-rooted at mammoth-skate); empty
      // on
      // a mgmt-only grow. The rest the scion reads in-container (keys.yaml / .sops.yaml).
      sowAndGraft
          .sowing(
              "cluster-pki",
              gardening,
              hostScenario,
              hostTree,
              Map.of(Amendment.WORKLOAD_TARGETS, workloadClusterNames))
          .the_scion_is_sown_and_grafted("the cluster CA is sealed");
      return self();
    }

    @NestedSteps
    @As("the github app is registered")
    public When the_github_app_is_registered(
        @Hidden ScenarioModel hostScenario, @Hidden ReportModel hostTree) {
      // The ghapp registration scion ensures the one org-owned GitHub App exists and seals its
      // credentials (idempotent on a cellar hit; rehydrated from .secrets via the host
      // SecretsGateway
      // seam; else registered live through the manifest flow). No amendment: the scion reads the
      // App
      // manifest resource and calls the .secrets door in-container, so the sow carries an empty
      // trigger. Sown beside the cluster-pki seal, before provisioning, so the credentials exist
      // when
      // the writer push and the reader render need them.
      sowAndGraft
          .sowing("ghapp", gardening, hostScenario, hostTree)
          .the_scion_is_sown_and_grafted("the github app is registered");
      return self();
    }

    @NestedSteps
    @As("the replicator secrets are sealed")
    public When the_replicator_secrets_are_sealed(
        @Hidden ScenarioModel hostScenario, @Hidden ReportModel hostTree) {
      // The replicator-secrets seal scion rehydrates the mittwald SOURCE secrets from .secrets
      // (tekton git/docker, tailscale oauth) via the host SecretsGateway seam and files them SEALED
      // for the manifests synthesis to reveal + ReplicatorManifestsUnit to render onto the
      // node-bootstrap lane. Sown BEFORE incus-provision (which sub-sows manifests synthesis, the
      // consumer). No amendment: the scion reads .secrets in-container, so the sow carries an empty
      // trigger.
      sowAndGraft
          .sowing("replicator-secrets", gardening, hostScenario, hostTree)
          .the_scion_is_sown_and_grafted("the replicator secrets are sealed");
      return self();
    }

    @NestedSteps
    @As("the github app webhook is reconciled")
    public When the_github_app_webhook_is_reconciled(
        @Hidden ScenarioModel hostScenario, @Hidden ReportModel hostTree) {
      // The ghapp webhook scion re-points the org App's webhook at the current Tailscale funnel and
      // sets its shared HMAC secret — the App-level settings the operator would otherwise change by
      // hand in the GitHub UI on every funnel rename. ONE amendment hands the neutral FUNNEL role
      // the funnel endpoint URL: the ONLY host-held fact, since the MagicDNS leaf is a manifest
      // constant but the tailnet suffix is host-config, appended by Tailscale at runtime and never
      // on the in-container synthesis context. The scion reads the App credentials from the cellar
      // (sealed by the ghapp registration above) and the webhook secret from .secrets in-container.
      // The edge is gardening-gated (rke2lab.gardening=cultivating), so under a survey/preview this
      // crossing no-ops rather than calling GitHub.
      final JsonNode funnelUrl =
          JsonNodeFactory.instance.textNode(new PacWebhookFunnel(config.tailnet()).url());
      sowAndGraft
          .sowing(
              "ghapp-webhook",
              gardening,
              hostScenario,
              hostTree,
              Map.of(Amendment.FUNNEL, funnelUrl))
          .the_scion_is_sown_and_grafted("the github app webhook is reconciled");
      return self();
    }

    @NestedSteps
    @As("the instance is provisioned")
    public When the_instance_is_provisioned(
        @Hidden ScenarioModel hostScenario, @Hidden ReportModel hostTree) {
      // ONE per-consult amendment hands incus the IMAGE scalars from BootstrapConfig — the seed-
      // image build scalars the scion folds into the buildChecksum and the artifact paths it
      // projects for the GROW (§ host-cellar-realisation, computed OSGi-side). The stable cluster/
      // node identity is the ambient incus FACET (contributed at the GIVEN, merged at the door);
      // the
      // worktree root the scion reads from its own Worktree component. The host names only the
      // neutral IMAGE role — never an incus field nor a path.
      sowAndGraft
          .sowing(
              "incus-provision",
              gardening,
              hostScenario,
              hostTree,
              Map.of(Amendment.IMAGE, imageScalars))
          .the_scion_is_sown_and_grafted("the instance is provisioned");
      return self();
    }

    @As("the instance grows")
    public When the_instance_grows() {
      // The pure-host GROW (§ host-cellar § the-grow-anatomy): NOT a scion — the com.pulumi graph
      // cannot enter Felix, so it runs here, host-flat, between the incus provision crossing and
      // systemd (which runs inside the instance). It fetches the InstanceGrowPlan the incus scion
      // projected
      // (decoded host-side via the dual-realm codec) and actualises it: Project→{Network,Profile,
      // Image}→Instance — network + image + per-node identity, NO host disk mounts (the NixOS
      // node-base substrate reads its per-node facts back over devlxd; the former /srv/host
      // delivery
      // is dissolved). Gated on a live Pulumi deployment on THIS worker thread
      // (PulumiDeploymentSeed
      // installs it) — absent in a standalone/offline run, nothing to grow.
      if (!PulumiDeploymentSeed.isDeploymentPresent()) {
        return self();
      }
      // Read the plan through the TRANSACTIONAL cellar, not the durable cellarRealisation: the
      // incus scion published it within THIS run (a tag grafted into the host trunk at the
      // provision crossing), and the drain to the durable backend happens only at the run
      // boundary — after this step. So mid-run only the transactional overlay carries the plan
      // (read-your-writes). Reading the durable backend here saw an empty case and grew nothing,
      // so a preview (which never drains) declared no instance. The plan is now self-contained
      // (network + image + per-node identity), so the GROW no longer fetches the worktree facts —
      // it actualises the plan alone.
      // Fill the ingress contract's config from the run's BootstrapConfig and delegate the grow to
      // the pulumi actualiser: the run names no com.pulumi type, it only fetches the plan and hands
      // the actualiser a flat IngressConfig (a URI/Path rendered to its string form here).
      final IngressConfig ingress =
          new IngressConfig(
              config.incusProject(),
              config.incusDefaultRemote(),
              config.incusRemoteAddress().toString(),
              config.incusConfigFolder() == null ? "" : config.incusConfigFolder().toString(),
              config.nodeName(),
              config.profileName(),
              config.lanBridgeParent(),
              config.vmnetNetworkName());
      // The per-node bootstrap material the GROW lays into the guest through the UNIFORM cloud-init
      // channel (write_files; the same channel CAPN/CAPRKE2 use for a workload node). All opaque
      // here. Two sources, both dual-realm cases the transactional cellar reveals (read-your-writes
      // on the run's overlay, else the durable backend a prior grow drained):
      //   - the cluster PKI the seal scion filed: the sops CA bundle (PLAIN) + the age identity
      // that
      //     decrypts it (SEALED), so the guest's sops-nix lays the CA set before rke2-server;
      //   - the node-side bootstrap manifests the synthesis scion carved (SEALED — App key + age):
      //     Flux + the cilium HelmChartConfig, written into server/manifests before rke2-server so
      //     the CNI and Flux come up node-side.
      // Each is absent only on a run where its producer did not file — the guest units are tolerant
      // (rke2 self-signs its CA; a node with no bootstrap set simply seeds nothing).
      final NodeBootstrapMaterial material =
          new NodeBootstrapMaterial(
              workingCellar
                  .fetch(parcel, ClusterPkiCoordinate.CLUSTER_AGE_KEY, ClusterAgeKey.class)
                  .map(ClusterAgeKey::identity),
              workingCellar
                  .fetch(parcel, ClusterPkiCoordinate.CLUSTER_CA_BUNDLE, ClusterCaBundle.class)
                  .map(ClusterCaBundle::sops),
              workingCellar
                  .fetch(
                      parcel,
                      ServerManifestsCoordinate.SERVER_MANIFESTS,
                      ServerManifestsBundle.class)
                  .map(ServerManifestsBundle::manifests));
      // The cold/warm condition, READ NOW from the prior stack state — before the grow starts the
      // instance and the observation is lost. Frozen on the run's TRANSIENT bus as the GrowOutcome
      // fact (evicted at the drain, never conserved): the readiness-budget tuning reads it a few
      // steps on to pick a short fail-fast deadline for a live master, the patient one for a boot.
      final Growth growth =
          cellarRealisation
              .currentSnapshot(parcel)
              .map(GrowthCondition::new)
              .map(GrowthCondition::growth)
              .orElse(Growth.COLD);
      workingCellar
          .fetch(parcel, IncusGrowCoordinate.INSTANCE_GROW_PLAN, InstanceGrowPlan.class)
          .ifPresent(plan -> new InstanceGrow(ingress, line -> {}).grow(plan, material));
      workingCellar.storeTransient(
          parcel, IncusGrowCoordinate.GROW_OUTCOME, new GrowOutcome(growth));
      return self();
    }

    @As("the operator kubeconfig is published")
    public When the_operator_kubeconfig_is_published() {
      // The operator's natively-trusted admin kubeconfig, written host-side from the
      // AdminCredentials
      // the seal minted (SEALED — the transactional cellar reveals it on fetch). Endpoint added
      // here:
      // the apiserver's deterministic mDNS SAN <cluster>-<node>.local (nixos/rke2.nix's tls-san
      // drop-in), so TLS verifies against the embedded server-ca chain rooted at mammoth-skate-tls.
      // Written to kubeconfigRef (.local.d/kubeconfig.yaml) — the stable path the operator
      // and the readiness probe read. Live-only: absent a deployment there is nothing to access
      // yet.
      if (!PulumiDeploymentSeed.isDeploymentPresent()) {
        return self();
      }
      workingCellar
          .fetch(parcel, ClusterPkiCoordinate.ADMIN_CREDENTIALS, AdminCredentials.class)
          .ifPresent(this::publishOperatorKubeconfig);
      return self();
    }

    private void publishOperatorKubeconfig(AdminCredentials admin) {
      // The operator reaches the node over its deterministic mDNS name (the lan0 IP is
      // DHCP-churned);
      // the in-cluster Secret (rendered by the manifests HA layer) uses the kube-vip VIP instead.
      final String server =
          "https://" + config.clusterName() + "-" + config.nodeName() + ".local:6443";
      final String kubeconfig = admin.kubeconfig(config.clusterName(), server);
      final Path ref = config.kubeconfigRef();
      try {
        Files.createDirectories(ref.toAbsolutePath().getParent());
        Files.writeString(ref, kubeconfig);
        Files.setPosixFilePermissions(ref, PosixFilePermissions.fromString("rw-------"));
      } catch (IOException ex) {
        throw new UncheckedIOException("failed to publish the operator kubeconfig to " + ref, ex);
      }
    }

    @As("the readiness budget is tuned to the growth")
    public When the_readiness_budget_is_tuned_to_the_growth() {
      // Resolve the ambient ReadinessOverrides from the grow's cold/warm fact and publish it for
      // the
      // readiness scions (the RunGate route — a whole-run service they resolve from the registry,
      // not an envelope). WARM ⇒ the short fail-fast budget; COLD, or no grow at all (preview /
      // offline, absent GrowOutcome) ⇒ the config-derived overrides (#6, the annotation patient
      // defaults standing under them).
      final ReadinessOverrides overrides =
          workingCellar
              .fetch(parcel, IncusGrowCoordinate.GROW_OUTCOME, GrowOutcome.class)
              .map(GrowOutcome::growth)
              .filter(condition -> condition == Growth.WARM)
              .map(warm -> WARM_FAILFAST)
              .orElseGet(config::readinessOverrides);
      gardening
          .connection()
          .context()
          .registerService(ReadinessOverrides.class, overrides, new Hashtable<>());
      return self();
    }

    @NestedSteps
    @As("the systemd adapter is launched")
    public When the_systemd_adapter_is_launched(
        @Hidden ScenarioModel hostScenario, @Hidden ReportModel hostTree) {
      // Tolerating: systemd and cluster are the TERMINAL independent crossings — a systemd failure
      // must NOT fail-fast, so cluster still runs and both failures aggregate in one runbook. The
      // closing gate (the THEN) fails the run overall if either ended FAILED.
      sowAndGraft
          .sowing("systemd", gardening, hostScenario, hostTree)
          .the_scion_is_sown_and_grafted_tolerating_failure("the systemd adapter is launched");
      return self();
    }

    @NestedSteps
    @As("the cluster becomes ready")
    public When the_cluster_becomes_ready(
        @Hidden ScenarioModel hostScenario, @Hidden ReportModel hostTree) {
      // Tolerating (see the systemd crossing): the last crossing, aggregated with systemd; the
      // closing gate in the THEN enforces the overall verdict.
      sowAndGraft
          .sowing("cluster", gardening, hostScenario, hostTree)
          .the_scion_is_sown_and_grafted_tolerating_failure("the cluster becomes ready");
      return self();
    }
  }

  /** The closing THEN — the harvest is filed to its parcel's cellar, then the verdict is sealed. */
  public static class Then extends Stage<Then> {

    @ScenarioStage CellarStage cellar;

    private final ScenarioGraft graft = new ScenarioGraft();

    @As("the harvest is stored")
    public Then the_harvest_is_stored() {
      // The cellar bookend closes the run; the végétaux cultivated fresh this run are filed to the
      // parcel. (What exactly is stored is the harvest-shaping task; the bookend is here.)
      return self();
    }

    @As("the run fails if any crossing failed")
    public Then the_run_fails_if_any_crossing_failed(
        @Hidden ScenarioModel hostScenario, @Hidden ReportModel hostTree) {
      // The closing gate: the tolerating crossings (systemd, cluster) grafted their FAILED verdict
      // WITHOUT throwing, so their siblings ran and aggregated in the one runbook. Here — after the
      // harvest is filed — fail the whole run if any crossing left an error on the host case, so
      // the
      // verdict is honest (pulumi up exits non-zero, JUnit agrees with the runbook) without the
      // fail-fast having discarded the sibling diagnostics. The drain (afterTestExecution) still
      // runs
      // and persists the real harvest despite this throw — the non-transactional mirror.
      graft.assertNoCrossingFailed(hostScenario, hostTree);
      return self();
    }
  }
}
