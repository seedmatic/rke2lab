---
name: intervention-provenance-state
description: "★ EXECUTING 2026-06-14 on NEW branch feature/problem-oriented-provenance (worktree .claude/worktrees/feature+problem-oriented-provenance, fresh from origin/main). The 2026-06-14 brainstorm RESOLVED the model (problem-oriented medical record; spec+plan committed in wip/superpowers/). Subagent-driven exec UNDERWAY: Tasks 0-4 of 14 DONE & green (foundation cherry-picked + ProblemRef + Expectation/Intervention tagged). The OLD branch improve/operator-intervention-provenance stays a FROZEN cherry-pick reservoir (do not touch). See [[hub:specialist-as-ledger-northstar]] for the model, [[hub:model-substrate-alignment]] for why the old C3 writer was dropped."
metadata: 
  node_type: memory
  type: project
  originSessionId: 6fa6b30b-f578-4ee4-9ffa-806a1172c020
---

**★★ EXECUTING — RESUME POINT (read this first, 2026-06-14 PM).** The brainstorm is DONE and the model
is RESOLVED (problem-oriented medical record). Work is now on a NEW branch
`feature/problem-oriented-provenance` (worktree `.claude/worktrees/feature+problem-oriented-provenance`,
branched FRESH from origin/main; sops re-smudged on creation per [[sops-worktree-smudge-noise]]).

SPEC: `wip/superpowers/specs/2026-06-14-problem-oriented-provenance-design.md` (committed).
PLAN: `wip/superpowers/plans/2026-06-14-problem-oriented-provenance.md` (committed, 14 tasks / 6 increments).
Exec mode = subagent-driven (fresh subagent per task + spec-review then code-quality review).

**★ OLD-BRANCH FATE CLOSED 2026-06-15.** The frozen cherry-pick reservoir `improve/operator-intervention-provenance`
(content already landed in main, see below) had its husk worktree DELETED and the local-only ref renamed to
`archived/operator-intervention-provenance` (tip `b3ede92d`, unchanged — commits preserved, cherry-pickable). This
established the `archived/` terminal namespace (= shipped+kept-for-provenance, vs `deprecated/` = abandoned) — see
[[hub:branch-namespaces]]. No origin churn (branch was never pushed). The ONLY remaining open item stays the e2e LIVE
proof (below), still gated on [[pulumi-stack-per-worktree-backlog]].

**★★ SHIPPED TO MAIN 2026-06-14 (origin/main `7e5ec7d1`, pushed). All 14 tasks + final review done;
260 tests green (1 unrelated pre-existing skip). The 3 `wip/` commits (spec+plan) were STRIPPED at
merge via `git rebase --onto main fbc1d9af` (they were contiguous pure-wip at the base → 24 code-only
commits replayed, main has zero `wip/`), then FF + push. Worktree + feature branch REMOVED (branch
`-d` confirmed fully merged). The ONE remaining item = the e2e LIVE proof, GATED on
[[pulumi-stack-per-worktree-backlog]] — it's the operator's `pulumi up` gesture, not Claude's; every
0-5 task is already verified Java-pure + `@TempDir` (incl. a real headless `up()` in DriftReviewWiringTest).**

Commits (origin/main..HEAD, in order): foundation cherry-picks → `f80d4a5c` ProblemRef →
`fc432de0` Expectation-by-ProblemRef → `d70b862c` Intervention-tagged → `381da85c` Resource+Writer
(STABLE name `"intervention"`) → `835b3e29` LedgerSource → `f891c20e` RecordInterventionCommand →
`e34b75eb` ProblemReview → `f2eb4a2f` DriftSpecialist.review → `30431adc` Generalist.reviewOpenProblems
(+BUILDER, see [[builder-for-multisite-constructor]]) → `4235e362` idempotent inference →
`1c90a60b` reviewDriftAtReconstruction wiring → `d89e9a11` confounded efficacy (the payoff) →
`c1b8b842` restore manifest.lock (worktree re-lock noise, see below) → `82932bcf` final-review fixes.

WHAT SHIPPED, by increment:
- **0-3 (substrate):** ProblemRef = (Checkpoint, Optional<Symptom>) deterministic join key
  (`explains` = checkpoint-only covers all symptoms / symptom-specific covers its own;
  `explainsSymptom` checkpoint-agnostic = efficacy key). Expectation re-indexed by ProblemRef.
  Intervention tagged + persisted via STABLE-name InterventionResource + history-fold (writer/reader
  twins of SystemdAdapterResource/MedicalRecordReader — the [[hub:model-substrate-alignment]] win; the
  "make the name unique" CRITICAL was correctly REJECTED).
- **Task 8:** RecordInterventionCommand operator CLI (`--problem/--what/--provenance/--prescription-ref/
  --when/--backend`; testable core takes injected Instant + writer, never reads the wall clock).
