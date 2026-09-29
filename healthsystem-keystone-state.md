---
name: healthsystem-keystone-state
description: "HealthSystem keystone SHIPPED to main 2026-06-11 (squash ebd73906, pushed origin). Per-run keystone: holds registry + GrantPolicy, employs Generalist via credentialed ClinicalAccess, admits patient. Gated access-control + cohort correlation, interim grant rule. 121 tests green. feature + spike branches deleted. Referral-derived grants = the one deferred seam (step 2)."
metadata:
  node_type: memory
  type: project
---

The doctor's **HealthSystem keystone is SHIPPED** — promoted from matured design to working code and
merged to main.

**SHIPPED 2026-06-11**: squash commit **`ebd73906`** on `main`, pushed to origin (378a73f7→ebd739065).
`feature/healthsystem-keystone` and `spike/doctor-cohort-correlation` both DELETED (content integrated;
the spike's proof migrated into docs). 121 tests green (cache-off, surefire-counted). Executed via
subagent-driven-development (fresh implementer + 2-stage review per task); survived two mid-run window
reloads / an AWS token expiry — every task was committed so nothing was lost, and one subagent's
selective `git add` left an incoherent commit that was caught by an independent build check and fixed
by `--amend` (lesson: always verify the COMMIT compiles, not just the working tree).

**WHAT SHIPPED (the model):**
- `HealthSystem` (per-run keystone, `controlplane.bdd`): `admit(patient, registry, specialists, logger)`
  holds the `MedicalRecordRegistry` + a `GrantPolicy`, mints grants (self-grant + same-backend cohort),
  employs the Generalist with a credentialed `ClinicalAccess`, returns it via `generalist()`. The SINGLE
  construction site — killed the two duplicated `new Generalist(...)` + scattered `state.records`/
  `currentPatient` fields.
- `ClinicianId` (typed id record), `Clinician` (supertype over Generalist+Specialist, each declares an
  id; Specialist default = specialty kebab-cased), `Grant` (record `(ClinicianId, Patient)`),
  `GrantPolicy` (immutable, `withSelfGrant`/`withCohortGrant`/`isGranted`), `ClinicalAccess` (id CLOSED
  OVER, grant-checked `record()`/`record(Patient)`/`cohort()`, degrade-to-empty never throws),
  `ConsultationNarration` (de-duped proof-of-wire line).
- `Generalist` now holds ONLY a `ClinicalAccess` (Approach A strict — no registry/patient). `cohortFor`
  promoted off `@Spike`; the `meta/Spike.java` annotation DELETED. Dead `reportingReadinessTo` (a
  pulumiMode bug that was actually dead code) DELETED.
- The gate is REAL and enforced: negative tests prove a sibling not granted is excluded from the cohort.
  It barely bites the self-read (always self-granted); it genuinely bites the COHORT — gating and
  cohort-correlation are the same layer.

**DOCS (in docs/architecture/doctor/):** `healthsystem-access-control-design.adoc` (matured→implemented),
`integration-atlas.adoc` (additivity held in code — flipped ✅, before/after figures kept frozen as the
design-time proof), `cohort-correlation-proof.adoc` (the spike's Phase 1+2 evidence, redistributed out
of `wip/`), `glossary.adoc` + `docs/README.adoc` reconciled. Vocabulary stayed developer-level
(ClinicianId/Patient/grant; NPI/MRN/EHR jargon dropped).

**THE ONE DEFERRED SEAM (step 2):** referral-derived grants. Today the cohort grant is minted from an
interim same-backend rule; the north-star is grants DERIVED from referrals recorded in the records. The
`GrantPolicy` is the exact swap-in point — referral-derivation plugs in there without touching the
registry, the clinicians, or `ClinicalAccess`. This is the atlas's one amber "not-yet-proven" edge. See
[[doctor-live-record-roadmap]] step 2 (remediation/Referral round-trip, real driver = systemd-adapter
degraded healing, [[doctor-remediation-model]]).

**NEXT TOPIC = undecided** — step 2 (Referral) is the natural successor and would prove the deferred
grant seam. Conventions reinforced this chantier: [[hub:superpowers-assets-in-wip]] (plans/specs in wip/
not docs/), and verify the COMMIT builds (not just the tree) when a subagent dies mid-task.
