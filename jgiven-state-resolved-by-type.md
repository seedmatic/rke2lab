---
name: jgiven-state-resolved-by-type
description: jGiven shares stage state BY TYPE — two fields of the same type in one stage throws AmbiguousResolutionException
metadata: 
  node_type: memory
  type: project
  originSessionId: 2f937488-ea11-441b-b7a7-f56cb85ed71a
---

jGiven injects `@ProvidedScenarioState`/`@ExpectedScenarioState` between stages **by TYPE**, not by field name. So TWO fields of the SAME type in a single stage (e.g. two `java.nio.file.Path` in a `When`) throws `com.tngtech.jgiven.exception.AmbiguousResolutionException` at play time ("Ambiguous fields with same TYPE detected").

**Why:** hit 2026-07-14 adding a second `Path stagingRoot` beside the existing `Path overlayFile` in `ManifestSynthesisScenario.When` — the scenario died before posting its runbook, and the front-door masked it as a `lastRunbook()` NPE ("the scenario has not played yet").

**How to apply:** the clean fix is `@ProvidedScenarioState(resolution = Resolution.NAME)` (and the matching `@ExpectedScenarioState(resolution = Resolution.NAME)` on the reader) — jGiven then resolves by FIELD NAME, so two same-type fields coexist as long as the reader's field name matches the writer's. `import com.tngtech.jgiven.annotation.ScenarioState.Resolution;`. Caveat: switching ONE field of a type to NAME re-resolves the WHOLE type by name, so any partner field that relied on TYPE-matching a DIFFERENTLY-named reader field silently breaks (hit 2026-07-14: `When.overlayFile` → `Then.envFile` matched by type; naming `overlayFile` NAME left `envFile` null). Fix: give reader and writer the SAME field name. (A lighter alternative when the value is already derivable — e.g. `result.manifestFile().getParent()` — is to derive it and add no field at all; but explicit NAME-resolved state reads clearer.) See [[incontainer-test-not-in-seedmaster-reactor]].
