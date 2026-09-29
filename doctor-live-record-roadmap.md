---
name: doctor-live-record-roadmap
description: "Doctor roadmap (3-step chain). STEP 1 SHIPPED to main 2026-06-10 (squash df41a3be): clinical-vocabulary refactor + live MedicalRecord wired into the doctor (latent/wire-only). Steps 2 (remediation round-trip) & 3 (what-if/correlation) NOT started. NEXT TOPIC DELIBERATELY UNDECIDED — user picks it in a fresh session with the shipped code in hand."
metadata:
  node_type: memory
  type: project
  originSessionId: 4d3d8a2e-f292-4cbe-a699-fb4abfbd1e6c
---

The 3-step chain off the medical-record query API ([[medical-record-impl-complete]]).

**STEP 1 — SHIPPED to main 2026-06-10 (squash `df41a3be`, pushed; rebased onto origin's
`b3e01bfd`, the local duplicate `ab9c4d65` dropped; feature branch deleted).** Two chantiers:
- *Clinical-vocabulary refactor* (atomic, no-compat): the code named the patient-presented probe
  snapshot `Dossier` — French, and the INVERTED concept (clinical "dossier" = the patient FILE =
  `MedicalRecord`). Renamed to US clinical terms: **Dossier→Observation** (+ Pulumi output key
  `dossiers`→`observations`, both writer & reader), **SpecialistDomain→Specialty**,
  **Complaint→ChiefComplaint** (`currentComplaint`→`chiefComplaint`),
  **SymptomCorrelation→Comorbidity** (`correlatedWith`→`comorbiditiesWith`), and the misnomer fix
  **DiagnosisReader→ConsultationReportReader** (frees the word `Diagnosis`). Added
  `docs/architecture/doctor/glossary.adoc` (vocabulary source of truth); froze the dated
  exploration docs with a glossary pointer.
- *Live-record wiring* (latent/wire-only): a `MedicalRecordRegistry` seam the `Generalist` HOLDS +
  `LiveMedicalRecordRegistry` (lazy, file-backend-bound, memoized, degrade-to-empty, NEVER throws).
  The Generalist holds `(registry, currentPatient)` and retrieves the record as the FIRST act of
  `consult` — available but NOT yet driving routing (the step-2 seam). `BootstrapPipeline` derives
  the patient from `Deployment` (guarded) + builds the registry once per run; the two readiness
  stages log a proof-of-wire line. `consult(Symptom, Observation)` signature UNCHANGED. Verified
  103 tests green; reconstruction confirmed vs real dev state (283 checkpoints, reports empty = dev
  predates the doctor). Design+plan: `docs/architecture/doctor/vocabulary-and-live-record-{design,
  plan}.adoc`.

**STEP 2 — remediation round-trip (NOT started).** [[doctor-remediation-model]] +
`runbook-doctor.adoc` `[#consultation-flow]`: `diagnose(Referral) → ReferralReply`, the Remediator
tier, recruit-a-specialist, and the SOAP "Assessment" gap (a specialist with findings but no
prescription is silent today). REAL DRIVER waiting: the `systemd-adapter degraded` healing
([[master-provisioning-state]]). The `Referral` it introduces is the seam the step-3 access-control
layer rides on.

**STEP 3 — what-if planner + cross-patient correlation (NOT started).** [[preview-whatif-topic]]
inner loop over the step-2 round-trips. Cross-patient correlation = the HealthSystem layer below.

**★ THE HEALTHSYSTEM NORTH-STAR (designed 2026-06-10, NOT built — see
[[healthsystem-access-control-model]]).** A long brainstorm grew the access-control + correlation
layer: a `HealthSystem` entity owns the EHR (= the registry), employs providers by stable **NPI**
(open roster), admits patients by **MRN**; access gated by a `(NPI, MRN)` grant table (grants
DERIVED from referrals, not stored); cross-patient correlation consent-bounded to a provider's
granted cohort, emitting de-identified findings only. CRUX: shared by IDENTITY (same code, same
NPIs), NOT by a shared stack — nothing cross-cutting persisted, all derived per run over the shared
backend (the single-source principle that retired the accumulator). Captured as a NORTH-STAR
(validate-against-code, not a build-spec) at
`docs/architecture/doctor/healthsystem-access-control-design.adoc`. The layer is ADDITIVE — it wraps
the shipped `recordFor(Patient)` with `recordFor(Referral)` + a grant check — so step 1 needed NO
rework and step 2/3 grow on top.

**NEXT TOPIC = DELIBERATELY UNDECIDED (user's call, 2026-06-10).** Foundation-first discipline:
step 1 is the tested foundation; decide the next chantier in a FRESH session with the working code
in hand ([[hub:works-best-from-concrete-code]]). My standing advice when resumed: step 2 (remediation/
Referral) has the real driver and is the natural precursor to the HealthSystem gating — do NOT jump
straight to HealthSystem (over-investment for a population of one). Adjacent unrelated chantiers if
the user pivots: [[seed-vcluster]], [[config-restructuring-state]] Increment 2.

**Carry-over backlog (non-blocking, from the final review):** (a)
`BootstrapPipeline.reportingReadinessTo` doesn't set `pulumiMode` → derives the placeholder patient
even under engine (pre-existing; harmless while record is latent; revisit when it drives behavior);
(b) the ~12-line proof-of-wire log block is duplicated in both stages (below rule-of-three;
consolidate at a 3rd consult site); (c) `ConsultationReportReader` javadoc still says "diagnosis" in
prose (cosmetic); (d) 2 pre-existing Dependabot vulns on the repo (1 critical/1 moderate, NOT from
this work).
