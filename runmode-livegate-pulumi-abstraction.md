---
name: runmode-livegate-pulumi-abstraction
description: "ClusterSeed design (2026-07-06): RunMode is a tri-state FACT (STANDALONE|PULUMI_PREVIEW|PULUMI_RUN) that lives at the Pulumi EDGE — Pulumi is implementation, the domain must NOT see it. Only PULUMI_RUN mutates master; standalone+preview = inert probes + pending-marking, complete runbook, zero mutation. The domain consumes ONE abstract face — LiveGate (isOpen/through) — never RunMode. LiveGate is a projection of RunMode. Supersedes the committed 2-boolean RunMode (4b40a9d) which must be reworked."
metadata:
  type: project
---

**The fact (tri-state, kills the impossible 4th combo).** Task 2 committed `RunMode(boolean pulumiMode,
boolean previewMode)` — two free booleans, one impossible combo (standalone+preview). Real domain is
TRI-STATE: `STANDALONE | PULUMI_PREVIEW | PULUMI_RUN`. Only `PULUMI_RUN` (= `pulumi up`) provokes real
provisioning. Standalone + preview = inert probes + pending-marking executor → COMPLETE runbook, ZERO
mutation. (Why standalone ≠ live, user's collision scenario: standalone creating incus `master` for real
→ not in any Pulumi state → later `pulumi up` hits "already exists"; `refresh` won't import it. So
standalone MUST be inert.)

**CORRECTED truth table (2026-07-06, verified at source — supersedes the wrong one below).** The user's
thread "LiveGate is a STATE, not a fact — are you on it?" dissolved a contradiction I nearly asked them to
arbitrate. Verified: `through(live,deferred)` is NEVER called in prod (0 sites); the 3 real `isOpen()` sites
all do ONE thing (preview = deferred render vs touch reality); MUTATION (creating real resources) is gated
by `if (pulumiMode)` (ResourceManager:49), NOT by LiveGate. So RunMode (the STATE) has TWO INDEPENDENT
projections, today two correlated unnamed booleans scattered everywhere:
- `LiveGate.isOpen()` → "may I touch reality?" (ssh/kubectl/inspect)
- `pulumiMode` → "do I materialise Pulumi resources?"

| state | LiveGate.isOpen (touch reality?) | pulumiMode (materialise Pulumi resources?) |
| STANDALONE     | **YES** (reads reality: ssh/kubectl) | **NO** (standalone path, no Pulumi resource) |
| PULUMI_PREVIEW | no (deferred render)                 | yes (but dry-run) |
| PULUMI_RUN     | yes                                  | yes |

STANDALONE stays LIVE (the shipped code AND its javadoc are right) AND does NOT provision (the design is
right) — because those are DIFFERENT projections. The collision the user feared does NOT happen: master is
created by the `if (pulumiMode)` path, which standalone does NOT take — NOT by LiveGate. My earlier belief
"standalone must be inert" (below) was WRONG: it conflated the two projections. NO runtime behavior changes
in the rework — it only NAMES the correlated state. This is source-vs-projection's 3rd recurrence.

**OLD (wrong) truth table — kept for the trace, DO NOT act on it:**
- STANDALONE     → probes inert, pending, no mutation, output=print  ← WRONG: standalone is LIVE (reads reality)
- PULUMI_PREVIEW → probes inert, pending, no mutation, output=export (dry-run)
- PULUMI_RUN     → probes live, normal, mutates master, output=export

