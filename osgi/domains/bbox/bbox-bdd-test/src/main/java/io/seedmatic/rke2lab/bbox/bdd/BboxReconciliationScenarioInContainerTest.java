package io.seedmatic.rke2lab.bbox.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.jgiven.report.json.ScenarioJsonReader;
import com.tngtech.jgiven.report.json.ScenarioJsonWriter;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.seedmatic.rke2lab.bbox.contract.BboxAction;
import io.seedmatic.rke2lab.bbox.contract.BboxCoordinate;
import io.seedmatic.rke2lab.bbox.contract.BboxHarvest;
import io.seedmatic.rke2lab.bbox.contract.BboxReconciler;
import io.seedmatic.rke2lab.bbox.contract.BboxReservationRequest;
import io.seedmatic.rke2lab.bbox.contract.BboxRowOutcome;
import io.seedmatic.rke2lab.bbox.contract.BboxRunbookInput;
import io.seedmatic.rke2lab.doctor.contract.Checkpoint;
import io.seedmatic.rke2lab.doctor.contract.Consultation;
import io.seedmatic.rke2lab.doctor.contract.ConsultingService;
import io.seedmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioOutcome;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.RunGate;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.port.Sensitivity;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

/**
 * The in-container proof of the bbox scion, run WHERE the scenario lives (this passenger shares the
 * bbox-bdd host loader through the fragment). It registers the scion's collaborators — a mock
 * {@link BboxReconciler} and a mock {@link RunGate} (and, for the refusal case, a mock {@link
 * ConsultingService}) — into the SAME registry the scenario resolves from, then plays it
 * in-container through {@link ScenarioPlayer} (the shared play recipe the production {@code
 * GenericRunbookHandler} also drives) and asserts on the harvested {@link ScenarioOutcome}.
 *
 * <p>No seam, no system-export: because the fragment shares the bundle's classloader, the mock this
 * passenger registers is the same {@code Class} the scenario reads (unlike the out-of-container
 * shape, which registers on the host loader and needs the port system-exported). It is the {@code
 * HealthSystemContributionTest} shape — register in-container, then act, one method — applied to a
 * scenario play. Because the play, the harvest, and this assertion all sit on the same in-container
 * worker, it reads the LIVE outcome — the jGiven {@code ReportModel} as an object, no JSON
 * round-trip (that serialisation is the host-crossing handler's concern). Registrations are
 * unregistered in a {@code finally} because the framework is shared across the passenger's tests
 * (an oldest-wins ranking tie would otherwise leak a mock).
 */
public class BboxReconciliationScenarioInContainerTest {

  private static final SeedCodec CODEC = new SeedCodec();

  /**
   * The canonical run enumerates one row per node of every RESERVABLE cluster — the two {@code
   * <host>-mgmt}, each single-node: 2 rows.
   *
   * <p>It was 12 (2 × 6) while the enumerator crossed bare HOSTS with the canonical node SUPERSET.
   * Both halves were wrong: a host name has no role to split, so {@code ClusterRole.of} fell back
   * to MGMT and described pseudo-clusters, and the superset placed four non-existent nodes past the
   * end of a management cluster's {@code /29}. And a workload row could never be claimed anyway —
   * its nodes are CAPN cattle with random MACs, and a reservation is keyed by MAC.
   */
  private static final int DESIRED_COUNT = 2;

  /** The current parcel the host publishes at the GIVEN; the scion files its harvest under it. */
  private static final Parcel PARCEL = new Parcel("bioskop", "dev");

