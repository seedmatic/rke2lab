---
name: pipeline-state-per-topic-io-refactor-backlog
description: "GZ-2 DONE 2026-07-03: all 7 pipelines on the one-source-of-truth invariant — a topic reads every consumed value through a Supplier resolving at the owner (state::accessor), NEVER a copied reference (flux AND inherited ambient; the G4 'ambient direct' rule RETIRED). Exemplar systemd-synth bba655a9 (+ TargetChecksum fa9b8315, ClusterSeed 8f463496, Incus f5a539c7); LiveGate chantier DONE 809d42d3. DESIGN CONVERGED 2026-07-03 (whiteboard .claude/claude-preview.adoc), Phase A (Incus pilot) committed ca30367a. Fluent-pipeline state = generic State<I,B> per topic (narrow input record I + fixed accumulator B). NO O type param: a topic PUSHES its output through its Topic.Sink into B (it does not return it). Topic contract = IDENTIFICATION (interface Topic{role(); nested Topic.Sink; nested nature types Topic.Execution/Checkpoint/Pipeline}), NOT Topic<I,O>{O run(I)} (a topic is a fluent multi-verb builder). Nature is a nested TYPE not an enum — TopicNature+nature() DELETED, governance reads instanceof Topic.Checkpoint. Ambient via generalized PipelineContext registry (Class→record, from IncusResourceBootstrap.ContextRegistry), READ/WRITTEN ONLY when a transition builds an input — determinism rule. Backref REJECTED (Sink→B forward supersedes it). Census of 7 pipelines done. KEY: the codebase already HAD every role, we just named + uniformized them."
metadata:
  node_type: memory
  type: project
---

## GZ-2 DONE — the one-source-of-truth invariant, applied to all 7 pipelines (2026-07-03)

**The invariant (final, graved in fluent-pipeline-grammar.adoc):** every value a topic consumes it
reaches through a `Supplier` that resolves at the source of truth (the owner — `State`/sub-pipeline)
on each read; a topic NEVER holds a copied reference. Rationale the user insisted on: a copied
reference is a constant only at the instant it is read — when the system later evolves (mid-flow
write, reordering, a slot gaining a second writer) the copy silently drifts from its source, and that
drift is hard to find because nothing signals divergence. `Supplier` delegation makes it impossible by
construction. **"!!! one source of trust !!!"** (user).

Applies to BOTH input kinds, no exception: flux (`state::nixInstall`, `state.builder::bootstrap`) AND
inherited ambient (`state::systemdChart`, `this::policy`). This RESOLVES the G4 open question (inherited
ambient direct vs Supplier) in favour of **uniformity → Supplier**: the ambient's owner is the State, so
it delegates its read like any slot. The earlier "inherited ambient passed direct" rule is RETIRED.
Sole admissible bare value: a trivially eager single-use read a topic merely FORWARDS (e.g.
`ResourcesTopic` forwards config/policy to `createResources`), where delegating buys nothing.

**Idiom.** Topic holds `Supplier` fields, unwraps at the verb head (`final var chart =
this.systemdChart.get();`); a read spread across many methods (Incus `HostStage`) keeps a private
accessor (`context()` → `context.get()`) so bodies change only `context.`→`context()`. Owner exposes
the accessor; transition wires the method-ref. **`state::accessor` IS the Supplier** (compiler
synthesises it, capturing the owner) — do NOT add explicit `Supplier` fields on the owner, that is
ceremony for the same call path (settled with user after a full back-and-forth). systemd-synth is the
reference exemplar.

Commits: systemd-synth `bba655a9` (reference) · TargetChecksum `fa9b8315` · ClusterSeed ResourcesTopic
`8f463496` · Incus 4 topics `f5a539c7` · Application was already conform. All green under
`-Pall-worlds,nxmatic`. Incus note: it builds-then-runs sequentially (Supplier defers nothing there),
converted anyway for uniformity + latent-drift safety — no per-pipeline exception for the future
`PIPELINE_PATTERN` gate (F0) to special-case.

