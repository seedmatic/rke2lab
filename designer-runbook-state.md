---
name: designer-runbook-state
description: "The designer's runbook chantier (2026-06-21): a LIVE render of the design state + a COMPASS for which path to take. The design layer is richly graved in docs/architecture/integration-atlas.adoc: ritual gained a 3rd movement (scenario map) + completeness clause + answerability cascade design->spec->plan->codebase + the two-poles/two-links opening image. 5 atlas views carry a complete scenario map (doctor/config/runtime/unitrepo/manifests). REAL denominator measured by reverse-engineering all 53 docs (~296 raw / ~140-170 consolidated); pre-pass coverage was ~16%. KEY REFRAME: playable/narrated is NOT a nature, it is the state of a scenario's GATES (gate = box horizon colour; blue=open=playable, green/amber=shut=narrated); tag is DERIVED, flip green->blue opens the gate. Guard = TARGET on horizon, NOT next. Evaluation machine deliberately NOT built; the USE is graved."
metadata: 
  node_type: memory
  type: project
  originSessionId: e3ad00b4-fde5-4347-8966-78da73194f46
---

## What it is

The designer's runbook = a live render of the DESIGN state (the way the operator runbook renders the
runtime state) AND a COMPASS for orientation. It pays the re-entrance debt
([[hub:reentrance-northstar]]): the atlas is hand-written and can drift; the runbook re-derives its
playable half from the code so it cannot lie. Founding image (graved in the runbook spec opening):
*documentation materializes the DESIGN, codebase materializes the SYSTEM, the two runbooks are the
LINK* — operator runbook (code→runtime, from Pulumi), designer runbook (code→design, replays scenarios,
proves no drift). Engine = JGiven, ALREADY WIRED (jgiven-core/junit5 in BOM + exec/seed-master,
`jgiven.report.dir` set, ~18 scenario classes under `controlplane/bdd/`).

## The gate reframe (user's correction, load-bearing)

playable/narrated is NOT a category of scenario — it is the state of its GATES. A scenario's path runs
GIVEN→WHEN→THEN; each clause is licensed by a box; a box is gated by its horizon COLOUR (blue shipped =
gate OPEN, green/amber = gate SHUT). The tag is DERIVED, never hand-maintained: *playable* = every box
on the path is blue; *narrated* = at least one box is still green/amber. Every scenario is DESTINED to
become playable — narrated is transitory. The ritual's *flip green→blue* (when a model ships) IS the act
of opening a gate; it flips the scenario narrated→playable by derivation. NO scenario floats: a narrated
one is moored to the named boxes it waits on, so one can always point to exactly what must ship. This is
also the future guard's drift criterion, mechanical: box blue but scenario narrated = gate left shut
(forgot to flip); scenario playable but a box not blue = gate opened falsely (a lie). Worked example
graved: unitrepo scenario 3 ("a handler loads from the store") = 2 shut gates (a concrete `UnitHandler`
+ load-from-store) = 2 increments to playable.

## The two run modes + the compass use

* *DRY-RUN* — every scenario we have written (full intent): "where are we going".
* *LIVE* — scenarios green against the reactor today (each scenario's tag checked against its boxes'
  real colour): "where are we". Few greens at the start is the honest baseline.
* *COMPASS* (the user's own need) — the gap dry-run↔live read as a GRAPH OF GATES: each benefit is "N
  flips green→blue away"; a green box opening several scenarios at once is a LEVERAGE point (visible
  because scenarios name their boxes). The intended query: *"I want THIS scenario realized — what does
  it cost, in how many increments?"* → read its shut gates, name the boxes, count. This is the *plan*
  layer of the cascade: descending to impl = choosing which gates to open first. The USE is graved in
  the spec; the evaluation MACHINE is deliberately NOT built — the gesture already exists between us
  (asking the question by hand gives the answer); the runbook merely crystallizes it.

## Coverage — measured, honest (2026-06-21)

5 atlas views carry a COMPLETE scenario map: doctor (8), config (4), runtime (8), unitrepo (7),
**manifests (9, the exemplar of the coverage pass)**. Denominator reverse-engineered from all 53 docs:
~296 raw candidate capabilities, ~140-170 consolidated. Pre-pass coverage ~16% (the earlier "27/27 =
100%" was false — covered 4 OSGi views, 3 with capability maps reconstituted on the fly). STILL OUTSIDE
the atlas: systemd (~30 raw), bootstrap (~87 raw, over-granular), cluster-api (~34), transverse
patterns (~25).

## The repeatable per-subsystem gesture (rodé on manifests)

One increment per subsystem: (1) CONSOLIDATE the raw harvest into a canonical capability map — the
irreducible MANUAL judgement (capability vs detail), the un-automatable part; (2) ELEVATE each to a
Given/When/Then scenario; (3) prove ADDITIVE. Device keeping merges monotone: shared boxes
(`UnitResolver`, `ManifestSynthesisContext`) are NAMED shared across views, never redrawn as local
copies — one box, two readings.

## NEXT

Two open, in cascade order: (a) verify the spec⇄plan seam before descending — `designer-runbook.adoc`
is design + partial v0 spec (names DesignProbe / DesignStateScenarioTest / the JGiven report), NO plan
written yet; (b) continue the backfill, one subsystem per increment (systemd next — mostly shipped,
clean harvest). Worktree `feature/designer-runbook-v0` exists (off origin/main, sops re-smudged) for
when code starts. See [[hub:reentrance-northstar]] [[unitrepo-design-unification-state]]
[[bdd-jgiven-test-strategy]] [[dsl-unification-topic]].