  @Test
  void a_live_run_reconciles_every_row_green() throws Exception {
    // cultivating() true → the scion asks for a live apply; the mock reconciler matches every row.
    final ScenarioOutcome outcome =
        playWith(cultivatingGate(true), allMatching(), null, new RecordingCellar());
    final ReportModel runbook = outcome.runbook();

    assertNotNull(runbook, "the player harvested the played model");
    assertEquals(1, runbook.getScenarios().size(), "one scenario played");
    assertEquals(
        ExecutionStatus.SUCCESS,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "every desired row reconciled → the scenario plays green");
    assertTrue(
        outcome.consultations().isEmpty(), "a healthy run refused no row, so it consults no one");

    // The reversal, proven: the SCION harvested AND stored itself. The store is a cellar-entry on
    // the played model (the scion is a fragment; the host root drains at the boundary), read back
    // through the cellar's OWN generic API — a ScenarioCellar over the LIVE model with an empty
    // durable side, so fetch returns the run's own write (read-your-writes). At the
    // bbox-reservations coordinate, carrying the folded summary (12 MATCHING rows).
    final ScenarioCellar cellar =
        new ScenarioCellar(() -> runbook, RecordingCellar::new, Optional.empty());
    final BboxHarvest summary =
        cellar
            .fetch(PARCEL, BboxCoordinate.BBOX_RESERVATIONS, BboxHarvest.class)
            .orElseThrow(
                () -> new AssertionError("the scion stored no harvest at bbox-reservations"));
    assertEquals(DESIRED_COUNT, summary.desiredCount(), "the summary counts every row");
    assertEquals(DESIRED_COUNT, summary.matchingCount(), "a live all-matching run: all matched");

    // The HOST-CROSSING round-trip the GenericRunbookHandler + ScenarioGraft actually run in prod:
    // serialise the outcome's model to JSON (ScenarioJsonWriter) and rebuild it
    // (ScenarioJsonReader,
    // exactly what ScenarioGraft.rebuild does). The
    // live-outcome assertions above never exercise this — the coverage gap that let the live graft
    // fail with "no scenario to graft" while the tests stayed green.
    final String runbookJson = new ScenarioJsonWriter(runbook).toString();
    final File tmp = File.createTempFile("bbox-graft-roundtrip", ".json");
    tmp.deleteOnExit();
    Files.writeString(tmp.toPath(), runbookJson);
    final ReportModel rebuilt = new ScenarioJsonReader().apply(tmp);
    assertEquals(
        1,
        rebuilt.getScenarios().size(),
        "the serialised-then-rebuilt runbook still carries its scenario (the graft's input)");
  }

  @Test
  void a_survey_run_selects_surveying_and_renders_pending() throws Exception {
    // One survey run proves the TWO orthogonal axes at once. The scion is mode-blind — it holds one
    // BboxReconciler and drives it. TOUCH axis: under a surveying gate the FRONTIER (the
    // @OsgiService
    // bridge, LDAP filter on rke2lab.gardening) hands it the SURVEYING impl, never the cultivating
    // one. RENDER axis: SurveyRenderExtension swaps in the PendingMarkingScenarioExecutor, so the
    // bodies still run (the surveying reconciler IS called, the harvest IS stored) but every step
    // is
    // narrated PENDING → the scenario reads SCENARIO_PENDING, a plan, not a green result.
    final RecordingReconciler cultivating = new RecordingReconciler(BboxAction.MATCHING);
    final RecordingReconciler surveying = new RecordingReconciler(BboxAction.WOULD_CREATE);
    final BundleContext context = FrameworkUtil.getBundle(BboxBddTests.class).getBundleContext();
    final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    registrations.add(
        context.registerService(RunGate.class, cultivatingGate(false), new Hashtable<>()));
    registrations.add(
        context.registerService(BboxReconciler.class, cultivating, gardening("cultivating")));
    registrations.add(
        context.registerService(BboxReconciler.class, surveying, gardening("surveying")));
    registrations.add(
        context.registerService(Cellar.class, new RecordingCellar(), new Hashtable<>()));
    registrations.add(context.registerService(Parcel.class, PARCEL, new Hashtable<>()));
    try {
      final ScenarioOutcome outcome =
          new ScenarioPlayer()
              .play(
                  BboxReconciliationScenario.class,
                  BboxReconciliationScenario.INPUT.into(BboxRunbookInput.defaults()));
      // TOUCH: the frontier resolved the surveying half, and only it.
      assertTrue(surveying.called, "the frontier handed the scion the surveying reconciler");
      assertTrue(
          !cultivating.called, "the frontier never resolved the cultivating reconciler in survey");
      // RENDER: a survey narrates a PENDING plan, not a green result — even though the body ran.
      assertEquals(
          ExecutionStatus.SCENARIO_PENDING,
          outcome.runbook().getScenarios().get(0).getExecutionStatus(),
          "a surveyed scenario renders PENDING — a plan, not a result");
      assertTrue(
          outcome.consultations().isEmpty(),
          "WOULD_CREATE is not a refusal, so it consults no one");
    } finally {
      registrations.forEach(ServiceRegistration::unregister);
    }
  }

