package io.seedmatic.rke2lab.systemd.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.seedmatic.rke2lab.doctor.contract.Checkpoint;
import io.seedmatic.rke2lab.doctor.contract.ConsultingService;
import io.seedmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.seedmatic.rke2lab.doctor.contract.ObservationWire;
import io.seedmatic.rke2lab.doctor.contract.ReadinessCheckpoint;
import io.seedmatic.rke2lab.doctor.contract.SymptomKind;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import io.seedmatic.rke2lab.osgi.runtime.readiness.ReadinessBudget;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ConsultationSource;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.InputReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ReadinessBudgetReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ReadinessDeadlines;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioInputSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SurveyInert;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.systemd.contract.SystemdProbeRequest;
import io.seedmatic.rke2lab.systemd.contract.SystemdRunbookInput;
import io.seedmatic.rke2lab.systemd.contract.SystemdRuntimeProbe;
import io.seedmatic.rke2lab.systemd.contract.SystemdStatusSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The systemd-adapter readiness checkpoint, a production jGiven scenario told in the SYSTEMD
 * DOMAIN's own vocabulary — {@link SystemdRuntimeProbe} opened once over its dbus-on-TCP endpoint,
 * the returned {@link SystemdStatusSnapshot} asserted facet by facet (mandatory target healthy, no
 * failed units, runtime precheck ready), no host/Pulumi type. Played IN-CONTAINER by the engine so
 * the runbook shows a real node of the OSGi world; it lives in {@code systemd-bdd} (only ports, no
 * sealed internal), not a {@code -test} fragment (it is live seeding logic).
 *
 * <p>The systemd twin of {@code ClusterReadinessScenario}: identical shape, one difference of
 * nature — systemd has no phase chain, so the single probe's snapshot is read across a small chain
 * of readable assertions rather than a chain of contact calls. Its collaborator — the {@link
 * SystemdRuntimeProbe} — is INJECTED from its OWN bundle's registry by the {@link OsgiService}
 * bridge; the scenario is identical live and in test, only who published the probe differs (the
 * live {@code DbusSystemdProbe}, or a mock a test seeds into the registry before playing). A
 * not-ready facet throws, jGiven marks it FAILED and skips the downstream chained assertions, so
 * the runbook shows exactly which systemd fact broke.
 *
 * <p>The endpoint the probe opens is DERIVED, not deferred: the run's identity FACET (cluster/node,
 * amended by the host) feeds the netplan {@code ClusterNetworkBlueprint} — a pure identity→topology
 * function — for the node's mDNS FQDN and {@code <cluster>-nixos} host, paired with the systemd
 * dbus port (a service constant) into a {@link SystemdProbeRequest}. An unamended (offline) play
 * carries no identity, so it falls to a fixed marker the mock probe ignores.
 */
