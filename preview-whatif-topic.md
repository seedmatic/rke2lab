---
name: preview-whatif-topic
description: PARKED exploration (2026-06-07, 2 sessions) — preview as counterfactual BDD replay (what-if). Session 2 DISSOLVED the placement blocker (re-probe live in preview → nothing to read back) and grew it into a closed-loop diagnostic planner. Full capture in docs/architecture/doctor/preview-whatif-replay-exploration.adoc. Resume cold.
metadata: 
  node_type: memory
  type: project
  originSessionId: 6d3faadb-1da7-486c-9310-99b6dd4c49b5
---

PARKED exploration, captured cold in `rke2lab/docs/architecture/doctor/preview-whatif-replay-exploration.adoc` (indexed in
`docs/README.adoc`). Likely lands on `feature/runbook-doctor`. EXPLORATION ONLY — no spec, no plan, no
code. Two sessions on 2026-06-07; **session 2 overturned session 1's blocker** — read the wip doc, it
is fully rewritten.

**The idea (the user's, crystallized):** `pulumi preview` already *replays the BDD logic* to build the
next state. Make Given/When/Then feed on a fact base instead of deferring. Sharp case "dbus degraded →
restart prescribed": replay in dry-run with the fact base UPDATED by the prescription's expected result
("dbus operational") and let the BDD propagate along the `dependsOn` edge to show what the remediation
unblocks downstream. A **counterfactual replay (what-if)**, NOT a reconstruction (reconstruction is
SUBSUMED = zero-hypothesis case). Makes a Prescription testable BEFORE administration → the missing link
of the Remediator tier ([[doctor-remediation-model]]).

**TWO DISTINCT read-back needs — do NOT conflate (session-3 correction of a session-2 over-generalization).**
(1) The WHAT-IF's OWN fact base (observed half): DISSOLVED — preview **re-probes LIVE** (probes are reads,
no side effects) so the observation is fresh in-memory same run; diagnosis+prescription produced in the
same run. Nothing read back from state; StackReference moot FOR THIS. (Grounded: observed half is anyway
already top-level via systemdAdapterLaunchSummary/ReadinessOutputMapper; dev state verified EMPTY = only
previews since doctor branch, nothing persisted.) Cost: preview now depends on master reachability (defer
= honest fallback). (2) The PATIENT RECORD (longitudinal): NOT dissolved — it = ALL consultations a patient
had, ALL practitioners, ALL time → inherently a read-back of PERSISTED PAST consultations, which re-probe-live
does NOT give. **This is an ESSENTIAL element of the system, must be put in place.**