  /** The service properties tagging one half of a mode-sensitive collaborator pair. */
  private static Hashtable<String, Object> gardening(String mode) {
    final Hashtable<String, Object> properties = new Hashtable<>();
    properties.put("rke2lab.gardening", mode);
    return properties;
  }

  @Test
  void a_refused_row_makes_the_domain_consult_the_doctor_itself() throws Exception {
    // A FAILED row is a symptom; the scion resolves the doctor from its OWN registry and consults —
    // the consultation rides the outcome back, the host no longer computes it (fork B).
    final RecordingDoctor doctor = new RecordingDoctor();
    final ScenarioOutcome outcome =
        playWith(cultivatingGate(true), oneRefused(), doctor, new RecordingCellar());

    assertEquals(
        ExecutionStatus.FAILED,
        outcome.runbook().getScenarios().get(0).getExecutionStatus(),
        "a refused reservation row fails the checkpoint (the row is surfaced, not dropped)");
    assertEquals(1, doctor.consultedCheckpoints.size(), "the domain consulted the doctor once");
    assertTrue(
        doctor.consultedCheckpoints.get(0).contains("reservation-refused"),
        "the consult carries the typed symptom the doctor routes on (to the NETWORK domain)");

    final List<SeedEnvelope> consultations = outcome.consultations();
    assertEquals(1, consultations.size(), "the consultation rides the outcome back to the host");
    assertEquals(
        "bbox-reservations",
        CODEC.decode(consultations.get(0).payload()).path("scenarioId").asText(),
        "the consultation names the checkpoint the host joins on");
  }

  /**
   * Register the mock collaborators into THIS bundle's registry, play the scenario in-container
   * through the shared {@link ScenarioPlayer}, and return its live {@link ScenarioOutcome}.
   * Registrations are removed in the {@code finally} so each test plays against exactly its own
   * mocks.
   */
  private static ScenarioOutcome playWith(
      RunGate gate, BboxReconciler reconciler, ConsultingService doctor, Cellar cellar)
      throws Exception {
    final BundleContext context = FrameworkUtil.getBundle(BboxBddTests.class).getBundleContext();
    final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    registrations.add(context.registerService(RunGate.class, gate, new Hashtable<>()));
    registrations.add(context.registerService(BboxReconciler.class, reconciler, new Hashtable<>()));
    // The two ambient facts the scion needs to store its own harvest — the host publishes them at
    // the GIVEN in prod; here the passenger seeds a recording cellar and a fixed current parcel.
    registrations.add(context.registerService(Cellar.class, cellar, new Hashtable<>()));
    registrations.add(context.registerService(Parcel.class, PARCEL, new Hashtable<>()));
    if (doctor != null) {
      registrations.add(
          context.registerService(ConsultingService.class, doctor, new Hashtable<>()));
    }
    try {
      return new ScenarioPlayer()
          .play(
              BboxReconciliationScenario.class,
              BboxReconciliationScenario.INPUT.into(BboxRunbookInput.defaults()));
    } finally {
      registrations.forEach(ServiceRegistration::unregister);
    }
  }

