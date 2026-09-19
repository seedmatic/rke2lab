---
name: memory-synthesis-prune-the-how
description: "BACKLOG (user, 2026-06-20) — a dedicated POST-MERGE increment to synthesize the accumulated R4-era memory and PRUNE the how-it-was-done, keeping only the what/why. After R4 the repo memory has grown large (14+ R4 notes) with dense cross-references; much of it is process narrative (resume-states, WI-X-done logs, ★ history markers, resolved debates, pre-reload resume points) that, once shipped, only adds recall weight. Distinct nature from a code slice — a transverse memory-gardening pass, run on its own, NOT mixed into feature work."
metadata:
  node_type: memory
  type: feedback
---

## The need (user, 2026-06-20, during the R4 consolidation)

> "on commence à vraiment avoir beaucoup de matériel, et de cross references, il faut prévoir un
> increment pour synthétiser et oublier ce qui a déjà été fait, je veux dire le comment ça a été fait."

As R4 shipped, the memory accumulated 14+ notes plus the spec, heavily cross-linked. The user wants a
dedicated increment to SYNTHESIZE and FORGET the *how-it-was-done*, keeping the *what/why*.

## The distinction to apply (the pruning criterion)

- **KEEP — the what/why (durable knowledge):** invariants, rules, settled decisions and their rationale.
  E.g. [[osgi-system-export-resolution-only]] (the invariant), [[synth-context-channel-rule]],
  [[r4-resolver-service-ification]] (the decision), [[osgi-logs-flow-to-host]] (Pax + the insight),
  [[migration-branch-no-fallback]], [[null-arg-is-a-rule-violation]],
  [[prefer-non-static-inner-keep-the-graph]]. These are the knowledge; they stay (possibly condensed).
- **PRUNE — the how-it-was-done (spent process):** resume-states, "WI-C done" logs, ★ history markers,
  pre-window-reload resume points, blow-by-blow commit narratives, resolved-debate transcripts. Once
  shipped, this is archaeology — git history already holds it. E.g. [[osgi-runtime-r4-resume-state]] is
  almost entirely how (resume point); after merge it should collapse to a one-line "R4 shipped at
  <sha>, see the durable notes" pointer, or be deleted.

This is the MEMORY pendant of [[migration-branch-no-fallback]]: once the target is reached, throw away
the scaffolding (here, the process narrative), keep what the system IS.

## Scope of the increment (NOT now — post-merge, its own pass)

- Fold the R4 note cluster: merge overlapping notes, condense the durable ones, DELETE the spent
  resume/WI logs, prune dead ★ markers, repair cross-references after deletions (a `[[x]]` to a deleted
  note must be removed or repointed).
- Reconcile the `wip/specs/` R4 spec with the durable atlas: once the atlas runtime view carries the R4
  C4 figures + invariant (the merge does this), the dated `wip/specs/2026-06-20-…` spec is a frozen
  snapshot — decide keep-as-dated-record vs retire.
- Sweep ALL repo memory for the same how-vs-why split, not just R4 (older slices left resume-states too).
- Run it as a TRANSVERSE memory-gardening increment, never folded into a code slice (so rangement stays
  distinguishable from substance).

## Why post-merge, not now

The pre-merge consolidation only fixes note↔code INCOHERENCES (things that would ship a lie). Synthesis
+ pruning is gardening on top of correct notes — lower urgency, larger scope, and safer once R4 is
merged and the dust settles. Do it as the next memory-focused increment.

See [[osgi-runtime-r4-resume-state]] (the prime prune candidate) [[migration-branch-no-fallback]]
(the same throw-away-the-scaffolding discipline, applied to memory) [[claude-memory-cascade-state]].