**Still open (post-GZ-2, cosmetic/governance):** F0 `PIPELINE_PATTERN` governance gate; F1 inner-prefix
renames (`PipelineState`→`State`, `PipelineInputs`→`Inputs`). LiveGate chantier is DONE (`809d42d3`).

## Status (2026-07-03)

Design **converged** on the whiteboard `.claude/claude-preview.adoc` (full C4/Mermaid there — read it
for the diagrams). Materialization **started**: `Topic<I,O>` + `PipelineContext` in the pipeline-port.
This memory is the durable summary so we don't re-litigate.

## The decided shape — `State<I,B>` + `Topic` (identification) + `Topic.Sink` + ambient `PipelineContext`

Each topic reads a narrow **input record `I`** (only what it consumes) and PUSHES its output through its
nested **`Topic.Sink`** into the accumulator `B`. It does NOT return an output — so `State` has no `O`
parameter (it would be phantom), and `Topic` is NOT `Topic<I,O>`.

```java
final class State<I,B> {             // per-pipeline shape; I moves per topic, B fixed for the pipeline
  final I inputs;                    // flux + ambient FUSED into I at the transition, @NonNull, safe alone
  final B builder;                   // set-once accumulator; topics push into it via their Topic.Sink
  final PipelineContext context;     // ambient registry — touched ONLY by the transition
  final FluentTopicRunner runner;
}
// port topic contract = IDENTIFICATION, not O run(I) (a topic is a fluent multi-verb builder)
// nature is a nested TYPE, not a returned enum — a topic implements one of the three.
interface Topic {
  String role();
  interface Sink {}                  // nested: a sink is the write-face OF a topic, not top-level
  interface Execution  extends Topic {}
  interface Checkpoint extends Topic {}
  interface Pipeline   extends Topic {}
}
```

**`B` parameterized, `O` dropped (user, 2026-07-03).** `B` is FIXED for the pipeline (grows as outputs
fold in, type never changes); `I` MOVES per topic. `O` was a phantom once the topic pushes via
`Topic.Sink` instead of returning — same code smell we refused for `Topic<S extends Sink>`. Single
accumulator: `B` carries all outputs so far; a fan-in topic's input is built by reading several off `B`
at the transition.

**API vs impl (user, 2026-07-03).** A topic's API = its fluent verbs + `role()` + its nature type. Its
`(inputs, sink)` construction is IMPL the transition wires; the sink is never on the API → `Topic.Sink`
is NESTED, not top-level. A topic sees only its frozen `I` + its `Sink` — never `B`, never the context.
Direct accumulator-field writes (`pipeline.x = v`) are a DERIVATION the `PIPELINE_PATTERN` gate catches.

**Sink vs context — orthogonal (user, 2026-07-03).** Context = READ source, STATIC (frozen before topic
1, an input of the pipeline). Sink = WRITE channel, FLOWING (one per topic, wired at the transition,
how an output enters `B`). The Sink is also what CLOSES the back-reference question: a topic never walks
back — the accumulator holds forward (via sinks) everything a downstream topic could want.

**Read-face: `Supplier` is the CANONICAL dual of the sink `Consumer` (user insight 2026-07-03, refined at G4).**
The owner (`State`) holds a slot's reference at ONE point and hands out a capability: WRITE = `x -> state.f = x`
(the sink `Consumer`), READ = `state::f` (the source `Supplier`, a free method-ref on the owner's accessor —
"the state becomes its own supplier", user). The topic never names `state.builder`; it holds only capabilities
the transition wired. NOT a determinism violation (caller supplies it over a set-once monotonic slot — my
initial "value by default" objection was WRONG, user corrected). `source == Supplier` — NO `Topic.Source` type:
reads don't aggregate per consumer (the aggregate of a topic's reads is already `I`); dual of `Sink` is `I`,
dual of `Consumer<O>` is `Supplier<X>`. Honest asymmetry: WRITE is FORCED into Consumer (multi-verb topic can't
return); READ is a CHOSEN coherence (a bare value also compiles) — adopted because reads spread across a topic's
verbs just like writes. REVISED CRITERION: the Supplier read-face is the DEFAULT (not "only when deferred") —
it is the coherent decoupling. A bare value is admissible only for a trivially eager single-use read where
delegating buys nothing.