**Pulumi is IMPLEMENTATION — the domain must not expose it (user's decisive point).** A phase must NOT
read `PULUMI_PREVIEW`/`playsLive()` — that leaks Pulumi vocabulary into domain logic. Inversion of
dependency: the domain depends on an ABSTRACT face, the Pulumi edge produces it.
- `RunMode` (Pulumi vocabulary) = a DETAIL, lives at the edge (`pulumi-edge`, beside `LiveGate`); the
  DETECTION (`Deployment.getInstance().isDryRun()`) is Pulumi-pure and lives there too — the ONE place.
- The domain consumes `LiveGate` (`isOpen()` / `through(live, deferred)`) — its type surface names NO
  `com.pulumi`. `LiveGate` already exists and is already abstract → it's the face to consume.
- `LiveGate` becomes a PROJECTION of `RunMode` (`LiveGate.forRun(RunMode)` replacing `forRun(boolean)`),
  which finishes the factorization LiveGate's javadoc promised (solves the 3 inline
  `Deployment.isDryRun()` reads in `IncusResourceBootstrap`).

**ONE abstract face only (user, settled).** `LiveGate` suffices — "live vs deferred" is the only axis the
domain needs, and it produces the expected runbook (deferred = bodies don't touch reality but the
scenario still plays → tree builds → complete runbook; that IS `through(live,deferred)`).
`PendingMarkingScenarioExecutor` stays an implementation detail BEHIND the gate (deferred → the edge/seeder
installs it; the domain sees only "deferred"). NO separate render-mode exposed now (YAGNI). If ever
needed: a phase asks `adapt(RenderMode.class)` — the [[classrealm-adaptable-pattern]] IS the extension
tool. Door left open AND tooled.

**Source-vs-projection (the pattern, twice).** RunMode is the SOURCE (fact); LiveGate/probes/executor/
export are PROJECTIONS. Reading RunMode tells you everything; nothing re-decides in its corner. Same shape
as [[classrealm-adaptable-pattern]] (a world → its faces) — the recurrence is why it feels right.

**Two state TYPES, carried by the Java type (2026-07-06, user's breakthrough — "marque les états, pas les
steps").** A `@ProvidedScenarioState` value is one of two kinds, and the code ALREADY encodes each via its
type: (1) OBSERVATION (what the world IS) = `ObservationView`, with its guarded form
`ObservationView.deferredPreview(config)`; (2) OUTCOME (what a mutation PRODUCED) = a resource/URN, with
`createStandaloneResources()`. A step (When) ALWAYS writes scenario-DAG state (that's its essence, not a
mutation of the world); what varies is whether it touches the WORLD, and how (read→LiveGate,
mutate→materialises). So we mark STATES not STEPS — and we don't even annotate: the type already carries it
(`ObservationView`=observation, resource/URN=outcome). Closes the loop: RunMode (source) → LiveGate/
materialises (projections) → Observation/Outcome (the two state types the projections produce). A marker
interface (`Observation`/`Outcome`) or a static gate is DEFERRED to the handoff from the new codebase
(user, 2026-07-06) — door tooled, not opened (YAGNI).

**Rework SCOPE settled (A, 2026-07-06) + naming.** Verified at source: `RunMode` has NO readers (only stored
in `HostFacts`, constructed in one test); `through(live,deferred)` is NEVER called in prod; `pulumiMode`
threading lives in the DYING pipeline (erased Task 8). So scope A = fix at the EDGE only, do NOT polish the
dying pipeline's `pulumiMode` threading. Gestures: (1) `RunMode` → tri-state enum MOVED to `pulumi-edge`
beside LiveGate, factory `detect(pulumiMode)` (reads ambient `isDryRun` ONLY when pulumiMode, preserving the
short-circuit), projections `playsLive()` (this != PULUMI_PREVIEW) + `materialises()` (this != STANDALONE);
(2) `LiveGate.forRun(boolean)` → `forRun(RunMode)` = `new LiveGate(runMode.playsLive())` — the isDryRun read
moves into RunMode.detect; (3) `HostFacts.runMode` → `HostFacts.liveGate` (decision A: domain carries the
abstraction LiveGate, NOT the Pulumi-vocab RunMode); (4) one call-site `ClusterSeedPipeline:148`.

**TODO for the rework:** implement the 4 gestures above. See [[cluster-seed-transport-consensus]]
[[cluster-seed-execution-state]].
