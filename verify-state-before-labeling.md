---
name: verify-state-before-labeling
description: "feedback (2026-06-22): never label a chantier's state (DONE / IN PROGRESS / blocked) from a surface signal — verify the real state first. Caught labeling osgi-boot-single-source 'IN PROGRESS' because a grep still showed bootingEmbedded/embeddedBundle, when those were the no-arg method def + the scan-loop variable; the work was DONE (build green). The user (whose work session said 'done') had to push back."
metadata:
  node_type: memory
  type: feedback
---

A grep hit, a file's presence, a commit subject — these are *surface signals*, not state. Before
writing "DONE" / "IN PROGRESS" / "blocked" into a handoff or telling the user where things stand, read
what the code actually does and run the one objective gate (build green + surefire count). 

**Why:** on 2026-06-22 I wrote "osgi-boot-single-source IN PROGRESS, 5 files still have literals" from a
bare `grep bootingEmbedded\|embeddedBundle`. Reading the lines: `SeedRuntime.bootingEmbedded()` was the
*no-arg method definition* and the call-sites passed no jar name; `embeddedBundleNames` in OsgiRuntime
was the *scan-result variable*, not a hand-list. The chantier was finished and the build was green. The
user — whose work session had reported "done" — had to correct me.

**How to apply:** when about to assign a state label, ask "what is the objective test, and did I run it?"
For "is this refactor done": the literals are gone *as call-sites* (read them, don't grep-count) AND the
build is green. For "is this integrated": it's on the target branch AND builds there. Treat the work
session's own "done" as a strong prior to verify, not to second-guess by supposition. This is the
communication-side twin of [[single-source-of-truth-before-logic]] (read the fact from who defines it)
and of [[hub:model-substrate-alignment]] (describe the substrate that exists, not the one you assume).
See also [[build-verification-gotchas]] (the green-build-lies gates — the objective test itself).