**Inherited ambient (direct) vs sibling flux (Supplier) — the input split at the constructor (G4).** A topic's
constructor args are either: (a) AMBIENT inherited from the parent (precedes the pipeline: `SystemdChart`,
`SystemdSynthesisContext`) → passed DIRECT, no Supplier (nothing to delegate — no in-pipeline owner); or (b)
FLUX produced by a sibling topic → `Supplier` read-face. Same `I` flux/ambient split, applied to constructor
args. TARGET END-STATE (user's compass): a topic holds ONLY Suppliers (reads) + one Sink (writes) + direct
ambient — never an upstream topic, never a public getter. OPEN for GZ: does inherited ambient ALSO become a
Supplier (full uniformity) or stay direct? decide at GZ.

**Applied at G4 (SystemdInfrastructureSynthesizer, done):** the 4 topics dropped the whole-upstream-topic
derivation for `Supplier<SystemdService>` read-faces (`state::nixInstall`…), all public getters deleted. No
`ToolsOutput`/`Rke2InstallOutput` aggregation (readers pick disjoint individual services — same verdict as
A2''s deleted `PathOutput`). `ResourcesTopic` (G1b) fan-in was a direct value; revisit under the revised
canonical-Supplier criterion at GZ. Bound `runDuring<S extends Topic>` + uniform Supplier pass both at GZ.

**Sink is OPTIONAL — a Topic.Execution can be a pure EFFECT (G3, user 2026-07-03).** A topic does not always
produce an output-value to fold into `B`. It may perform a side EFFECT: mutate an external builder (a cdk8s
`App`/`Chart` produced by an earlier stage — cdk8s is build-by-side-effect, `synth()` walks the mutated tree)
or write to disk. `DefaultManifestSynthesisService`'s `systemd units`/`target finalization`/`synthesis`
stages have NO sink — nothing to add to `B`. They stay topics for the NARRATION (during/then label, phase
logging, per-phase TopicFailure), not for a flux. Doc's "pushes its output through its sink" was too absolute
→ "…OR performs an effect". An output type would be phantom on an effect topic (same reason State has no O).

**Ambient verdict FINAL (3 pipelines observed, G1b-2 resolved 2026-07-03).** `PipelineContext` is FOR
pipeline-level ambient ONLY — transverse data the TRANSITIONS read, shared across a pipeline + its
sub-pipelines. NOT a blanket `Inputs` replacement. Incus needed it (mid-flow `registry.update`, parent↔sub
shared) → cured. ClusterSeed/Application did NOT (clean set-once inputs, no mid-flow write, no sub-pipeline) →
left as-is (converting = compile-time→runtime downgrade). Two shapes it must NOT absorb, both from G3:
(1) DEEP-RENDER ambient — `ManifestSynthesisContext` is a THREAD-LOCAL read by 20+ UNITS via the
`ManifestsUnitVisitor` (not topics); threading an instance to each unit is what the thread-local avoids → stays
thread-local. KEY (user, verified in source): it carries the SAME KIND of data as PipelineContext — pure
pre-run ambient (the host→OSGi `ManifestSynthesisRequest` + pre-synthesis age key), NOT accumulated flux. The
two differ on ONE axis: DEPTH OF THE READER. PipelineContext = read by TRANSITIONS (surface, explicit
instance); thread-local = read by UNITS (deep, ambient by necessity — an explicit instance would contaminate
20+ non-topic unit signatures). Surface ambient → PipelineContext; deep ambient → thread-local. Neither is
"better"; different reader depth. (2) FLUX-mistaken-for-ambient — `SystemdSynthesisContext` is built INTO the `Targets` output
record and read off the accumulator → belongs in `B`, not context. So G3 = stages-only (done); the plan's
"retire ManifestSynthesisContext into PipelineContext" is CANCELLED (false premise).

**Build-by-side-effect: sink-less topics ↔ thread-local CO-OCCUR, same cause (G3, user insight 2026-07-03).**
In DefaultManifestSynthesisService the real accumulator is NOT our `B` — it's the EXTERNAL cdk8s tree
(App/Chart built by setup, mutated by every later stage, walked by synth()). `Topic.Sink` and
`PipelineContext` are the WRITE/READ faces of OUR `B`; when work lands in an external accumulator, BOTH faces
recede together: no value to push (sink-less effect topic) AND ambient must reach deep out-of-pipeline code
(thread-local, not stage-threaded context). Pipeline ORCHESTRATES the subsystem (narration/order/phase-fail),
doesn't CONTAIN it. NOT 1:1: `domain registry` both pushes a sink AND triggers the thread-local deep-render;
`synthesis` mutates disk with no thread-local. Correlation = "external accumulator ⇒ both I/O faces of B
recede", not "sink-less ⇔ thread-local".

**Naming — the `Pipeline` prefix is top-level only (user, 2026-07-03).** A type carries `Pipeline` only
when top-level and the prefix qualifies it (`PipelineContext`, `ClusterSeedPipeline`). Inner
classes/interfaces/records inherit the qualifier from the outer pipeline, so the prefix is redundant
there: use `State` (NOT `PipelineState`), `StateBuilder`, `Launch`, `Awaiting<Topic>`/`<Topic>Done`. The
old flat `PipelineState`/`PipelineInputs` names drop to `State` / the input record's own name on
migration. `Topic<I,O>` and `PipelineContext` are top-level port types (no enclosing class).

**Two roles, never conflated:**
- **flux** (data produced by an upstream topic) → carried by the record `I`, compile-time, `@NonNull`
  by construction, built explicitly at the transition. A miss is impossible.
- **ambient** (transverse, known before the first topic: modes, orchestrators, services, charts) →
  the `PipelineContext` registry, `require(Type.class)` runtime-checked. A miss = mis-wiring caught at
  the first run (fail-fast), never an "order-dependent not-yet-produced".

## THE determinism rule (user, 2026-07-03) — the load-bearing insight

The context is a mutable `Class→record` aggregator; nothing technically stops a topic from WRITING a
record mid-flow. If it could, a topic's input would depend on the runtime context state at execution
time → **non-deterministic**, order-coupled — the exact flat-bag disease we flee, just wearing a clean
type. So: **the context is read AND written ONLY when a transition builds an input. Never inside a
topic.** Consequences: (a) the topic does NOT see the context — signature `run(I)`, not
`run(I, context)`; (b) the transition is the single point of contact (read flux + read ambient → fuse
into a frozen `I` → optionally write newly-available ambient for later transitions); (c) flux and
ambient meet inside `I`, and where each field came from is a transition implementation detail the topic
need not know. This turns a *typed* contract into a *typed + deterministic + localized* one — that's
what makes the pattern hold over time, not just compile.

## What's REJECTED (don't reopen)

- **Backref chain** (walk previous states, lookup-by-input-class): answers a non-adjacent access that
  NEITHER real pipeline has; a miss is a runtime throw that would weaken the flux's compile-time
  guarantee. Rejected in favour of the record `I` built explicitly at each transition.
- **Typed-map lookup for flux**: same runtime-miss trade. The map/registry stays for AMBIENT only.
- **Recursive generics `State<I,O,Prev>` (HList)**: illegible in Java, fights the "clear roles" benefit.
- **Factoring the transition classes** (a generic `runDuring` on the shared state + `(R) this` cast):
  the grammar doc's own Pitfall — the type-state IS the deduplication. `State<I,B>` is a per-pipeline
  SHAPE; the transition classes stay explicit and hand-written per pipeline.
- **`Topic<I,O>{O run(I)}`** — a wrong intuition briefly materialized at the port. Mono-shot, but topics
  are fluent multi-verb builders. Replaced by `Topic` (identification) + `Topic.Sink`.

## What IS shared at the pipeline-port (the materialization)

- `Topic` — the IDENTIFICATION contract: `role()` + nested `Topic.Sink` marker + the three nested nature
  types `Topic.Execution`/`Topic.Checkpoint`/`Topic.Pipeline` (a topic implements one). The runner's bound
  `runDuring(<S extends Topic>…)`; the governance/retrofit hook (`instanceof Topic.Checkpoint`). NOT `O run(I)`.
- Nature = a nested TYPE, not a returned enum. `TopicNature` enum + `nature()` method DELETED (2026-07-03):
  the type already carries the nature; `instanceof` beats a stringly switch, and the three interfaces are the
  entry points where a nature-specific shared contract could later attach (empty today). Symmetric w/ `Topic.Sink`.
- `PipelineContext` — the ambient registry, generalized from `controlplane.incus.ContextRegistry`
  (register/require/lookup/contains — NO `update`, `Class→record`, fail-fast `require`).
  `IncusResourceBootstrap` becomes a CONSUMER; its local `ContextRegistry` deleted (done, ca30367a).
- `FluentTopicRunner` / `OnFailure` / `TopicFailure` — bound `runDuring(<S extends Topic>…)`, else no
  change.
- Bases `CheckpointTopic` / `PipelineTopic` — emerge later, NOT at the pure port (jGiven lives elsewhere);
  `PipelineTopic` NOT extracted per A5 (honest duplication).

## The census — all 7 rke2lab pipelines (scan 2026-07-03)

APPLIES: `ApplicationPipeline` (study case, linear), `ClusterSeedPipeline` (flux + fan-in at
ResourcesTopic), `IncusResourceBootstrap` (applies best — already on ContextRegistry),
`DefaultManifestSynthesisService` (proto-State<I,O>: named outputs Scaffold/Registry/Targets +
fan-in at SystemdUnitsStage). APPLIES-WITH-RESERVE: `TargetChecksumPipeline` (single accumulator
`targetChecksums` — I/O records oversized). DIVERGES/NA: `SystemdInfrastructureSynthesizer` (topics are
side-effect builders into SystemdChart, implicit fan-in via ctor injection — retrofit below),
`FrameworkLaunchPipeline` (degenerate single-`during("boot")` preset). `ResourceCreationPipeline` =
plain staged builder (`withX().build()`), outside the type-state pattern.

**Key finding — the OTHER generalizable logic:** three ad-hoc ambient contexts were each reinvented —
`ContextRegistry` (incus), `ManifestSynthesisContext` (thread-local, manifests),
`SystemdSynthesisContext` (systemd). The unified `PipelineContext` replaces all three.

## Two non-conformities — DECIDED (user 2026-07-03)

1. **`SystemdInfrastructureSynthesizer` retrofit — IN the atomic wave.** Today the flux carrier is the
   topic INSTANCES themselves (State threads `toolsTopic`/`rke2InstallTopic`; downstream topics take
   them by ctor + read via `@Nullable`+`requireNonNull` getters `getFloxInstallService()` etc.). Only a
   handful of `SystemdService` handles actually cross a topic boundary; the rest is pure side-effect
   into `SystemdChart`. Flux traced: Tools exposes nixInstall/floxInstall; Rke2Install consumes Tools,
   exposes bootstrapEnv/install; Network consumes Rke2Install; Storage consumes Tools + Rke2Install
   (FAN-IN). Retrofit: output records `ToolsOutput(nixInstall, floxInstall)`,
   `Rke2InstallOutput(bootstrapEnv, install)` (Network/Storage → Void); input records `Rke2InstallInput(ToolsOutput)`,
   `NetworkInput(Rke2InstallOutput)`, `StorageInput(ToolsOutput, Rke2InstallOutput)`; each topic keeps
   its `this`-returning verbs + gains a `toOutput()` terminal, public getters DELETED; ambient
   (`SystemdChart`, `SystemdSynthesisContext`) moves to `PipelineContext`. Gain: cross-topic deps become
   explicit + compile-time. Cost: real chantier (4 topics + synthesizer rewritten), side-effect into
   SystemdChart stays.
2. **`FrameworkLaunchPipeline` — materialize the 3 topics.** The topics EXIST in the logic (merged, not
   a javadoc fiction): `bootEmbedded()` chains `BootPlanner.plan(BootRequest…)` (discovery + plan, PURE)
   then `FrameworkLauncher.launch(plan)` (EFFECT) — the pure/effect seam the javadoc describes is real,
   but collapsed into one `during("boot")`, and there is NO `PlanDone` class though the javadoc promises
   "inspect the pure plan at PlanDone without booting Felix". Decision: materialize discovery→plan→launch
   as a real type-state entry (delivering the promised inspectable `PlanDone`); `embedded()` stays the
   preset shortcut on top, javadoc corrected (it chains, it does not expose states).

## Pilot = `IncusResourceBootstrap` (user, 2026-07-03) — richest case reveals the hole

Pilot is NOT the linear `ApplicationPipeline` — deliberately the RICHEST pipeline, to surface what a
linear pilot would hide (user: "en prendre un plus riche/complexe comme premier cas d'étude, ça
révèlera ce qu'on aurait pu rater"). It exercises every hard axis: real ambient registry, 10-field
accumulator, fan-in, AND a mid-flow registry write.

**The hole it revealed (before writing code):** `ApplyState.registry` (the `ContextRegistry` we planned
to generalize) is a fourre-tout — ONE genuine ambient (`ControlplanePolicy`, registered in `apply()`
before topic 1) + FOUR outputs (`DeploymentMetadata`, `ProvisioningMetadata`, `BuildMetadata`,
`RuntimeMetadata`, produced by Host/Provider stages). The prototype we wanted to lift was itself
conflating flux and ambient. Under the design: ambient → `PipelineContext`, outputs → accumulator `B`.
And `ProviderStage.ensureImage`'s `registry.update(BuildMetadata)` mid-flow (the motion the determinism
rule forbids) is NOT a legitimate ambient enrichment — `BuildMetadata` is a TWO-CONTRIBUTOR output
(Host: manifests, Provider: imageChecksum). In `State<I,B>` it splits into two distinct outputs fused
at the fan-in (`toResult`); the `update()` disappears as the symptom it was.

**Decision (user 2026-07-03): materialize `PipelineContext` WITHOUT `update()`** (register/require/
lookup/contains only). Revisit whether a legitimate ambient-`update` need exists at the A5 retro —
decide empirically on what the conversion shows, not on hypothesis. This is why the pilot matters: the
`update()` question would never have surfaced on the linear pipeline.

## Sub-pipelines + the three topic-body natures (user, 2026-07-03)

**Sub-pipeline pattern (`pipeline → sub-pipeline1, sub-pipeline2, …`).** A topic whose body is itself a
`during/then` chain, with its OWN `State<I,B>` (own `B`, own topics), returning ONE composite output
to the parent. The parent sees one slot in its `B`, one `O`; the sub-pipeline encapsulates its
complexity → the parent's `B` stays small, each `I` narrow. Not speculative — the codebase ALREADY
nests: `ApplicationPipeline`→`ClusterSeedPipeline`; `ClusterSeedPipeline`→`ResourceManager`→
`ResourceCreationPipeline`; `DefaultManifestSynthesisService`→`SystemdInfrastructureSynthesizer`. We
just name the pattern. **Port rule:** a sub-pipeline SHARES the parent's `PipelineContext` (ambient is
common: config/policy/services) but its `B` is strictly local.

