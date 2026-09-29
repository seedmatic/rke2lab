---
name: osgi-runtime-r1-scr-state
description: "IMPL slice R1: stand up SCR — ★ SHIPPED to design/target-module-layout (squash merge bdc71678, 2026-06-18); worktree feature/osgi-runtime-r1-scr torn down. (1) added org.apache.felix.scr 2.2.18 to the BOM + the 3 DS-runtime API jars it imports but osgi.core lacks; (2) testkit gained a topology builder() + listener-based awaitService() (ServiceTracker, NOT poll); (3) 3-module proof osgi-bench-scr-{api,provider,consumer} + 2 spike tests proving @Component PUBLISHES and @Reference BINDS, with the anti-cheat (consumer unsatisfied when provider absent). Re-verified from the integration worktree before merge: -Posgi green (5 tests, 4 surefire reports, 0 skipped), full reactor green (33 modules). NEXT = R2 (declare the 5 SPIs @Component, geste A only)."
metadata:
  node_type: memory
  type: project
---

## What shipped (R1 = the geste-B proof, twin of the bench P1/P2)

Implemented on worktree `feature/osgi-runtime-r1-scr`, base `design/target-module-layout` @7d8e9873.
All four deliverables done and GREEN:

1. **BOM** — added `org.apache.felix.scr` **2.2.18** (`felix.scr.version`). PLUS three DS-runtime
   API jars that felix.scr imports as MANDATORY but `osgi.core`'s system bundle does NOT carry:
   `org.osgi.service.component` 1.5.1 (carries `service.component` + `.runtime` + `.runtime.dto`,
   a DIFFERENT artifact from the *annotations* jar), `org.osgi.util.promise` 1.3.0,
   `org.osgi.util.function` 1.2.0. Without these, `felix.scr` fails to RESOLVE at launch.

2. **Testkit** (`FelixFrameworkExtension`) — two new gestures:
   - `startScr()` installs+starts `org.apache.felix.scr` and blocks until `ServiceComponentRuntime`
     appears (proof the SCR extender is live).
   - `awaitService(Class<T>, long)` / `awaitService(String, long)` — **listener-based via
     `ServiceTracker.waitForService(timeout)`, NOT a busy poll** (the user explicitly rejected the
     poll the bench had used). `org.osgi.util.tracker` is in `osgi.core`, already a dep → free.
   - `SCR_API_PACKAGES` constant = the versioned `system.packages.extra` string for the DS-runtime
     API packages; SCR tests append their own shared api package to it.
   - Also migrated `MetatypeIntrospectionSpikeTest` off its `for 50 × 10ms` poll to `awaitService`.

3. **Proof bundles** — `osgi/osgi-bench/osgi-bench-scr-{api,provider,consumer}` (3 modules, NOT
   one). `api` = `Greeter` + `GreetingClient` interfaces, exported. `provider` = `@Component(service
   =Greeter.class)`. `consumer` = `@Component(service=GreetingClient.class)` with a mandatory `1..1`
   `@Reference Greeter`. bnd generates the `OSGI-INF/*.xml` + `Service-Component` from the
   annotations (geste A, proven). Wired into the bench parent + `osgi-bench-tests` (scope test).

4. **Tests** (tagged `@OsgiSpike`, in `osgi-bench-tests`):
   - `ScrActivationSpikeTest.componentPublishesAndReferenceBinds` — PUBLISH (Greeter in registry) +
     BIND (GreetingClient appears only once the @Reference is bound; `greeting()` == "hello, scr").
   - `ScrUnsatisfiedReferenceSpikeTest.consumerStaysUnsatisfiedWhenProviderAbsent` — the ANTI-CHEAT:
     consumer started WITHOUT the provider bundle → stays unsatisfied, GreetingClient never published.

## Testkit shape — builder of topology (user-driven refactor)

`FelixFrameworkExtension` is a **JUnit5 (Jupiter) extension** (`BeforeAll/AfterAllCallback`) — NOT
an OSGi `osgi.extender`; it merely LAUNCHES the framework where SCR's extender lives. The user asked
whether the framework setup could be injected; outcome = a `builder()` that DECLARES the topology
(`.withScr()` / `.systemPackages(...)` / `.installFromClasspath(...)` / `.installBundles(...)`) run
in `beforeAll`, leaving the `@Test` body with only the proof (`awaitService` / `resolve`). The
anti-cheat now reads in the topology itself (`.installBundles("scr-consumer")` with no provider).
All 4 bench test classes migrated; the old public no-arg/string constructors + public
`startScr()`/`installFromClasspath()` are GONE (now builder-driven / private). `bundle(classifier)`
returns a builder-installed bundle for tests that need the handle (Metatype). Did NOT add a
`ParameterResolver` (param injection) — orchestration order matters and the builder covers it.

## Two non-obvious design decisions (would re-bite if forgotten)

- **3-module split is REQUIRED for the anti-cheat.** SCR activates ALL components in a bundle at
  once; if provider+consumer share a bundle the unsatisfied state is unobservable. Separate bundles
  (like P1's host present/absent) let the test start the consumer with the provider absent. And the
  API must be its OWN bundle so the consumer still RESOLVES when the provider is gone (resolution
  succeeds, activation does not) — API inside the provider would make "provider absent" a resolution
  failure, the wrong proof.
- **ONE exporter of the shared api package, WITH a matching version.** First green-then-red symptom:
  PUBLISH failed because the api package was double-exported — the system bundle (via
  `system.packages.extra`, UNVERSIONED → 0.0.0) AND the installed `scr-api` bundle (`version=0.1.0`).
  The provider imports `[0.1,1)`, so it wired to the bundle copy while the test read the system-bundle
  copy → two distinct `Greeter` Class objects → tracker never matched. Fix: export from the system
  bundle WITH `version=0.1.0` (matches the import) and do NOT install the `scr-api` bundle (its
  classes are already on the classpath = in the system bundle). The Metatype proof never hit this
  because `MetaTypeService` has a single exporter.

## Validation (done)

- `flox activate -- ./mvnw -pl :osgi-bench-tests -am clean test -Posgi -DskipTests=false
  -Dmaven.build.cache.skipCache=true` → **5 tests, 4 surefire reports, 0 skipped, BUILD SUCCESS**
  ([[build-verification-gotchas]]: counted the reports, not just the BUILD SUCCESS line).
- Default build (no `-Posgi`) → "No tests to run" — spikes correctly excluded by the `spike` tag.
- Full reactor `clean install` → BUILD SUCCESS, 33 modules, nothing else broken by the BOM additions.
  (Used `install` to validate; CLAUDE.md prefers `verify` — harmless here, SNAPSHOTs only.) NO `-Plive`.

## Close discipline

- CLOSE = commit everything (code AND `.claude/memory/`), build GREEN ✅, then HAND OFF to the
  `design/target-module-layout` session for the squash-merge — **this session does NOT saw its own
  worktree** ([[merge-from-target-worktree]]).
- Next slices: R2 (declare the 5 SPIs as `@Component`, geste A only), R3 (intra-bundle `@Reference`
  consumption), R4 (host boot seam, `-Plive`). See [[osgi-runtime-migration-state]] §5.

See [[osgi-runtime-migration-state]] (parent spec + R1–R7 chain), [[bnd-annotations-spike-state]]
(the geste-A P1/P2 twin this mirrors), [[osgi-test-in-vscode-three-ways]] (FelixFrameworkExtension +
the system.packages.extra typed-access trick), [[build-verification-gotchas]],
[[standing-autonomy-except-runtime-config]], [[merge-from-target-worktree]].
