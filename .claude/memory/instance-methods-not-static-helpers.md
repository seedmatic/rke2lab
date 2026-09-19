---
name: instance-methods-not-static-helpers
description: "Recurring correction — I default to `private static` helper methods; the user wants behavior as instance/member methods on the owning DAG pojo."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: f37aea8f-e85f-4a18-9278-ff26bcb03565
  modified: 2026-08-29T08:07:00.819Z
---

I repeatedly default to `private static` helper methods (transform/build/parse helpers). The user has corrected this **multiple times** and finds it hard to keep me in line ("tu introduis à nouveau des helpers, moi je veux des méthodes attachées au DAG pojo", "difficile de te policer à ce propos", 2026-08-29).

**Why:** it violates the project's Instance-passing discipline (CLAUDE.md). Static helpers hide dependencies, resist testing/mocking, and don't belong to the object that owns the state. Behavior should hang off the POJO that travels the value-DAG.

**How to apply:**
- When about to write `private static X buildY(...)` / `XyzHelper.doThing(...)`, STOP — make it an **instance method on the owning pojo** instead. In jGiven scenarios, that's the **Stage** that holds the `@ProvidedScenarioState` (e.g. `ReplicatorSecretsSealScenario.When.assemble/gitSecret/floxSecret` were converted from `private static` → `private` instance members, `When::assemble` → `this::assemble`).
- Do it **uniformly**: if one sibling helper becomes an instance method, convert them ALL (mixed static/instance in one class is the exact smell). Don't just fix the new one and leave the old static.
- **Sanctioned exceptions stay static:** genuinely pure utilities with no state (`Math`-like, JSON `parse`/`read`/`namespaces` on a `JsonNode`), and factory methods (`of`/`from`/`builder`). The line: does it build/transform a DOMAIN object or orchestrate? → instance. Is it a pure leaf util? → static is fine.
- Don't mirror existing static helpers just because they're there — proactively make new code instance-based (and offer to convert the neighbours).

Concrete win: mirror the pattern already in place for the RESULT (e.g. replicator SourceSecrets), but express the construction as instance members, not static helpers. See [[flox-env-migration-design]].
