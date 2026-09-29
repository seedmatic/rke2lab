---
name: world-gateway-2c-complete-2d-designed-state
description: World-gateway on feature/cluster-edge — 2C MIGRATION COMPLETE (REALM_BOUNDARY worklist 38→0, gate flipped WARN→ERROR = the static separation lock; whole-branch review verdict READY TO MERGE) and 2D DESIGNED + SPEC'D (commit 50a4419f, the JSON-Schema-per-coordinate contract + a 4th SCHEMA_CONCORD gate). NEXT after compaction = writing-plans on the 2D spec, then execute (subagent-driven), then the remote-validation capstone (its own brainstorm→spec), then finishing-a-development-branch. The merge gate = REALM_BOUNDARY ERROR (done) + SCHEMA_CONCORD ERROR (2D) + the capstone (dynamic proof). Authoritative ledger: .superpowers/sdd/progress.md. See [[world-gateway-2a-execution-state]] [[document-codec-instance-in-2d-backlog]] [[osgi-layout-shipped-state]].
metadata:
  type: project
---

## 2C COMPLETE (2026-06-30, feature/cluster-edge — kept, not merged)

Executed via subagent-driven-development from `wip/plans/2026-06-30-world-gateway-2c-reconstruction-path.md`
(committed 2c9bbe68 = the SDD review BASE). The PEER MODEL: host knows the STACK (opaque to OSGi), OSGi
knows the DOCTOR (opaque to host), joined ONLY by opaque `Document(domain, coordinate, payload:String)`
+ the Checkpoint/Patient seam identities. 8 commits, all green, all reviewed:
- `35940184` z1 — Checkpoint + Patient → world-gateway seam (both realms name them). 38→33.
- `a75f81c8` z2 — consult residue: probe returns a typed host `ObservationView`, Document built ONLY at
  the consult boundary (serialize-at-boundary; host never deserializes a checkpoint it wrote). 33→21.
- `afcd0c28` z3a — `InterventionLedgerWriter.append(Document)` + CLI **Option A** (boots embedded framework,
  canonicalizes via a standalone `InterventionIntake @Component`, no @References). 21→17.
- `3fc6f767` + `9fc49185` z3b — the peer node: two host read journals (`MedicalRecordJournal.historyOf(Patient)`,
  `InterventionJournal.entries()`) yield `List<Document>`; the 4 readers + registry moved to
  doctor-core/.internal; `JournalMedicalRecordRegistry @Component`; Snapshot family + StackCoordinate
  host-internal (pulumi-edge); `reviewDrift()` no-arg; DriftReview + LiveMedicalRecordRegistry deleted;
  MedicalRecordDump host-pure. 17→1. (+fix: InterventionLedgerReaderTest, the reviewDrift ledger fold.)
- `e5b6a788` z4 — ClusterSchemaRef drops doctor.records.SchemaRef (the isolate). 1→0. MILESTONE.
- `a314ccbc` — pom `<name>` hygiene (41 tags aligned to post-layout paths; <name>-only, artifactIds unchanged).
- `f7bc086f` — test-contract: removed 2 dead pulumi-edge ctors + named the test contract via *Fixture
  (StackHandleSnapshotSourceFixture, ensureReachable no-op-logger). (fromEnvironment was NOT dead — kept.)
- `9cc51d92` z5 — **FLIP REALM_BOUNDARY WARN→ERROR**. Full reactor BUILD SUCCESS at ERROR = the static
  separation proof. realm-boundary 0/0 on every assembly. doctor-port lost its whole governance block;
  cluster-port kept SPEC_COVERAGE WARN; controlplane kept DUPLICATE_REALM_CLASS WARN (the cdk8s carrier dup).

**FINAL WHOLE-BRANCH REVIEW (2c9bbe68..9cc51d92, capable model): READY TO MERGE.** All 5 cross-zone
dimensions hold (peer-model invariant end-to-end; wire shapes round-trip; SCR graph closes; no orphans
except 1 stale comment N-1; Minor triage all acceptable/post-merge). Deferred Minors (acceptable):
N-1 PulumiInterventionLedgerWriter:41 stale InterventionLedgerSource comment; z3a-M1/M3, z3b-M1/M2.

