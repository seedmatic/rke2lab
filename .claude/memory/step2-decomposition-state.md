---
name: step2-decomposition-state
description: "Step 2 (decomposition + APIs, the docrepo->rke2lab migration track) — DESIGN SHIPPED (4 OSGi planes + 2 axes + static->dynamic 6-stage roadmap; Model B REJECTED). SLICE 1 SHIPPED 689e2fff (BootstrapConfig relocate). SLICE 2 = OSGi BENCH built+green on branch refactor/config-extender 2026-06-17, ready to squash-merge: real bnd bundles + real embedded Felix in tests proving P1 (Require osgi.extender resolves/refuses) + P2 (typed Metatype OCD by PID). Scope WIDENED (user choice): Step 2 IS the bundle decomposition, so real bnd build + real engine, not 'cheap resolution-only'. The dep bump (spring-boot 4, cdk8s 2.70) rode along and turned out REQUIRED for JDK25 (old byte-buddy can't read Java 25 bytecode) — NOT pure scope-creep. NEXT after merge = slice 3 (doctor split, cartography-first)."
metadata: 
  node_type: memory
  type: project
  originSessionId: 2a081aec-b4d1-4baf-a048-40c7d5fa7f04
---

**Chantier = Step 2** of the docrepo/unitrepo absorption: decomposition + APIs (the migration
track). Step 1 (retire the walker) is SHIPPED to origin/main (merge `641d7782`): the Felix
`UnitResolver` drives production manifest synthesis behind the type-state
assembled->resolve()->coherent; `resolve()` is the single coherence-rule coordinator. The shipped
resolver IS the decomposition ORACLE. See [[hub:docrepo-dag-state]] STEP-2 TARGET, [[coherence-rules-coordinator]].

## Workspace
- Worktree: `rke2lab.d/design/step2-decomposition-spec` (branch `design/step2-decomposition-spec`,
  base origin/main). External-worktree model, sops re-smudged. NOTHING committed yet on the branch.
- Preview file `.claude/claude-preview.adoc` holds the latest diagrams (overwritten per topic).
- jdeps analysis scripts + data in `/tmp/step2-jdeps/` (global.edges, analyze.py, harden.py,
  frontier.py, host-classes.txt) — EPHEMERAL, regenerate if gone (compile with
  `-Dmaven.build.cache.skipCache=true` first, then jdeps -verbose:class -filter:none over the 8
  non-incus modules; build-cache replay leaves target/classes EMPTY otherwise — [[build-verification-gotchas]]).

## Method agreed (the operating frame)
- **Spike-first, then spec.** Design follows the proof (user reversed "pure design spec"). The oracle
  must EXECUTE on candidate cuts, not be hand-judged. Docs land in `wip/specs/` + `wip/plans/`
  (NOT `wip/superpowers/`, and NEVER mention "superpowers" in artifacts — user instruction
  2026-06-16; supersedes [[hub:superpowers-assets-in-wip]] path).
- **Cuts derived from REAL class coupling** (jdeps over bytecode), not hand-transcribed — this is what
  makes the oracle honest (a refusal is a discovery, not a confirmation). Crossing edges = the API/SPI
  catalogue: host->pure concrete = API to export; pure->host = leak to invert into an SPI.
- **Two phases per frontier: ESTABLISH first, VALIDATE (resolve()) second.** Incremental — one
  frontier at a time. Node granularity = class then aggregate; clustering = objective metric (SCC +
  articulation points) crossed with manual reading.

## Phase 1 RESULTS (bottom-up, DONE + hardened)
Graph = 284 coupled classes, 1099 edges, 26 host-marked (import com.pulumi/io.grpc), sdks/incus
excluded (175 generated-gRPC = host-obligatory). Three galaxies:
- **orchestration** `controlplane.*` (pipeline/incus/resources/bbox/readiness) — host-heavy; hub =
  `IncusResourceBootstrap` (articulation deg 46).
- **manifests** `manifests.*` — PURE, closed on itself, 0 Pulumi.
- **bridge** = `bdd -> pulumi.automation` (28 edges, the densest cross-role edge: pure diagnostic over
  the persistence substrate).
