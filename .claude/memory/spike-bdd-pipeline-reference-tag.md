---
name: spike-bdd-pipeline-reference-tag
description: "The BDD-as-pipeline-engine POC codebase is pinned at git tag `spike/bdd-pipeline-poc` (commit 7a5c3d68) — the reference implementation of the 7 proven mechanisms, retrievable after the disposable branch feature/bdd-pipeline-poc is deleted. Consult during the ClusterSeed pipeline migration. Namespace is `spike/`, NOT `wip/` (easy to mis-recall)."
metadata:
  node_type: memory
  type: reference
---

The enrich-proofs spike that closed the BDD-turn go/no-go gate (GO reached 2026-07-04) lives in a
**disposable** bench module on branch `feature/bdd-pipeline-poc`. That branch gets deleted, but its
code is the migration's **reference implementation**, pinned forever at:

**`spike/bdd-pipeline-poc`** → commit `7a5c3d68` (namespace `spike/`, not `wip/`).

Retrieve any file after the branch is gone: `git show spike/bdd-pipeline-poc:<path>` (or check it out
into a scratch worktree). The bench is `osgi/runtime/bench/bench-bdd-pipeline/` at that tag. This
memory entry lives in `design/pre-integration` ON PURPOSE — the pointer must survive in the checkout
we keep working in, NOT on the branch we delete (where the same note also existed and vanishes with it).

The seven proven mechanisms to consult during the ClusterSeed-vertical migration:

- `SeedLauncherMain` / `SeedLauncherMainSpikeTest` — the JUnit launcher driven from a bare `main()`
  subprocess (zero surefire), the "engine from a lambda" premise.
- `FailFastPreviewExecutor` / `FailFastPreviewSpikeTest` — fail-fast→pending in-place (overrides
  `ScenarioExecutor.failed` to re-enable execution, listener rewrites NORMAL→PENDING).
- `PollUntilReadyExtension` + `@PollUntilReady` — a `TestTemplateInvocationContextProvider` with the
  adaptive 15/8/3/2s cadence that STOPS the instant the probe is ready.
- `DagGateSpikeTest` — the DAG gate recovering `@NestedSteps` call ORDER via ASM, rejecting a
  required-before-produced pipeline a presence-only check accepts.
- `CrossWorldGraftSpikeTest` — the cross-world edge grafting a remote `ScenarioModel` as a sub-tree
  (`addNestedStep`) + status propagation (proven in isolation; real-Felix integration is the increment).
- `FanInStatusSpikeTest` — real two-input fan-in in ONE composing scenario + fail-fast read off
  `ScenarioModel.getExecutionStatus()` (not a thrown exception).
- plus the nesting-scope-swallow gotcha (`NestedScopeSwallowSpikeTest`).

Durable design record (lifted into `docs/` on `design/pre-integration`, not on the disposable branch):
`docs/architecture/osgi/bdd-pipeline-poc-design.adoc` + the Diagram Q / go-no-go table in
`docs/architecture/atlas/seed.adoc`. See [[pipeline-spec-legibility-cleanup-post-go]].
