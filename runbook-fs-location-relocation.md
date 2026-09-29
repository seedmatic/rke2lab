---
name: runbook-fs-location-relocation
description: "WATCH (not a to-do): a runbook once landed in the wrong FS place; investigation 2026-07-05 found no reproducible symptom — revisit only if it recurs."
metadata:
  type: project
---

**Status: ON WATCH, no action.** The user recalled a jGiven runbook once landing on disk in the root module rather than a `target/` folder, and asked to relocate it. Investigated 2026-07-05 (after the engine-lifecycle socle + T7) — **no reproducible symptom found**, so nothing to fix now. Per the user's rule: if we can't find the concrete trace, set it aside and revisit when we hit it again.

**What the investigation established (so a future session doesn't chase a phantom):**

- No `jgiven-reports` at the repo root; no stray untracked runbook/json files anywhere. Every `jgiven-reports` dir sits under a module's `target/`.
- Runtime render: `ClusterSeedTopic.runbookOutputDir()` already resolves an EXPLICIT `seed-master/target/runbook` (via `localWorktreePath()`), not a CWD-relative path — the `RunbookRenderer` writes there.
- The standalone play (`Scenario.create + setModel + finished()`, the prod-checkpoint idiom) does NOT write any report to disk — only jGiven's JUnit `JGivenExtension.afterAll` calls `finishReport`, and its `jgiven.report.dir` is already pinned per-module in build-parent's managed surefire (`${project.build.directory}/jgiven-reports/json`).

So the original symptom predates those settings, or is no longer reproducible. **Only reopen if a runbook actually appears outside a `target/` again** — then capture the exact command + path before touching code. See [[engine-lifecycle-socle-state]].
