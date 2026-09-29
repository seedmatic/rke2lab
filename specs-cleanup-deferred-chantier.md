---
name: specs-cleanup-deferred-chantier
description: "Deferred docs-cleanup chantier (user decision 2026-07-09): the LIVE-FALSE names our foundation work created were fixed inline; a broader style pass (session-chronicle → lesson/rule) + residual world-gateway rename echoes are gathered here to do LATER, as a dedicated chantier, NOT interleaved with Strate 1."
metadata:
  type: project
---

**User decision (2026-07-09):** "on corrige le faux, et on grave ce qu'il reste à faire, mais on le
fait plus tard." Applied while dissolving the pipeline aggregator / relocating jgiven-wrap. The rule:
fix what OUR work made FALSE inline (that is debt we created); defer stylistic/echo cleanup to a
dedicated pass so it does not contaminate the foundation stratum. See
[[foundations-before-domain-migration]] (don't mix strata).

## Already fixed inline (2026-07-09) — do NOT redo
- `seed-spec.adoc` §migration: `Topic.Checkpoint/Pipeline` "dies with the seed migration" → corrected;
  those natures were ALREADY killed (0 prod impl), so it is now a RENAME of pipeline-port to the
  derivation grammar, not a kill.
- `realm-library-isolation-spec.adoc`: the LIVE invariant listed `type=seam (world-gateway, …)` and
  "the library does not transpire into world-gateway" — a dead module name in a present-tense
  invariant → renamed to `seed-broker-port`.
- `osgi-aggregator-layout-spec.adoc` note 2: rewritten from session-chronicle to the two RULES (don't
  fold jGiven's export into scenario-engine; the seam never carries jGiven).
- All `pipeline-jgiven` → `jgiven-wrap` echoes in code + live docs (bdd.adoc, seed-spec, atlas/seed,
  bdd-pipeline-poc-design).

## DEFERRED — the chantier to do later

### 1. `world-gateway` → `seed-broker` echoes in docs (rename already shipped in code)
These are HISTORY/rationale mentions, legitimate as narrative but stale as names. NOT live-false, so
deferred. Files (2026-07-09): `osgi-aggregator-layout-spec.adoc` (§5.5 IS the naming-decision section —
whole rationale block names `world-gateway`; also the tree diagrams §diagram + package
`…world.gateway.port`), `integration-atlas.adoc` (129/139 — says "renamed world-gateway → seed-broker",
correct as history), `atlas/seed.adoc` (266 the invariant node already shows the arrow, 352),
`atlas/doctor.adoc` (350/360 link text "the world-gateway spec" → the file is now seed-broker-spec),
`config-restructuring-spec.adoc` (802 link text), `osgi-remote-edge-preview.adoc` (exploration doc),
`bdd-pipeline-poc-design.adoc` (POC record). Lowest-hanging: the link TEXTS that say "world-gateway
spec" while pointing at `seed-broker-spec.adoc` (atlas/doctor, config-restructuring).

### 2. Session-chronicle style → lesson/rule (broad, ~30 docs)
The date-stamped "WAS done / shipped as / tried and reverted / AVANT" narrative style appears in ~30
`docs/architecture/` files. Much is LEGITIMATE (glossaries, exploration/design docs, debt reports,
dated plans in `docs/superpowers/` are frozen snapshots — leave those). The candidate subset is the
LIVING SPECS that carry session narrative where a rule would serve better. This is a flou-perimeter
pass — scope it explicitly before doing it, do NOT batch-rewrite. User's principle: a spec should
carry the LESSON and what-not-to-redo, not the chronicle (that lives in memory like this file).

**How to apply:** pick this up as its OWN chantier (not inside a foundation increment). Start with §1
(mechanical, bounded); §2 needs a scoping decision first (which docs are "living specs" vs "frozen
records").
