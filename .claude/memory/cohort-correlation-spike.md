---
name: cohort-correlation-spike
description: "spike/doctor-cohort-correlation PROVED cross-patient medical-record correlation (Phase 1 unit + Phase 2 live preview). Verdict PROMOTABLE. User chose to PROMOTE to a real feature in a FRESH session. Branch parked clean, NOT merged. Resume here."
metadata: 
  node_type: memory
  type: project
  originSessionId: c3cdc9ef-2759-4a4c-91b6-06d10b0c9df6
---

A spike (2026-06-10, branch `spike/doctor-cohort-correlation`, off main; 3 commits, parked CLEAN,
NOT merged) proving the doctor can correlate ACROSS patients' medical records — the read half of the
HealthSystem north-star ([[healthsystem-access-control-model]]). Motivated by the user: cross-patient
correlation will be load-bearing in the future system, so prove the architecture handles it EARLY,
cheaply, before committing the design.

**PROVEN (both levels), verdict PROMOTABLE:**
- *Phase 1* (`LiveMedicalRecordRegistryCohortTest`, 104/0/0): seed 2 patients in a temp backend (dev
  raises connection-refused; peer had connection-refused + RESTART_UNIT, resolved). `cohortFor(dev)`
  folds both; `peer.efficacyOf(connection-refused).everWorked()==true` while dev's own ==false — the
  benefit comes ONLY from the sibling.
- *Phase 2* (real `pulumi preview`, `simulate: connection-refused` on dev): logged
  `⚕ consulted with 283 prior visit(s); connection-refused seen 0× before` then
  `⚕ cohort: connection-refused seen on 1 of 2 patient(s); 1 prior treatment(s) resolved it`. The
  per-patient view was BLIND (dev predates the doctor); the cohort view delivered the benefit. Lock-free
  under preview, degraded-mode provisioning unaffected. Seeded `peer` stack + simulate config TORN DOWN
  (no trace in the real backend).

**What the spike built (all `@Spike("spike/doctor-cohort-correlation")`-marked, greppable):**
- `cohortFor(Patient)` on the `MedicalRecordRegistry` INTERFACE (default = current patient's own
  record); `LiveMedicalRecordRegistry` overrides it to enumerate sibling stacks via
  `PulumiBackendLayout.stacksDir(backend, project)` → `recordFor` each. Registry read-core UNCHANGED.
- `Generalist.cohortFinding(symptom)` folds the cohort; `SystemdAdapterStage.consultDoctor` logs it.
- The repo's FIRST custom annotation `io.seedmatic.rke2lab.controlplane.meta.Spike` (SOURCE retention) —
  see [[hub:branch-namespaces]]. KEY STRUCTURAL LEARNING: the per-patient pure clinical methods
  (`historyOf`/`efficacyOf`) fold across patients FOR FREE — the model was shaped right.
- (An earlier standalone `CohortReader` was absorbed into the registry; a nullable backendDir on
  Generalist was rejected mid-flight by the user as no-incomplete-state violation → null-object
  interface-default seam instead.)

**NEXT = PROMOTE to a real `feature/` in a FRESH session (user's call, 2026-06-10).** Scope to
brainstorm→design→plan first (the north-star is validate-against-code, NOT a build-spec):
- Cut `feature/...` OFF THE SPIKE branch (carry the proven `cohortFor` seam; spike stays the immutable
  proof record). Strip `@Spike` on promotion.
- **THE OPEN CRUX surfaced at decision time:** the `(NPI, MRN)` grant is DERIVED FROM REFERRALS, but
  the referral round-trip (step 2, [[doctor-remediation-model]]) ISN'T BUILT. So gated-cohort may
  REQUIRE step 2 first (grant derivation needs referrals to exist), OR an interim grant source. Decide
  this in the brainstorm — the promotion may be bigger (step 2 + gating) than one chunk.
- Promotion adds (per the spike findings doc + north-star): the grant FILTER over the cohort,
  DE-IDENTIFIED findings (name no other patient), and a POLICY letting the finding drive behavior
  (today log-only).

**Artifacts:** spike design+findings = `wip/doctor-cohort-correlation-spike.adoc` (on the branch;
migrate to docs/ at promotion-merge per wip-guard). Context was large at this point — fresh session
chosen deliberately. main is UNTOUCHED by the spike.
