---
name: medical-record-query-api-state
description: "State of the medical-record query-API chantier (feature/medical-record-accumulator) — design + plan DONE & committed, implementation NOT started. Resume cold here."
metadata: 
  node_type: memory
  type: project
  originSessionId: 4d3d8a2e-f292-4cbe-a699-fb4abfbd1e6c
---

Chantier on branch `feature/medical-record-accumulator`. Brainstorm + design + implementation plan
are DONE and COMMITTED; **no implementation code written yet**. This SUPERSEDES the accumulator idea
in [[preview-whatif-topic]] (that top-level self-referential accumulator is ABANDONED).

**THE PIVOT (why the accumulator died):** the user felt the accumulator "was bizarre" — it
duplicated, by hand, history Pulumi already keeps. Reframed: the medical record is **reconstructed
on demand from Pulumi (the single source)**, nothing extra exported. It is a **query API** answering
four practitioner questions, not a stored blob.

**PROVEN by a real sandbox (`wip/sandbox/`, committed):** a Pulumi Java program, DURING its own
`up` (engine holds the lock), reads its OWN state+history via the Automation API — LOCK-FREE
(verified in pkg/backend/diy: ExportDeployment/GetHistory take no lock; lock = concurrency only).
`getHistory` grows 0→1→2 across runs; a per-node `consultationReport` written via `registerOutputs`
is read back from `exportStack()` the next run, by the right key/content. Run via
`flox activate -- wip/sandbox/run.sh` (file backend in .sb-state, JDK25 forced via JAVA_HOME).

**Key Pulumi facts (verified, javap + Go source):** program NEVER reads state (engine does; Context
only has name/config/log/export). `exportStack()` = current state ONLY. `getHistory()` = metadata
spine only. `snapshotForVersion`/`stack export --version` = the optional `SpecificDeploymentExporter`
capability — cloud has it, **file backend REFUSES it**, Automation-API-Java has NO versioned export.
BUT the file backend WRITES a full `.checkpoint.json` per past version under
`.pulumi/history/<project>/<stack>/<stack>-<n>.checkpoint.json` (proven on disk) → we parse those in
Java (Jackson). Self-hosted Pulumi service backend = commercial (Business Critical), rejected. Go
fork rejected (disproportionate).

**ARCHITECTURE (3 layers) — REWORKED 2026-06-08, supersedes the interface/Deployment sketch above:**
(1) NEW top-level module `pulumi-automation-ext` (NOT under sdks/). groupId `io.seedmatic.rke2lab`,
artifactId `pulumi-automation-ext`, package `io.seedmatic.rke2lab.pulumi.automation`. Anchor =
`LocalWorkspace.createOrSelectStack`. Classes: **StackHandle**, **StackSnapshot** (delegates to
`com.pulumi.automation.StackDeployment`, adds `outputsNamed`), **StackHistory + nested Entry**,
**StackCheckpoint** (the file-backend-coupled class). (2) Neutral doctor data: **`MedicalRecord` is an
immutable RECORD, NOT an interface** (`record MedicalRecord(Patient, List<Visit>)`) — the 4 clinical
questions are PURE METHODS on it (currentComplaint/historyOf/efficacyOf/correlatedWith), testable with
synthetic Visits, no Pulumi, no mock. + Patient + Visit + 4 view records. (3) **`MedicalRecordReader`**
(concrete, eager, NO cache) folds the timeline → MedicalRecord; `SnapshotSource` seam (the ONE real
test seam) + StackHandleSnapshotSource. Offline = **`MedicalRecordDump`** (full record → YAML for
operator+Claude), NOT a multi-query CLI. In-run consumer OUT OF SCOPE (treats() topic).

**FOUR KEY DECISIONS this session (all verified, in the plan):**
- *Delegation, not extension* — `StackDeployment` is `final` (javap) so can't extend, BUT `fromJson(String)`
  is PUBLIC → StackCheckpoint SYNTHESIZES the instance the file backend refuses to export (reshape
  `{version, checkpoint.latest}`→`{version, deployment}`, hand to fromJson; Pulumi parses the graph, not us).
- *Naming: `Stack*` prefix on the technical/peripheral side* (mirrors Pulumi's own StackDeployment/
  StackSummary) → keeps the central doctor domain bare (Checkpoint enum, Symptom, Generalist). Prefix
  protects the core, doesn't infect it.
- *Lookup currency = `StackHistory.Entry`, NEVER a bare int* — TWO "version" meanings (deployment SCHEMA
  version `3`=DeploymentV3 in the envelope, vs UPDATE sequence number in .history.json = what the doctor
  means). StackSnapshot exposes NO version(); the update number lives only on Entry. Files are named by
  TIMESTAMP not <stack>-<n> (verified on disk); Entry carries its checkpoint file → checkpointOf(entry).
