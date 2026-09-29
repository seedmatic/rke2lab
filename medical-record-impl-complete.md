---
name: medical-record-impl-complete
description: "Medical-record query-API implementation: all 15 tasks DONE, full reactor green (130 tests), on feature/medical-record-accumulator — pending final review + finishing-a-development-branch (merge). wip/ docs + plan still uncommitted."
metadata:
  node_type: memory
  type: project
  originSessionId: 4d3d8a2e-f292-4cbe-a699-fb4abfbd1e6c
---

The medical-record query-API chantier ([[medical-record-query-api-state]]) is IMPLEMENTED end to end
on `feature/medical-record-accumulator` (subagent-driven-development, 2026-06-08/09). Supersedes the
"impl NOT started" status in [[medical-record-query-api-state]].

**Shipped (all reviewed spec+quality where non-trivial; trivial records self-reviewed):**
- Module `pulumi-automation-ext`: StackSnapshot, StackCheckpoint, StackHistory(+Entry), StackHandle,
  exception hierarchy (StackException/Access/Content), PulumiBackendLayout. 20 tests.
- New `pulumi-automation-ext-testkit` module: StackHistoryFixture (shared cross-module fixture,
  src/main/java, scope=test — [[hub:shared-test-fixtures-module]]).
- seed-master doctor records: Patient, Visit + 4 query views, MedicalRecord (pure clinical folds),
  DiagnosisReader (tolerant/additive), SnapshotSource + MedicalRecordReader (the fail-at-end
  aggregator, [[hub:error-handling-layered-contract]]), StackHandleSnapshotSource, MedicalRecordDump
  (offline YAML, lenient caller). seed-master 95 tests.
- **Full reactor green gate: 130 tests (netplan 4, ext 20, manifests 11, seed-master 95), 0
  fail/error/skip, no Corrupted channel.** Verified by counting surefire, not BUILD SUCCESS.

**Key fixes driven out during execution (not in the original plan):**
- Build: JGiven wrote to the forked-JVM native stream → "Corrupted channel"; fixed with a TCP
  forkNode (SurefireForkNodeFactory) so the BDD text report stays on (commit 328722f9).
- Ordering: the file backend leaves history `version`=0, so the timeline must order by the
  DEPLOYMENT INSTANT parsed from the filename nanosecond stamp, not version. Revealed by reading the
  real dev backend (283 deployments). StackHistory + MedicalRecord now order by `when` (8697865a).
- Single-source-of-truth: extracted PulumiBackendLayout (owns `.pulumi/history/<project>/<stack>`);
  breaking the ext→testkit test edge to avoid a reactor cycle (74751c74).
- efficacyOf made per-symptom (fixed cross-symptom bleed); "first prescription" pick is PROVISIONAL
  ([[efficacy-first-prescription-provisional]]).

**Task 14 evidence (three layers, [[task14-readonly-preview-integration]],
[[seeded-history-automation-api]]):** foundation = wip/sandbox (lock-free mid-up self-read);
forward-compat = dump read-only vs real dev (degrades gracefully on cross-version state, reports
empty); real-graph = RealGraphInjectionTest (inject tagged report into a real 23-node dev checkpoint,
guarded by assumeTrue); longitudinal = SeededMedicalHistoryTest (synthetic multi-visit, chronic +
efficacy). All seeded data TAGGED `dossier.details.seeded` (honest, not fake-proof).

**DONE since (all committed, branch green 130 tests):** backlog #1/#2/#3 folded in (1be15850 —
ConsultationReport.OUTPUT_KEY shared by both writers + reader; Symptom.ENVELOPE_KEY on the plan both
sides; all four toOutputMap → LinkedHashMap). Final whole-branch review = READY TO MERGE; its one
Important catch (StackHandle.currentSnapshotFromHistory sorted by version=0 → arbitrary latest) +
nits FIXED (1a557f8d: sort by when, drop Comparator import, javadoc/test-name drift, 4 tests routed
through the reachable constants). Deferred-and-documented (NOT blockers, confirmed only two):
#4 StackCheckpoint.snapshot catch-all catch(Exception)→Content; #5 efficacy first-Rx provisional
([[efficacy-first-prescription-provisional]]).

**DOCS DELIVERED (2026-06-09, commit b6d5e2b8).** `medical-record-query-api-design.adoc` moved
wip/ → docs/architecture/doctor/ (git rename) AND reconciled to as-built: error-handling section
rewritten to the layered contract (was "skip+log+degrade"), ordering corrected to deployment-instant
not version, As-built mapping table added (diagnosisAt/PulumiMedicalRecord/snapshotForVersion →
shipped types), wip/sandbox refs framed as not-retained proof. Hub `runbook-doctor.adoc` reconciled
in THREE places (status banner + the stale "post-hoc reconstruction still deferred" body paragraph
[now "read-side part 2 DONE"] + Related-docs footer) — the user flagged that the footer alone wasn't
enough, the hub BODY asserted this work was deferred. Indexed in docs/README.adoc. Executed plan
`wip/medical-record-query-api-plan.adoc` DELETED (git rm -f; substance now in code + design doc; base
recoverable from 180d1827).

**THE ONLY REMAINING WORK = finish the branch (superpowers:finishing-a-development-branch).**
Environment: NORMAL repo (not worktree), branch feature/medical-record-accumulator, ~34 commits ahead
of main, 130 tests green (docs commit changed no code, gate from 1a557f8d holds). ⚠️ BLOCKER for any
merge/PR to main: `wip/sandbox/` is STILL TRACKED (8 files, not gitignored) and wip-guard
([[hub:wip-guard-hooks]], fires only when branch==main) blocks it. The design doc calls sandbox the
throwaway proof "not retained on main" → decide at finish: git rm wip/sandbox before merge, OR keep
branch as-is. Present the 4 options (merge locally / push+PR / keep / discard) — user picks.

**Commits on the branch (newest first):** 1a557f8d final-review-fixes · 1be15850 backlog-SoT ·
74751c74 layout-SoT · 7684c4a5 seeded-history · 8697865a ordering · a2b77af9 dump · 09f73d19 reader ·
a748f074 snapshotsource · 09661632 testkit · 7bee7c9b seam · 328722f9 jgiven-fix · + tasks 6-10 +
phase-1 module commits. (~33 ahead of main.)
