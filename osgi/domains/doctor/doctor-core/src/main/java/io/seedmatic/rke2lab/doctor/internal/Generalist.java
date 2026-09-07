package io.seedmatic.rke2lab.doctor.internal;

import io.seedmatic.rke2lab.doctor.contract.Assessment;
import io.seedmatic.rke2lab.doctor.contract.ClinicianId;
import io.seedmatic.rke2lab.doctor.contract.Consultation;
import io.seedmatic.rke2lab.doctor.contract.ConsultationReport;
import io.seedmatic.rke2lab.doctor.contract.ConsultingService;
import io.seedmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.seedmatic.rke2lab.doctor.contract.Expectation;
import io.seedmatic.rke2lab.doctor.contract.InterventionLedger;
import io.seedmatic.rke2lab.doctor.contract.MedicalRecord;
import io.seedmatic.rke2lab.doctor.contract.Observation;
import io.seedmatic.rke2lab.doctor.contract.ObservationWire;
import io.seedmatic.rke2lab.doctor.contract.ProblemReview;
import io.seedmatic.rke2lab.doctor.contract.ReadinessCheckpoint;
import io.seedmatic.rke2lab.doctor.contract.Referral;
import io.seedmatic.rke2lab.doctor.contract.ReferralReply;
import io.seedmatic.rke2lab.doctor.contract.RemediationPlan;
import io.seedmatic.rke2lab.doctor.contract.Specialty;
import io.seedmatic.rke2lab.doctor.contract.Symptom;
import io.seedmatic.rke2lab.doctor.contract.Visit;
import io.seedmatic.rke2lab.doctor.spi.ClinicalReasoning;
import io.seedmatic.rke2lab.doctor.spi.Clinician;
import io.seedmatic.rke2lab.doctor.spi.Specialist;
import io.seedmatic.rke2lab.domain.annotations.Transitional;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * The doctor's coordinator. When a checkpoint fails, the patient consults: the Generalist takes the
 * symptom + the captured {@link Observation}, and synthesizes a {@link RemediationPlan}.
 *
 * <ol>
 *   <li><b>firstLook</b> — retrieves the patient's {@link MedicalRecord} through its {@link
 *       ClinicalAccess} (the first act of the visit); it does not yet drive routing.
 *   <li><b>route</b> — route <em>deterministically</em> by symptom to the relevant specialties (a
 *       readable rules table — no inference). Irrelevant specialists stay dormant.
 *   <li><b>synthesize</b> — collect each routed specialist's prescription (if any) into one plan.
 * </ol>
 *
 * The Generalist reads records only through its {@link ClinicalAccess} (bound to its id by the
 * {@link HealthSystem} at employment); it holds no registry or patient directly.
 *
 * <p>The settled design splits this per-run object into an EMPLOYED practitioner (a stable
 * {@code @Component}) and a per-run Consultation value carrying the access — so every actor can be
 * a component of the system.
 */
@Transitional(
    to = "GeneralPractitioner + Consultation",
    spec = "practitioners-as-components-design.adoc")
public final class Generalist implements Clinician, ConsultingService, ClinicalReasoning {

  /** The Generalist's stable id — the grant policy's join key for the general practitioner. */
  public static final ClinicianId GENERALIST_ID = new ClinicianId("generalist");

  private final List<Specialist> specialists;
  private final ClinicalAccess access;
  private final DriftSpecialist driftSpecialist;
  private final Optional<InterventionLedgerRegistry> ledgerRegistry;
  private final SeedCodec codec = new SeedCodec();