**Three natures a topic's body can have** (answers "do we have jgiven's scenarios in the pipeline
model?" — yes, as a topic BODY, not a topic):

- *execution* (`Topic.Execution`) — does a gesture, pushes output via its `Topic.Sink`.
- *checkpoint (narrated)* (`Topic.Checkpoint`) — runs a jgiven scenario → a `ReportModel` narrative + verdict.
  Only TWO today: `ClusterReadinessTopic`, `SystemdAdapterTopic` (via `*Scenario extends Stage<>`). "One engine
  (JGiven), two layers": `during/then` orchestrates, jgiven narrates INSIDE a topic. Narration serves
  the operator (runbook) — put it where a human must READ the result, not everywhere.
- *pipeline (nested)* (`Topic.Pipeline`) — body is itself a `during/then` chain (own `B`).

**Nature is a nested TYPE, not a returned enum + not an abstract base (REVISED 2026-07-03, on the code +
user insight).** The three natures are nested sub-interfaces of `Topic` (`Topic.Execution`/`Checkpoint`/
`Pipeline`), symmetric with `Topic.Sink`. A topic implements one; governance reads `instanceof
Topic.Checkpoint`. The `TopicNature` enum + `nature()` method were DELETED — the type carries the nature,
`instanceof` beats a stringly switch, and the three interfaces are the entry points where a shared
nature-specific contract could later attach (empty today — honest).

