---
name: bootstrap-pipeline-contributable-vision
description: "VISION (user, 2026-06-20, during the CLI-OSGi alignment): make the bootstrap pipeline CONTRIBUTABLE so each seed (seed-master, manifests-cli, netplan-cli) expresses its OWN version by contributing its stages, instead of each main() copy-pasting the boot+resolve+run motif. KEY INSIGHT (user): the STARTUP is identical for all seeds (already captured by embeddedBootStack()); the divergence is only at the TAIL of the chain — seed-master's tail = the full BootstrapPipeline; a CLI's tail = resolve ONE -port service and drive it. So the boot prefix is the shared engine; what each seed contributes is its chain tail. NOT coded — sequenced AFTER the CLI alignment shipped (which proves the multi-entrypoint boot this rests on)."
metadata:
  node_type: memory
  type: project
---

## The user's framing

Reviewing the CLI alignment, the user reframed the duplication: don't lift the shared
boot+`awaitService` motif into a generic helper beside the pipeline — make the **bootstrap pipeline
itself contributable**, so every seed (seed-master AND the two CLIs are all "seeds") expresses its own
version by **contributing its stages**. seed-master contributes [preflight, bbox, incus, systemd,
resources, outputs]; manifests-cli contributes [synthesize]; netplan-cli contributes [synthesis]. Same
machinery, different compositions.

> "il faudrait rendre le pipeline de bootstrap contribuable, et du coup chaque seed pourrait exprimer
> sa propre version" … "globalement tout le démarrage est identique, c'est en fin de chaîne qu'on a
> besoin de chaîner différemment"

## The structural insight (decides the shape)

The **startup is identical** across all three seeds — exactly what `OsgiRuntime.embeddedBootStack()`
(the guard + pax + scr + resolver) already captures after the CLI alignment. The divergence is ONLY at
the **tail** of the chain, once Felix is booted and the registry is populated:

- seed-master's tail = run the full `BootstrapPipeline` (several services, several topics);
- a CLI's tail = resolve ONE `-port` service from the registry, drive it, done.

So: the **boot prefix is the common engine**; what each seed *contributes* is its **chain tail**.

## Why it's its OWN chantier (not folded into the CLI alignment)

The current grammar (`BootstrapPipeline`, `ApplicationPipeline`) gets its safety from **type-state**:
each `during(...)` returns a distinct class (`PreflightDone` → `AwaitingBbox` → …), so order/skip/early
-termination won't compile. "Contributable" trades that compile-time rigidity for composition (an
ordered list of contributed topics per seed). That rewrites `BootstrapPipeline` — bigger than "align
the CLIs", and it overlaps the [[pipeline-orchestration-osgi-vision]] chantier. The CLI alignment was
deliberately kept MINIMAL (CLIs boot inline, the duplication left visible) so this refactor poses
itself cleanly on a sound base (3 seeds already booting). A speculative `EmbeddedRuntimePipeline`
draft that pre-froze two tail shapes was written then DELETED here — the contributable engine should
make tails contributable, not enumerate them.

## Relation to the other vision

This is the SMALLER, nearer step: refactor the bootstrap pipeline's SHAPE (contributable tails). The
[[pipeline-orchestration-osgi-vision]] is the LARGER one: move the DECISION logic into OSGi as a
capability. The reusable engine `TopicRunner.runDuring(label, …)` (already gives logging +
`PipelineStageFailure`) is the natural seam for the contributable engine.

★ KEY (user, 2026-06-20) — deduplicating the pipeline engine REPAIRS (not merely prepares) the
orchestration-OSGi vision's premise. The R4 malaise was framed as "the OSGi world holds only a DATA
MODEL that describes; the LOGIC stays host-side — backwards". But the pipeline LOGIC was ALREADY living
in the OSGi world — `SynthesisTopicRunner` + `BootstrapInfrastructureSynthesizer` run INSIDE the
manifests-core bundle. It was MASKED by the duplication: because the same engine also existed host-side
(`TopicRunner`), it read as "host logic copied down", not as "orchestration already inhabits OSGi". So
the "all logic is host-side" reading was partly an ARTEFACT of the duplication, not the real topology.
Extracting `pipeline-core` doesn't just delete a copy — it makes visible that orchestration already runs
on both sides, so the ground for [[pipeline-orchestration-osgi-vision]] is already half-prepared (and the
malaise that motivated it was overstated by the masking). Dedup repairs the map before that chantier
reads it.

