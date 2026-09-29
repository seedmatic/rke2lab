---
name: third-party-modules-aggregator
description: "IDEA (not yet done): a transverse aggregator for our THIRD-PARTY adapter modules — OSGi wraps + the Pulumi incus SDK — distinct from our metier modules"
metadata:
  node_type: memory
  type: project
  originSessionId: 2f937488-ea11-441b-b7a7-f56cb85ed71a
---

IDEA raised 2026-07-14 (NOT yet implemented — a structural refactor to schedule separately, do NOT fold
into a feature commit): group all our modules that ADAPT/vendor EXTERNAL code under one transverse
aggregator, the architectural opposite of our metier realisations.

The membership criterion is NOT "it's an OSGi wrap" but "it encapsulates a third party FOR US", whatever
the target world:
- the OSGi wraps — `osgi/runtime/jgiven-wrap`, `osgi/runtime/victools-wrap` (third parties adapted FOR
  Felix) — today noyés among boot/framework/scenario-engine/testkit/bench in `osgi/runtime/`, not at
  their conceptual place.
- the **Pulumi incus SDK** (`sdks/incus/`) — a third party adapted FOR the host.
- any future wrap (a `jsync` wrap is NOT needed — host classpath, see [[jsync-for-host-live-reconcile]]).

Open questions before doing it: the name/location (`third-party/`? `vendor/`? `external/`?) and the full
inventory of modules to move. It touches `osgi/runtime/`, `sdks/`, and the root reactor's `<modules>` +
each moved module's `relativePath` — a real refactor, tangent to the host-tree chantier. Deferred; the
first genuine trigger is when we'd otherwise create a third OSGi wrap or want to tidy `osgi/runtime/`.