Hardening (7 SCC cycles): 6 are intra-package (healthy). The ONE cross-module cycle =
`unitrepo <-> manifests` (5 classes: ManifestsUniverse/CrossDomainRule/ManifestsVisitOrder <->
ManifestsDomainRegistry/CoherentManifestsDomainRegistry) — **SOLDERED**, so the resolver-adapter stays
INSIDE the manifests bundle, not a separate module. **Specialists proven EXTRACTABLE**: no back-edge
(pure consumers of the vocabulary); they are NOT in the record-model SCC of 10.

## Phase 2 RESULTS (top-down découpage, user broadly agrees)
Candidate role-based modules: **doctor-core** (record model + vocabulary + 3 seams, pure) /
**specialist-plugins** (one Unit each) / **ledger-read** (near-pure, folds history) / **ledger-write**
(host, imports com.pulumi) / **config** / **orchestration** (host) / **manifests+adapter** (soldered) /
leaves. Line = pure MODELS describe, HOST actualises over the engine ([[hub:model-substrate-alignment]]).

**Composite specialist model CAPTURED (not built):** independent -> cabinet -> clinic -> hospital are
the SAME kind (a Unit) at different composition granularity; a composite aggregates members'
Provide/Require; resolver wires every level identically. Capturing it is what FORCES one-specialist-one-Unit
at the leaf. BUILD scope = independent specialists only; "living" restructuring = deferred v2 (hot-swap edge).

## The PIVOT (the key insight of the session)
Establishing the doctor frontier revealed **config is the cas-zero of the generic bundle<->host
contract**; the doctor is just an INSTANCE of the same contract. Evidence in code: `InfraDomain` (enum,
config package) already half-codes it — each constant `contribute(ConfigLoader)` declares its needs
(`loader.requirePath("incus","configDir")` etc.) and the host loader satisfies them. So config/bootstrap
is the FIRST vertical slice: nailing it DEFINES the contract every other bundle reuses.

Findings proven from the graph:
- `BootstrapConfig` is NOT a host leak — a pure record (Path/URI/Duration, 0 Pulumi) derived from
  `Rke2labConfig`, only MISLOCATED in host `controlplane.incus`. Its sole outgoing dep = Rke2labConfig.
  Resolution = RELOCATE to the pure config layer (not invert). Then 8 `bdd->incus` edges become `bdd->config`.
- The config seam is INVERTED today: pure `Rke2labConfig -> ConfigLoader` (host, imports com.pulumi.Config).
  The SPI `SectionReader` already exists; `Rke2labConfig` short-circuits it by depending on the concrete loader.
- **NO bootstrap loop**: neither UnitResolver nor ManifestsUniverse consume config (grep empty) — the
  resolver is pure computation over UnitResource, so model B has no self-reference paradox. Risk LIFTED.

## Model B was REJECTED by the OSGi completeness review (2026-06-17) — SUPERSEDED

Model B (config keys as a custom `unitrepo.config.key` resolution namespace, each key a
Provide/Require the resolver wires) was drawn, user chose it, and a spike proved it works
*mechanically*. THEN the user asked "what did we miss from the OSGi standard?" — a completeness
review grounded on the real jars (`~/.m2/repository/org/osgi/`, NOT memory) showed B is INFIDELE:
the standard keeps config OUT of resolution. See [[hub:check-osgi-standard-before-modeling]] (the meta-lesson).

## CORRECT MODEL — the 3 OSGi planes (verified on bytecode)

Config splits across three planes the standard separates; B had conflated them:
- **RESOLUTION** (static, the oracle applies): a bundle `Require osgi.extender`; the host `Provide`s it.
  `osgi.extender` is a REAL namespace (`org.osgi.namespace.extender.ExtenderNamespace`, value
  `"osgi.extender"`, attr `version`, extends `org.osgi.resource.Namespace` — fetched + verified 1.0.1).
  This is the bridge to resolution: config touches resolve via the DELIVERY MECHANISM (DS=`osgi.component`,
  Metatype=`osgi.metatype`), NOT per-key.
- **DELIVERY** (runtime): Config Admin (`org.osgi.service.cm`) pushes values by PID (`ManagedService.updated`),
  AFTER resolution. Schema described by **Metatype** (`ObjectClassDefinition`+`AttributeDefinition`: type,
  cardinality, required/optional, default) — which `InfraConfigFragment` + `InfraDomain`'s typed keys already
  REINVENT.
