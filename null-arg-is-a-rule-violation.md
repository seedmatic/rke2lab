---
name: null-arg-is-a-rule-violation
description: "A null literal passed to OUR OWN API is a non-null-rule violation, not a smell to weigh; it was the visible tell that exposed the OSGi framework-bypass at R4"
metadata:
  node_type: memory
  type: feedback
---

A `null` literal passed as an argument to **our own API** is a **violation** of the non-null-by-default
strategy ([[non-null-input-rule]]), not a "candidate to investigate". The discriminator is whose API:
- **Our API** → `null` arg is forbidden. Fix it (no-op object, `Optional` return, builder default, or a
  dedicated type for the tri-state).
- **Third-party edge** (Pulumi `InvokeOptions`, `JsonPatch.add(path, null)`, YAML map values) → the `null`
  is their contract or the *data*, not a dependency arg. Legitimate.
- **`Optional`** present → the optionality is deliberate/controlled; NOT the smell.

**Why:** at R4 the visible tell that we were bypassing the OSGi framework was exactly this —
`new IncusResourceBootstrap(config)` delegating `this(config, null)`. The `null` dependency arg meant
"resolve via ServiceLoader instead of the registry". The `null` literal is generic code-health, not
OSGi-specific, but it is the cheapest signal that a dependency is absent or a convenience overload lies.

**How to apply:** (1) when a problem surfaces, FIRST check whether it stems from one of the known
violations below — if yes, fix the violation; if no, postpone to AFTER the first bootable functional
version (user steer 2026-06-20). (2) Prefer **fail-fast guards on locally-verifiable conditions** over
propagating context up the call chain to reconstruct the error at the caller (e.g. `bootEmbeddedOsgiRuntime`
throws if `!hasEmbeddedBundles()` rather than returning null and letting a downstream NPE surface).

**Known host-world violations (pre-existing, NOT introduced by R4 — backlog, post-bootable):**
- `TargetChecksumPipeline.computeChecksum(roots, null, null)` — convenience overload passing nulls.
- `SeedSystemdAdapterEndpointGate` → `SeedSystemdAdapterRuntimeStatusSnapshot.snapshot(config, null)` —
  nullable `Consumer<String> logger`; should be a no-op consumer (a real-logger overload already exists).
- `IncusResourceBootstrap.LookupResult("", state, null)` — tri-state `Boolean managed`; model as
  `Optional<Boolean>` or a dedicated type.
- Frontier: `ApplicationPipeline.Launch(null)` — param is Pulumi `Context`; `null` = "no Pulumi engine";
  `Optional<Context>` would be the clean gesture.

See [[non-null-input-rule]] [[osgi-runtime-r4-resume-state]] [[dual-path-inline-until-r5]].