An earlier design gave checkpoint + nested-pipeline an abstract BASE hosting shared ceremony. KILLED on
reading the code: (1) the two checkpoints live in DIFFERENT pipelines (`SystemdAdapterTopic` in
`ClusterSeedPipeline`, `ClusterReadinessTopic` in `ResourceCreationPipeline` which is OUT of the pattern);
(2) the ceremony diverges on ~6 axes, not 2 — the jgiven script, the skip-bodies condition
(`preview && simulated.isEmpty()` vs `preview`), all THREE outcomes (success/preview/failure — `ReadinessAuthority`
STOP/degraded vs `VerificationResult` projection), the consult-checkpoint shape (one observation vs a phase
collection), even the field types (`doctor` Optional vs nullable, `recordedAt` `Optional<Instant>` vs `Instant`,
sink `Map` vs `VerificationResult`). A template-method base needs ≥6 hooks → the plan's own CAVEAT
("N=2; if hooks ≥3 keep duplication") fires. So the ceremony stays honestly DUPLICATED; `Topic.Checkpoint`
is identification-only. Same for `Topic.Pipeline` (A5 verdict): NOT a base. Extract only if a 3rd checkpoint
or a genuinely shared nested-pipeline accumulator appears.

**Nested-type friction found + accepted:** `implements Topic.Checkpoint` brings the nested `Checkpoint`
type into scope, SHADOWING a same-named domain import (`world.gateway.port.Checkpoint`). Fixed by naming the
domain enum by FQN via a `DOMAIN_CHECKPOINT` constant in each checkpoint. Localized cost of the nesting.