@SeedScenario
public class SystemdAdapterScenario
    extends ScenarioTestBase<
        SystemdAdapterScenario.Given, SystemdAdapterScenario.When, SystemdAdapterScenario.Then>
    implements InputReceiver<SystemdRunbookInput>,
        ReadinessBudgetReceiver,
        ConsultationSource,
        ScenarioPlayer.Playable,
        SurveyInert {

  /**
   * The inbound channel {@code SystemdRunbookHandler.seedFrom} seeds the {@link
   * SystemdRunbookInput} through and this scenario receives (via {@link InputReceiver}) —
   * single-sourced here, referenced by the handler for the seeding end ({@code INPUT.into(input)}).
   * A {@link RegisterExtension} so its {@code TestInstancePostProcessor} fires before the body
   * reads {@link #input}.
   */
  @RegisterExtension
  public static final ScenarioInputSeed<SystemdRunbookInput> INPUT =
      new ScenarioInputSeed<>(SystemdRunbookInput.class, "systemd-runbook-input");

  private final Scenario<Given, When, Then> scenario = createScenario();

  // The activation input the front-door seeds before the body (InputReceiver) — its FACET identity
  // is what the probe endpoint derives from. @MonotonicNonNull: null until receiveInput sets it.
  @MonotonicNonNull private SystemdRunbookInput input;

  // The two-tier readiness budget the ReadinessBudgetExtension resolves from the @Test's
  // @ReadinessDeadlines (folded with the stack override) before the body — threaded into the probe
  // so awaitReady bounds the reach + convergence. @MonotonicNonNull: null until receiveBudget sets.
  @MonotonicNonNull private ReadinessBudget budget;

  // Injected by the OsgiServiceExtension from THIS bundle's registry before the body. The probe
  // moved to the When stage (@OsgiService there, filled by the stage creator); the doctor stays
  // here — await=false, a snapshot the consult reads, empty when a world booted without it.
  @OsgiService(await = false)
  private Optional<ConsultingService> doctor = Optional.empty();

  // The consultations the run raised on a failing facet — the ScenarioOutcomeExtension PULLS them
  // (ConsultationSource) at the run boundary. Set in the @Test after the body (jGiven defers a
  // failing facet's throw to scenario-end, so it still reaches this); empty until then.
  private List<SeedEnvelope> consultations = List.of();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveInput(SystemdRunbookInput input) {
    this.input = input;
  }

  @Override
  public String readinessCheckpoint() {
    return Checkpoint.SYSTEMD_ADAPTER.slug();
  }

  @Override
  public void receiveBudget(ReadinessBudget budget) {
    this.budget = budget;
  }

  @Override
  public List<SeedEnvelope> consultations() {
    return consultations;
  }

  @Test
  @ReadinessDeadlines(connect = "PT2M", ready = "PT1M")
  void the_systemd_adapter_becomes_reachable() {
    final List<ObservationWire> observations = new ArrayList<>();
    final SystemdProbeRequest endpoint = endpointFrom(Optional.ofNullable(input));
    final ReadinessBudget resolvedBudget =
        Objects.requireNonNull(
            budget, "the ReadinessBudgetExtension must inject the budget before the body");
    given()
        .the_seed_node(endpoint.nodeName())
        .and()
        .probed_through(observations, endpoint, resolvedBudget);
    when()
        .the_systemd_endpoint_is_probed()
        .and()
        .the_mandatory_target_is_healthy()
        .and()
        .no_units_have_failed();
    then().the_systemd_adapter_is_reachable();
    this.consultations = consultOnFailure(observations);
  }

  /**
   * The domain consults the doctor ITSELF on a failing facet (fork B: the checkpoint owns its
   * consult, not the host). A facet reported not-ready recorded its {@link ObservationWire}
   * carrying a typed {@link SymptomKind}; if any is non-ok, resolve the doctor's {@link
   * ConsultingService} from THIS bundle's registry, build the {@code readiness-checkpoint}
   * SeedEnvelope around the observations, and consult. The returned {@code consultation} {@code
   * SeedEnvelope}s ride the envelope back to the host, which records them. A healthy run raised no
   * symptom, so it consults no one and returns an empty list.
   */
  private List<SeedEnvelope> consultOnFailure(List<ObservationWire> observations) {
    final boolean anySymptom = observations.stream().anyMatch(o -> o.symptom().isPresent());
    if (!anySymptom) {
      return List.of();
    }
    return doctor
        .map(consulting -> List.of(consulting.consult(consultCheckpoint(observations))))
        .orElseGet(List::of);
  }

  /**
   * The {@code readiness-checkpoint} SeedEnvelope the domain hands the doctor — its observations,
   * named by the systemd-adapter checkpoint the host joins the runbook on.
   */
  private static SeedEnvelope consultCheckpoint(List<ObservationWire> observations) {
    final ReadinessCheckpoint checkpoint =
        new ReadinessCheckpoint(
            Checkpoint.SYSTEMD_ADAPTER.slug(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.copyOf(observations));
    final SeedCodec codec = new SeedCodec();
    return SeedEnvelope.of(DoctorCoordinate.READINESS_CHECKPOINT, codec.encode(checkpoint));
  }

  /**
   * The endpoint the probe opens — DERIVED from the run's identity FACET: the scenario feeds the
   * cluster/node the host amended into {@code ClusterNetworkBlueprint} (a pure identity→topology
   * function, consumed directly like manifests does) and pairs the node's mDNS FQDN with the
   * systemd dbus port (a service constant, {@link SystemdEndpoints} — a port is not
   * identity-derived, so it lives with the service, not in the blueprint). No identity (an offline
   * play) falls to the fixed {@link #MARKER} the mock probe ignores. This is the live endpoint
   * plumbing the marker stood in for — the seam {@code SystemdProbeRequest}'s javadoc always
   * intended ("the flat host fills it").
   */
  private static SystemdProbeRequest endpointFrom(Optional<SystemdRunbookInput> input) {
    return input
        .flatMap(SystemdRunbookInput::identity)
        .map(SystemdAdapterScenario::deriveEndpoint)
        .orElse(MARKER);
  }

  private static SystemdProbeRequest deriveEndpoint(SystemdRunbookInput.Identity identity) {
    final ClusterNetworkBlueprint blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster(identity.clusterName())
            .node(identity.nodeName())
            .deriveRecipeModel()
            .build();
    return new SystemdProbeRequest(
        blueprint.names().nodeMdnsFqdn(),
        DBUS_TCP_PORT,
        identity.nodeName(),
        blueprint.names().nixosHost());
  }

  /**
   * The systemd dbus-on-TCP adapter's fixed port — a SERVICE constant (identical on every cluster),
   * so it lives with the domain that owns it, NOT in the identity-derived netplan blueprint. Paired
   * with the blueprint's node mDNS FQDN to form the probe endpoint; mirrors the adapter script's
   * own default.
   */
  private static final int DBUS_TCP_PORT = 12434;

  private static final SystemdProbeRequest MARKER =
      new SystemdProbeRequest("localhost", 0, "seed", "unknown");

  /** Given: the seed node to reach, the probe, and the shared observation buffer the When fills. */
  public static class Given extends Stage<Given> {

    /** The snapshot the probe returned, read across the When's facet assertions. */
    @ProvidedScenarioState SystemdStatusSnapshot snapshot;

    /**
     * The per-facet observations the When records — the material the domain's own consult reads.
     */
    @ProvidedScenarioState List<ObservationWire> observations;

    /** The endpoint the When probes — derived from the identity FACET (or the marker offline). */
    @ProvidedScenarioState SystemdProbeRequest endpoint;

    /** The two-tier readiness budget the When hands the probe's awaitReady. */
    @ProvidedScenarioState ReadinessBudget budget;

    public Given the_seed_node(String name) {
      return self();
    }

    @Hidden
    public Given probed_through(
        List<ObservationWire> observations, SystemdProbeRequest endpoint, ReadinessBudget budget) {
      this.observations = observations;
      this.endpoint = endpoint;
      this.budget = budget;
      return self();
    }
  }

  /**
   * When: the single probe is opened once, then its {@link SystemdStatusSnapshot} is read across a
   * small chain of readable facet assertions. Each facet records its {@link ObservationWire} (ok,
   * or failed with a typed {@link SymptomKind}) into the shared buffer, then a not-ready facet
   * throws — jGiven marks its step FAILED and skips the downstream chained steps. Fail-fast is the
   * chain's own semantics; the recorded observations are what the domain's own doctor consult
   * reads.
   */
  public static class When extends Stage<When> {

    // Injected straight from the bundle registry by the @OsgiService bridge (the stage creator) —
    // not threaded from the scenario through the Given as a step param.
    @OsgiService private Optional<SystemdRuntimeProbe> probe = Optional.empty();

    @ExpectedScenarioState List<ObservationWire> observations;

    @ExpectedScenarioState SystemdProbeRequest endpoint;

    @ExpectedScenarioState ReadinessBudget budget;

    @ProvidedScenarioState SystemdStatusSnapshot snapshot;

    public When the_systemd_endpoint_is_probed() {
      try {
        this.snapshot = probe.orElseThrow().awaitReady(endpoint, budget);
      } catch (RuntimeException unreachable) {
        record("systemd endpoint", false, SymptomKind.CONNECTION_REFUSED);
        throw new SystemdNotReadyError("systemd endpoint", unreachable);
      }
      return check(
          "systemd endpoint", snapshot.runtimePrecheckReady(), SymptomKind.CONNECTION_REFUSED);
    }

    public When the_mandatory_target_is_healthy() {
      return check(
          "mandatory target", snapshot.mandatoryTargetHealthy(), SymptomKind.CONNECTION_REFUSED);
    }

    public When no_units_have_failed() {
      return check("failed units", snapshot.failedUnits() == 0, SymptomKind.CONNECTION_REFUSED);
    }

    /**
     * Record the facet's observation, then fail-fast if not ready: an ok facet records an ok wire
     * and returns; a not-ready facet records a failed wire carrying the typed symptom (the doctor's
     * routing key) and throws so jGiven marks the step FAILED.
     */
    private When check(String facet, boolean ready, SymptomKind failureSymptom) {
      record(facet, ready, failureSymptom);
      if (!ready) {
        throw new SystemdNotReadyError(facet, snapshot);
      }
      return self();
    }

    private void record(String facet, boolean ready, SymptomKind failureSymptom) {
      if (ready) {
        observations.add(
            new ObservationWire("ok", facet, Optional.empty(), Map.of("facet", facet)));
        return;
      }
      observations.add(
          new ObservationWire(
              "failed",
              facet + ": not ready — " + snapshotSummary(),
              Optional.of(failureSymptom),
              Map.of("facet", facet, "snapshot", snapshotSummary())));
    }

    // The probe's verdict is a single boolean, but WHY it is not-ready lives in the snapshot — the
    // mandatory target, its state, failed units. Carry that into the failure message AND the
    // observation so the runbook is self-diagnosing (no step-debugger, no log fishing). Null only
    // on
    // the unreachable path, where the caught exception already carries the reason.
    private String snapshotSummary() {
      return snapshot == null ? "no snapshot (endpoint unreachable)" : snapshot.summary();
    }
  }

  /**
   * Then: the systemd adapter is reachable — reached only once every facet passed (a failing facet
   * throws in the When), the readable closing line, not where evaluation happens.
   */
  public static class Then extends Stage<Then> {

    public Then the_systemd_adapter_is_reachable() {
      return self();
    }
  }
}
