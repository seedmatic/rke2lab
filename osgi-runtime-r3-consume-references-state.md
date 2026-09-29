---
name: osgi-runtime-r3-consume-references-state
description: "IMPL slice R3: consume the intra-bundle aggregate via @Reference. ★ SHIPPED to design/target-module-layout (squash merge c471dbc8, 2026-06-19); worktree torn down. RECADRED after read-only carto challenge (do NOT just follow the spec verbatim): R3 was FOCUSED on NodeEnvContributorRegistry ONLY (the contributor aggregate — the one case with a real intra-bundle consumer). DefaultManifestUpdateGate is PUSHED to R4 (its only consumer is host EntryGatePolicyEnforcer, no intra-bundle consumer to serve). Steps: (1) REFACTOR NodeEnvContributorRegistry — pull NodeEnvContext OUT of the constructor (it's only used by aggregate/write, NOT by discovery) + fix the mutating sort (orderedContributors() sorts the field in place → breaks on an immutable @Reference-injected List, sort a COPY); (2) make it @Component consuming @Reference(MULTIPLE) List<NodeEnvContributor>; (3) KEEP a ServiceLoader fallback for the two framework-less `new` callers (RKE2LabEnvConfigManifestsUnit intra-bundle + IncusResourceBootstrap host) — DUAL-PATH, ServiceLoader removal is R5; (4) PROVE on embedded Felix that SCR injects all 6 contributors (cardinality multiple anti-cheat, twin of R1). @OsgiSpike, -Posgi green, NO -Plive. Set up 2026-06-19, NOT yet implemented."
metadata:
  node_type: memory
  type: project
---

## What R3 is — RECADRED (read the challenge, do NOT follow spec §5 verbatim)

The spec §5 R3 says "inside osgi/manifests only: NodeEnvContributorRegistry → @Component consuming
@Reference(MULTIPLE) List<NodeEnvContributor>; DefaultManifestUpdateGate → @Reference for the synthesis
service." A read-only carto pass (2026-06-19, integration @78d781e4) found the spec is OPTIMISTIC on two
points; the user challenged and recadred:

1. **DefaultManifestUpdateGate has NO intra-bundle consumer.** Its only caller is host
   `EntryGatePolicyEnforcer` (exec/seed-master, via `ServiceLoader.load(ManifestUpdateGate.class)`).
   Wiring it `@Reference ManifestSynthesisService` now would serve nobody intra-bundle. → PUSHED to R4
   (the host boot seam), with the rest of the host consumption. R3 does NOT touch it.

