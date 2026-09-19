---
name: pipeline-jgiven-separation-design
description: Design arc (2026-07-01, brainstorm CONVERGED, mostly NOT built) — the chain non-null → pulumi-outputs → jgiven → pipeline. Root cause = two pipelines (our fluent grammar vs jgiven Scenario/Stage) tangled in 6 seed-master classes. Vocabulary reset + LiveGate + output registry + jgiven provider decided.
metadata:
  type: project
---

## ★ THE COMPASS (user, 2026-07-01) — the runbook is the OPERATOR's view, read AND act
Reframing that supersedes the "narrate the pipeline" framing: the runbook does NOT exist to narrate
seed-master's boot pipeline. seed-master is only ONE piece (the cluster bootstrap) of a larger system.
The runbook is the projection of **the OPERATOR's mental model of the whole system** — and the
inclusion criterion is NOT "is it a pipeline topic?" but **"does the operator need it to UNDERSTAND
and ACT on the system?"** (user: "il faut regarder selon le point de vue de l'opérateur. qu'a-t-il
besoin de savoir pour comprendre et intervenir sur le système.")

Scope DECIDED: **read AND act** (not read-only). The runbook is the medium through which the operator
both understands (state, health, why — the doctor/symptoms/drift) AND acts (replay a checkpoint, apply
a remedy, relaunch a phase). Intervention happens THROUGH the model, not only elsewhere (kubectl/pulumi).
So the doctor / symptoms / drift / remedies we kept meeting are not isolated features — they are pieces
of this actionable operator-view.

CONSEQUENCE for the pipeline↔jgiven question below: the earlier framing (separate two pipelines; jgiven
is a thin output detail behind a provider) was mechanically right but the WRONG COMPASS. The runbook
today shows only 2 of 8 pipeline topics (systemd-adapter + cluster-readiness); the other 6 (environment,
preflight, bbox, incus, bootstrap-resources, outputs) are invisible — but the fix is NOT "narrate all 8
topics". It is: render what the OPERATOR needs to comprehend+act, across the whole system (bootstrap is
just the start; the live cluster, nodes, drift, incidents are the rest). Whether a given topic appears
is judged by operator-need, not by being a pipeline step. The deep jgiven coupling the user sensed is
justified by THIS (the operator model deserves to be rendered+actionable), not by "the pipeline deserves
narration". This is a broad brainstorm to run at resume, from the operator's point of view — larger than
the readiness fix. The mechanics below (RunMode, two gates, Topic vocab, probe=step) stay valid as
MEANS; the compass is the END.

**Operator-view inventory (started 2026-07-01) — triage each by operator-need, NOT pipeline-topic:**
- **bbox reconciliation → IN (user, first verdict).** The operator needs the reservation state (which
  node/IP reservations exist; created/updated/matching/failed counts). The Pulumi log already shows it
  (`desired=12 created=0 matching=12 …`) but OUTSIDE the runbook → invisible in the operator-view today.
  First proof the criterion is "operator need", not "is a topic".
- (to triage at resume: environment, preflight, incus provisioning, bootstrap-resources, outputs — plus
  the WHOLE-SYSTEM pieces beyond seed-master: live cluster, nodes, drift, incidents.)

## ★ OWNERSHIP PIVOT (user, 2026-07-02) — the pipeline is OSGi-owned; host is a consumer
The biggest reframe since the compass. The pipeline — grammar + engine + the bootstrap REASONING — is
owned by the **OSGi world**, NOT by seed-master. Grounded in the code (Explore pass 2026-07-02):
- The grammar primitives already live in **`osgi/foundation/pipeline/pipeline-port`**
  (`FluentTopicRunner`/`OnFailure`/`TopicFailure`, ~33 LOC, pure, ZERO jgiven import, **dual-consumable**:
  real bundle at build, exported from system bundle for the flat host classloader at runtime). The
  runner ALREADY speaks the target vocabulary — `runDuring(topic,…)`, `TopicFailure` — so `*Stage→*Topic`
  merely aligns the stranded host classes onto the shared word.
