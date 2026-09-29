---
name: orchestration-purity-benefit
description: "ANALYSIS + HORIZON INCREMENT (2026-06-21): what we'd gain by making the ORCHESTRATION pure (decoupling the jgiven scenario engine from the host), and why it's worth planning as a SEPARATE increment. Not pure-OSGi by dogma (host orchestration may well be the final architecture); but maximizing what lives in OSGi is a goal because that's where the code is best-structured and decouplable. The big benefit: a pure scenario engine makes the designer runbook a pure bundle, and lets a scenario be resolved as a unit (gates become resolution edges -> the compass becomes Felix-mechanical, not hand-counted)."
metadata:
  node_type: memory
  type: feedback
---

## The question (the user's, precisely)

Not "how to put orchestration in OSGi" — host orchestration may be the final architecture, and we are
deliberately NOT pure-OSGi by dogma. The question is the COMPASS reasoning applied to ourselves: *if we
made the orchestration (the jgiven scenario engine) pure, what concrete benefit would we gain?* The
user's stance: maximizing what lives in the OSGi world is a standing goal because that is where the code
is best structured and most decouplable — so the benefit must justify the cost.

## The benefits (real, ranked)

1. *The designer runbook becomes a pure bundle — closes the re-entrance loop (STRONGEST, most direct).*
   The design DSL IS jgiven scenarios. If the scenario engine is pure, the designer runbook (which
   probes the reactor structure — already pure) can live entirely in OSGi, borrowing nothing from the
   host. The instrument that measures the system's purity would itself be pure. Today jgiven is
   compile-scope host, so every scenario is contaminated by adherence to the host.

2. *A scenario becomes resolvable as a unit — the bridge to unitrepo (the real treasure, but distant).*
   A pure scenario can be expressed as a `UnitResource` (Provide/Require): "this scenario REQUIRES these
   boxes". Then the GATES we defined become resolution EDGES: the compass's "how many increments" is no
   longer hand-counted — it is RESOLVED by the Felix resolver, exactly like manifests. The compass
   becomes mechanical because scenarios are units. Several gates away, but this is the prize.

3. *Decouplability / testability (diffuse).* A pure scenario engine tests without booting the host,
   reuses outside seed-master, versions per package.

## The cost (honest)

jgiven carries a REPORT engine (HTML/adoc). Putting that in a pure bundle is heavy and probably wrong —
rendering is actualisation, it belongs to the host. So "pure orchestration" does NOT mean "all of jgiven
in OSGi": it means SEPARATING the scenario MODEL (pure, Given/When/Then as data) from the rendering
ENGINE (host). That is a refactor, not a move.

## Decision

Worth planning as a SEPARATE increment (gross benefit confirmed by the user), NOT now. It is not
required for the doctor-model extraction we are about to start — that extraction is beneficial on its
own. This belongs to [[pipeline-orchestration-osgi-vision]] (orchestrator as OSGi service, actualisation
steps stay host as ports — DIP), and the compass-as-resolution idea ties to
[[unitrepo-design-unification-state]] (scenarios as `UnitResource`s, gates as resolution edges).
Sequence AFTER the doctor-model extraction lands. See [[designer-runbook-state]].

## Now traced by scenarios (the user caught a stray increment — re-entrance applied)

The completeness clause forbids an increment with no scenario. This benefit was first graved as a plan
with no scenario tracing it — a stray. Fixed: the 3 capabilities it realizes are now written as design
scenarios, all narrated-green behind the SAME shared leverage gate (the orchestration-OSGi inversion):
(1) runtime view — "the orchestration runs as an OSGi service"; (2) unitrepo view — "a design scenario
resolves as a unit (gates become resolution edges)"; (3) designer-runbook — "the designer runbook is a
pure bundle" + "the compass is resolved by Felix, not hand-counted". A shared gate opening three
scenarios IS a leverage box — exactly what the compass will surface; here it was surfaced by hand.
