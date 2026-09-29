---
name: bdd-context-injection-carrier
description: "The unified way host context reaches BDD stages (2026-07-07, shipped): ONE StageContext carrier pushed into the jGiven value-DAG via executor.readScenarioState — no *Aware/accept* channel; runbook + outputs harvested by inject-the-holder. Verified at jGiven 2.0.3 source. Reuse for the other 6 pipelines."
metadata:
  type: project
---

**The target shape for host→stage context in a BDD pipeline** (settled + shipped with ClusterSeed Task 7, `exec/seed-master/.../bdd/`). Reuse verbatim for the other 6 pipelines.

**ONE injection channel — the value-DAG.** `HostSeeder.postProcessTestInstance` fills a single `StageContext` carrier (a plain class with `@ProvidedScenarioState` fields) from the JUnit session store, then `scenarioBase.getScenario().getExecutor().readScenarioState(carrier)`. Every stage resolves its `@ExpectedScenarioState` from that same map jGiven uses for phase-to-phase flow. This REPLACED the old double-hop (5 `*Aware` interfaces + 5 `accept*` + pass-through scenario fields). The scenario class carries NO state now — pure composer (phase order + nested sub-trees).

**Verified at jGiven 2.0.3 source (not assumed):**
- `ScenarioExecutor.readScenarioState(Object)` is public; the `injector` (`ValueInjector`) is a `final` field, `readValues` only ADDS to the map, never clears. So a carrier read at postProcess-time persists the whole run.
- `Scenario.createScenario()` (field-init) only constructs; `performInitialization()` (adds stages + `executor.setListener`) runs LAZILY on first `when()` inside the `@Test` — long after our postProcess. So both the executor swap AND the carrier read land before jGiven's init.
- `ScenarioBase.setModel()` → `executor.setListener(modelBuilder)` (line 94): our preview-executor swap in HostSeeder (declared FIRST) is in place before jGiven wires the listener onto it.
- **`executeAfterScenarioMethods` runs ONLY on registered stages, NEVER on the test instance.** So `@AfterScenario` on the scenario class is DEAD CODE — I almost shipped a latent bug. Terminal work (publishing outputs) belongs on the terminal STAGE.

**Harvest = inject-the-holder (twin channels, symmetric):** the driver seeds an `AtomicReference` holder into the store; the run fills it; the driver reads the same reference after.
- Outputs: `OutputsStage` (the terminal stage) publishes the collected map into the sink it reads as `@ExpectedScenarioState Optional<AtomicReference<Map>>`.
- Runbook: HostSeeder publishes jGiven's OWN model (created + named in jGiven's `beforeAll`, never replaced — verified) into an `AtomicReference<ReportModel>` holder. NO plant of our own model, NO identity copy — killed the former `named != null`.

**jGiven state-key rule (bytecode-verified):** `Resolution.TYPE` keys by field type, `Resolution.NAME` by field name; same key twice → `AmbiguousResolutionException`. So every `Optional<X>` / `AtomicReference<X>` scenario-state MUST be `Resolution.NAME` (erased type collides otherwise).

See [[bdd-null-hygiene-frontier-rule]] [[cluster-seed-execution-state]] [[jgiven-custom-executor-seam]] [[cluster-seed-inbound-session-store]].
