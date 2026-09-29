---
name: bdd-jgiven-test-strategy
description: "How the user wants tests built — JGiven BDD covering real module use-cases, DSL-first prototype before wiring to main"
metadata: 
  node_type: memory
  type: project
  originSessionId: 28d6a117-e5b7-4227-bca3-9d64e719c38b
---

The user is starting a test discipline for rke2lab (near-zero coverage today: only `Cdk8sApiObjectResolverTest`, `DefaultManifestExplodeServiceTest`, and two netplan tests exist; tests are skipped by default via `.mvn`, run with `-DskipTests=false`).

**Preferences (durable):**
- Tests use **JGiven** (BDD Given/When/Then), not bare JUnit. JGiven is not yet wired anywhere — no dep in BOM/poms as of 2026-06-04.
- Cover **real use-cases of the modules first**, not isolated unit tests. The unit-level `DefaultManifestExplodeServiceTest` (commit fe213bf0) does NOT match this philosophy — revisit whether to keep it as a regression net or fold it into a use-case scenario.
- **GUIDING PRINCIPLE — tests are living documentation.** The user sees the test suite as the BEST way to show how the system behaves and what it's for — better than an operator or user manual, because it's executable and never goes stale. Design implication: Given/When/Then sentences must read as prose describing behavior, and JGiven's generated reports (HTML/AsciiDoc) ARE the deliverable "manual". Scenario naming, stage method `@As` text, and attached arguments should target a human reader, not just assertion coverage.

