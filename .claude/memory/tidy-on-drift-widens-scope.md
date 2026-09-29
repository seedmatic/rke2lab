---
name: tidy-on-drift-widens-scope
description: "The user deliberately widens a work session's scope to tidy-up-to-convened-patterns whenever they notice a drift — a large diff with drift cleaned is EXPECTED, not scope creep to flag"
metadata:
  node_type: memory
  type: feedback
---

In a work session the user ALWAYS widens the scope to include tidying-to-the-patterns-we-convened
whenever they notice a drift. Their words (2026-07-05): "dans les sessions de travail, j'elargie
tjrs a la mise au propre selon les patterns que nous avons convenu ensemble quand je remarque un
drift. donc c'est pour ca que le scope est souvent largement elargi."

**Why:** this is the same discipline as the code-hygiene rule (never leave drift/dead code behind)
and [[refactor-statics-on-touch]] — the task at hand is the trigger to clean what has drifted around
it. It is intentional, not accidental sprawl.

**How to apply:**
- When reviewing an execution and the diff is WIDER than the plan (e.g. the engine-lifecycle socle
  aac0518de: 129 files — inline-JDK-FQN→imports across 42 files, a Maven property rename +
  default-hoist, System.out→logger, module renames, build-cache tracking `*.bnd`), do NOT flag it as
  scope creep or a suspicious deviation. It is the user's tidy-on-drift discipline at work. Report it
  as "cleanup convened as patterns", not as an alarm.
- Still DO verify the widening is green (tests + staging gates) and mention what was swept, so the
  reader sees it — surfacing is fine, alarming is wrong.
- The distinction that still matters: a widening toward a CONVENED pattern is welcome; an unconvened
  new abstraction is not. If unsure whether a pattern was convened, ask — don't assume either way.

Related: [[refactor-statics-on-touch]] [[hub:sequential-no-compat-workflow]] [[hub:works-best-from-concrete-code]].