- jgiven is already an OSGi bundle (`jgiven-wrap` + in-container testkit).
- **TWO consumers of the one grammar**: (a) the **boot pipeline** (`BootPipeline.embedded()` in
  `osgi/runtime/framework-launcher` (was `launcher`); `BootPlanner`→`BootPlan` pure decision, uses NO runner; `FrameworkLauncher`→
  `BootedFramework` the act — only code touching `org.osgi.framework.launch`) — grammar YES, jgiven NO
  (it precedes jgiven's world; topics = discovery/plan/launch); (b) the **reasoning pipeline** (topics
  preflight/bbox/incus/systemd/readiness/outputs) — grammar YES, jgiven YES; stranded in
  `exec/seed-master/controlplane/pipeline` TODAY, target = inside the OSGi world.

**DETACHED BY DESIGN — embedded exec is a special case (user, 2026-07-02).** The OSGi world is designed
to live DETACHED from the embedded executable and be accessed REMOTELY. General case = standalone OSGi
world, self-amorcing, exposes a service; host = remote client. `BootPipeline.embedded()` is the
DEGENERATE case (host co-locates + boots OSGi in-process). The amorce chicken-and-egg ("what starts OSGi
can't run inside OSGi") is REAL ONLY in the embedded case — a property of co-location, not architecture.
Hierarchy: `OSGi world (self-amorcing, exposes service) ⟵ frontier (remote | folded in-process when
embedded) ⟵ host-client (Pulumi edge)`.

**WHICH USE CASE IS WHICH (user, 2026-07-02).** Not a theoretical symmetry — two real seed uses:
- **seed-master (bootstrap) → embedded exec.** It PRECEDES the cluster (no cluster yet to deploy a
  workload into) → must embed+amorce OSGi itself → owns the amorce paradox. HAS the Pulumi edge
  (`RunMode`, stack sink) because it CREATES infra via Pulumi; `Deployment.isDryRun()` is a fact only here.
- **seed in-cluster (other uses) → detached workloads.** Cluster already exists; OSGi runs as a
  workload, remote. No paradox, and NO Pulumi/`RunMode` (a workload runs inside the created cluster).
- ⇒ The Pulumi coupling is NOT a property of the OSGi world — it's a property of the BOOTSTRAP's
  embedded host. Confirms (second route) the two gates live at the host edge (`pulumi-edge`), never in
  OSGi reasoning.

**The seam (remote-first, HARDEST, NOT decided).** `RunMode`+Pulumi context enter the OSGi world; runbook
+output contributions come back out. Remote-first (a service frontier OSGi exposes); embedded FOLDS it
into in-process `BootedFramework.awaitService(...)`, doesn't DEFINE it. Output gate probably splits:
contributors OSGi-side (pure `Map`, no Pulumi across the seam) + assembly/write host-side. **OPEN: what
TRIGGERS the reasoning pipeline in the detached case** — remote host-client commands it (passive until
called; embedded = same call folded), or OSGi reasons on its own lifecycle + exposes result (then where
does `RunMode` enter?). This `RunMode→OSGi→runbook` joint = the next brainstorm. Spec (extended
2026-07-02) = `docs/architecture/bdd/bdd.adoc` (moved from `patterns/`, renamed from
`two-gates-spec`; `dsl-unification-exploration.adoc` DELETED and folded in).

## ★ NAMING + THREE LEVELS (2026-07-02, graved in pipeline-spec.adoc)
"Boot" vs "bootstrap" hid an altitude difference. DECIDED naming: **`FrameworkLaunch`** (launch the
OSGi framework) vs **`ClusterSeed`** (seed-master seeds the cluster) — two verbs, two objects, no
homonym. And `Stage` is jgiven's word → our unit is a **`Topic`**. So the renames:
- `BootPipeline` (osgi/runtime/launcher) → **`FrameworkLaunchPipeline`**, topic `"bootstrap"`→`"framework"`.
- `BootstrapPipeline` (exec/seed-master) → **`ClusterSeedPipeline`**.
- `BootstrapStage` → **`ClusterSeedTopic`** (NOT `*Stage` — Topic is our word).

The atlas-first audit (reading the late-June memories BEFORE speccing) found the dissociation the user
asked for is really **THREE levels of pipeline**, each with a vision ALREADY mapped — do NOT re-derive:
- **L-boot (framework launch)** = the boot EXECUTOR (`FrameworkLaunchPipeline` + `BootPlanner`/`BootPlan`
  + `FrameworkLauncher`). Grammar yes, jgiven NO. Vision = unify prod `OsgiRuntime` + test
  `FelixFrameworkExtension` into one boot-pipeline → [[boot-pipeline-unification-backlog]].
- **L-shape (contributable seed)** = `ClusterSeedPipeline`. Grammar yes, jgiven YES (owns runbook).
  Vision = the boot PREFIX is identical for all 3 seeds (seed-master/manifests-cli/netplan-cli); only
  the TAIL diverges → each seed contributes its tail → [[bootstrap-pipeline-contributable-vision]].
- **L-altitude (decision in OSGi)** = WHERE decision logic lives. Vision = the orchestration DSL becomes
  an OSGi capability, actualisation stays host as ports (DIP); `ClusterSeedTopic` is pure decision
  (candidate), 4/~10 topics still import Pulumi/gRPC (re-seam first) → [[pipeline-orchestration-osgi-vision]].
- Two transverse: pure jgiven MODEL vs host rendering ENGINE → [[orchestration-purity-benefit]]; and
  `osgi/jgiven/` dissolves into `pipeline` → [[jgiven-domain-into-pipeline-debt]].

KEY dissociation resolved: `FrameworkLaunch` is NOT seed-master's — it is the shared boot prefix of the
3 seeds; `ClusterSeed` is only seed-master's tail; `ClusterSeedTopic` is their single seam (the embedded
fold) AND the pure-decision unit L-altitude moves into OSGi. Rename is SAFE/orthogonal (doesn't prejudge
contributable or OSGi-orchestration); the shape rewrite is the vision, later. **This session RE-DERIVED
part of these 5 notes for lack of the atlas-first reflex — hence the reflex is now the rule.**