**Chosen approach (the user's plan):**
1. Design a **global plan** mapping entry points → expected outputs across ALL modules (not just manifests).
2. **Implement the JGiven scenarios first, NOT yet wired to the main modules** — stub/fake stages — to validate that the DSL reads well and captures the use-cases, before paying integration cost.
3. Only after the DSL feels right, wire stages to real module entry points.

**Entry-point map started (manifests module):**
- Point A: `ManifestSynthesisService.synthesize(req)` → manifests.k8s.yaml + systemd units dir + counts. ⚠️ DORMANT: `buildDomainRegistry` throws UnsupportedOperationException (layers→components migration); registrars exist but aren't wired. Verify in code, not just docs, before designing scenarios on it.
- Point B: `ManifestExplodeService.explode(req)` → `<domain>/<package>/<file>` tree by annotation. Healthy; site of the recent config.yaml.d bug.
- Real consumer chains A→B in `IncusResourceBootstrap` (seed-master) ~line 516-523.
- Context slices have easy factories: `BootstrapIdentity.unknown()`, `NetworkTopology.empty()`, `ComponentVersions.defaults()`, `ImageState.unknown()`.

**Full entry-point map (all modules, 2026-06-04):**
- **manifests** — `ManifestSynthesisService.synthesize` (A, dormant), `ManifestExplodeService.explode` (B, healthy), `Main` CLI. SPI-loaded.
- **netplan** — `NetplanSynthesisService.synthesize(req)` → NetplanSynthesisResult (pure derivation, no IO; cleanest BDD target). `NetplanCli` + BlueprintExportCommand/SynthesisCommand. Already has 2 plain-JUnit tests as style reference.
- **seed-master** — `controlplane.Main.main` drives the fluent `.during("bootstrap", b -> b.runBootstrapPipeline())` pipeline → Pulumi/Incus side effects. Hardest to test (external systems); needs heavy fakes.
- **cdk8s-systemd** — `SystemdChart(scope,id).synthesize(outdir)` → .service/.target files. Pure-ish (filesystem out).
- **systemd-contract** — api-only: `SystemdAdapterApiPaths`, `SystemdStatusSnapshot` (DTOs/contracts, little behavior).
- **sdks/incus** — generated Incus API client (low test value, it's generated).

**BDD-readiness ranking (best first):** netplan synthesize (pure) > manifests explode (fs-out, healthy, recently buggy) > cdk8s-systemd synthesize (fs-out) > manifests synthesize A (dormant) > seed-master pipeline (external systems).

**Layering is inter-module (decided 2026-06-04):** operator-level narrative scenarios live in `seed-master/src/test` (the orchestrator the operator actually runs), and later in each `seed-xxx`. Reusable *technical* stages live near the module they exercise (`manifests/src/test`: synthesize/explode). Layering follows the dependency direction (seed-master already depends on manifests). Two-tier JGiven: operator steps delegate to technical steps via `@NestedSteps` so the HTML report reads as an operator guide on top, technical detail underneath.

**Sharing technical stages — start with test-jar, expect to migrate.** Decision: begin with Maven `test-jar` (manifests publishes its stages, seed-master consumes scope=test). The user has bad experience with test-jar (same GAV as main jar + classifier; Maven mishandles transitive test-jar deps / classifier confusion). Accepted as a known risk to avoid creating a module prematurely; migrate to a dedicated `bdd-fixtures`/`test-support` module when the pain hits. Prototype starts in `manifests/src/test` (stages+scenarios together) before any extraction.

**Scenario language = English.** Per [[hub:shared-artifacts-in-english]], JGiven scenario/step prose is living documentation = a shared artifact → write method names and `@As` text in en-US, not French.

**Design decisions locked in the 2026-06-04 brainstorm (ready to resume):**
- *Narrative level:* two-tier (operator narrative on top via `@NestedSteps`, reusable technical steps underneath).
- *Location:* prototype in `manifests/src/test`; target architecture puts operator scenarios in `seed-master/src/test` then each `seed-xxx`; technical stages near the module they exercise. Share via test-jar to start (migrate to bdd-fixtures module when it hurts).
- *First use-cases = a pair* proving the same technical stages serve a dormant and a live case:
  - **Scenario A — operator, synthesize()**: "master deploys only what vcluster provisioning needs" (= the trimming spec, executable). Chosen fake strategy: **call the real `synthesize()` and capture the current failure** — `buildDomainRegistry` throws UnsupportedOperationException. A is a red-expected scenario that documents reality AND becomes the engine that drives restoring buildDomainRegistry. (Not a hand-built tree, not a stub list.)
  - **Scenario B — operator, explode()**: "rke2-config fragments land where the installer can find them" — the config.yaml.d fix told as operator behavior. Branchable for real immediately.
- *Unit test fate:* **fold `DefaultManifestExplodeServiceTest` (fe213bf0) into Scenario B and delete it**; its 6 naming cases become parameterized JGiven `@Case` variants of B. (Matches "tests = use-cases" + no dead code.)
- *Still to do when resuming:* propose 2-3 JGiven wiring approaches (dep coords/versions in BOM), present remaining design sections, write spec to `wip/specs/`, user review, then writing-plans.

**Bigger why (user's stated direction, 2026-06-04):** the user is tired of typing command lines and wants to move toward **automation with a natural-language-oriented interface**. BDD "tests = living manual" is a first step on that path (readable sentences that describe + verify behavior instead of commands to retype). Keep this north star in mind for future tooling choices, not just tests.

**TDD-in-test / BDD-in-main split (decided 2026-06-06, config refactor):** the two test styles
have DIFFERENT jobs and must NOT duplicate coverage.

- **TDD = plain JUnit in `src/test`** = the COVERAGE layer: exhaustive mechanics, every resolved
  value, defaults, accumulation order, edge cases. Keep these (e.g. `ConfigLoaderTest`,
  `Rke2labConfigTest`).
- **BDD = JGiven in `src/main`** = only the BEHAVIOUR THE SYSTEM PLAYS at runtime. A scenario
  earns its place in `src/main` ONLY by being runbook-played (e.g. the config entry gate the
  doctor consults). It asserts the gate OUTCOME (ready vs missing-inputs), and STOPS — it does
  NOT re-assert resolved values (that's TDD's job). Non-played scenarios would be dead BDD weight
  in production.
- Classpath rule confirmed in seed-master pom: **`jgiven-core` is compile-scope** (Stage<…>
  subclasses can live in `src/main`), **`jgiven-junit5` is test-scope** (the `ScenarioTest`
  runner stays in `src/test`). Precedent: SystemdAdapter — `Given/When/Then` stages in
  `src/main/.../bdd/`, `SystemdAdapterScenarioTest` in `src/test`.
- Structure preference: **nested static `Stage<…>` classes** colocated with the class they
  describe (BDD documents the class's real logic for the reviewer), not separate stage files —
  though SystemdAdapter currently uses separate files.

**Test-double policy (decided 2026-06-07).** Mockito is now in the project (parent `<dependencies>`,
test scope; version from the BOM via spring-boot-dependencies, 5.11.0 with byte-buddy 1.14.19 =
JGiven's). Rule, by the NATURE of the seam:
- **Lambda** for a single-method `@FunctionalInterface` with a canned return (e.g. the probes:
  `ClusterReadinessProbe`, `SystemdAdapterProbe`). A lambda is shorter and clearer than mock+when.
- **Mockito** when verifying an INTERACTION (`verify(x, never())…`), doubling a MULTI-METHOD
  collaborator (the doctor's `Specialist`), or faking a STATIC seam — `mockStatic(Deployment.class)`
  for the preview path (`Deployment.getInstance()` returns `DeploymentInstance`, mock THAT type).
- **NEVER** mock a JGiven `Stage<…>` — byte-buddy already subclasses them; the two collide.
The user also wants BDD (Given/When/Then) for UNIT tests, accepting two JGiven uses: `main` (scenarios
ARE prod behaviour) + `test` (readable component tests). Localisation: a stage describing PROD
behaviour lives in `main`; a stage that exists only to test lives in `test`.
**KNOWN DEBT:** Mockito's inline mock-maker self-attaches the byte-buddy agent → "Java agent loaded
dynamically" warning (non-blocking on JDK 25, future-forbidden). A `-javaagent` argLine fix attempt
failed (dependency-plugin `properties` goal didn't substitute the path) and was reverted; revisit
with a tested approach when the JDK enforces it.

**Wiring status (2026-06-07):** JGiven IS wired now (supersedes the 2026-06-04 "not yet wired" note
above): jgiven-core compile-scope in seed-master (main scenarios), jgiven-junit5 test-scope in the
parent (common to all modules). The first real two-tier scenarios exist in seed-master
(SystemdAdapter + ClusterReadiness checkpoints, runbook-played). The dedup pattern is settled: the
TEST drives the production stage's `launch()` with an INJECTED probe (not a re-implemented scenario)
— see [[runbook-doctor-state]] commit 0d2c7a4f. NESTING the Given/When/Then into nested static
classes (the line 70-72 preference) is DONE (commit e3ac4b4d): the six stage files collapsed into
`SystemdAdapterScenario` / `ClusterReadinessScenario`, each holding `public static class
Given/When/Then`; cross-scenario edge kept via `@ScenarioStage SystemdAdapterScenario.Given` etc.
Rendered prose byte-identical (step names = method names, unaffected by the type rename). BDD-style
UNIT tests are now DONE too (commits 97d0b9d0 + 97383f0b): a shared `OperatorConfiguration` test DSL
(`empty/mandatory/full` + `with/without` + `asLoader/asDto/asBootstrapConfig/asPolicy`) replaced the
config fixtures duplicated across 6 files; `DoctorScenario`/`DoctorScenarioTest` tell the doctor's
diagnosis as BDD while `DoctorTest` keeps the exhaustive type mechanics. **CONFIG-DELEGATION RULE
(user, 2026-06-07):** when a test's SUBJECT is config behaviour, delegate to the `ConfigEntryGate`
BDD stages (compose via `@ScenarioStage`); the DSL is for config-as-FIXTURE only. The whole
BDD-quality pass is COMPLETE — see [[runbook-doctor-state]].

**JUnit 6 watch-item:** can't bump JUnit alone — JUnit 6.0 removed 5.x APIs JGiven 2.0.3 compiled
against. Latest released JGiven is 2.0.3 (no JUnit-6 release yet). Bump JUnit+JGiven TOGETHER when
JGiven ships JUnit-6 support; import `junit-bom` before spring-boot in `bom/pom.xml`. Meanwhile avoid
`@ParameterizedTest` under a JGiven `ScenarioTest` (it probes JUnit ≥5.13 `ParameterInfo`; we have
5.10.5 → harmless NoClassDefFoundError noise). Prefer one explicit scenario per case.

See [[hub:working-style-narrate-progress]] and [[config-restructuring-state]] and [[runbook-doctor-state]].
