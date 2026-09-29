---
name: referral-roundtrip-state
description: "★ SHIPPED to main 2026-06-13 (squash 59d04f66 + chore-memory). diagnose(Referral)→ReferralReply makes the doctor LOQUACIOUS in failure — a specialist ALWAYS returns an Assessment (the why), prescription or not. 4 new types + 2 fake exemplar specialists; Assessment persists through layer-3 + renders distinctly in the runbook. 150 tests green. feature branch deleted (wip spec+plan lived only there). NEXT TOPIC undecided."
metadata:
  node_type: memory
  type: project
  originSessionId: a1f0fd81-d8f4-478e-8043-510f2093c00b
---

The chantier AFTER the shipped HealthSystem keystone ([[healthsystem-keystone-state]]). **SHIPPED to
main 2026-06-13** as a squash (`59d04f66` feat + a chore-memory commit), feature branch
`feature/referral-roundtrip` deleted. Executed subagent-driven (9 tasks, fresh implementer +
two-stage review per task); fix passes on Tasks 1/3/5/7/8 (all real findings — trim invariant,
requireNonNull, dead-code removal ×2, assertion hardening). Final whole-feature review: zero must-fix,
3 trivial backlog nits. Build verified on committed HEAD: **150 tests, 0 failures**.

**WHAT SHIPPED (the gap it closed):** before, a specialist with nothing to prescribe returned
`Optional.empty()` and its reasoning VANISHED (3 mute dead-ends). Now the seam is
`diagnose(Referral) → ReferralReply`; a reply ALWAYS carries an `Assessment` (SOAP 'A', the why) and
OPTIONALLY a `Prescription`. A declining specialist speaks its reasoning.

**TYPES (controlplane.bdd):** `SchemaRef` (open self-declared coordinate, e.g.
"dbus-tcp/connection-refused/v1" — NOT an enum, NO registry, round-tripped verbatim), `Assessment`
(schemaRef + open payload + summary; mirrors Prescription's typed-envelope), `Referral` (transient
request: patient/symptom/observation + a read-only MedicalRecord ref — seam for a future
history-aware specialist; driver doesn't read it yet; never serialized), `ReferralReply` (referral is
`Optional` — present live via assessing()/prescribing(), EMPTY on read-back via reconstructed(); no
synthetic Referral fabricated).

**CHANGED:** `RemediationPlan` carries `List<ReferralReply>`; `prescriptions()` is a DERIVED view
(Visit/MedicalRecord/renderer unchanged). `Prescription.humanHint` SHRANK to action-only (reasoning
moved into Assessment). Persistence: toOutputMap emits `replies` (top-level `prescriptions` key gone);
ConsultationReportReader reconstructs, dropping a reply only when its why is unparseable. Seam +
payload + persistence were ONE atomic commit (a reply always has an Assessment → reader can't rebuild
from a prescriptions-only map).

**THE PAYOFF — runbook shows BOTH seams** (the user's reframe: building this chunk IS what makes the
runbook realistic): a treated reply renders `🔬 Assessment` + `℞ Mitigation` (→ HEALER seam); a
declined reply renders `🔬 Assessment` with no Mitigation (→ RECRUIT seam). Two FAKE exemplar
specialists (`NetworkSpecialist`, `ClusterSpecialist`) populate `Generalist.consult`'s EXISTING
fan-out (NOT the agenda-loop) so the driver symptom CONNECTION_REFUSED shows DbusTcp prescribing
alongside Network declining-with-a-why. The fakes are exemplars for the real specialists later, NOT
throwaway.

**DOCS:** glossary resolved the reserved `Diagnosis` entry → `ReferralReply` (Diagnosis-as-noun
DROPPED as ambiguous; no Diagnosis type), `Assessment` moved reserved→built, added Referral+SchemaRef.
Integration atlas PROMOTED to transverse top-level `docs/architecture/integration-atlas.adoc` (per-
subsystem index, doctor = first view) + additivity proof for this chunk.

**STILL DEFERRED (enabled, not built):** the agenda loop / multi-specialist synthesis; the
Remediator/healer tier (imperative driver already served by the rendered humanHint); referral→grant
(the keystone's amber edge — [[healthsystem-keystone-state]]); resolving/validating the Assessment
payload against its schemaRef (no consumer yet — `recruit-a-specialist` is the natural one, and the
`🔬 Assessment`-without-`℞` line is now its visible trigger). MODULE REORG / responsibility re-carving
explicitly deferred until the model stabilises (user: "chacun à sa place" later).

**NEXT TOPIC undecided.** Natural successors: recruit-a-specialist (consume an Assessment-without-
prescription), or a real Remediator tier, or the module reorg. [[doctor-live-record-roadmap]]
[[hub:works-best-from-concrete-code]] [[hub:docs-diagrams-not-java]].