## DECISION — Incus re-decomposed by phases (Option D, user 2026-07-03)

Do NOT take Incus's 4 inherited topics as given. Re-cut by domain sense into 3 phases, each a
sub-pipeline (own `B`, shared ambient `PipelineContext`). Parent Incus becomes `prepare → provision →
launch`:
- *PREPARE* (sub-pipeline): resolvePaths → synthManifests → stageAssets → captureMetadata → syncToHost.
  Returns `PreparedHost`(localPaths, nixosPaths, deployment, provisioning, runtime, manifests). DISSOLVES
  the HostStage monster — its 4 metadata + synth/staging/sync become PREPARE's internal topics, in
  PREPARE's LOCAL `B`, invisible to the parent.
- *PROVISION* (sub-pipeline): ensureProject → ensureNetworks → ensureProfile → ensureImage →
  imageStateConfigMap. Returns `ProvisionedResources`(providerContext, projectName, profileName,
  imageFingerprint, imageChecksum). The image fan-in / former `BuildMetadata.update()` stays LOCAL to
  PROVISION — no mid-flow update, no leak to parent.
- *LAUNCH*: createInstance, fan-in reading `PreparedHost` + `ProvisionedResources`. Returns
  `LaunchedInstance`(instance). OPEN: LAUNCH as a 1-topic sub-pipeline (uniformity) vs a leaf execution
  topic in the parent (pragmatism) — lean leaf unless a 2nd gesture emerges.