  /** A RunGate the test pins to a chosen cultivating value. */
  private static RunGate cultivatingGate(boolean cultivating) {
    return () -> cultivating;
  }

  /** A reconciler that matches every desired row (a healthy live run). */
  private static BboxReconciler allMatching() {
    return (baseUri, adminPassword, requests) ->
        requests.stream().map(r -> outcome(r, BboxAction.MATCHING)).toList();
  }

  /** A reconciler whose first row is refused (FAILED) and the rest match. */
  private static BboxReconciler oneRefused() {
    return (baseUri, adminPassword, requests) -> {
      final List<BboxRowOutcome> outcomes = new ArrayList<>(requests.size());
      for (int i = 0; i < requests.size(); i++) {
        outcomes.add(outcome(requests.get(i), i == 0 ? BboxAction.FAILED : BboxAction.MATCHING));
      }
      return outcomes;
    };
  }

  private static BboxRowOutcome outcome(BboxReservationRequest request, BboxAction action) {
    return new BboxRowOutcome(
        request.cluster(),
        request.node(),
        action,
        request.mac(),
        request.ip(),
        request.hostname(),
        OptionalInt.empty(),
        Optional.empty(),
        Optional.empty(),
        action == BboxAction.FAILED
            ? Optional.of("router refused the reservation")
            : Optional.empty());
  }

  /** A reconciler that records whether it was called — proving which half the frontier resolved. */
  private static final class RecordingReconciler implements BboxReconciler {
    private final BboxAction action;
    boolean called;

    RecordingReconciler(BboxAction action) {
      this.action = action;
    }

    @Override
    public List<BboxRowOutcome> reconcile(
        URI baseUri, Optional<String> adminPassword, List<BboxReservationRequest> requests) {
      this.called = true;
      return requests.stream().map(r -> outcome(r, action)).toList();
    }
  }

  /**
   * A mock doctor — records each consulted checkpoint's payload (so the test asserts the domain
   * routed the typed symptom) and returns a minimal {@code consultation} SeedEnvelope naming the
   * checkpoint the host joins on. The test seeds it into the registry before playing.
   */
  private static final class RecordingDoctor implements ConsultingService {
    final List<String> consultedCheckpoints = new ArrayList<>();

    @Override
    public SeedEnvelope consult(SeedEnvelope checkpoint) {
      consultedCheckpoints.add(checkpoint.payload());
      final Consultation reply =
          new Consultation(
              Checkpoint.BBOX_RESERVATIONS.slug(),
              "the bbox reservations checkpoint was consulted",
              "",
              Map.of(),
              List.of());
      return SeedEnvelope.of(DoctorCoordinate.CONSULTATION, CODEC.encode(reply));
    }

    @Override
    public void reviewDrift() {}
  }

  /**
   * A neutral cellar that records every {@code store} — the twin of the host's {@code PulumiCellar}
   * for the test, minus the Pulumi backend. It proves the SCION reached the cellar and stored its
   * own harvest; {@code fetch}/{@code neighbours} are unused on this path.
   */
  private static final class RecordingCellar implements Cellar {
    final List<SeedCoordinate> storedAt = new ArrayList<>();
    final List<Object> stored = new ArrayList<>();

    @Override
    public <T> void store(
        Parcel parcel, SeedCoordinate coordinate, T value, Sensitivity sensitivity) {
      storedAt.add(coordinate);
      stored.add(value);
    }

    @Override
    public <T> List<T> fetch(Parcel parcel, Class<T> type) {
      return List.of();
    }

    @Override
    public <T> Optional<T> fetch(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
      return Optional.empty();
    }

    @Override
    public <T> Optional<T> withdraw(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
      return Optional.empty();
    }

    @Override
    public List<Parcel> neighbours(Parcel parcel) {
      return List.of(parcel);
    }
  }
}