- *Additive-record guarantee* — `ConsultationReport.toOutputMap()` is an open key-bag, no schema number;
  record "version" = which keys present. DiagnosisReader MUST be tolerant (read-by-key, ignore unknown,
  absent=empty), locked by a Task-10 test. So the medical record grows per-feature with NO migration.

**The medical record is a DIRECTED GRAPH of immutable records** — a tree by containment today
(MedicalRecord→Visit→ConsultationReport→{Dossier, RemediationPlan→Prescription}); gains cross-node edges
where spec says "references, not copies". The 4 questions reduce to pure folds over `visits`. Cross-node
edges serialize by id (checkpoint.slug()), derived views (correlatedWith/efficacyOf) computed not stored;
YAML aliasing (&/*) is the cycle safety-net. BOM bumped to released 1.0.0 (installed to ~/.m2).

**THE COURRIER (the gap the user surfaced):** spec `runbook-doctor.adoc` §307-332 "referral round-trip"
= 3 objects: Referral (request, generalist→specialist), Prescription (→patient, already in record),
**ReferralReply (response, specialist→generalist, doctor-to-doctor) = the courrier, versed into NO
record today** (Specialist.diagnose returns Optional<Prescription>, drops the why). DECISION: do NOT
reserve a field now; the record is additive, we'll attach ReferralReply to ConsultationReport when the
treats() topic PRODUCES it. Just verified the attach point (ConsultationReport = "the artifact the record
aggregates") + made DiagnosisReader tolerant so the future add is safe.

**COMMITS on the branch (newest first):** 180d1827 plan · c001b80b/437aa6f0/0ebfdc90 design (query
API + module) · 1145e1d4 sandbox · b26afd4f memories · 6b60e8c4 initial. **main pushed** (083b4fc8):
66295da3 local-kroki preview infra + 083b4fc8 flox env fix.

**Docs:** design = `wip/medical-record-query-api-design.adoc`; plan = `wip/medical-record-query-api-plan.adoc`
(15 tasks / 4 phases, C4/UML mermaid + signatures, NO code bodies per user pref). Both land to
`docs/architecture/doctor/` at merge (wip/ folder convention, hooks block wip/ reaching main).
Plan was converted .md→.adoc (render glitch FIXED — mermaid only renders in `.adoc` via kroki, the
`.md` fences were inert text); the `.md` is deleted. Write spec/plan docs DIRECTLY in `.adoc`.

**EXECUTION IN PROGRESS (subagent-driven-development, 2026-06-08).** Branch
`feature/medical-record-accumulator`. Phase-1 module DONE & reviewed:
- Task 1 module skeleton (b76b0b37). Task 2 StackSnapshot (delegates StackDeployment, outputsNamed).
  Task 3 StackCheckpoint (reshape→fromJson). Task 4 StackHistory (spine, version↔timestamp-file,
  glob excludes .attrs, startTime=epoch SECONDS). Task 5 StackHandle (attach live / forBackend
  file-only; currentSnapshot=Optional, snapshotOf(entry)=throws) — commit `edd7db81`, **implemented but
  spec+quality review NOT yet run** (resume by reviewing edd7db81, then continue).
- **Exception hierarchy committed `bceb8223`, reviewed ✅✅:** abstract `StackException extends Exception`
  (CHECKED, holds `Path path()`) ← `StackAccessException` (I/O absent/unreadable — RETRYABLE) /
  `StackContentException` (bad JSON / missing version|latest|startTime|result / fromJson — NEVER retry).
  `snapshot()` & `entries()` declare `throws StackAccessException, StackContentException`. Classification
  trap honoured: catch `JsonProcessingException`(→Content) BEFORE `IOException`(→Access). Old
  StackCheckpointException/StackHistoryException DELETED. All in [[hub:error-handling-layered-contract]].
- Module test count so far: 18 (3 StackSnapshot + 4 StackCheckpoint + 6 StackHistory + 5 StackHandle).
- Verified Pulumi 1.28.0: `LocalWorkspace.createOrSelectStack(String, Path)` → `WorkspaceStack`;
  `WorkspaceStack.exportStack()` → `StackDeployment` throws checked `AutomationException`.

**NEXT when resumed:** (1) run spec+quality review on Task 5 (`edd7db81`); (2) Tasks 6-15 per the plan
`wip/medical-record-query-api-plan.adoc` (Phase 2 doctor records, Phase 3 reader+adapter, Phase 4 YAML
dump+sandbox). Honour: lookup currency = StackHistory.Entry; MedicalRecord = immutable record (pure
clinical methods); MedicalRecordReader = aggregator (fail-at-end: partial record + addSuppressed enriched
with WHICH Entry); offline = MedicalRecordDump YAML (catch+recover). Caveat Task 10: confirm
`RemediationProgramRef` exposes `id()` + add `parse(String)`. Review rule = strict-diff + backlog
([[hub:review-scope-backlog]]); a pre-existing-code backlog is presented at the END. Reviews so far found
only the recurring "narrating javadoc" smell — keep instructing implementers to avoid it up front.
