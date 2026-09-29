---
name: pipeline-migration-strategy-revised
description: "REVISED migration strategy (2026-07-09, supersedes the one-pipeline-at-a-time plan in seed-spec): the migration unit is a DOMAIN not a pipeline; the target is to KILL the observability grammar (Topic.Checkpoint/Pipeline, during/then) — NOT Topic.Execution; the seed host pipeline dissolves into sow→sub-scenarios; Topic.Execution (manifests value-threading) survives, extracted to its own clean port."
metadata:
  type: project
---

**Context:** the seed-broker realization (Phases 1-2 shipped: the one broker door + the
world-gateway→seed-broker-port rename) forced a rethink of HOW the pipeline→BDD migration should be
done. The old plan (seed-spec §migration) was "one pipeline at a time, ClusterSeedPipeline first,
fold its phases into @ScenarioStages IN seed-master, the other 6 coexist on the fluent model." Two
scans (controlplane + manifests-core) invalidated its hypotheses. See [[gateway-is-rest-in-jvm-insight]]
[[pipeline-modules-destined-to-disappear]] [[cluster-seed-execution-state]].

## Four revisions the scans forced

**1. Migration UNIT = a DOMAIN, not a pipeline.** The controlplane scan (2026-07-09) found seed-master
holds ~10 classes of pure domain reasoning stranded host-side, whose real home is the domain's OSGi
`-core`: `ClusterBootstrapReadinessVerifier`→cluster; `SeedSystemdAdapterRuntimeStatusSnapshot` +
`SeedSystemdAdapterEndpointGate`→systemd; `BboxReconciliationOrchestrator` + `BlueprintRowEnumerator`
→bbox; `ManifestLinkPolicy` + `ControlplanePolicy`→manifests; `IncusIdentityMaterialAssembler` +
`SystemdTarget`→incus/manifests; `ObservationView`→doctor. Destination is the DOMAIN, not seed-master.
So a "pipeline" doesn't fold into seed-master — per domain: (a) re-dispatch its reasoning to its
`-core`, (b) its scenario lives in its `-bdd` played in-container, (c) the host keeps only the
Pulumi-irreducible (`*Resource`, the edge) + a broker `sow`. The host pipeline DISSOLVES into a
cascade of `sow`→sub-scenarios (the germination cascade), it is not re-hosted.

**2. Target = kill the OBSERVABILITY grammar, NOT Topic.Execution.** `Topic` (pipeline-port) has THREE
natures: `Topic.Execution` (value-threading, "most topics"), `Topic.Checkpoint` (plays a jGiven
scenario → narrative + verdict), `Topic.Pipeline` (a during/then chain). The BDD migration kills only
Checkpoint + Pipeline + the fluent `during/then` — the OBSERVABILITY half. `Topic.Execution` is a
DIFFERENT, legitimate abstraction (functional composition with type-safe ordering) that must NOT be
forced into a scenario.

**3. manifests-core is `is-a-pure-synthesis-pipeline-that-does-not-fit`.** The manifests scan
(2026-07-09) is unambiguous: all 10 of manifests-core's `Topic`s are `Topic.Execution` (0 Checkpoint,
0 Pipeline), used for value-threading a CDK8s synthesis (`Scaffold`/`Registry`/`Targets`,
`SystemdService` nodes) that ends in `app.synth()` writing YAML/unit files. NO ReportModel, no
narration, no probe, no verdict — the output is FILES, not a played scenario. `SystemdInfrastructure
Synthesizer` emits systemd artifacts but is a manifests-internal codegen detail, NOT a systemd-domain
scenario candidate. Forcing manifests into jGiven would be an error (synthesis ≠ observability).
Counts: 10 `.during(` sites (6 in DefaultManifestSynthesisService, 4 in SystemdInfrastructureSynthesizer),
10 Topic.Execution impls.

**CORRECTED (2026-07-09, code scan) — the value-threading grammar has FOUR prod user-clusters, not
one, and netplan is NOT one of them:**

