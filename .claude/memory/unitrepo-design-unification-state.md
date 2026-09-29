---
name: unitrepo-design-unification-state
description: "Design-level unification of the docrepo-dag-wip 'unitrepo' vision INTO rke2lab (2026-06-20, design-only, NOTHING graved). The atlas-additivity ritual run end-to-end against the unitrepo spec: it HOLDS, additive, and half of it already SHIPPED (resolver in prod, R4 framework, handler-spi providing-half). Two design decisions settled. A reusable atlas device born: capability-anchoring."
metadata:
  node_type: memory
  type: project
---

The user reframed the goal (2026-06-20): docrepo-dag-wip was ISOLATED because rke2lab was not
ready to host it; now rke2lab is mature (R4 framework boots in prod, `UnitResolver` in prod,
walker retired) it is time to UNIFY the two design visions into ONE — at the **design level**,
not a code plan, not a `git mv`. The forcing test: does the unitrepo spec still hold inside the
rke2lab atlas? It does — and the world has overtaken half of it.

## What was done — the atlas ritual, end to end (in `.claude/claude-preview.adoc`, a VIEW not graved)

Four diagrams, the standard ritual ([[hub:brainstorm-vocabulary-view-first]] →
[[hub:decision-options-in-preview]], prose+Mermaid never Java [[hub:docs-diagrams-not-java]]):
- **Diagram 0 — vocabulary**: every term of both worlds, faithful to code, colour-coded
  shipped/folding/design/collision. The grammar that lets us make sentences about the unit repo.
- **Diagram 1 — re-seating**: unitrepo becomes the **4th per-subsystem view** under the rke2lab
  two-spaces frame, beside doctor/config/runtime; frame unchanged.
- **Diagram 2 — additivity (the real test)**: BEFORE rke2lab vs AFTER + unitrepo design boxes.
  VERDICT = monotone, HOLDS. ONE honest changed collaborator: `ComponentResource` stops calling
  the engine on its own data and is DRIVEN by a `ResourceDescription` through the ACL south port —
  box stays, gRPC arrow stays, only its INPUT shifts (inline → description). Same nature as the
  HealthSystem `Generalist` collaborator-swap.
- **Diagram 3+4 — capability-anchoring** (see below).

## The unification verdict — HOLDS, and is AHEAD of the spec

- **Green→blue (the world caught up with the 2026-06-13 spec)**: `resolve` SHIPPED (`UnitResolver`
  real Felix, `ManifestsUniverse` makes `ManifestsUnit`→`UnitResource`, `CoherentManifestsDomainRegistry`,
  walker DELETED); the gRPC/OSGi bet is the SHIPPED #1565 invariant (osgi/ imports zero io.grpc/com.pulumi);
  `unitrepo-handler-spi` is already the atlas's `-spi` exemplar.
- **The mediation seam COMPOSES, does not conflict** (the worry going in): `ResourceDescription`/ACL is
  the DOWNSTREAM extension of the shipped `-port`/`awaitService` seam, not a rival. Chain: bundle computes
  model → host reads it via `awaitService` (shipped) → host turns it into a neutral `ResourceDescription`
  and actualises via the ACL south port (design). This IS [[pipeline-orchestration-osgi-vision]] (DIP:
  actualisation stays host, exposed as ports). North port = domain terms only = identical discipline to the
  `-port` pure-model rule.
- **Still pure design (untouched, additive)**: ContentStore on JGit (option B — JGit only in seed-master
  today, no ContentStore in the reactor), `ingest` (history-fold of a Pulumi checkpoint), `load`-from-store,
  life-model k8s, the whole P2P federation.

## Two design decisions SETTLED this session (reopenable until graved)

1. **Mechanism C DROPPED** — the V1 spec loaded handlers via a hand-rolled child classloader "no
   framework"; R4 shipped an embedded Felix, and the brainstorm itself called C "the embryo of
   OSGi-embedded". So handlers load as **bundles in the embedded Felix**; C retires. Also requalify
   "standalone / no running framework" everywhere → the resolver runs SCR-injected inside booted Felix.
2. **Handler edge = TWO orthogonal axes, not one overloaded namespace** (verified on the real
   felix.scr 2.2.18 jar — [[hub:check-osgi-standard-before-modeling]]). The standard DS extender has ONE
   name `osgi.extender=osgi.component`, the component identity carried out-of-band. So: keep ONE extender
   contract `osgi.extender=unitrepo.handler` (matches the standard, already shipped) AND add a SEPARATE
   `unitrepo.type` namespace that carries the per-type wiring into the resolution graph (makes
   "connected by construction" a resolver-PROVEN property, not a runtime promise). The doc's per-type
   `osgi.extender=unitrepo.type.X` (option 3) was non-conformant — it conflated the two axes, the same
   "two independent decisions bundled into one" error pattern the user catches (cf. exception-root axes).
   `UnitResource.provide/require` already supports this with no structural change; emitting the edge on
   `ManifestsUniverse` units is impl, not vision.

## The capability-anchoring DEVICE (user: "il faut le garder, et le généraliser")

A new STANDING atlas tool, the positive twin of the monotone-additivity proof: for each model, a
sentence "before we could NOT say X / after we CAN say X", pinned to the box that enables it,
colour-coded by horizon (blue=sayable now, green=unlocked-next, amber=design horizon). Two readings
fall out: (a) the coloured columns ARE the roadmap (derived, not invented); (b) it is a two-way
coverage test — a sentence with no enabling box = a design gap; a box licensing no sentence = dead
weight. Generalised across ALL FOUR subsystems (doctor/config/runtime/unitrepo) it yields ONE
project narrative: broad blue base (diagnose/resolve/configure/boot-OSGi today), a green band the
shipped R4 framework unlocks next (unitrepo ingest/load/seam + doctor grant-seam), an amber P2P
horizon. Additivity proves nothing is BROKEN; capability-anchoring proves each box EARNS its place.

## Glossary remediation owed when graved (the atlas reserves system/space/world/universe —
[[system-space-world-universe-glossary]])

- **"World-A"** (unitrepo durability regime, pull=own) collides HARD with `world`=classloader region →
  rename "pull-own regime".
- **"node"** collides with rke2lab's cluster/env `node` (NodeEnvContributor) → disambiguate unit-node vs
  cluster-node.
- "schema-name space"/"class space" — keep hyphenated, never bare `space`.
- `universe` = a HAPPY coincidence (unitrepo's `RepositoryView` candidate-set IS the reserved sense).
- `Unit`/`Visit` were chosen to align with rke2lab — safe anchors.
- Module layout: spec's flat `unitrepo-*-api` → adopt the `-port`/`-core`/`-spi` taxonomy
  ([[rename-contract-to-port-state]]); `unitrepo-pulumi` = host-world ACL.

## NEXT (design, not code — the missing brainstorm→spec→plan middle link)

We have the INTEGRATION design, NOT a revised spec. The next artifact is the **spec revision +
repatriation into rke2lab** (itself design = the terminal brainstorm artifact): apply the green→blue,
the 2 decisions, the glossary remediation; re-seat the unitrepo atlas as the 4th view. This ALSO
secures the corpus (docrepo-dag-wip is local-only, never pushed — the standing risk). Last design hole
before the spec = **deplier Diagram 5: the mediation seam** (`ResourceDescription`/ACL as the formal
before/after extension of `-port`/`awaitService`). Only AFTER the spec can a plan for the first
framework-unlocked increment (handler edge + load-from-store) be written. See [[hub:docrepo-dag-state]] (hub)
[[osgi-runtime-r4-boot-seam-state]] [[step2-decomposition-state]].