Parent `B` = 3 composite slots instead of 10 flat fields. This is the concrete payoff of the
sub-pipeline pattern for the richest case, and it is why the pilot is Incus (the decomposition need only
surfaces on a rich pipeline).

## The Sink closes the back-reference question (2026-07-03)

The codebase already had every role — we just hadn't NAMED or generalized them. Sharpest example:
yesterday's back-reference chain (walk previous states to find an upstream output by input-class) was
answering "how does a topic reach a non-adjacent upstream output?". The `Sink` (+ its accumulator `B`)
answers the SAME need, FORWARD: every topic PUSHES its output through its `Sink` into `B`; `B` retains
all outputs so far; the transition READS `B` (flux) + context (ambient) to compose the next topic's
`I`. So a topic never walks back — the accumulator already holds forward everything a downstream topic
could want, and the transition picks the exact `I`. Yesterday we rejected backref and chose
"inputs-safe-alone, `I` built at the transition" on paper; we didn't see the FEEDING mechanism
(`Sink`) was already present in `EnvironmentTopic`. The forward-accumulator is alive because of the
sink. Mapping of "already there vs false trail": ambient → `ContextRegistry`→`PipelineContext` (was
there); reach an upstream output → backref-walk (FALSE trail) vs `Sink`→`B` forward (was there); write
output → `O run(I)` mono-shot (FALSE trail I briefly materialized) vs `Sink` push multi-verb (was there,
in EnvironmentTopic).