2. **NodeEnvContributorRegistry has TWO framework-less `new` callers**, so R3 is necessarily DUAL-PATH
   (SCR + ServiceLoader fallback), additive like R2 — NOT a ServiceLoader removal (that's R5):
   - intra-bundle: `RKE2LabEnvConfigManifestsUnit:59` `new NodeEnvContributorRegistry(nodeEnvContext)`
   - host: `IncusResourceBootstrap:1289` `new NodeEnvContributorRegistry(layerContext)`
   Neither runs under Felix today (Felix arrives R4/R6), so `new` must keep working.

So R3 is FOCUSED: only `NodeEnvContributorRegistry`, only the contributor-aggregate case, dual-path,
plus the embedded-Felix proof. This is the genuinely-intra-bundle slice; the host cases are R4.

## The refactor (PREREQUISITE — the class is not @Component-able as-is)

`NodeEnvContributorRegistry` today: constructor takes `NodeEnvContext context` and immediately calls
`loadContributors()` (ServiceLoader). Two problems block SCR instantiation:

- **Context-in-constructor conflates two concerns.** `loadContributors()` does NOT use `context` —
  only `aggregateContributions()` and `writeAllContributions()` do. SCR-instantiated `@Component`s
  cannot receive a runtime `NodeEnvContext` via constructor. → Pull `NodeEnvContext` OUT of the
  constructor and pass it as a METHOD parameter to `aggregateContributions(context)` /
  `writeAllContributions(outputDir, context)`. Then the no-context registry is a clean singleton.
- **`orderedContributors()` MUTATES the field** (`contributors.sort(...)`, line ~45). An
  `@Reference`-injected `List` may be immutable → `UnsupportedOperationException` at runtime. Sort a
  COPY (`new ArrayList<>(contributors)` then sort), return that. This is a runtime-only trap, invisible
  to the compiler.

Update the two `new` call sites (RKE2LabEnvConfigManifestsUnit, IncusResourceBootstrap) to the new
method signatures (context passed to aggregate/write, not the ctor). Behaviour identical.

## The @Component + dual-path shape

- `@Component(service = NodeEnvContributorRegistry.class)` (or expose via an interface if cleaner) with
  `@Reference(cardinality = MULTIPLE, policy = STATIC) volatile List<NodeEnvContributor> contributors;`
  (field or constructor-injection — DS supports both; field is simplest given no other ctor args).
- KEEP a ServiceLoader path for the `new` callers. Cleanest: a static factory or a constructor that
  takes the contributor list (used by `new` via `ServiceLoader.load(...).stream()...toList()`), and the
  no-arg `@Component` path where SCR injects the list. Both converge on the same `orderedContributors`
  logic. Do NOT delete `loadContributors` (that's R5) — refactor it into the fallback path.
- The 6 contributors already carry `@Component(service=NodeEnvContributor.class)` (shipped R2), so SCR
  has them to inject. Nothing to add on the provider side.

## The proof (embedded Felix, twin of R1, cardinality-multiple anti-cheat)

In osgi-bench-tests (or a manifests-core test that boots Felix via the testkit builder, [[osgi-runtime-r1-scr-state]]):
- Install manifests-core + start SCR; await `NodeEnvContributorRegistry` service; assert its injected
  list has ALL 6 contributors (the cardinality:=multiple anti-cheat: a registry that resolved only some
  would be the cheat). Tag `@OsgiSpike`.
- Note the typed-access trap from R1: ONE versioned exporter of the shared api package via
  system.packages.extra, else distinct Class copies and the lookup misses.

## Validation

- FULL `flox activate -- ./mvnw clean package -Posgi -Dmaven.build.cache.skipCache=true
  -DskipTests=false` from the worktree; COUNT surefire reports ([[build-verification-gotchas]]).
- Existing manifests-core + seed-master tests must stay green (dual-path → `new` callers unchanged in
  behaviour). NO `-Plive`.

## Workspace / close discipline

- Worktree `feature/osgi-runtime-r3-consume-references`, base `design/target-module-layout` @78d781e4.
  External-worktree model; `.code-workspace` sibling carries CLAUDE_CONFIG_DIR. sops re-smudged at setup
  (.secrets + keys.yaml clean'd). MEMORY dir = `.claude/memory/`.
- Build-time + test only → inside [[standing-autonomy-except-runtime-config]]. Act without asking.
- CLOSE = commit everything (code AND `.claude/memory/`), build GREEN, then HAND OFF to the
  design/target-module-layout session for the squash-merge — **this session does NOT saw its own
  worktree** ([[merge-from-target-worktree]]).

## ★ SHIPPED to design/target-module-layout (squash merge c471dbc8, 2026-06-19); worktree torn down

Done exactly as recadred, all green; re-verified from the integration worktree before merge (-Posgi
clean package, the R3 spike test 1/0-skipped, existing tests green). The worktree's 4 review commits
(below) were squashed into c471dbc8. Commits (granular for review on the worktree; the design session
squashed them on merge — granularity is the reviewable layer, not what lands):

- `ab648e92 fix(deps)` — excluded `org.osgi:org.osgi.core` (the 5.0.0 leak) from felix.resolver in
  unitrepo-core; manifests-core now declares `osgi.core` at COMPILE (its main code used
  `org.osgi.resource` but only compiled off the leak). **A real latent bug** found standing up the proof.
- `f0f7aeb3 refactor(osgi-testkit)` — bench testkit PROMOTED to shared `osgi/osgi-testkit` (neutral pkg
  `io.seedmatic.rke2lab.osgi.testkit`, git-mv history kept); `install()` generalised off the `osgi-bench-`
  prefix; NEW builder verb `exportImportsOf(artifact)` reads the bundle's bnd `Import-Package` header and
  mirrors it as system-bundle exports (fail-fast, single source of truth — replaced a 15-line literal
  systemPackages list). 4 bench proofs + aggregators rewired.
- `446d6483 feat R3` — registry `@Component`+`@Reference(MULTIPLE)`, ctx out of ctor, non-mutating sort,
  `forServiceLoader()` dual-path, the 2 callers updated, `NodeEnvContributorRegistryScrSpikeTest`.
- `d2c176c8 docs(memory)`.

KEY runtime facts learned (beyond the plan):

- **Delayed-component gotcha:** the registry & contributors PROVIDE services, so SCR keeps them
  SATISFIED but does NOT activate (→ does not inject `@Reference`) until the service is REQUESTED. The
  proof must `getService` the registry to force activation, THEN read `boundServices` (=6). A bare
  "is it satisfied" check passes vacuously (0..n).
- **getAllServiceReferences, not getServiceReference:** the latter hides services whose class the
  (system) caller bundle can't load — which the registry/contributor classes are (exported BY the
  bundle, not to the system bundle). Assert via SCR `ServiceComponentRuntime` DTOs, never a cast.
- bnd emitted the registry descriptor `cardinality="0..n" field="contributors"
  field-collection-type="service"` — `@Reference(MULTIPLE)` → `0..n`, confirmed in the jar.

Two new feedback memories born here: [[single-source-of-truth-before-logic]] (the `exportImportsOf`
gesture) and [[no-system-out-use-logger]] (no System.out even in throwaway probes).

CLOSE state: code + memory committed, full `-Posgi clean package` GREEN. Awaiting HAND-OFF to the
design/target-module-layout session for squash-merge (this session does not saw its own worktree).
Two parked threads for the user: the `osgi-bench → bench` rename, and the manifests-core split (the
heavy-bundle systemPackages surface documented it as a candidate, not acted on).

See [[osgi-runtime-migration-state]] (parent spec + R1–R7 chain), [[osgi-runtime-r2-declare-spis-state]]
(R2 declared the @Components this consumes), [[osgi-runtime-r1-scr-state]] (the embedded-Felix proof
pattern + typed-access trap), [[build-verification-gotchas]], [[merge-from-target-worktree]],
[[standing-autonomy-except-runtime-config]].
