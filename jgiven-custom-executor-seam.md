---
name: jgiven-custom-executor-seam
description: "How to inject a custom ScenarioExecutor (the preview PreviewExecutor) into a jGiven scenario ONLY in preview mode — verified empirically 2026-07-05 (jGiven 2.0.3, JUnit 6.0.3, two throwaway tests). RETAINED seam (user's idea): extend ScenarioTestBase (NOT ScenarioTest), declare @ExtendWith[our seeder, JGivenExtension] in that order; our TestInstancePostProcessor runs first, reads runMode from the store, and setExecutor()s conditionally via getScenario() — before jGiven's injectStages wires the @ScenarioStages onto it. NOT via ScenarioHolder (null at our postProcess). This is P4's mechanism for the ClusterSeed BDD migration."
metadata:
  type: reference
---

**Question it answers.** `JGivenExtension` (jGiven-junit5) creates/drives the scenario and, in
`postProcessTestInstance`, calls `getScenario().getExecutor().injectStages(...)` with the DEFAULT
executor — it exposes no seam to swap in a custom `ScenarioExecutor`. We need a custom one ONLY in
preview (the `PreviewExecutor` that decorates the `ScenarioListener` to rewrite NORMAL→PENDING, so a
dry-run renders a COMPLETE runbook without executing — our `DeferringScenarioExecutor` can't, it kills
`@NestedSteps` sub-trees, cf. E9). Can a custom executor be injected, conditionally, anyway?

**Verdict: YES.** Two throwaway tests (played green, deleted). The RETAINED seam is the user's idea —
cleaner than overriding `createScenario()`.

**The retained seam (extension-ordering + conditional swap).**
```
@ExtendWith(PreviewExecutorSeeder.class)   // OURS, first
@ExtendWith(JGivenExtension.class)          // jGiven, second
class SeedScenario extends ScenarioTestBase<G,W,T> {   // ScenarioTestBase, NOT ScenarioTest
  private final Scenario<G,W,T> scenario = createScenario();
  @Override public Scenario<G,W,T> getScenario() { return scenario; }
}

class PreviewExecutorSeeder implements BeforeAllCallback, TestInstancePostProcessor {
  public void postProcessTestInstance(Object instance, ExtensionContext ctx) {   // runs BEFORE jGiven's
    RunMode runMode = ctx.getStore(NS).get(HOST_FACTS...);   // store IS seeded (beforeAll preceded)
    if (runMode.preview() && instance instanceof ScenarioTestBase<?,?,?> stb) {
      stb.getScenario().setExecutor(new PreviewExecutor());  // before jGiven's injectStages
    }
  }
}
```
- Same class declaring `[seeder, JGivenExtension]` → our `postProcessTestInstance` runs BEFORE jGiven's
  (declaration order for equally-scoped class extensions). Inheriting `JGivenExtension` via `ScenarioTest`
  would register it FIRST (superclass-before-subclass walk) — so we extend `ScenarioTestBase` and declare
  both ourselves.
- We reach the scenario via `getScenario()` on the instance — the SAME object jGiven then reads in its
  own `postProcessTestInstance` — so our `setExecutor` mutates exactly the executor jGiven's
  `injectStages` will use.
- `setExecutor` (ScenarioBase.java:68) passes `assertNotInitialized` (set before `startScenario`).
- Swap is CONDITIONAL: only in preview. In live, jGiven's default executor stays.
- Measured: nested `@ScenarioStage` plays on our executor (runbook `When the top step runs / (nested) a
  nested step runs`).

**NOT via ScenarioHolder (user's hypothesis, probed and ruled out).** At our `postProcessTestInstance`,
`ScenarioHolder.get().getScenarioOfCurrentThread()` is `null` — jGiven only populates the holder in ITS
`postProcessTestInstance`, which runs AFTER ours. So the holder is empty-too-early / wired-too-late; the
channel is `getScenario()` on the instance, not the holder. (The user's "intercalate via an extension"
instinct was right; only the holder channel was wrong.)

**The trap of the abandoned approach (createScenario override), kept as a warning.** Overriding
`createScenario()` and holding the executor in an inline-initialized INSTANCE FIELD gives an NPE: Java
inits super-fields before subclass-fields, so when `ScenarioTest`'s `scenario = createScenario()` runs,
the subclass field is still `null` → `setExecutor(null)` → NPE in `injectStages`. The retained
extension seam avoids this AND reads runMode at the right time (store already seeded at postProcess) —
no lazy-read needed.

See [[cluster-seed-transport-consensus]] [[cluster-seed-inbound-session-store]]
[[scenario-state-dag-gate-closes-migration]]. E9 design record: docs/architecture/osgi/bdd-pipeline-poc-design.adoc.
