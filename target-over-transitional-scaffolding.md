---
name: target-over-transitional-scaffolding
description: "Migration steering (user, 2026-07-07): the intermediate steps of a migration exist to SECURE it; when they COMPLICATE development instead of securing it, skip them and go to the target. Focus on the target shape, not on making the transitional world comfortable."
metadata:
  type: feedback
---

**The rule (user's words, 2026-07-07):** "il faut qu'on se concentre sur la cible. les étapes
intermédiaires sont faites pour sécuriser la migration. mais si elle complexifie le développement de
manière temporaire, il faut skip."

**Why:** most of the friction in the ClusterSeed migration came from making the OLD and NEW worlds
COHABIT — keeping the fluent pipeline alive beside the BDD scenario. That cohabitation is a
securing device (don't break the live boot before the driver is rewritten), NOT a goal. When it
costs more than it protects, cut toward the target instead of polishing the transitional plumbing.

**How to apply:**
- The TARGET is the pure-BDD scenario + a pure driver; the fluent pipeline / Topics / eager
  ResourceCreationPipeline are condemned (marked `@Transitional`), deleted in one block at Task 8.
- Don't over-invest tests/fakes that ONLY exist to run the transitional world offline. Concrete
  case: `ResourcesStageTest` (full 5-phase DAG) was DEFERRED rather than fabricate a fake
  `BootstrapResult` (10 Pulumi composites) — the real end-to-end one arrives with the pure driver
  (Task 8), so testing it in the transitional world is scaffolding, not target value.
- `PureStagesTest` was DELETED for the same reason: offline play of the FULL scenario never happens
  in the target (resources/cluster are axis-2 — they dialogue with the world), so a test asserting
  it fought reality.
- Signal to skip: you're adding null/Optional/@Nullable/duplication/fakes SOLELY to keep the two
  worlds running at once. That complexity is transitional — prefer moving to the target.

**Balance:** still don't break the live boot mid-flight (that's what the securing steps are for).
Skip the step only when it complicates WITHOUT securing anything real. See
[[cluster-seed-execution-state]] [[collaborative-design-method]].
