---
name: dsl-unification-topic
description: SUPERSEDED — the "JGiven as Shared Engine / DSL unification" exploration was folded into the pipeline spec and DELETED (2026-07-02). Pointer only.
metadata:
  node_type: memory
  type: project
  originSessionId: be4e1ccc-a995-4154-b3d5-3e5ba252941b
---

# DSL unification — SUPERSEDED / folded

**SUPERSEDED / folded (2026-07-02).** The parked doc
`docs/architecture/patterns/dsl-unification-exploration.adoc` was DELETED (`git rm`); its living
content merged into `docs/architecture/bdd/bdd.adoc` (the one pipeline spec, OSGi-owned).
Do NOT restart this as a separate subject — it IS the pipeline spec now. See
[[pipeline-jgiven-separation-design]] for the full arc.

What survived the fold (all still true):

- Landing direction = **one engine (JGiven), two layers**: `during/then` stays the authoring surface
  (type-state, compile-time); JGiven is the runtime substrate (narration + shared `ReportModel`).
  Supersedes the old Option-D "pipeline becomes a scenario".
- The two questions June left open are now CLOSED: **state model → Role 1** (`TopicContext` +
  `TopicOutcomes` passed as instances, jgiven thread-local confined to one scenario — honors
  instance-passing discipline; Role 2 rejected). **Depth → judged by the operator-compass**, not a
  blanket rule (a topic narrates iff the operator needs it; bbox → IN is the first verdict). No
  prototype was needed after all.
- Code correction that still holds: scenarios do NOT extend Pulumi ComponentResource (plain classes
  holding `Given/When/Then extends Stage<>`; `bdd-diagnostic-pattern.adoc` is aspirational).

Branch when started was `refactor/jgiven-shared-engine`; the work now rides the reliability arc on
`feature/cluster-edge`. Must still preserve the `consultDoctor` seam + shared
`ConsultationLog`/`ReportModel` threading ([[runbook-doctor-state]]).