- **ACTIVATION** (DS): `configuration-policy=require` → component state `UNSATISFIED_CONFIGURATION` if no
  config. THIS is the "loud fail on missing config" we wanted — at activation, not resolution.

**Re-framed cas-zero (better, OSGi-native): the generic bundle↔host contract = a bundle `Require
osgi.extender`, the host `Provide`s it.** Config is the FIRST instance (it requires the config-delivery
extender). NOT the invented per-key namespace.

**SPIKE PROVEN at the right grain** — `ConfigExtenderResolutionSpike` (seed-master test scope, replaces the
deleted per-key `ConfigCapabilityResolutionSpike`): a bundle `Require osgi.extender=osgi.metatype/osgi.component`,
host `Provide`s them, resolver wires (Tests run: 2, surefire-counted, skipCache); a missing extender throws
`ResolutionException` (loud, not silent). NOT yet committed.

Still true from before: `BootstrapConfig` = pure record, RELOCATE from host `incus` to pure config layer
(sole dep = Rke2labConfig); the `Rke2labConfig -> ConfigLoader` seam is INVERTED, `SectionReader` SPI exists.
No bootstrap loop (resolver consumes no config). Vocabulary: `InfraConfigFragment` is MISNAMED (collides with
OSGi fragment = host-attached bundle) — it's an `ObjectClassDefinition`; RENAME.

## ★ THIRD STRUCTURING AXIS (found 2026-06-17) — the attach MECHANISM

Orthogonal to role and to purity (model/host): HOW a Unit joins the system. Three mechanisms, all with a
PROD exemplar already in the reactor:
- **Closed composition** (today): add a domain = EDIT the centre (`InfraDomain` enum, hardcoded
  `ManifestsDomainRegistryBuilder.register` chain). No resolution footprint.
- **Fragment / host-attach** (`osgi.wiring.host`): a piece names a HOST and merges into it without
  modifying it. Exemplar **`SystemdDropIn`** (cdk8s-systemd, IN PROD): names `targetUnitName`, writes a
  separate `unit.d/x.conf`, systemd merges at runtime. For AUGMENTING an existing host.
- **Extender / discovery** (`osgi.extender`): a runtime SCANS autonomous contributors and aggregates.
  Exemplar **`NodeEnvContributor`** (manifests, IN PROD): `ServiceLoader.load` over `META-INF/services/`,
  no central edit. For rendering a WHOLE NEW autonomous domain to the system.

**Key finding: `NodeEnvContributor` IS already the extender version of `InfraDomain`** — same concept
("a domain declares `domainId()` and contributes"), two maturities (enum-closed vs SPI-discovered). So the
enum→extender trajectory is not hypothetical; start AND end both exist in the reactor. The three gestures to
migrate: enum→interface (`domainId()`+`contribute`), each constant→a class + a `META-INF/services` line,
the domain bundle `Require osgi.extender`. This is a migration ORTHOGONAL to the config-plane migration —
two independent axes on the same `InfraDomain` object.

## Atlas (the migration is THIS)