**Correction (2026-07-03): the topic contract is a fluent multi-verb builder + Sink, NOT `O run(I)`.**
`Topic<I,O>{O run(I)}` was a wrong intuition materialized in the port — it models a mono-shot call,
but our topics are multi-verb fluent chains (`env.loadX().loadY()`, each verb `this`-returning, pushing
its part via the sink as it goes) executed by `runDuring<S>`. Remove `Topic<I,O>` from the port. The
canonical sink is the multi-method interface (à la `EnvironmentTopic.Sink`), not a mono-record
`Consumer<O>` — it fits topics that produce progressively across verbs. Incus's direct-field writes
(`prepare.localPaths = x`) are a DERIVATION to fix: route through a per-topic Sink so the topic is
decoupled from `B` and testable in isolation.

## A5 retro verdict (2026-07-03, Incus pilot done)

- **No `SubPipelineTopic` base.** Read the 3 hand-written sub-pipelines (PreparePipeline/
  ProvisionPipeline/LaunchPipeline) side by side: the only common part is `runner`/`onFailure`/
  `bootstrap = context.require(BootstrapContext.class)` + the `runDuring→assemble record` skeleton
  (~3-4 lines). Everything else diverges structurally — each accumulator is a set of TYPED fields
  (not a map, deliberately, to keep flux compile-time), the topics/bodies/upstreams/output type all
  differ. A base would force heavy generics for ~4 lines of gain and couldn't abstract the typed-field
  accumulators without reintroducing a runtime map. This is the caveat "honest duplication beats the
  leaky abstraction" — visual regularity of the three (same ossature) is convention-uniformity, enough.
  Revisit only if a later pipeline shows a genuinely shared sub-pipeline shape.
- **`update()`-free confirmed.** Splitting BuildMetadata into two producers (PREPARE manifests +
  PROVISION imageChecksum) recombined at `toResult` removed the only `update` use → `PipelineContext`
  stays register/require/lookup/contains, no update.
- **`toResult` = the parent's terminal verb = `build()` of the parent B** (3 composite slots →
  BootstrapResult). Kept the name `toResult` (a documented terminal verb); sub-pipelines use `run()`
  as their `toOutput`.
- Cleaned the dangling `ApplyState` javadoc left above PreparePipeline.

## Still to settle (final design, before/while materializing)

- Where `PipelineContext` lives: pipeline-port (`osgi/foundation/pipeline`, beside FluentTopicRunner) —
  chosen; `IncusResourceBootstrap.ContextRegistry` then deleted.
- flux/ambient boundary rule: *produced by a topic → flux (record I); known before the first topic →
  ambient (registry)*. Verify nothing order-conditional leaks into the registry (e.g. `outputBuilder`,
  `bboxOrchestrator` are ambient — wired at launch).
- `StateBuilder` vs fan-in: single accumulator carrying the outputs (proposed) vs each transition
  carrying only the last O. Single accumulator is simpler; confirm it doesn't recreate a mini-bag.
- Scope of the atomic wave: `ApplicationPipeline` first as proof, then the rest — or migrate all at once
  (CLAUDE.md uniformity, no "legacy" variant).

See [[null-safety-set-once-fields-monotonic]] [[null-safety-optional-from-source-to-resolver]]
[[options-always-as-c4-diagrams]] (the whiteboard is where this was drawn).
