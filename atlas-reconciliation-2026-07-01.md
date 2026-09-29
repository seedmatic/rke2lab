---
name: atlas-reconciliation-2026-07-01
description: Atlas ↔ specs ↔ codebase reconciled 2026-07-01 (33 commits had shipped off-atlas since 06-30). world-gateway 2D flipped DESIGN→SHIPPED (multiplexor = named erasure), a 6th Host/pipeline view added, governance spec 4→6 gates. Backlog left: world-gateway-spec.adoc chapeau still multiplexor.
metadata:
  type: project
---

The user noticed we'd done "plein de choses hors atlas" — the atlas was last touched 2026-06-30
(`adc113f6`), and 33 commits shipped since without passing the additivity ritual. Reconciled the
atlas + the drifted specs to the codebase (discipline [[specs-current-at-brainstorm-end]]; the
`SPEC_COVERAGE` staging gate is the build-time enforcer of exactly this). NOTHING was rewritten as a
blank page — the ritual's "name the erasure" rule was applied.

## What was reconciled (all DONE, uncommitted)
- **world-gateway 2D — the big drift.** The atlas view described the DESIGN-era `DomainDagMultiplexor`
  (mux/demux, `DomainDagMapper` @Component, Diagrams N/N2/O) — a design that was PIVOTED during
  execution to **records-as-contract** (`@DocumentContract` wire-records + generated schema +
  `SCHEMA_CONCORD` gate + per-realm `DocumentCodec`), which is what actually shipped (T5→T10, gate at
  ERROR). Fix: the atlas NOTE now says SHIPPED-by-records-as-contract, the multiplexor is a **named
  erasure** (kept as design-time reasoning), records-as-contract the monotone addition on 2A–2C.
  Section heading DESIGN→SHIPPED. `world-gateway-2d-schema-contract-spec.adoc` status DESIGN→IMPLEMENTED.
- **Host / control-plane pipeline — the 6th view (NEW).** The user's point: the pulumi preview/up
  pipeline is the CORE ROLE of the host, yet absent from the atlas (the 5 prior views were all
  OSGi-centric — the host was the implicit context, never a subject). It surfaced only because
  null-safety hit a design defect IN the pipeline (the watcher re-parse). New view: the pipeline as the
  SOLE orchestrator of two gates (living/output), Diagram P before/after, monotone verdict, 2 narrated
  scenarios, governance note. Index 5→6 views.
- **Governance note + spec.** The staging extension carries SIX `StagingGate`s (not 4 as the spec's
  "The four gates" section said): RECORD_PURITY, SPEC_COVERAGE, INSTANCE_DISCIPLINE, REALM_BOUNDARY,
  DUPLICATE_REALM_CLASS, SCHEMA_CONCORD (all committed `0299913a`+). Fixed `staging-gates-governance-spec.adoc`
  4→6 (+ the two missing rows). Atlas note now lists all six + null-safety as a SEPARATE javac gate
  (not a staging law).
- **docs/README** — two-gates entry added.

## Backlog LEFT (not improvised)
- **`world-gateway-spec.adoc` (the chapeau) still ~multiplexor** (5 multiplexor / 3 records hits). NOT
  rewritten — realigning it onto records-as-contract is its own doc chantier. The atlas already names
  the erasure, so this is not urgent, but the design-of-record doc lies by omission until redone.
- The atlas is 1949→~2050 lines; the `DomainDag*` diagrams N/N2/O remain as design-time history.

See [[world-gateway-2c-complete-2d-designed-state]] [[flatten-at-edge-observation-layer]]
[[spec-coverage-gate-state]] [[specs-current-at-brainstorm-end]].