## L2 (pipeline-bootstrap) ABANDONED at execution — the code disproved the premise (2026-06-20)

The approved design scoped L1 (pipeline-core) + L2 (pipeline-bootstrap, passive stages) + L3
(SeedRuntime). Executing it, L1 shipped (FluentTopicRunner unifies the two duplicated runners;
manifests-core consumes it in-bundle @319cc4f5, seed-master flat @d065ce2f). Then the import audit
KILLED L2: of the 4 candidate "passive" stages, only `RuntimeCommandPreflight` is actually pure;
`BootstrapOptions`→`Rke2labConfig`, `PreflightStage`→`EntryGatePolicyEnforcer`+`OsgiRuntime`,
`BboxStage`→`BboxReconciliationOrchestrator` all drag host-world collaborators. Extracting them would
pull config/policy/bbox into a module meant to be passive → breaks the very passive⇒dual-consumable
invariant ([[osgi-system-export-resolution-only]]) that justified the split.

Crucially, this does NOT betray the user's reason for wanting L2 (avoid re-duplicating pipeline logic):
the duplication actually WITNESSED was the RUNNER, and L1 already eliminated it. These stages are NOT
duplicated anywhere — they are seed-master-specific. So L2 would prevent no observed duplication while
polluting the passive module = speculative. DECISION (user, 2026-06-20): abandon L2 now; keep L1+L3.
Extracting these stages becomes meaningful only AFTER their collaborators are inverted (config/policy/
bbox exposed as ports) — which is the [[pipeline-orchestration-osgi-vision]] chantier, not this one.

## Third horizon — ENRICH the DSL's coverage (user, 2026-06-20)

Distinct from both visions above, sequenced after them: the pipeline DSL wraps only a FRACTION of the
code; much imperative logic should migrate into the fluent grammar for readability/maintainability.
Orthogonal to the other two — about *how much* logic the grammar expresses, not *where* it runs or
*whether* it is duplicated; holds in EITHER world; presupposes the shared engine L1–L3 builds.

## RESOLUTION — the contributable refactor is CLOSED, it dissolves into the OSGi move (2026-07-02)

Revisited from the pipeline side during the OSGi-ownership chantier (spec
`docs/architecture/bdd/bdd.adoc`), planned as task "S2 — contributable seed". Reading the
code before starting it confirmed, from the pipeline side, exactly what the "L2 ABANDONED" section above
found from the module side — so S2 was abandoned, not scheduled:

1. the shared boot PREFIX already exists (all 3 seeds already call
   `FrameworkLaunchPipeline.embedded().during(...)` — the renamed `OsgiRuntime.embeddedBootStack()` motif);
   nothing left to factor.
2. the tails are incommensurable, not "same engine, different composition": the two CLIs' tail is a
   single `during(topic, Service.class, consumer)`; seed-master's is a 6-topic type-state chain owning
   the runbook + gates. A generic ordered list of contributed topics trades seed-master's compile-time
   order/skip safety for a uniformity with no business payoff.
3. it moves ZERO arrows on the initiation axis (the measure of OSGi-ownership) — a host-side
   rearrangement.

The contributable idea only becomes meaningful once what a seed contributes is *what it asks OSGi to
reason about* — i.e. once the decision logic is inverted behind ports. That IS
[[pipeline-orchestration-osgi-vision]] (planned as S3), which this memory's own "L2 abandoned" and
"Relation to the other vision" sections already pointed to. So this whole note is now a CLOSED vision
absorbed into that one; do not resurrect a standalone "contributable pipeline" chantier.

See [[cli-osgi-migration-carto]] (the prerequisite, shipped) [[pipeline-orchestration-osgi-vision]]
[[osgi-runtime-r4-boot-seam-state]].
