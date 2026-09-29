---
name: eliminate-field-constants-via-schema-binding-backlog
description: HORIZON backlog (post-2D, user's real typing goal) — once the 6 per-coordinate JSON schemas exist (world-gateway 2D), generate/bind typed payload objects FROM the schema so the WorldGatewayCatalog.FIELD_* string literals and the put(String,…)/path(String) call sites disappear. 2D contracts the FIELD_*; this eliminates them. Not 2D scope.
metadata:
  type: project
---

## The goal (user, 2026-06-30)

The user wants to "mieux typer" the proliferation of `WorldGatewayCatalog.FIELD_*` strings — the
`payload.put(FIELD_ACTION, …)` / `payload.path(FIELD_REASON)` style scattered across the Document
producers/consumers. That is the END state; 2D is the PRECONDITION.

## Why 2D does NOT remove them (clarified)

`SCHEMA_CONCORD` (2D) checks CONCORDANCE between two coexisting representations: the `FIELD_*` the
code reads/writes AND the `properties` the schema declares. The gate confronts them — so both must
exist. After 2D: schema + FIELD_*, held in agreement at build. The FIELD_* do not disappear in 2D;
they become contract-checked.

## The horizon that DOES remove them (post-2D, two candidate paths)

Both require the schema to exist first (hence: after 2D writes the 6 schemas):

1. **Codegen from schema** — generate a typed record per coordinate
   (`ReadinessVerdict(action, reason)`) from `<slug>.schema.json`; code uses generated accessors, no
   FIELD_* literals. Schema becomes the single source; the strings evaporate.
2. **Schema data-binding in the codec** — `DocumentCodec` reads/writes through the schema, exposing a
   typed object instead of `put(String,…)`. (The codec is `gateway-document-codec`, already
   per-realm; see [[nesting-our-own-flat-module-per-realm]].)

Either way the `FIELD_*` catalog + its dispersed call sites collapse onto the schema-derived type.

## Sequencing

After world-gateway 2D (the 6 schemas + the gate flip) AND likely after the remote-validation
capstone (which turns runtime validation on — a binding codec wants that path live). Related to the
"~19 dispersed payload-construction sites" follow-up the 2D spec §8 already records (migrating them
onto the codec is the natural first step; codegen/binding is the second). See
[[world-gateway-2c-complete-2d-designed-state]] [[document-codec-instance-in-2d-backlog]].