- **4 (drift specialist, the heart):** ProblemReview (transient) → DriftSpecialist.review (window
  `(prior,next]` filtered by explains + !=PULUMI_ENGINE; OPERATOR_MANUAL → confounded-DECLARED no-append;
  prior EXTERNAL_CHANGE_DETECTED in window → confounded-inferred no-append = IDEMPOTENT; else infer +
  append; both assessment-only `ReferralReply.reconstructed`, no Prescription) → Generalist.reviewOpenProblems
  (folds the record; held OUTSIDE acute roster; Generalist gained a BUILDER, no-op-writer default) →
  BootstrapPipeline.reviewDriftAtReconstruction (symptom-INDEPENDENT, every run, no-op when no file:// backend).
- **5 (the payoff):** TreatmentEfficacy.Attempt(+confounded); efficacyOf(Symptom, InterventionLedger)
  marks confounded when a non-PULUMI_ENGINE intervention in the window explainsSymptom; everWorked()
  excludes confounded; single-arg DELETED (no shim), all 14 callers migrated.

FINAL REVIEW (full-branch reviewer subagent, fixes in `d80a1023`): one real CRITICAL — LedgerSource.load()
swallowed EVERY StackException into empty(), but absence never throws (entries() returns [] on a missing
history dir), so the catch fired ONLY on corruption/I-O → silently resurrecting the false-efficacy bug.
FIXED to propagate + a corruption-propagation test. Plus: malformed `--when` → uniform usage
IllegalArgumentException (was escaping main's catch) + test; DriftSpecialist two confounded-inferred letters
folded into one helper; stale InterventionResource javadoc (described the rejected unique-name design)
corrected. The stable-name + history-fold design was re-affirmed as correct.

★ USER CORRECTION mid-review (the principle, now its own note → [[validate-at-the-boundary]]): the
reviewer's "add requireNonNull to Intervention" was REJECTED. Validation belongs at the BOUNDARY DELEGATE
that plugs a foreign-API hole, NOT as a defensive guard in our own domain type. `parseWhen` (adapts
`Instant.parse`'s DateTimeParseException — a foreign API breaking our contract) is the legit delegate that
"bouche le trou". But `Intervention`'s only foreign-data entry is `InterventionReader`, ALREADY rejecting a
missing required field to Optional.empty() at the boundary — so a requireNonNull in the record only guards
OUR OWN callers = defensive smell. ALL guards removed (incl. the pre-existing `problem` one, for uniformity);
the record enforces no-incomplete-state STRUCTURALLY (can't construct without every field).

Exec discipline that held all session: CONTROLLER runs the authoritative test (`-am` + `skipCache`),
reviewers READ CODE only; the CRITICAL was VERIFIED against the substrate (StackHistory.entries() source)
before fixing, not taken on faith.

---

**(historical — the parking decision, now superseded by the EXECUTING banner above)** The OLD branch was
parked because A+B were latent and the brainstorm might relocate the Expectation. The brainstorm resolved
it (problem-oriented model), so A/B/C were cherry-picked forward and extended with `ProblemRef`.

**★ WHERE THE BRANCH LIVES NOW (workspace cleanup, 2026-06-14).** The branch was extracted from the main
checkout into a DEDICATED worktree: `.claude/worktrees/improve+operator-intervention-provenance` (tip
b3ede92d, clean). The main checkout is back on `main`, read-only ([[hub:worktree-per-conversation]] rule). The
branch's sole role now = **a cherry-pick reservoir** — do NOT rebase or merge it (a rebase would rewrite
the SHAs and defeat cherry-picking the KEEP-candidate commits listed below). On creation the worktree's
sops secrets came out encrypted and were re-smudged → see [[sops-worktree-smudge-noise]]. **RESUME in a
FRESH session, NOT by continuing C/D here:**
1. Re-read [[hub:specialist-as-ledger-northstar]] (the full model: two memories, multi-time consultation =
   the deferred agenda loop, ledger-backed specialist whose domain is outside seed-master's field) and
   [[hub:model-substrate-alignment]] (why the C3 writer's mechanism was wrong).
2. FINISH that brainstorm: Diagram 0 vocabulary was drawn (.claude/claude-preview.adoc, may be stale);
   the OPEN fork was "is a LedgerBackedSpecialist a subtype of Specialist (same diagnose(Referral) seam)
   or consulted differently" — the multi-time-consultation insight reframes it: the drift trigger is the
   generalist's FINAL-SYNTHESIS step noticing expected-vs-observed drift, not a bolted-on mode.
3. Write ONE spec of the full model, THEN decide what of the parked A/B/C survives (cherry-pick/rebuild
   from this branch), THEN re-spec & build.
WHAT'S ON THE BRANCH (16 commits, last = b3ede92d): KEEP-candidates A (Provenance/Intervention/Reader/
Ledger), B (ExpectationPredicate/Expectation/derive+persist B3/read-into-Visit B4), C1 Layout, C2b
StackCoordinate. SUPERSEDED (the fresh brainstorm likely drops): C3 InterventionLedgerWriter (export+
union+catch = wrong model), the 2 spikes (f2a7ba18, b3ede92d). The spike b3ede92d's FINDING is still
valuable (per-resource+fold works, up() clean with no top-level export) — keep the knowledge, drop the file.

---

**WHAT & WHY.** The operator-intervention gap proven on real master ([[master-provisioning-state]]):
a cure that came from an out-of-band `nft delete` leaves no trace, so `MedicalRecord.efficacyOf`
would credit the never-applied `restart-unit` prescription. Fix = record any actor's **Intervention**
with **Provenance** (`pulumi-engine` = administered / `operator-manual` = declared / `external-change-detected`
= inferred by drift), persisted in a dedicated `intervention-ledger` Pulumi stack. This is the data source
a future drift/provenance specialist consults (the ad-hoc-OPERATOR→codified-specialist rung of the
recruit-a-specialist gradient). Spec: `wip/superpowers/specs/2026-06-13-intervention-with-provenance-design.md`;
plan: `wip/superpowers/plans/2026-06-13-intervention-with-provenance.md`.

**APPROACH = drift-first, expected-state at the centre.** Each prescription records an `Expectation`
(typed `ResolutionPredicate` now, sealed-interface FINGERPRINT SEAM for later). At the next run the
`DriftDetector` joins Expectation × Observation × ledger: a resolved symptom with NO administered
(`pulumi-engine`) intervention and NO declared `operator-manual` one in the window → infers an
`external-change-detected` Intervention. `efficacyOf` then marks such attempts **confounded**, not effective.

**DONE on-branch (subagent-driven, every task passed spec + code-quality review):**
- Increment A: `Provenance` enum, `Intervention` record+`toOutputMap`, `InterventionReader.fromOutputMap`,
  `InterventionLedger` (time-sorted, `between(fromExclusive,toInclusive)` window fold).
- Increment B: `ExpectationPredicate` sealed iface + `ResolutionPredicate`; `Expectation` record+reader;
  `ConsultationReport.expectations(Instant)` derivation + per-node `expectations` output in
  `SystemdAdapterResource` (recordedAt = `deployment().timestamp()`, NO hidden `Instant.now()`); read back
  into the `Visit` (4th component — all 11 `new Visit(` sites migrated, no compat shim).
- Increment C so far: `InterventionLedgerLayout` (SoT: PROJECT/STACK/OUTPUT_KEY + `stacksDir` delegate);
  C2 SPIKE settled the write path.

**★ C2 SPIKE FINDING (the load-bearing unknown, now settled):** the ledger writer = **Automation-API
inline program + `up()`** (NOT export/import — `importStack` updates live state but writes no history pair,
and `StackHandle.forBackend` is a HISTORY reader → invisible). Three facts the writer must carry: (1) the
Java SDK's post-`up()` `getOutputs()` THROWS `JsonSyntaxException` on an array-valued top-level output
(deserializes as scalar string) — CATCH & IGNORE, state is written, read back via `StackHandle`; (2) append
= read-union-rewrite (the inline program replaces the output wholesale); (3) `outputsNamed("interventions")`
returns `List<Object>` whose single element is the `List<Map>` of entries (flatten one level, like B4). `pulumi`
CLI is on PATH in flox (v3.225.1), `up()` runs headless. Tests use JUnit `@TempDir` = per-test isolated
backend (the runtime isolation, mktemp-equivalent).

**★ MID-EXEC CORRECTION (user, during C2) = C2b.** The spike's param-order bug (`createOrSelectStack(project,
stack, …)` — two adjacent same-typed Strings, swapped, wrote under `<project>/<project>/`) is the textbook
"2+ same-typed params → typed value" smell. Fix = a `StackCoordinate(project, stack)` record (validated like
`SchemaRef`), exposed via `InterventionLedgerLayout.ledger()`; C3/C4 take a `StackCoordinate` and unpack at the
ONE Pulumi-API line, swap impossible. Scope discipline: do NOT change the shared
`StackHandle.forBackend(Path,String,String)` signature (medical-record reader uses it too → backlog). Validates
[[hub:works-best-from-concrete-code]] — the smell only surfaced in concrete spike code, the correction propagated
back into the plan.

**NEXT — SUPERSEDED by the PARKING banner at the top of this note.** The old linear C3→C5→D path is
ABANDONED (C3's mechanism was the wrong model). Do NOT resume it. Resume = the fresh-session brainstorm
described in the parking banner ([[hub:specialist-as-ledger-northstar]] is the model to finish + spec).

**Adjacent git state (2026-06-14):** `origin/main` is current and PUSHED — the workspace-cleanup session
pushed 3 chore commits (gitignore + memory notes) via the `gh` credential-helper workaround
(`GH_TOKEN= git -c credential.helper='!gh auth git-credential' push`), so the earlier "auth failing / not
pushed" state is resolved for routine pushes (the [[hub:maven-github-token-resolution]] backlog still stands
for the Maven build path). NOTE: the live-probe fix ([[master-provisioning-state]]) lives on its own
branch `fix/systemd-live-probe-contract`, NOT merged. This improve/ branch is independent of both.