  private Generalist(
      List<Specialist> specialists,
      ClinicalAccess access,
      DriftSpecialist driftSpecialist,
      Optional<InterventionLedgerRegistry> ledgerRegistry) {
    this.specialists = List.copyOf(specialists);
    this.access = access;
    this.driftSpecialist = driftSpecialist;
    this.ledgerRegistry = ledgerRegistry;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private List<Specialist> specialists = List.of();
    @MonotonicNonNull private ClinicalAccess access;
    private Optional<DriftSpecialist> driftSpecialist = Optional.empty();
    private Optional<InterventionLedgerRegistry> ledgerRegistry = Optional.empty();

    public Builder specialists(List<Specialist> specialists) {
      this.specialists = specialists;
      return this;
    }

    public Builder access(ClinicalAccess access) {
      this.access = access;
      return this;
    }

    public Builder driftSpecialist(DriftSpecialist driftSpecialist) {
      this.driftSpecialist = Optional.of(driftSpecialist);
      return this;
    }

    /**
     * The intervention-ledger registry {@link #reviewDrift()} reads the ledger from. Absent → the
     * review folds the empty ledger (coherent with the medical-record registry's no-backend
     * degrade).
     */
    public Builder ledgerRegistry(InterventionLedgerRegistry ledgerRegistry) {
      this.ledgerRegistry = Optional.of(ledgerRegistry);
      return this;
    }

    public Generalist build() {
      final ClinicalAccess boundAccess = access;
      if (boundAccess == null) {
        throw new IllegalStateException("access is required");
      }
      // No ledger wired → the drift inference is computed and returned but not persisted, coherent
      // with the registry's no-backend degrade. A no-op registry keeps an empty ledger, records
      // nowhere.
      final DriftSpecialist drift =
          driftSpecialist.orElseGet(
              () -> new DriftSpecialist(NoOpInterventionLedgerRegistry.INSTANCE));
      return new Generalist(specialists, boundAccess, drift, ledgerRegistry);
    }
  }

  @Override
  public ClinicianId clinicianId() {
    return GENERALIST_ID;
  }

  /**
   * The admitted patient's record, read through the held access. Bundle-internal (no longer on the
   * {@code ConsultingService} seam — no record crosses to the host); {@link #reviewDrift()} folds
   * over it, and the doctor's own in-container tests read it white-box.
   */
  public MedicalRecord recordForCurrentPatient() {
    return access.record();
  }

  /**
   * The one-line consultation narration for the symptom, folded over the held patient's own record.
   * Twin of {@link #cohortFinding(Symptom)} — both render a line the consulting stages log.
   */
  @Override
  public String consultedLine(Symptom symptom) {
    final MedicalRecord record = access.record();
    return "consulted with "
        + record.visits().size()
        + " prior visit(s); "
        + symptom.id()
        + " seen "
        + record.historyOf(symptom).count()
        + "× before";
  }

  /**
   * A one-line cross-patient finding for the symptom, folded across the granted cohort. Empty
   * cohort (or no backend) yields a finding over just the current patient.
   */
  @Override
  public String cohortFinding(Symptom symptom) {
    final List<MedicalRecord> cohort = access.cohort();
    final long withSymptom = cohort.stream().filter(r -> r.historyOf(symptom).count() > 0).count();
    final long treatedAndResolved =
        cohort.stream()
            .filter(r -> r.efficacyOf(symptom, InterventionLedger.empty()).everWorked())
            .count();
    return "cohort: "
        + symptom.id()
        + " seen on "
        + withSymptom
        + " of "
        + cohort.size()
        + " patient(s); "
        + treatedAndResolved
        + " prior treatment(s) resolved it";
  }

  /**
   * Consult on a checkpoint {@link SeedEnvelope}: route its symptom + observation to the
   * specialists and synthesize the narration and the rendered AsciiDoc diagnosis, returned as a
   * {@code consultation} SeedEnvelope. The twin of the readiness authority's assess — same
   * checkpoint, the consulting concern rather than the provisioning verdict.
   */
  @Override
  public SeedEnvelope consult(SeedEnvelope checkpoint) {
    final ReadinessCheckpoint decoded = codec.decode(checkpoint, ReadinessCheckpoint.class);
    final String scenarioId = decoded.scenarioId();
    final List<Observation> observations = observationsFrom(decoded);
    // Route on the first observation that carries a symptom (systemd has one; cluster has one per
    // phase, only the failing one is symptom-bearing). The record keeps ALL of them — the seam
    // loses no information.
    final Observation routed =
        observations.stream()
            .filter(o -> o.symptom().isPresent())
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException("checkpoint carries no symptom to consult"));
    final Symptom symptom = routed.symptom().get();
    final RemediationPlan plan = consult(symptom, routed);
    final ConsultationReport report = new ConsultationReport(scenarioId, observations, plan);
    final Instant recordedAt =
        decoded
            .recordedAt()
            .orElseThrow(
                () -> new IllegalArgumentException("consult checkpoint carries no recordedAt"));