- **manifests-core** (`DefaultManifestSynthesisService` + `SystemdInfrastructureSynthesizer`, 10 sites)
  — CDK8s synthesis → YAML/unit files. SURVIVES.
- **incus** (`TargetChecksumPipeline` in seed-master: cloud-init→registered-components→collect Map,
  `ChecksumSink extends Topic.Sink`, `Topic.Execution` stages) — a deterministic FOLD of sources to a
  checksum artifact, NOT a scenario, NOT Pulumi. SURVIVES → belongs in incus-core.
- **seed host** (`EnvironmentTopic`/`OutputsTopic`/`ApplicationPipeline`) — facts/outputs threading.
  DIES (host pipeline dissolves into sow→scion).
- **boot** (`FrameworkLaunchPipeline.Embedded implements Topic.Execution`, effect, NO sink). DIES
  (becomes the scenario's first step).
- **netplan does NOT use the grammar.** `DefaultNetplanSynthesisService` is a bare builder chain;
  `SynthesisCommand`'s `.during("synthesis", …)` is the BOOT preset (`FrameworkLaunchPipeline.embedded()
  .during`), not value-threading. The prior "manifests-cli/netplan-cli use the grammar" claim was a
  false positive — corrected.

**4. USER DECISION (2026-07-09): kill the observability grammar alone; Topic.Execution survives,
extracted to a shared FOUNDATION port.** Because TWO surviving domains value-thread (manifests
synthesis + incus checksum fold) and neither is a scenario nor Pulumi, the rule-of-two says a shared
foundation port, NOT a specialization inside manifests-core (that would force incus to reimplement the
grammar or depend on manifests — an absurd inversion). So: (a) migrate the seed's Checkpoint/Pipeline
usage to jGiven scenarios (per-domain, revision 1); (b) EXTRACT `Topic.Execution` + `Sink`/`Supplier`
into a shared foundation port; (c) `pipeline-port` then disappears — its observability types die with
the seed migration, its synthesis core survives renamed. This finally SPLITS the two abstractions
pipeline-port conflated. **NAME deferred (user, 2026-07-09): `synthesis-port` is the working name but
"pas si parlant que ça" — fix it on the stabilized extracted code, not the projection; the perimeter is
manifests-core + incus, NOT manifests-only.** `FrameworkLaunchPipeline` (boot), `BulletproofPipeline`/
`SoclePipelineTest` (engine tests), `junit-testkit/Pipeline.java` are NOT the grammar — false-positive
name hits, they stay.

## What "cluster first" means now (re-anchored)
NOT "fold ClusterSeedPipeline phases into seed-master stages." It is: move
`ClusterBootstrapReadinessVerifier` into cluster-core, let `cluster-bdd` (already exists, fork B 2a)
own the readiness scenario, host keeps `ClusterReadinessResource` (Pulumi) + a `sow`. cluster is first
because cluster-bdd already exists.

## seed-broker-bdd (the host ROOT) — content mapped, NAME deferred
The cascade: root = HOST (Pulumi sows the first seed = launches the root scenario); blooms = domains
(scenarios played in-container). The 33 files of controlplane/bdd split three ways:
- **STAYS host root:** the composer `ClusterSeedScenario`; injection machinery `HostSeeder`/`HostFacts`/
  `StageContext`; runbook render `RunbookRenderer`/`ClusterSeedRun`/`PendingMarkingScenarioExecutor`/
  `SeedAborted`/`SeedProbes`; the Pulumi-native stages `Preflight`/`Bbox`/`Incus`/`Resources`/`Outputs`
  + live probes; the checkpoint stages `SystemdAdapterStage`/`ClusterReadinessStage` (they sow+graft — host).
- **DESCENDS to a domain-bdd:** `ClusterReadinessScenario` (seed-master copy, doubled by cluster-bdd —
  the AVANT to delete once graft is wired); `SystemdAdapterScenario`→a future systemd-bdd; their domain
  probes (`ClusterReadinessProbe`/Simulated, `SystemdAdapterProbe`/Simulated).
- **SORTS elsewhere:** `ObservationView`→doctor/broker; `MedicalRecordDump`/`RecordInterventionCommand`
  → standalone host CLIs (doctor-facing, not the seed scenario).
Distinction that holds: a checkpoint STAGE stays host (sows+grafts); the SCENARIO it played descends to
the domain (that IS 2a→graft). NAME of the host root ("seed-bdd" vs "seed-broker-bdd") DEFERRED until
the re-dispatch shows the exact host residue — decide on stabilized content, not projection.

## E1 re-anchored
"a -port sheds type=seam when 0 host files import it" is now a MEASURABLE CONSEQUENCE of the
re-dispatch: each domain-reasoning class moved host→OSGi drops one `-port`'s host-import count toward 0.
Re-dispatch IS the E1 chantier. Baseline host-import counts: doctor 9, manifests 8, bbox 8, cluster 6,
systemd/incus 4, netplan 2, auth 1.

## THREE names deferred until the code is extracted (decide together, on real shape)
The user's discipline: name on stabilized content, not projection. All three fix together when the
synthesis port is extracted:
1. **The host root** (composer + Pulumi stages + graft): `seed-bdd` vs `seed-broker-bdd` — pick after
   re-dispatch shows the exact host residue.
2. **The shared fluent DERIVATION grammar** (Topic.Execution + Sink, staying IN the renamed
   pipeline-port — in-place rename, user-chosen 2026-07-09).

   **ROOT MOTIVATION FIRST (user, 2026-07-09): this is NOT "the remnant of pipeline-port after we
   killed observability" — it is a first-class thing the system wanted all along: ONE common fluent
   API for expressing a DERIVATION, wherever a derivation occurs in the system.** Removing the scenario
   natures did not CREATE this port, it REVEALED the root. So the naming question is not "what to call
   what survives" but "what to call the system's shared fluent derivation grammar" — the port is merely
   where that grammar lives. Frame everything (module name, unit name, docs) from that root, positively.

   **LEANING `derivation`/`fold` (on trial), NOT `synthesis`.** Why not the product-name: the two real
   users produce DIFFERENT natures of thing — manifests-core → `ManifestSynthesisResult` (a MATERIAL
   artifact, `app.synth()` writes YAML + systemd unit files); incus `TargetChecksumPipeline` →
   `Map<String,String>` (computed checksums, an in-memory VALUE, no artifact). "synthesis"/"assembly"
   name the PRODUCT, which VARIES and breaks for incus ("assemble a checksum" is meaningless). What is
   CONSTANT across every use is the ACT — the derivation itself: thread values through ordered fluent
   steps, each pushing named contributions into an accumulator, deterministic, no narration/verdict.
   The honest name is on the DERIVATION (constant, and it IS the root subject), not the product
   (variable): `derivation` (module `derivation-port`, unit `DerivationStep`) or `fold` (shorter,
   functional, the accumulator literally IS a fold — but more cryptic). ON TRIAL: grave it, see if it
   holds over time. netplan is NOT a user (its `.during` is the boot preset, not this grammar).
3. **The UNIT** (today `Topic`): `Topic` was a borrowed word — its impl is ALREADY called `Stage` in
   the code (`new Stage(input, sink)`, per Topic.java's Sink javadoc). Candidates: `Stage` (honest,
   but COLLIDES with jGiven `com.tngtech.jgiven.Stage` that the BDD scenarios extend — two Stages) or
   `Step` (no jGiven clash). Leaning `Step`. `Topic.Sink`→`<Unit>.Sink`. Note: `Topic.Checkpoint`/
   `Topic.Pipeline` do NOT get renamed — they DIE with the seed→jGiven migration; only `Topic.Execution`
   survives as the new unit.

## Next step (not yet done)
Rewrite seed-spec §migration on this revised strategy BEFORE coding. Then per-domain: cluster first
(re-dispatch ClusterBootstrapReadinessVerifier + wire the graft). The seed-spec still carries the OLD
one-pipeline-at-a-time plan — it is now the stale AVANT.
