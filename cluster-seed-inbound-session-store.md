---
name: cluster-seed-inbound-session-store
description: "The inbound host-facts channel for the ClusterSeed jGiven scenario (increment 2) is the NATIVE JUnit session store — NOT a custom ThreadLocal/exchange. Verified against junit-platform 6.0.3 + jGiven 2.0.3 sources on 2026-07-05. Supersedes the HostFactsExchange idea (which itself corrected the spec's impossible 'store.put before the run'). Requires an additive socle change: JUnitLauncherCore must open a LauncherSession instead of LauncherFactory.create() nude."
metadata:
  type: project
---

**The channel (settled 2026-07-05, ClusterSeed→jGiven increment 2, branch feature/cluster-seed-scenario).**
Inbound host-facts (config, policy, options, RunMode, the 3 effectful actors, logger, onFailure) cross
the launcher membrane via the NATIVE JUnit **session store** — no custom type at all.

**How.** The host driver (`ClusterSeedTopic`) owns the entry point — it builds the launcher — so it
opens a `LauncherSession` (`LauncherFactory.openSession()`, present in 6.0.3) and does
`session.getStore().put(NS, HOST_FACTS, bag)` BEFORE `execute()`. Inside the run, a `HostFactsSeeder`
(`BeforeAllCallback`, ordered before `@SeedRuntime`) reads `context.getStore(NS).get(HOST_FACTS)` and
sets the scenario instance's `@ProvidedScenarioState` fields; `JGivenExtension.readScenarioState` (a
`TestInstancePostProcessor`) then siphons them into the value-DAG.

**Why it works cross-thread (the load-bearing bytecode facts — the user's "isn't getStore a ThreadLocal?"
was the right thing to check, and the answer is NO).**
- `DefaultLauncherSession.store` = an INSTANCE FIELD `private final NamespacedHierarchicalStore`, not a
  `ThreadLocal`.
- `NamespacedHierarchicalStore` is backed by a `ConcurrentMap storedValues` + a `parentStore` — built
  for cross-thread access. The host's `put` is visible on the `junit-launcher-core` worker thread: SAME
  shared map.
- `LauncherStoreFacade` (jupiter-engine): `requestLevelStore.getParent() == sessionLevelStore`, so a
  `context.getStore(NS)` lookup in the run walks the parent chain up to the session-seeded value. The
  store propagates BY REFERENCE (`SessionPerRequestLauncher` takes `Function<NamespacedHierarchicalStore,
  Launcher>`), not per-thread.

**Path NOT taken, and why.** A custom `HostFactsExchange` (ThreadLocal, socle idiom) was drafted after
discovering the spec's original "driver store.put BEFORE the run" is mechanically impossible (the
`ExtensionContext.Store` only exists inside `execute()`). The session store makes that exchange
redundant: it IS "a reference already there that leads to the Store." ZERO prod membrane type. The
socle's `LaunchedPipelineExchange` stays TEST-only (its driver boots Felix outside any session, so it
has no session store to seed).

**Cost (first task of the plan).** `JUnitLauncherCore.run` today does `LauncherFactory.create()` nude
(no session). It must open/accept a `LauncherSession` so the store is seedable — an ADDITIVE change to
the shipped increment-1 socle.

Outbound is unchanged: synchronous harvest return `SeedRun(runbook, outputs)`; connection via
`@SeedRuntime` self-boot. See [[cluster-seed-transport-consensus]] [[engine-lifecycle-socle-state]]
[[scenario-state-dag-gate-closes-migration]].
