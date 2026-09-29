---
name: engine-lifecycle-socle-state
description: "Progress + key design decisions of the engine-lifecycle socle (increment 1 of the BDD-as-engine migration), on branch feature/engine-lifecycle-socle. Carves osgi/runtime/engine and builds the connection/lever/discipline socle the ClusterSeed vertical (increment 2) consumes."
metadata:
  type: project
---

**What.** Increment 1 (the SOCLE) of the BDD-as-engine migration. Spec: `docs/architecture/osgi/engine-lifecycle-spec.adoc` (8 figures). Plan (gitignored scaffold): `docs/superpowers/plans/2026-07-05-engine-lifecycle-socle.md`, 7 TDD tasks. Branch `feature/engine-lifecycle-socle` off `design/pre-integration` tip (8912b292).

**Done (commits on the branch):**
- T1 `refactor(engine): carve osgi/runtime/engine` — new bundle module, the runtime-playable machinery MOVED out of junit-testkit (InContainerJUnitRunner, OutOfContainerFrameworkExtension, FrameworkLog, ScrDiagnostics). junit-testkit left a tag-only bundle.
- T2 `extract JUnitLauncherCore` — neutral core (3 OSGi crossings) + DiscoveryStrategy/HarvestStrategy SAMs; InContainerJUnitRunner is a thin consumer.
- T3 `@Osgi -> @OsgiWorld, add @Pipeline, delete @OsgiSpike`.
- T4 `OsgiConnection` — see decision below.
- T5 `StartLevelLever` (single-cursor lever, descent transient + synchronous; imports BootPlan.START_LEVEL_*).
- T6 `@IsolatedWorld`/`@SeedRuntime` on `BaseWorldExtension` — DONE, committed (821e879f). Test
  `ExtensionDisciplineTest` (mirrors InstanceDisciplineTest, specific→general naming): SynchronousBundleListener
  counts felix.scr STARTED — isolation sawtooths, stationary holds. Nested-under-WorldFixture topology forced by
  JUnit ordering (class @ExtendWith before field @RegisterExtension).