BEFORE/AFTER cartography graved in the spec (factual, ~8 pipelines) on TWO axes: defined-where
(host/OSGi) + INITIATED-by (host/OSGi) — the user's axis. Finding: exactly ONE pipeline is
OSGi-INITIATED today (`BootstrapInfrastructureSynthesizer`, called by the manifests `@Component`); all
others are host-initiated (directly or via ~15 `awaitService(...)` calls). No `@Activate` runs a
`during/then`. ⇒ the L-altitude vision's MEASURE = moving rows host→OSGi on the initiation axis; the
column has 1 entry today, the vision fills it. Verdict: design HOLDS (renames are a partition not a
rewrite; initiation only moves host→OSGi). CAVEAT surfaced: **`Stage` is overloaded** — `BootstrapStage`
exists TWICE (seed-master + manifests-core), 14 `*Stage` over 4 modules; `Stage→Topic` is transverse,
scope must be decided up front (I once ran the rename blind and it clobbered manifests-core's
`BootstrapStage`/`NetworkStage`/`StorageStage` — REVERTED; lesson: no blind sweep).

WORK PLAN graved (spec § "Work plan"): TWO orthogonal threads, do NOT braid.
- Thread R (reliability, tactical) = non-null→outputs→jgiven→pipeline, fixed in reverse (the arc).
- Thread S (structure, strategic), dependency order: **S0** lexical rename (decide Stage scope FIRST) →
  **S1** untangle the fold in ClusterSeedTopic → **S2** contributable seed (rewrites ClusterSeedPipeline)
  → **S3** decision-into-OSGi (re-seam the 4 pulumi/grpc topics; the RunMode→OSGi→runbook seam decided
  HERE) → **S4** boot-executor unification (parallel) → **S5** transverse (pure jgiven model; osgi/jgiven
  →pipeline). S2/S3 overlap (both rewrite ClusterSeedPipeline). Each S# = own chantier + worktree.