**THE MECHANISM (session-3 finding — we had MISSED it): StackReference ≠ Automation API.**
(a) `StackReference` (inside a Pulumi program / inception): reads TOP-LEVEL outputs ONLY (the ctx.export map;
`StackReferenceArgs`={name}, `getOutput(String)`; NO API to a child resource). **PROVEN BY A REAL TEST**
(throwaway Java Pulumi program, file backend /tmp, master untouched): a program does `registerOutputs(consultationReport=…)`
with NO ctx.export → `pulumi stack export` SHOWS the per-node consultationReport under the node (Stack has no
top-level outputs); then a SELF-StackReference under `pulumi preview` returns `selfRef_topLevelKeys=[]` and
`output("consultationReport")="<null>"`. ⇒ registerOutputs persists per-node WITHOUT ctx.export, but StackReference
is STRICTLY top-level and CANNOT reach per-node (returns null). The read DID resolve in preview (StackReference-in-preview
works). THIS was the whole "placement blocker".
(b) **Automation API** (`com.pulumi.automation.LocalWorkspace`; drives Pulumi from OUTSIDE):
`exportStack(String) : StackDeployment`; `StackDeployment.deployment() : Map<String,Object>` = the FULL state
(every resources[] entry + per-node outputs incl. consultationReport) + history. exportStack internally runs
`pulumi stack export --show-secrets` then fromJson. ⇒ **the per-node recadrage is NOT a dead end — fully
READABLE via exportStack(), just not via StackReference.** Caveat: StackDeployment.deployment() is OPAQUE ("no
strongly typed model yet") → navigate the raw Map by hand.

**VERIFIED ON THE REAL JAR (not deduced — corrects 2 earlier wrong calls).** BOM is now on `com.pulumi:pulumi`
**1.28.0** (bom/pom.xml); the m2 jar CONTAINS `com/pulumi/automation/LocalWorkspace.class` + StackDeployment +
LocalWorkspaceOptions + LocalPulumiCommand. javap confirms every signature the solution needs:
`createOrSelectStack(String, Path[, LocalWorkspaceOptions])` (local-source variant), `exportStack(String)`,
`getConfig`/`setConfig`/`removeConfig` (full save→override→restore trio incl. absent-key case), `getStackOutputs`
(top-level only), and the inline `Consumer<Context>` overloads (the rejected inception path). (Automation API
was already present in 1.0.0 too — user-confirmed; earlier javap miss was a too-narrow scan.)

**★ THE SOLUTION (FINAL — the self-referential medical-record ACCUMULATOR).** Simpler than every
intermediate option (two-jar wrapper / FIFO handoff / inception — ALL SUPERSEDED). The patient record is
carried forward in a TOP-LEVEL stack output: `ctx.export("medicalRecord", previous ⊕ this-run)`. Each run:
(1) RECOVER the previous record via a self-`StackReference.output("medicalRecord")` — native engine Read,
OFF-LOCK, resolves in preview AND up (proven); (2) PRE-INITIALIZE the new output with it (user's words:
"pre-initialize the output with the previous version and we've won"); (3) APPEND this run's consultations
(in-memory ConsultationLog) + amend on every new prescription, SUMMARIZE the old (retention = append+summary,
user's choice — also feeds the Drools specialist's case-history); (4) RE-EXPORT — same key is BOTH ends of
the cycle (next run reads it). WHY it beats the wrapper: lock GONE (native StackReference Read, not a shelled
exportStack under the run's own lock); mono-process, seed-master only, NO wrapper/FIFO/config-mode. Diff at
each up is REAL (a genuine new visit) not the phantom-diff OutputBuilder avoids; no-symptom run = no change.

**TOP-LEVEL IS MANDATORY for this path — proven, not deduced.** Test 2 (preview): a self-StackReference
returns `<null>` for a per-node output + `[]` top-level keys. So the accumulator MUST `ctx.export` to
top-level; the per-node consultationReport (shipped recadrage) stays UNTOUCHED as the runbook read-side
source — the top-level medicalRecord is ADDITIVE, a different role. Placement RESOLVED BY DESIGN (export
on purpose), not renegotiated.

**WORKING STATE vs ARCHIVE — flush at the end (user refinement).** In-flight consultations of THIS run =
working state (in-memory ConsultationLog + parallel per-node consultationReport, NOT exported). The
archive (top-level medicalRecord) is written ONCE at the end by FLUSHING the run's log into it. Not just
cleaner — the ONLY shape Pulumi allows: ctx.export is DECLARATIVE (one final value, no incremental
append), so "accumulate in the export" is mechanically impossible → collect in memory, flush once. Flush
reads from in-memory log ⊕ previous record (self-StackReference), NEVER from the program's own per-node (a
program cannot read its own per-node mid-run — proven `<null>`); per-node stays a parallel mirror
(runbook + diff). The flush is the CURATION point (append + summarize applied here). Natural home = the
caller-owned `finally` where RunbookRenderer already runs.

**Wrapper / exportStack = DEMOTED to a recovery script** (rebuild medicalRecord from per-node state +
getHistory if the top-level output is ever lost). Verified available in 1.28.0 (`com.pulumi.automation.LocalWorkspace`,
createOrSelectStack/exportStack/getConfig/setConfig/removeConfig — javap on real jar) but NOT the normal path.
Adjacent to [[runbook-doctor-state]] pulumi-doctor-integration. Grounded: file backend keeps 5 historical
checkpoints but they predate layer-3 (no outputs) — a real `up` must run for consultations to land.

**The what-if is the MIRROR of `simulate`.** simulate CLOSES a gate (inject upstream failure → downstream
SKIPPED, ships today); what-if OPENS a gate (inject the prescription's expected success-state → downstream
WALKED). User's framing: don't *predict* downstream — reopen the gate and let dry-run WALK the path it
already walks, see where it leads. Reachability is what dry-run naturally yields (walks structure, doesn't
probe results) — honest by construction. The seam exists: ClusterReadinessStage's `nestedSystemdAdapterProbe`
+ the `the_systemd_adapter_dependency_is_satisfied` gate step.

**KEYSTONE — generalist as a closed-loop PLANNER (not a one-shot router).** Specialist addresses its
prescription TO the generalist; generalist SIMULATES the remediation (opens the gate) → unmasks new
downstream symptoms → re-consults the other specialists → complete multi-step plan. Two loops: INNER
(diagnostic, one preview, in memory, zero side effects: observe→consult→prescription+expected-state→
simulate→unmask→re-consult) and OUTER (execution across visits, already in spec.adoc: plan→operator
administers→next up re-observes real). `expected-state` on Prescription is the hinge that makes a
prescription simulable. Terminates because the graph is a DAG (single topological sweep, bounded).

**SWEEP semantics (user-fixed):** on a real failure, re-probing its DEPENDENTS is pointless (they fail
because of the root) → switch the DOWNSTREAM SUBTREE to dry-run (reachability only). BUT independent
SIBLINGS (other branches) are still PROBED FOR REAL in the same pass — "if we left other problems lying
around, they still get evaluated". This = the semantics of a real `pulumi up` (a failure blocks
descendants, never siblings). "Reachable FROM the failing node" = its descendants, NOT all remaining
nodes. One pass over a branched DAG → COMPLETE diagnosis (fault roots + prescriptions, descendants
marked reachable-if-fixed, independent branches fully diagnosed).

