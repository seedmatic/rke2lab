---
name: seed-broker-shape-drags-victools
description: "Depending on seed-broker-shape drags the victools jsonschema closure — breaks in-container resolution; use seed-broker-codec for AmendmentBinder"
metadata:
  node_type: memory
  type: project
  originSessionId: 2f937488-ea11-441b-b7a7-f56cb85ed71a
---

`seed-broker-shape` packages TWO classes of very different weight in ONE exported package
(`io.seedmatic.rke2lab.seed.broker.shape`):
- `AmendmentBinder` — light: reflection over `@Amendment` record components + jackson `JsonNode`.
- `RecordSchemaProjector` — heavy: pulls the **victools jsonschema-generator** (`com.github.victools`).

Because OSGi resolves per-bundle, importing the `seed.broker.shape` PACKAGE forces the whole bundle
to resolve → the victools closure must be present in that world. A domain whose in-container test
world lacks victools (e.g. incus-bdd) then fails to resolve with a cascade of UNRESOLVED bundles
(`seed.broker.shape`, junit, guava, byte-buddy…) — the tell is the shape bundle in the unresolved list.

**Rule:** an amend reflector needs only `AmendmentBinder`, which now lives in **`seed-broker-codec`**
(jackson+reflection home, no schema-generator weight) — moved there 2026-07-14 (commit 10cb7b7a).
Import it from codec, NOT shape. Only a SHAPE reflector (schema projection, e.g. ManifestsShapeReflector)
should depend on `seed-broker-shape` — and only in a world that already carries victools (manifests-core).

**Why:** [[incontainer-test-not-in-seedmaster-reactor]] — in-container worlds derive their bundle
closure via `installImportClosureOf(host)`; a heavy transitive import that the world can't satisfy
breaks the whole resolve, not just one bundle.