NOTE (user, 2026-07-02): stay in BRAINSTORM/SPEC — finish the spec + a high-level plan; do NOT jump to
implementation. The code stays untouched (only the uncommitted pulumi-edge trio + readiness WIP remain).

## ★ MODE / PREVIEW — the fine decisions (2026-07-02, all graved in pipeline-spec)
A chain of user corrections that settled how "mode" works across the seam. Each supersedes my prior
wrong framing — recorded so they are not re-derived:
1. **Preview is NOT Pulumi-only.** Separate the preview CAPABILITY (dry-run the reasoning → jgiven
   PENDING + living gate closed — OSGi-NATIVE, jgiven is a bundle) from its TRIGGER
   (`Deployment.isDryRun()` in bootstrap; operator command / CRD field detached — OPEN). My error was
   "no Pulumi → no preview".
2. **Promote the AXES, not the enum.** `RunMode {STANDALONE,PREVIEW,APPLY}` is HOST launch vocabulary —
   STANDALONE = "host runs exec without Pulumi", meaningless detached, and that's CORRECT: the OSGi
   world must never see "STANDALONE". The OSGi-native PORT speaks in AXES (probe: live/deferred).
   `RunMode` is the host's PROJECTION (mode→axis values). Corrects my "promote RunMode into OSGi".
