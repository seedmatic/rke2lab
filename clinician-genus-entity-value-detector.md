---
name: clinician-genus-entity-value-detector
description: "The doctor vocabulary made into model (settled brainstorm 2026-06-25): Clinician is the GENUS (identity + an operation model); its species are diagnostician (Specialist: assess+prescribe) and remediator (administers). The entity-vs-value detector + the per-role port-exposure gradient."
metadata:
  node_type: memory
  type: project
---

A 2026-06-25 brainstorm (during the specialist-distribution increment) settled the doctor
vocabulary as MODEL, by challenging a naming reflex until the real axis surfaced. Builds on
[[doctor-remediation-model]] (the Remediator was already designed there) and
[[object-graph-navigability-principle]].

**Name by MEANING, never by Java role.** The `HealthSystem` is `HealthSystem`, not
`HealthSystemService`; `Patient` is `Patient`, not `PatientRecord`. A `Service`/`Record` suffix only
ever names a HALF of something, and "going object" means data + operations live together in one named
type. The interface/impl split (`HealthSystem` port iface ↔ `DefaultHealthSystem` `.internal` impl)
is NOT the data/ops split — it is the WORLD-BOUNDARY axis (contract crosses the OSGi seam,
system-exported; the object's data stays sealed bundle-side; `Default` is the codebase's existing
impl-disambiguation idiom, cf. `DefaultManifestSynthesisService`).

**The entity-vs-value detector (the corrected axis — NOT data/ops, NOT state/effect).** A first
wrong cut said "record = data-only, object = data+ops". `MedicalRecord` falsifies it: it is a
`record` SATURATED with operations (`efficacyOf`, `historyOf`, `comorbiditiesWith`) yet is plainly a
value. The axis that actually classifies:

- **Value** (record): identity BY VALUE (two equal `MedicalRecord`s are the same); its operations are
  pure SELF-PROJECTIONS — they reveal its own immutable content (a fold over its own fields).
  `MedicalRecord`, `InterventionLedger`, `Assessment`, `Prescription`, `Patient`, `Observation`,
  `RemediationPlan`.
- **Entity** (object): identity BY ID + a lifecycle; its operations are an OPERATION MODEL — a
  behavioral repertoire that PRODUCES or ACTS on collaborators, not a self-projection. `HealthSystem`
  (admits, employs), `Generalist` (consults, coordinates), `Remediator` (administers).

**Clinicians are entities BY CONSTRUCTION.** Statelessness is NOT the test — a stateless
`NetworkSpecialist` is still an entity, because `Clinician` carries a `clinicianId()` (identity by
id) AND each species implements its own operation model. The genus/species, made into the SPI:

| type | operation model |
|---|---|
| `Clinician` (genus) | `clinicianId()` — identity, the one universal |
| `Specialist` (diagnostician) | **assess** (always) + **prescribe** (conditional — a reply ALWAYS carries an Assessment, a Prescription only when there is a treatment; a decline is "assessed, did not prescribe") |
| `Remediator` | **administer** (a Prescription → outcome) — neither diagnoses nor prescribes; that is WHY "a clinician but not a doctor" |

**Per-role PORT-EXPOSURE GRADIENT (decided — not a blanket "clinicians are port services").** Who
crosses the seam is decided per role by who needs to call it:
- `HealthSystem` (+ the `DoctorConsultingService` it returns) — crosses NOW; the one door the boot
  pipeline uses (`awaitService(HealthSystem).admit(patient)`).
- diagnosticians (`Specialist`s) — stay OSGi-INTERNAL forever; the doctor's private roster, collected
  by DS `@Reference`; a pipeline no more names `NetworkSpecialist` than a patient names a radiologist.
- `Remediator` — surfaces on the port LATER, when the boot pipeline itself becomes OSGi orchestration
  ([[pipeline-orchestration-osgi-vision]]): administering is an orchestration STAGE action, driven on
  the operator's terms (loop-closure between visits, [[doctor-remediation-model]]).

**Two findings the detector surfaced (the "we may discover some missing / mis-folded" the user
predicted) — BOTH now resolved:**
1. `DriftSpecialist` has an operation model (`review`, and it WRITES to the ledger) but is NOT a
   `Clinician` today → a MISSING entity. RESOLVED in design (2026-06-26): reframed as the
   **médecin-conseil**, a PURE-READER efficacy analyst (drops the write → no longer a third
   write-bearing species; a system-employed reviewer tier). See
   [[medecin-conseil-efficacy-analyst-design]]. NOT yet built.
2. `Specialist.diagnose(Referral) → ReferralReply` FUSED assess+prescribe → RESOLVED + BUILT
   (2026-06-26, commit 750589db): split into `assess(Referral) → Assessment` (always) +
   `prescribe(Referral, Assessment) → Optional<Prescription>` (conditional); the Generalist assembles
   the reply. See [[multiplexor-two-models-design]] STEP 4.

**Backlog — Generalist visibility (a SEPARATE slice, not the specialist distribution).** The
Generalist IS a Clinician (carries `GENERALIST_ID`), but it does NOT distribute like a specialist:
it has no domain (it is the transversal coordinator, home = doctor-core) and is PER-RUN + stateful
(built by `DoctorGraph` with a patient-bound `ClinicalAccess` at each `admit(patient)`), so it cannot
be a DS singleton `@Component`. Its coherence as a "visible practitioner" rides on its IDENTITY, not
on OSGi distribution. If a practitioner directory/registry is ever wanted (who is employed in this
health system?), the Generalist must appear there beside the specialists — but that is its own slice,
decided then, not folded into the specialist DS distribution.

See [[multiplexor-two-models-design]] [[doctor-live-record-roadmap]] [[object-graph-navigability-principle]].