## 2D DESIGNED + SPEC'D (2026-06-30, commit 50a4419f) — RESUME AT writing-plans

Spec: `docs/architecture/osgi/world-gateway-2d-schema-contract-spec.adoc`. Brainstormed WITH the user
(design-of-record CONFIRMED + reconciled with the 2C reality — the design imagined JsonNode + one
DomainDagMapper/domain; 2C reality is String payload + N readers/coordinate). 5 reconciliation decisions,
all settled — do NOT re-litigate, plan + execute:
1. **anchor by coordinate, NO DomainDagMapper façade** — schemas are `doctor-core/src/main/resources/schema/<slug>.schema.json`
   (6 files); gate links schema↔code via the `Coordinate` const + `WorldGatewayCatalog.FIELD_*`.
2. **codec PER REALM** — OSGi `DocumentCodec @Component` (twin of manifests' YamlMapper) + a plain host
   instance. NOT one shared @Component (impossible cross-realm — the jackson wall). newPayload() is already
   gone; construction is dispersed across ~19 classes (residual migration = a tracked follow-up).
3. **networknt** (`com.networknt:json-schema-validator`) — jackson-based (reuses bundled JsonNode), pinned
   in bom, bundled for OSGi like jackson. Serves build meta-schema check AND future runtime validation.
4. **build-gate SCHEMA_CONCORD is load-bearing NOW; codecs are runtime-validation-CAPABLE but OFF in
   embedded** (the OSGi reader is the implicit validator). The CAPSTONE turns runtime validation ON — it
   inherits a tested off→on switch. Runtime validation = wired + dormant, not absent.
5. **schemas owned OSGi-side** (doctor-core resources); host gets them as wire-data when remote (no shared
   FS file, no shared Java type).

**The SCHEMA_CONCORD gate (the 4th law):** add to BOTH StagingGate enums (annotation module +
maven-embed-staging-ext ASM mirror — names must stay in step). Checks per coordinate: (a) the schema
validates against the JSON-Schema meta-schema (networknt); (b) CONCORD — the FIELD_* the code writes/reads
== the schema's declared properties. **Part (b) is the real engineering** (ASM introspection to discover
which FIELD_* a coordinate uses, like the existing gates). Governed WARN while the 6 schemas are written,
then flipped ERROR (same pattern as REALM_BOUNDARY).

**The 6 coordinates to schema:** readiness-checkpoint, readiness-verdict, consultation, intervention-request,
intervention, visit. (Producer/consumer map is in the spec §3.) Suggested cut: zone-0 (networknt + the gate
WARN + the dormant codecs) → 1-per-coordinate → final flip. **2D's flip is IN the merge** (branch merges
with TWO gates locked: REALM_BOUNDARY + SCHEMA_CONCORD).

## Scope boundary + backlogs (do NOT fold into 2D)

- **The remote-validation capstone** = increment 4, the DYNAMIC half of the merge gate (host+OSGi in two
  processes, runtime validation turned ON). Its own brainstorm→spec, after 2D, just before merge.
- **Payload carrier / streaming**: `Document.payload` STAYS a `String` (the logical contract). The user
  probed stream/pipe then "stream avec cache" — resolved: a re-read cache = a String with extra steps
  (the current consumption RE-READS — SystemdAdapterStage:253-254 parses narration AND keeps it in the log,
  so consume-once breaks it); the only winning form is a bounded spillable cache, which is a TRANSPORT
  framing behind the codec, decided at remote — NEVER the Document API. KB-scale payloads never hit a
  threshold. Changing the carrier touches 26 committed sites + reopens the 2C seam. → transport/remote backlog.
- **SPEC_COVERAGE hardening** = its OWN post-merge campaign (38 WARN, ~half manifests-core's ~101 types;
  none caused by 2C). Same pattern later: reduce worklist → flip SPEC_COVERAGE WARN→ERROR.
- **2D = JSON schemas only**, NOT a merge prerequisite by itself — but the user chose to do it on-branch
  before merge (the branch carries the whole 2A→2B→2C→2D arc). NO 2E. Roadmap ends at 2D + capstone.