- T7 `test(engine): acceptance BulletproofPipeline + SoclePipelineTest` — DONE. The increment-2 go/no-go, committed.
  SoclePipelineTest (@OsgiWorld @Pipeline) boots Felix via the testkit, drives JUnitLauncherCore<ReportModel> over
  BulletproofPipeline (@SeedRuntime, NO Test suffix → invisible to surefire), asserts: 1 scenario green, 2 top-level
  steps, the 2nd carries 2 nested sub-steps, connect step saw system bundle ACTIVE. All 7 tasks now green; full
  `-pl :seed-master -am package` green, all 7 staging gates 0 error.

  **T7's two load-bearing mechanisms (the launcher instantiates the pipeline, so neither world-IN nor runbook-OUT
  can be a plain call):**
  1. `LaunchedPipelineExchange` — an IMMUTABLE RECORD `(OsgiConnection, ReportModel)` bound to JUnitLauncherCore's
     worker thread (jGiven's own ScenarioHolder ThreadLocal shape). Driver `bind()`s it in the harvest and holds the
     reference; pipeline reads it via the ONE static `current()` (the irreducible membrane crossing). The user drove
     this from a static-helper bag → to an instance → to a record: **inject the model** (driver creates the empty
     ReportModel, pipeline `setModel`s it, jGiven fills THAT instance in place) kills the OUT channel entirely, so
     no mutation, one ThreadLocal, two sanctioned statics (factory `bind` + thread-lookup `current`).
  2. Extension ORDERING: `@ExtendWith(ConnectionSeeder.class)` declared BEFORE `@SeedRuntime` on the pipeline class —
     class extensions run beforeAll in declaration order, so the seeder puts the exchange's non-owning connection in
     BaseWorldExtension's Store BEFORE the discipline's beforeAll, which then finds it instead of calling embedded()
     (can't boot in the engine test module — no staged bundles). A field @RegisterExtension runs too LATE (after all
     class extensions) — the ExtensionDisciplineTest trap. BulletproofPipeline plays STANDALONE
     (Scenario.create+setModel, prod-checkpoint idiom) with a trivial Then stage (Scenario.create instantiates all
     three stage types upfront, so Object.class fails byte-buddy).

**Cross-cutting sweeps done mid-T6 (committed f9b638b4), NOT in the plan — user "fix at the wheel" discipline:**
- Inline JDK FQN → import across 42 files, via OpenRewrite (ShortenFullyQualifiedTypeReferences). On-demand only:
  `./mvnw -Pfix-fqn rewrite:run` (plugin config in build-parent pluginManagement; NOT lifecycle-bound — it
  re-parses the model and collides with maven-embed-staging-ext at every phase). Exclusions: pulumi-generated-sdks,
  package-info, SystemdAdapterTopic/ClusterReadinessTopic (deliberate Topic.Checkpoint-shadow FQN).
- Renamed `rke2lab.manifests.unpack.skip` → `rke2lab.staging.skip`, default relocated to build-parent (was pinned
  =false in each exec pom → shadowed the parent profile). fix-fqn profile flips it true to skip staging during a
  rewrite pass. See [[maven-build-cache-and-staging-verify]].
- System.out/err diagnostics → SeedLog/slf4j (stderr = logging-config target, not an app channel); genuine
  data-output stdout (dump YAML, blueprint export) kept.

**Module renames — DONE 2026-07-05 (after T7, on a green tree).** A recensement confirmed only these two leaves
were too generic; every other leaf is domain-prefixed and every `packaging=pom` aggregator legitimately carries its
dir name. Both renamed as a balayage (git mv dir + package subtree, artifactId/<name>, all imports, bnd BSN+exports,
10 dependent poms, both specs):
- `launcher` → `framework-launcher`, package `io.seedmatic.rke2lab.osgi.runtime` → `…osgi.runtime.framework` (the 4
  boot classes were flat in `runtime`; now symmetric with the sibling).
- `engine` → `scenario-engine`, package `…osgi.runtime.engine` → `…osgi.runtime.scenario.engine` (+ `.container`/
  `.diagnostic`). BSN `io.seedmatic.rke2lab.osgi.runtime.scenario.engine`.
  Bare `launcher`/`engine` no longer exist anywhere. Verified: engine reactor + all in-container consumers
  (doctor/manifests/cluster-edge/dbus-systemd-edge/bench/pipeline-testkit) green; full `-pl :seed-master -am package`
  green, 7 staging gates 0 error (spec-coverage matches type simple-names, so it stayed green throughout).

**Load-bearing design decisions the user drove (do NOT re-litigate):**

1. **Realm/seam split, NOT one flat package.** engine exports only the IN-realm subpackages
   `…engine.container` (InContainerJUnitRunner) + `…engine.diagnostic` (ScrDiagnostics); the SEAM/host-side
   classes (OutOfContainerFrameworkExtension, FrameworkLog, + the socle types OsgiConnection/StartLevelLever/
   disciplines) stay in the base package, UNEXPORTED. Exporting a seam class = DUPLICATE_REALM_CLASS collision.
   "A bundle owns its package" (except fragments) → the in-container install list names the engine BSN, not testkit's.

2. **OsgiConnection is a CONTRACT (context()+ownsLifecycle()+close()), NOT world()→BootedFramework** (the plan's
   coupling). Reasons: the socle only consumes context+close; BootedFramework's ctor is package-private in the
   launcher module (uncrossable from engine); a fine contract asks for a CAPABILITY not a producer identity, so
   it serves every boot (prod embedded / test / future remote). `embedded()` = prod (wraps
   FrameworkLaunchPipeline.launch(), from staged META-INF/bundles — only exec modules stage, so NOT bootable in
   engine's own tests); `over(context, ownsLifecycle, onClose)` wraps an already-booted world; `remote()` throws.
   The socle's tests boot a real Felix via the testkit (OutOfContainerFrameworkExtension) and wrap with over().
   Whiteboard of the fork: `.claude/claude-preview.adoc`.

3. **Null-safety at the boundary** (the machinery is runtime code now → NullAway checks it): genuinely-optional in
   our API → `Optional`, never `@Nullable`; set-once field → `@MonotonicNonNull` + requireNonNull accessor. See
   [[null-safety-optional-from-source-to-resolver]] [[null-safety-set-once-fields-monotonic]].

4. **Build-cache fix:** `.mvn/maven-build-cache-config.xml` now tracks `*.bnd` + root `bnd.bnd` (was untracked →
   stale bundle manifests). See [[maven-build-cache-and-staging-verify]].

**Constraints:** flox + reactor-only (`-pl :engine -am`, never mvn install); tests skipped by default
(`-DskipTests=false`); verify staging/in-container with `package` not `test` (fragments need sibling jars);
`grep -ri pulumi osgi/` must stay 0. See [[options-always-as-c4-diagrams]] [[refactor-statics-on-touch]].
