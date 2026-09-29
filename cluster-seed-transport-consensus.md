---
name: cluster-seed-transport-consensus
description: "Q1 of the ClusterSeed BDD transposition (increment 2) is settled: how the run's inputs/outputs/runbook cross the launcher membrane. The custom ThreadLocal exchange (LaunchedPipelineExchange) is a TEST-ONLY crutch — redundant in live because jGiven's own ScenarioHolder + JGivenExtension already hold the ReportModel thread-confined. Outbound = synchronous harvest return R=SeedRun(runbook,outputs); crash-safety = try-play/finally-render in the host driver. Inbound host-fact bag routing is the OPEN next question."
metadata:
  type: project
---

**Where.** Brainstorm (increment 2, ClusterSeed→jGiven) on branch feature/cluster-seed-scenario,
whiteboard `.claude/claude-preview.adoc`. Q1 (the launcher-membrane transport) is CONSENSED; Q2 (phase
structure + inbound host-fact bag) is next.

**The load-bearing facts (verified in jGiven 2.0.3 jars, not from memory).**
- `com.tngtech.jgiven.impl.ScenarioHolder` is a native singleton `ThreadLocal<ScenarioBase>`
  (`ScenarioHolder.get().getScenarioOfCurrentThread()`); `ScenarioBase.getModel()` exposes the
  `ReportModel` publicly.
- `JGivenExtension` (junit5) CREATES the `ReportModel` in `beforeEach`, stores it in the JUnit Store,
  finalizes it in `afterEach` via `CommonReportHelper`.
- `JUnitLauncherCore.run()` is SYNCHRONOUS: `worker.start()` then `worker.join()`, result via
  `AtomicReference` after join.

**Consequence — the user was right: exit the custom global.** The socle's `LaunchedPipelineExchange`
(a custom `ThreadLocal` record) is a TEST-ONLY crutch: `SoclePipelineTest` boots Felix OUTSIDE the
launcher and had to inject a non-owning `OsgiConnection.over(...)` + a pre-made `ReportModel`. In LIVE,
`@SeedRuntime` self-boots (no connection to inject) and jGiven already holds the model — so no custom
ThreadLocal is needed in prod.

**The 3 settled decisions.**
1. OUTBOUND = the harvest's return value. On the worker thread the harvest reads
   `ScenarioHolder.get().getScenarioOfCurrentThread().getModel()` (or the Store's report-model) and
   returns `R = SeedRun(ReportModel runbook, Map<String,Object> outputs)`. No custom ThreadLocal for
   output. (The user's Future/return insight — realized as the synchronous harvest return.)
2. The ENTRY scenario owns the model; `@ScenarioStage` sub-stages share it via jGiven's native
   scenario-state (no global). In prod we do NOT inject a pre-made model — `JGivenExtension` makes it.
3. Crash-safety = `try { harvest } finally { render }` in the HOST driver (transposes today's
   `ClusterSeedTopic.seedClusterWithinFramework` finally). A CRITICAL stop still yields a runbook,
   because the model is reachable (holder/Store) before the exception unwinds. This is the piece the
   user validated last.

**OPEN — Q2 inbound host-fact bag.** The launcher instantiates the scenario reflectively (no-arg
ctor), so the host bag can't pass by constructor — but it also must NOT ride our (now-deleted)
ThreadLocal. The bag: `BootstrapConfig`, `ControlplanePolicy`, `BootstrapOptions`, RunMode/`LiveGate`,
the 3 effectful host actors (`BboxReconciliationOrchestrator`, `ResourceManager`, `OutputBuilder` —
DIP ports the scenario calls), `readinessLogger`, `OnFailure`, `ConsultationLog`. The 4 OSGi services
(`ConsultingService`, `SystemdRuntimeProbe`, `ClusterReadinessContact`, `ReadinessAuthority`) LEAVE the
bag — the scenario, running inside the booted framework, resolves them itself via awaitService. Candidate
inbound channels to decide next: JUnit Store seeding vs a single inbound record vs OSGi-side resolution.

See [[bdd-pipeline-migration-plan]] [[engine-lifecycle-socle-state]] [[scenario-state-dag-gate-closes-migration]].
