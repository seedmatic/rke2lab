---
name: healthsystem-access-control-model
description: "The doctor's access-control + correlation layer (step 2/3), designed 2026-06-10 as a NORTH-STAR (validate-against-code, NOT a build-spec). HealthSystem owns the EHR, employs providers by NPI, admits patients by MRN; gated by a (NPI,MRN) grant table; cross-patient correlation consent-bounded to a granted cohort. Shared by IDENTITY not by a shared stack. NOT built."
metadata:
  node_type: memory
  type: project
  originSessionId: 4d3d8a2e-f292-4cbe-a699-fb4abfbd1e6c
---

A long 2026-06-10 brainstorm (paused step-1 execution to design it) grew the doctor's
**access-control + correlation layer**. Captured as a NORTH-STAR at
`docs/architecture/doctor/healthsystem-access-control-design.adoc` — a vision to validate against
working code, NOT a build-spec. NONE of it is built; step 1 shipped wire-only
([[doctor-live-record-roadmap]]). It is ADDITIVE: wraps the shipped `recordFor(Patient)` with
`recordFor(Referral)` + a grant check, so nothing shipped needs rework.

**How the model was reached (the chain of corrections — each from the user):**
1. "all prescribers hold a reference to the registry" → not just the Generalist; every provider
   (Generalist + Specialists) holds the EHR.
2. "access only when visited" + "use info but emit only deductions" → confidentiality layer: a
   provider reads a file only when the patient was referred to it; it emits conclusions, never
   copies one patient's specifics into another's file.
3. "consult during a visit all the patients he already got authorization for in the past" →
   authorization ACCUMULATES; the granted set is the provider's correlatable cohort
   (epidemiology-by-consent).
4. "where does cross-patient data live, given per-stack state?" → the BRIDGE question.
5. "shared between stacks by IDENTITIES, no shared stack" → the resolution.
6. "define the clinic entity, crystal clear" → the keystone; user chose **HealthSystem** (US: the
   umbrella that operates the shared EHR), flat tier (no Clinic sub-tier yet).

**THE MODEL (settled vocabulary — real US healthcare terms):**
- **HealthSystem** owns the EHR, employs providers, admits patients. Per-run CODE, not a stack.
- **EHR** = the `MedicalRecordRegistry`; reconstructs files on demand, holds nothing.
- **NPI** = a provider's stable self-declared id (kebab, like `treats()`/`programRef`); the JOIN KEY
  for grants + cohort; open roster (recruited providers get fresh NPIs — a closed enum can't
  recruit).
- **MRN** = the patient's id = the stack identity (`Patient` org/project/stack).
- **grant** = right of an NPI to read an MRN's file, keyed `(NPI, MRN)`; **DERIVED from referrals**
  already in the files, never stored centrally.
- **Referral** = the request that mints a grant (builds on the existing `runbook-doctor.adoc`
  `[#consultation-flow]` referral round-trip — this layer makes it the ACCESS KEY).
- **granted cohort** = the MRNs an NPI may correlate across; findings emitted DE-IDENTIFIED into the
  current patient's file only (aggregate "seen across cohort", no other MRN named).
- Admission = the visit: the HealthSystem mints the Generalist's self-grant on admission; routing to
  a Specialist mints its `(NPI,MRN)` grant. `consult`/`diagnose` signatures unchanged (providers
  hold the EHR + their NPI; MRN comes from admission).

**THE CRUX — no shared stack (the bridge):** the HealthSystem is shared by IDENTITY (same code, same
NPIs in every run), NOT by a store. So nothing cross-cutting is persisted: patient files folded from
each stack's own history; grants derived from referrals in the files; cohort enumerated by scanning
sibling stacks in the SHARED BACKEND (lock-free — reconstruction reads don't take the stack lock);
NPIs self-declared by code. Same single-source principle that retired the accumulator
([[medical-record-query-api-state]] pivot). VERIFIED FACTS behind this: StackReference reads
top-level outputs only (per-node returns null — proven by the sref-producer/consumer/sandbox-selfread
probe stacks); a dedicated EHR/clinic stack written via Automation API exists as the HEAVY,
LOCK-BOUND fallback only — demoted, not the design. **BOUNDARY:** holds only while all patients share
ONE backend; multi-backend would force a real shared store (noted, not designed).

**Reserved step-2 vocabulary (in the glossary + design):** `Diagnosis` = the intended
`Specialist.diagnose(...)` return carrying an **Assessment** (the SOAP "A", always present — the
specialist always speaks) + an optional `Prescription`. The `MedicalRecord` IS the shared
communication channel between practitioners. The freed word `Diagnosis` is why
DiagnosisReader→ConsultationReportReader was renamed in step 1.

**When to build:** when a REAL driver appears — a second patient, an actual correlation need, or the
remediation exercise — and let that code correct this model. Do NOT build ahead (over-investment for
a population of one). Step 2 (remediation/Referral) comes first; this gating layer grows on its
Referral seam.