    // The two reconstruction sub-trees cross opaquely to the host (which copies them verbatim into
    // its Pulumi outputs); OSGi rebuilds them via the codec (fromMap) on the read path. The codec
    // (toMap) renders each rich record to its opaque blob, then the whole Consultation record.
    final Consultation consultation =
        new Consultation(
            scenarioId,
            narrationLine(symptom),
            diagnosisBlock(plan),
            codec.toMap(report),
            report.expectations(recordedAt).stream()
                .map(codec::toMap)
                .map(Object.class::cast)
                .toList());
    return SeedEnvelope.of(DoctorCoordinate.CONSULTATION, codec.encode(consultation));
  }

  /**
   * Maps each captured {@link ObservationWire} to the doctor's own {@link Observation}: the wire's
   * typed {@code SymptomKind} slug resolves to a {@link Symptom} (the two share one kebab-case
   * vocabulary), the rest carries through. No doctor type crossed the seam — the mapping happens
   * here, OSGi-side.
   */
  private List<Observation> observationsFrom(ReadinessCheckpoint checkpoint) {
    final List<Observation> observations = new ArrayList<>();
    for (ObservationWire wire : checkpoint.observations()) {
      final Optional<Symptom> symptom = wire.symptom().flatMap(k -> Symptom.parse(k.slug()));
      observations.add(Observation.of(wire.status(), symptom, wire.summary(), wire.details()));
    }
    return observations;
  }

  /** Joins the two narration lines: consulted + cohort finding. */
  private String narrationLine(Symptom symptom) {
    return consultedLine(symptom) + " — " + cohortFinding(symptom);
  }

  /**
   * The Diagnosis (⚕ generalist summary) + Assessment (🔬 specialist reasoning) + Mitigation (℞
   * prescriptions) block, as AsciiDoc text. Each reply's assessment is always rendered; its
   * prescription (mitigation) is rendered only when present. Copied from RunbookRenderer.
   */
  private String diagnosisBlock(RemediationPlan plan) {
    final StringBuilder block = new StringBuilder();
    block.append("⚕ Diagnosis: ").append(plan.generalistSummary());
    for (ReferralReply reply : plan.replies()) {
      block
          .append("\n\n🔬 Assessment (")
          .append(reply.assessment().schemaRef().id())
          .append("): ")
          .append(reply.assessment().summary());
      if (reply.hasPrescription()) {
        block
            .append("\n\n℞ Mitigation (")
            .append(reply.prescription().get().programRef().id())
            .append("): ")
            .append(reply.prescription().get().humanHint());
      }
    }
    return block.toString();
  }

  /**
   * The patient consults: refer the symptom + observation to the routed specialists, collect each
   * specialist's {@link ReferralReply} (always an assessment, optionally a prescription), return a
   * remediation plan carrying the replies.
   */
  @Override
  public RemediationPlan consult(Symptom symptom, Observation observation) {
    final MedicalRecord record = access.record();
    firstLook(record, symptom, observation);
    final List<Specialty> route = routeBySymptom(symptom);
    if (route.isEmpty()) {
      return new RemediationPlan(
          symptom, List.of(), "no specialist routes for symptom " + symptom.id());
    }

    final Referral referral = Referral.of(record.patient(), symptom, observation, record);
    final List<ReferralReply> replies = new ArrayList<>();
    for (Specialist specialist : specialists) {
      if (route.contains(specialist.domain())) {
        replies.add(refer(specialist, referral));
      }
    }

    final long prescribed = replies.stream().filter(ReferralReply::hasPrescription).count();
    final String summary =
        replies.isEmpty()
            ? "consulted " + route + " for " + symptom.id() + "; no specialist replied"
            : replies.size()
                + " reply(ies) for "
                + symptom.id()
                + " from "
                + route
                + "; "
                + prescribed
                + " prescription(s)";
    return new RemediationPlan(symptom, replies, summary);
  }

  /**
   * Run a specialist's two acts and assemble the reply: it ALWAYS assesses, and prescribes only
   * when it has a treatment. The decision sits HERE, in the coordinator — the seam where the
   * efficacy-first gate will later interpose between the assessment and the prescription (consult
   * the history before authorizing a treatment). The specialist provides the two pieces; the
   * Generalist owns the reply shape.
   */
  private static ReferralReply refer(Specialist specialist, Referral referral) {
    final Assessment assessment = specialist.assess(referral);
    return specialist
        .prescribe(referral, assessment)
        .map(prescription -> ReferralReply.prescribing(referral, assessment, prescription))
        .orElseGet(() -> ReferralReply.assessing(referral, assessment));
  }

  /**
   * The follow-up coordination, the ONLY record-path verb on the seam — no-arg by design: the
   * patient comes from the held {@link ClinicalAccess} (so the record is rebuilt OSGi-side behind
   * the {@link MedicalRecordRegistry}) and the ledger from the held {@link
   * InterventionLedgerRegistry} (both Cellar-backed at their frontier). Nothing is read from a
   * caller and nothing crosses back: the host triggers {@code reviewDrift()} and the inferred drift
   * is persisted through the drift specialist. With no ledger registry wired the ledger is empty
   * (the no-backend degrade).
   */
  @Override
  public void reviewDrift() {
    final MedicalRecord record = access.record();
    final InterventionLedger ledger =
        ledgerRegistry.map(InterventionLedgerRegistry::ledger).orElseGet(InterventionLedger::empty);
    reviewOpenProblems(record, ledger);
  }

  /**
   * The follow-up coordination at synthesis: for each visit with a following visit, for each
   * Expectation on that visit whose predicate held at the next visit (the symptom resolved), review
   * the problem with the drift specialist and collect its letters. The ledger is rebuilt once by
   * {@link #reviewDrift()} and passed in. Bundle-internal (no longer on the seam — no record/ledger
   * crosses to the host); the doctor's own in-container tests drive it white-box.
   *
   * <p>"Resolved-but-unadministered" today is simply "resolved": no engine administers fixes yet,
   * so every resolved expectation is reviewed.
   */
  public List<ReferralReply> reviewOpenProblems(MedicalRecord record, InterventionLedger ledger) {
    final List<ReferralReply> letters = new ArrayList<>();
    final List<Visit> visits = record.visits();
    for (int i = 0; i + 1 < visits.size(); i++) {
      final Visit visit = visits.get(i);
      final Visit nextVisit = visits.get(i + 1);
      for (Expectation expectation : visit.expectations()) {
        if (expectation.predicate().heldAt(nextVisit)) {
          final ProblemReview review =
              new ProblemReview(expectation.problem(), expectation, visit, nextVisit, ledger);
          letters.add(driftSpecialist.review(review));
        }
      }
    }
    return letters;
  }

  private void firstLook(MedicalRecord record, Symptom symptom, Observation observation) {
    // record retrieved + available; step-2 reasoning attaches here
  }

  /**
   * Deterministic symptom → domain routing. A readable rules table, not inference: a symptom maps
   * to the domains whose specialists could treat it. Unknown symptoms route nowhere (empty plan).
   */
  private static List<Specialty> routeBySymptom(Symptom symptom) {
    return switch (symptom) {
      case CONNECTION_REFUSED -> List.of(Specialty.SYSTEMD, Specialty.NETWORK);
      case TIMEOUT -> List.of(Specialty.NETWORK);
      // Cluster-readiness symptoms are typed and named in the runbook from Increment D; no
      // specialist treats them yet, so they route to the CLUSTER domain and yield an empty plan
      // (symptom seen, no treatment offered) until a cluster specialist is added.
      case KUBECONFIG_MISSING, CONTROLLER_NOT_READY -> List.of(Specialty.CLUSTER);
      case API_NOT_READY -> List.of(Specialty.CLUSTER, Specialty.NETWORK);
      // A refused DHCP reservation is a network fault — routes to the NETWORK domain (no bbox
      // specialist treats it yet, so the plan is empty until one is added: symptom seen, no
      // treatment offered — the same shape as the cluster-readiness symptoms above).
      case RESERVATION_REFUSED -> List.of(Specialty.NETWORK);
      // Incus provisioning symptoms route to the INCUS domain (no incus specialist treats them yet,
      // so the plan is empty until one is added — symptom seen, no treatment offered).
      case IMAGE_BUILD_FAILED, INSTANCE_UNREACHABLE -> List.of(Specialty.INCUS);
      // A failed netplan blueprint export is a network-config fault — routes to the NETWORK domain
      // (no specialist treats it yet: symptom seen, empty plan).
      case BLUEPRINT_EXPORT_FAILED -> List.of(Specialty.NETWORK);
      // An incomplete manifest synthesis has no domain specialty (manifests is not a Specialty), so
      // it routes nowhere — the symptom is recorded, no treatment offered.
      case SYNTHESIS_INCOMPLETE -> List.of();
    };
  }
}