3. **The mode RESOLVES; the context INJECTS (complementary).** The probe axis becomes a RESOLUTION axis
   — resolve the pipeline/topic under LDAP `(probe=deferred)`, Felix wires the variant that never
   touches the real system (the living gate stops being an `if`, becomes a `Require` — "gates become
   resolution edges", the [[orchestration-purity-benefit]] idea applied to the mode). Changing mode =
   a RE-RESOLUTION event (natural: each bootstrap launch resolves once; operator re-resolves in-cluster).
   BUT resolution carries no run data — the patient/config/sink/instant still enter via `TopicContext`
   (injected instance). Rule: **the mode resolves, the context injects.** The stack (output) axis stays
   Pulumi/host-only and simply has no in-cluster edge; the probe/preview axis survives detached.
4. **Host transposes the ROLE, not the MECHANISM.** Resolution is OSGi-native (Felix resolver); the host
   has no resolver and today looks up by TYPE only (`awaitService(Class)`, no filter — verified). So the
   host does NOT resolve — a **port-factory** carries the mode INTENTION and OSGi resolves it:
   `PipelineProvider.forMode(probe=deferred)` → OSGi resolves the variant internally (LDAP stays
   OSGi-side) → returns it. Chosen OVER extending `awaitService(Class, filter)` because that teaches the
   host LDAP AND breaks detached (a remote host has no local registry). The port-factory carries an
   intention → works embedded (folds onto awaitService) AND detached (remote call) — remote-first.
   `RunMode` = the host's producer of that intention. Port shape decided at chantier S3.

## The thread (each link revealed by fixing the previous at its source)
**non-null → pulumi-outputs → jgiven → pipeline.** The user's framing, verbatim: "le fil c'est
non-null -> pulumi outputs -> jgiven -> pipeline". Each `→` is "fixing X at the source revealed the
real defect was Y, further upstream".

1. **non-null** — hardening `exec/seed-master` NullAway: `SeedNodeBootstrapWatcher` needed
   `toBoolean/toInt/stringValue` + `@Nullable Object` to re-parse a `Map`. The null was the symptom.
2. **→ pulumi-outputs** — root: flatten-too-early. `SeedSystemdAdapterRuntimeStatusSnapshot.snapshot()`
   flattens a typed snapshot to `Map` AT PRODUCTION, so the map travels internally and the watcher
   re-parses. Fix = the OUTPUT gate (see below) + `SystemdRuntimeStatusReport`.
3. **→ jgiven** — chasing the preview path found a REAL bug: `pulumi preview` hangs ~1 min on
   `waitForKubeconfigPublished`. Fix = the LIVING gate (`LiveGate`). Then "who configures jgiven?"
   surfaced the dead dry-run (below) and the missing jgiven provider.
4. **→ pipeline** — deepest cause: TWO pipelines tangled. Our fluent grammar (`FluentTopicRunner`,
   `during`/`then`) is INDEPENDENT of jgiven (verified: `FluentTopicRunner` has ZERO jgiven import;
   `pipeline` module has jgiven only as a separate `pipeline-jgiven` wrap). They are merely tangled in
   6 seed-master classes (`PipelineState`, `BootstrapStage`, `BootstrapPipeline`, the 3 `*Stage`).

## RunMode — the named concept; the two gates DERIVE from it (DECIDED 2026-07-01, revises LiveGate)
The user found `LiveGate` under-named and re-derived the real model: THREE launch modes are three
coordinates in a TWO-AXIS space (probe × stack), NOT one binary. A single `RunMode` enum names the
input; both gates derive from it. (User: "standalone → probe live, no storage; pulumi preview → probe
dry-run, stack read-only; pulumi up → probe live, stack read-write".)

| `RunMode` | probe axis (living gate) | stack axis (output gate) |
| STANDALONE | live | none (print — `pulumiContext == null`) |
| PREVIEW (`pulumi preview`) | **dry-run (deferred)** | read-only (Pulumi engine) |
| APPLY (`pulumi up`) | live | read-write (Pulumi engine) |

KEY: PREVIEW is the ONLY mode with a dry-run probe — STANDALONE and APPLY both probe live. So the
user's earlier "standalone = dry-run" was the trap of FUSING the two axes (it confused "no stack" with
"no probe"). The two axes are orthogonal; each is binary in OUR code:
- **probe axis**: live vs deferred → deferred ONLY in PREVIEW (`pulumiMode && isDryRun()`).
- **stack axis**: our code only sees `pulumiContext != null` (export) vs null (print) — the
  read-only/read-write split is INTERNAL to Pulumi (`OutputsStage.exportOrPrint`: same `context.export`
  call, the engine doesn't apply in preview). We don't pilot ro/rw.

- **LIVING gate** = derived from `RunMode` (was `LiveGate`, now `RunMode.probesLive()`): "may an action
  touch the REAL system (probe systemd, wait kubeconfig)?" NOT about the stack. Lives in
  `host/pulumi/pulumi-edge` (`@NullMarked`). Keep the `through(live, deferred)` combinator; the gate is
  a PROJECTION of `RunMode`, not an independent object. The ONE dry-run read happens resolving RunMode.
  STATUS: built as `LiveGate.forRun(pulumiMode)` for readiness (`playClusterReadiness` injects
  `SimulatedClusterReadinessProbe.deferred()` when closed) — REFACTOR to `RunMode` on resume;
  `SystemdAdapterStage` still reads `isDryRun()` inline — migrate onto RunMode via `TopicContext`.
- `RunMode` is a field of `TopicContext` (resolved once at launch); topics read it, never
  `Deployment.getInstance()`.
- **OUTPUT gate** = `PulumiOutputContributor` (SPI) + `PulumiOutputRegistry` (in pulumi-edge, CREATED,
  null-clean, NOT yet wired). Single flatten point (`contribute()`), unique-key guarantee. `OutputBuilder`
  melts into `registry.add(...).assemble()`. `SystemdRuntimeStatusReport` = domain-pure record
  `{Status{OK,EXECUTION_ERROR}, Optional<SystemdStatusSnapshot>, summary}`; deferred-preview is the
  living gate closed, NOT a status; the contributor builds the K8s envelope.

## FOUNDING CONSTRAINT (user, 2026-07-01): probes ARE jgiven scenario steps
"tous les probes doivent être des scénarios jgiven, sinon on a pas de runbook." The runbook exists
ONLY because each probe is played as a scenario step (it records into the ReportModel). A probe that
isn't a scenario step produces no runbook entry. So probe ⟺ scenario-step is the RAISON D'ÊTRE of the
pipeline↔jgiven coupling — not incidental.

Corollary — this DISQUALIFIES the current readiness fix. `SimulatedClusterReadinessProbe.deferred()`
fabricates a fake probe that RETURNS a value → the step EXECUTES and renders **PASSED (fake green)** —
the exact dishonesty the user rejected. The correct PREVIEW rendering is jgiven's native dry-run:
`disableMethodExecution()` + `setDefaultInvocationMode(PENDING)` → step bodies DON'T run, steps render
**PENDING** (not PASSED, not "deferred" — my wrong word). The probe body never executes, so it never
touches the real system, AND the runbook shell renders honestly as "to do".

So the probe axis in PREVIEW is NOT "inject another probe" — it is "put the jgiven SESSION in dry-run",
and because probes ARE steps, they all become PENDING at once. The living gate acts THROUGH the jgiven
session, not beside it. This fits the provider decision: `BootstrapTopic` owns the session → it flips
the session to dry-run in PREVIEW (derived from `RunMode`) → every probe-step becomes PENDING. One
point, no fake probes.

BUT the blocker below stands: our `startScenario(String)` path never activates jgiven dry-run. So the
real work is to make our scenario execution HONOR jgiven dry-run (take the path that reads
`Config.dryRun()` / drive the interceptor), NOT to inject deferred probes. Open design question for
resume: how to trigger `disableMethodExecution` without the JUnit `startScenario(class,method,args)`
entry (no public API) — candidates: drive `ScenarioExecutor`/interceptor directly, or a jgiven-wrap
helper in `pipeline-jgiven`. `SimulatedClusterReadinessProbe.deferred()` + the `checking()` symptom
tweak are a WRONG DIRECTION to unwind.

## The jgiven dead dry-run (KEY finding, verified in jgiven-core 2.0.3 sources)
`Config.dryRun()` reads `System.getProperty("jgiven.report.dry-run")` — BUT `disableMethodExecution()`
is triggered ONLY in `ScenarioExecutor.startScenario(Class,Method,List<NamedArgument>)` (the JUnit
path). Our stages call `startScenario(String)` (the 1-arg overload) which NEVER consults
`Config.dryRun()`. So the `System.setProperty(JGIVEN_DRY_RUN,"true")` in BOTH `SystemdAdapterStage`
and `ClusterReadinessStage` is DEAD CODE — the dry-run never activates on our path. There is NO public
Java API to enable it (only the system property + the JUnit entry we don't use). ⇒ `LiveGate` +
deferred-probe injection is the ONLY controllable mechanism; the `System.setProperty` dead lines must
be DELETED from both stages. (I restored them mid-session at the user's request believing they worked;
they don't — remove on implement.)

## Terminology reset — separate our pipeline from jgiven (DECIDED)
`Stage` and `State` are jgiven words (`Given/When/Then extends Stage`, `@ScenarioState`); our classes
borrowed them and caused the tangle. Our vocabulary:

| concept | jgiven (theirs) | ours (DECIDED) |
| unit of work | `Stage` | **`*Topic`** (BootstrapStage→BootstrapTopic, ClusterReadinessStage→ClusterReadinessTopic, SystemdAdapterStage→SystemdAdapterTopic) |
| executor | `ScenarioExecutor` | `FluentTopicRunner` (already ours) |
| the whole | `Scenario` | the pipeline (`during`.`then`) |
| read context | `@ScenarioState` | **`TopicContext`** (immutable: config, policy, **LiveGate**, runbook, consultations, doctor, recordedAt, resolved ports) |
| accumulated results | — | **`TopicOutcomes`** (mutable: bboxResult, bootstrapResult, systemdAdapterLaunchSummary, resourceResult) |
| living doc | `ReportModel` | produced VIA the jgiven session, held by the provider |

`PipelineState` (current) splits into `TopicContext` (read) + `TopicOutcomes` (mutable). `LiveGate`
becomes a FIELD of `TopicContext`, read by reference — no longer threaded as a per-stage param (that
was the "LiveGate mal placée" realization). Topics today recopy ~7 identical params
(config/policy/runbook/consultations/doctor/recordedAt/liveGate); after, a topic takes
`(TopicContext, its probe, its sink)` — ~10 params → ~3.

## The jgiven provider (DECIDED)
`BootstrapStage` (→ `BootstrapTopic`) is ALREADY the de-facto jgiven provider: it `new ReportModel()`
(BootstrapStage:61) and renders it (`RunbookRenderer`, :110) in a finally. Make it the FULL provider:
it owns the jgiven session lifecycle (create the model, render). Since the dry-run is dead, the
"session config" acte shrinks to create+render — the `RunbookSession` object (if kept) is thin. The
topics CONSUME the session (write their scenario), never configure jgiven. Vocabulary of ports/edges:
the provider FURNISHES the BDD session; topics consume it.

## STATUS + what's built vs designed
- BUILT (uncommitted): `LiveGate` (forRun), `PulumiOutputContributor`+`PulumiOutputRegistry` (unwired),
  `playClusterReadiness` gates the probe, `SimulatedClusterReadinessProbe.deferred()`, `checking()`
  throws on `symptom().isPresent()` (deferred ≠ failure), `ClusterReadinessStage` takes `LiveGate`.
  The `System.setProperty(JGIVEN_DRY_RUN)` was RESTORED (mistakenly) — DELETE it on implement.
- DESIGNED, not built: the `*Topic` rename, `TopicContext`/`TopicOutcomes` split, LiveGate-in-context,
  the full output-registry wiring + `SystemdRuntimeStatusReport`, `SystemdAdapterStage` onto the gate,
  the jgiven provider.
- Specs: `docs/architecture/osgi/two-gates-spec.adoc` (DESIGN) covers the two gates but PRE-DATES the
  pipeline/jgiven separation + the Topic vocabulary — MUST be extended on resume. Atlas has the 6th
  Host/pipeline view. Whiteboard `.claude/claude-preview.adoc` holds the frozen C4 (two gates + jgiven
  provider + TopicContext).

## RESUME (fresh head)
1. Extend `two-gates-spec.adoc` (or a sibling) with the pipeline↔jgiven separation + Topic vocabulary.
2. Remote-debug validate: `_JAVA_OPTIONS=-agentlib:jdwp=...:8000 pulumi preview`, attach VSCode
   "Attach to Pulumi RKE2Lab Program", breakpoint `ClusterReadinessScenario.checking()` — confirm the
   deferred probe fires and no waitFor runs (the dead-dry-run finding predicts the live probe WOULD
   run without the gate).
3. Implement: rename `*Stage`→`*Topic`, split `PipelineState`→`TopicContext`+`TopicOutcomes`, LiveGate
   in context, delete dead `System.setProperty(JGIVEN_DRY_RUN)` (both), wire the output registry +
   `SystemdRuntimeStatusReport` + typed watcher, `SystemdAdapterTopic` onto the gate. Build `-Pall-worlds`
   NEVER `,nxmatic`; measure NullAway with `clean package -DskipTests=true`.

See [[flatten-at-edge-observation-layer]] [[atlas-reconciliation-2026-07-01]] [[nullaway-jdk25-recipe]].