rke2lab `docs/architecture/integration-atlas.adoc` EXISTS, doctor-only, explicitly awaits a 2nd subsystem —
**our Step 2 IS it**. docrepo atlas holds inventory lines to port (Pulumi-mediation, bundle-decomposition,
live-resolution, stack-as-unit). Ritual = monotone before/after. The config-as-extender realizes the
Pulumi-mediation / extender edge; to be drawn from the proven spike. wip docs redistribute into the durable
atlas at merge (the slice's green boxes flip blue).

## NEXT (resume here) — CONSOLIDATING

State: exploration rich, NOTHING committed, the in-`wip` spec still describes rejected Model B.
1. (this consolidation) commit `ConfigExtenderResolutionSpike`; rewrite `wip/specs/2026-06-17-config-bundle-host-contract-design.adoc`
   to the 3-plane model + the two orthogonal axes (purity, attach-mechanism); re-run atlas ritual in the spec.
2. THEN resume the attach-axis: dig the FRAGMENT end (which domains/units AUGMENT a host vs are discovered),
   `SystemdDropIn` as the reference exemplar.
3. Open decision still pending: scope of the FIRST shippable slice (config 3-plane alone? + enum→extender? +
   the BootstrapConfig relocation + SectionReader inversion?).

## ★ FOURTH PLANE — the service registry (verified on bytecode 2026-06-17)

Beyond resolution/delivery/activation there is a 4th OSGi plane: the **service registry** (runtime,
DYNAMIC). `BundleContext.registerService`/`getServiceReference`, `ServiceListener`
(REGISTERED/MODIFIED/UNREGISTERING), scopes (`service.scope` = singleton/bundle/prototype). Services
come and go WHILE the framework runs — vs resolution which is a static snapshot. DS bridges it
(`@Component` publishes a service, `@Reference` consumes+rebinds). **This is the plane that carries the
"living" vision** (recruit/restructure specialists & domains at runtime) — previously filed vaguely as
"hot-swap v2", now named. `ServiceLoader` (what NodeEnvContributor uses today) is the STATIC, poor
cousin of the registry; the live trajectory goes through the registry, not ServiceLoader.

## ★★ THE ROADMAP — introduce the axes STATIC → DYNAMIC (session-exit deliverable 2026-06-17)

Ordering principle (user choice): static→dynamic — each stage additive, rests on the prior, fidelity to
OSGi (resolve before activate before service), risk grows left→right. Four planes (resolution / delivery=
Config Admin / activation=DS / registry=runtime) × two orthogonal classification axes (purity model/host;
attach-mechanism closed/fragment/extender). The 6 stages:

1. **STATIC decomposition** — role+purity cut, modules validated by the resolver ORACLE (resolution plane
   only). Lowest risk (oracle shipped Step 1). Includes BootstrapConfig relocate, manifests soldered-adapter
   stays one bundle, doctor-core vs ledger-read vs ledger-write split.
2. **CONFIG as cas-zero** — the bundle↔host contract via `Require osgi.extender`; config schema = Metatype;
   RENAME InfraConfigFragment. Still resolution-plane (the extender requirement). Spike PROVEN.
3. **DELIVERY** — Config Admin-style by PID; invert the `Rke2labConfig→ConfigLoader` seam behind SectionReader.
   Values leave resolution, arrive at runtime.
4. **ACTIVATION** — DS `configuration-policy=require` — the loud-fail on missing config at the RIGHT level.
5. **ATTACH-MECHANISM opening** — `InfraDomain` enum → extender (domains discovered, not enumerated), using
   NodeEnvContributor as the prod exemplar. (Orthogonal migration to the config-plane one.)
6. **REGISTRY runtime** — the LIVING plane: recruit/restructure at runtime, hot-swap; specialists & composite
   cabinets become live services. Highest risk, gated.

Status: PROVEN/shipped = Step-1 resolver, config-extender spike, SystemdDropIn + NodeEnvContributor exemplars.
DESIGNED-not-built = stages 1–5 frontiers. DEFERRED v2 = stage 6.

## SESSION 2026-06-17 (afternoon) — SPEC REWRITTEN, ATLAS ENRICHED, all 3 tasks DONE

**★ SHIPPED to origin/main 2026-06-17 (origin/main = `d5a23458`, fast-forward, 3 doc-only commits).**
Branch `design/step2-decomposition-spec` MERGED + worktree REMOVED + branch deleted (the design phase is
done; impl is a fresh branch). The 3 commits:
- `b633d486` spike (was 27b3c740 pre-rebase) — the config-extender spike, unchanged.
- `f4cc599d` — **REWROTE** `wip/specs/2026-06-17-config-bundle-host-contract-design.adoc` to the correct
  model: 4 OSGi planes (resolution/delivery/activation/registry) + 2 orthogonal axes (purity model/host;
  attach closed/fragment/extender) + static→dynamic 6-stage roadmap + atlas additivity test. Model B now
  recorded ONLY as the rejected alternative (§4). §7 digs the FRAGMENT end; §12 decides the first slice.
- `d5a23458` — **ENRICHED the durable atlas** `docs/architecture/integration-atlas.adoc`: new section
  "The two spaces — OSGi and host" (variant A: two swimlanes, the purity axis made STRUCTURAL), placed
  between legend and per-subsystem index so every view reads against it. User explicitly valued this.

TASK RESULTS (all closed this session):
1. ✅ Spec rewritten (above). 2. ✅ FRAGMENT end dug — `SystemdDropIn` is the SOLE prod fragment instance
   (1 site: rke2lab-server-hooks); fragment = augment-an-existing-host (names targetUnitName, merges a
   delta, `osgi.wiring.host`) vs extender = new-autonomous-domain (NodeEnvContributor, ServiceLoader, 6
   impls). `NodeEnvContributor` IS the extender-form of `InfraDomain` confirmed in code. 3. ✅ FIRST SLICE
   DECIDED = **Stage 1 static decomposition + the BootstrapConfig relocate** (lowest-risk, resolution-plane
   only, oracle-backed); the proven config-extender contract is slice 2, not slice 1 (sequence keeps each
   merge monotone vs the atlas).

## SLICE 1 SHIPPED to origin/main 2026-06-17 eve (`689e2fff`)

Slice 1 = **the `BootstrapConfig` relocate ALONE** (host `controlplane.incus` → pure
`controlplane.config`, sole dep `Rke2labConfig`). Pure ~33-call-site `import` rewrite, 265 tests green.
Merged direct (no PR — rke2lab is solo, see [[hub:...]] dev prefs). Commits on main:
- `a2cdb0d3` spec §12↔§8 reconcile; `9f722c6d` the relocate; `e51f7bd9` FQN/shell cleanup;
  `5abe3f39` spec corrections; `689e2fff` test-infra (separate dedicated branch, squashed).

**THREE design facts CORRECTED this session by a FRESH jdeps pass (the /tmp data was stale/wrong) —
the merged spec now carries the fixes:**
1. *Edge count was wrong.* `BootstrapConfig` is imported by **33 classes across 9 packages** (bdd 10,
   pipeline+stages 10, resources 5, systemd 3, readiness 2, config 2, incus 2), NOT "8 bdd→incus edges".
   The relocate is a ~33-site mechanical move.
2. *The doctor split is NOT a relocate — it is boundary CREATION.* There are **no `doctor*`/`ledger*`
   packages today**; the record model + read-side + host write-side are interleaved across
   `bdd`+`readiness`+`resources`. So it needs a cartography phase first (map class→module), THEN express
   as Provide/Require for the oracle. Re-scoped OUT of slice 1 to its own later slice. (This reverses the
   "bigger cut" decision once the data showed the split's true nature.)
3. *There is NO OSGi framework runtime in production.* Felix is used only as a resolution ALGORITHM
   (`new ResolverImpl(...)` in `UnitResolver`); no `BundleContext`/Config Admin/DS/Metatype runtime;
   entrypoint is plain `main()`→Pulumi. So roadmap stages 3–4 (DELIVERY=Config Admin, ACTIVATION=DS) are
   a real architectural LIFT (stand up/emulate a runtime), NOT small increments. Only stages 1–2
   (resolution plane) + Metatype-as-descriptive-types are cheap.

## NEXT SESSION — SLICE 2 DECIDED (user, 2026-06-17): config-extender

**Slice 2 = config-extender (stage 2), resolution-plane, CHEAP — CHOSEN.** Build it next:
- The `Require osgi.extender` contract — already spike-proven (`ConfigExtenderResolutionSpikeTest`,
  `@Tag("spike")`); promote that proof into the real config bundle/host wiring.
- Adopt the Metatype *shape* (`ObjectClassDefinition` + `AttributeDefinition`) as PURE descriptive types
  — no runtime. `InfraDomain.contribute` + `InfraConfigFragment` already reinvent this schema inline.
- RENAME `InfraConfigFragment` → it's an `ObjectClassDefinition`, NOT an OSGi fragment (the vocab
  collision). Real config keys for the Metatype schema are listed lower in this file (INCUS/IMAGE/…).
- Stays entirely on the resolution plane → oracle-backed, no OSGi runtime needed (see corrected fact 3).
- Real config keys (from InfraDomain): INCUS requires configDir (opt project/defaultRemote/remoteAddress);
  IMAGE requires sharedFolder (opt alias/builderHost/distrobuilderConfig); NETWORK opt
  lanBridgeParent/vmnetNetworkName/nfsAutomount; WORKTREE requires dir; SYSTEMD opt dbusHost/dbusPort;
  HOST opt rotationRetentionCount.

- **Slice 3 (LATER) = the doctor split**, cartography-first (see corrected fact 2). Bigger/riskier; do the
  class→module mapping before expressing borders to the oracle.
- Stages 3–4 (delivery/activation) gated on the runtime-lift decision (fact 3) — sequence deliberately.
- Also invert the `Rke2labConfig → ConfigLoader` seam behind the existing `SectionReader` SPI (stage 3).
- jdeps scripts are EPHEMERAL (`/tmp/step2-jdeps/`); regenerate per [[build-verification-gotchas]]
  (build `-Dmaven.build.cache.skipCache=true` first, then jdeps over the 8 non-incus modules). Slice 1
  proved the /tmp data can be STALE — always re-derive load-bearing coupling claims on fresh bytecode.

## Test infrastructure (shipped this session, `689e2fff`) — see [[test-tag-taxonomy-by-zone]]

Tagged the suite by zone/cost (host/osgi/live/spike), added `GrpcChannelNoiseCapture` (host-space JUL
extension), unified `*LiveTest`/`*SpikeTest` naming, tag-driven default exclusion (`live | spike`).
Also surfaced [[seedlog-logback-migration-backlog]] (host-space logging unification, deferred).

Real config keys (from InfraDomain, for Metatype schema): INCUS requires configDir (opt
project/defaultRemote/remoteAddress); IMAGE requires sharedFolder (opt alias/builderHost/distrobuilderConfig);
NETWORK opt lanBridgeParent/vmnetNetworkName/nfsAutomount; WORKTREE requires dir; SYSTEMD opt dbusHost/dbusPort;
HOST opt rotationRetentionCount.

## SLICE 2 — OSGi BENCH (built + green 2026-06-17, branch refactor/config-extender, ready to squash-merge)

Disposable reactor subtree `osgi-bench/` = aggregator + 4 leaf modules: `osgi-bench-config` +
`osgi-bench-host` (bnd-maven-plugin bundles carrying Require/Provide `osgi.extender`),
`osgi-bench-testkit` (reusable `FelixFrameworkExtension` Jupiter extension + `@OsgiSpike` composed
tag), `osgi-bench-tests` (P1+P2). bnd 7.3.0, version in PARENT pluginManagement. Production config
(`InfraDomain` etc.) NOT touched — bench = scaffolding; real config adopts the pattern later by MOVE.

- **P1**: real embedded Felix resolves config's `Require osgi.extender` vs host's `Provide`, refuses
  when host absent. **P2**: unknown client reads an `ObjectClassDefinition` by PID via the real
  felix.metatype `MetaTypeService`, TYPED — enabled by `system.packages.extra`; see
  [[osgi-test-in-vscode-three-ways]] (3 approaches spiked; only this one is typed AND VSCode-clickable).
- **VSCode harness PROVEN**: plain JUnit5 + Jupiter extension, runs from Test Explorer on a click.
- **Dep bump REQUIRED, not creep**: spring-boot 3.3.12→4.1.0 is needed on JDK 25 (old byte-buddy can't
  read Java 25 bytecode → seed-master won't compile without it); + cdk8s 2.70, bnd 7.3, spotless 3.7,
  etc. Compile+unit green only — a `-Plive` pass (gated on [[pulumi-stack-per-worktree-backlog]]) is
  PRIORITY backlog before trusting `pulumi up`. Verify via FULL `clean package -Posgi
  -Dmaven.build.cache.skipCache=true` (partial `-pl :seed-master` runs gave FALSE failures from stale
  target/ — [[build-verification-gotchas]]).
- **Atlas**: config view added as 2nd ritual subsystem, monotone proof.
- **Lessons (user feedback)**: (1) git-hygiene — branch carries atomicity commits (dead-code/review
  fixes, successive refactors) that should've been squashed per-task; squash-merge fixes it, but be
  cleaner next time. (2) MEMORY ISOLATION BUG — the home memory dir symlinks to `main/.claude/memory`,
  so session memory edits LEAK into main's working tree instead of the branch. Fix this session: copy
  the files into the branch worktree + commit ON THE BRANCH (memory = branch work, arrives in main via
  merge). Durable fix (TODO): make the memory symlink follow the current worktree, not hard-point main.

See [[osgi-test-in-vscode-three-ways]], [[hub:check-osgi-standard-before-modeling]] (the meta-lesson),
[[hub:docrepo-dag-state]], [[coherence-rules-coordinator]], [[hub:model-substrate-alignment]],
[[hub:specialist-as-ledger-northstar]], [[build-verification-gotchas]], [[hub:diagram-preview-file]].