**Specialist DECLARES what it treats (inverted routing — SINGLE AUTHORITY, user-clarified 2026-06-07).**
Today the symptom→specialist knowledge is DUPLICATED: Generalist.routeBySymptom `switch` AND each
specialist's guard (DbusTcpSpecialist `if symptom != X`). Invert: the *domain* says which symptoms a
specialist of that domain CAN treat = it SEEDS a default repertoire the practitioner INHERITS; but the
practitioner is the SOLE AUTHORITY that PROVIDES its own effective `treats()` (adds/removes). The domain
is the seed, NEVER a 2nd source that co-owns the answer — NOT "flat" (drops the default), NOT
"computed two-source" (re-creates the switch's sync). One owner. (This corrected an earlier "computed
baseline ⊕ add ⊖ remove" framing; aligned with rules-engine doc.) Refines recruitment: symptom no domain
CAN treat = specialty gap (recruit a domain); domain-can-but-all-removed = practitioner gap (assign
another). Delete Generalist.routeBySymptom.

**THE ONE REAL TENSION NOW = DAG-as-data.** The planner needs to WALK topology as data (descendant vs
sibling? orchestrate the sweep, open gates). Today topology lives in the ComponentResources' `dependsOn`
(single source since 13bb7715) but checkpoints play in a HARD-CODED order (SystemdAdapterStage then
ClusterReadinessStage) — linear order == topological order only by accident (2 linear nodes:
SYSTEMD_ADAPTER→CLUSTER_READINESS). This is THE real architecture work (where Pulumi-placement was a
non-problem). Rejoins [[domain-registry-abstraction]].

**Terminology pinned:** `Dossier` = reason-for-consultation / observations (NOT the patient record);
`ConsultationReport` = one visit (dossiers + diagnosis + prescriptions); patient record = longitudinal
whole (observed ⊕ diagnosed ⊕ prescribed), materialized by the runbook. User: "a patient record must
include the prescriptions made to it AND WHY".

**Next when picked up (NOT now):** (1) decide DAG-as-data shape (the real first move; placement is gone);
(2) prototype the mirror-of-simulate gate-opening on the existing systemd→cluster edge; (3) prototype
`Specialist.treats()` (baseline⊕override) + delete routeBySymptom; (4) only then spec→plan. Open: render
COUNTERFACTUAL clearly (not a guarantee); expected-state shape (bool vs rich Dossier); inner-loop
termination on cyclic/diamond graphs. Relates to [[runbook-doctor-state]] and [[doctor-remediation-model]].
