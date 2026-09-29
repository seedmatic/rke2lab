---
name: synth-context-channel-rule
description: "Rule (user-settled 2026-06-20) for which channel a synthesis dependency uses — ThreadLocal ManifestSynthesisContext vs @Reference/explicit — decided by the ThreadLocal ownership invariant"
metadata:
  node_type: memory
  type: feedback
---

When a manifests-synthesis dependency needs to reach a unit/service, the channel is chosen by an
**objective criterion, not taste**: does the ThreadLocal ownership invariant hold for ALL its readers?

A `ThreadLocal` is legitimate ONLY if (user's two invariants): (1) every reader runs within the
scope of whoever `bind()`-set it (same thread, owned), and (2) it is always removed on the setter's
return (symmetric — here `bind()` returns an AutoCloseable `Scope` used in try-with-resources). The
manifests ThreadLocal `ManifestSynthesisContext` (owned by **manifests-core**, OSGi world; bound by
`DefaultManifestSynthesisService.synthesize()`; NO thread fork between bind and the unit loop) meets
both — so it is a legitimate intra-OSGi relay, NOT an anti-pattern here.

**The rule:**
- **All readers are under `bind` (i.e. only reached during `synthesize`)** → put it in
  `ManifestSynthesisContext` (the ThreadLocal). These are *synthesis DATA* slices: `BootstrapIdentity`,
  `ImageState`, `NetworkTopology`, `ComponentVersions`, `FloxDebugPolicy` — all `manifests-port`
  records, fed by the host across the real frontier (`ManifestSynthesisRequest`, a port type), then
  relayed by the ThreadLocal. **`IncusIdentityMaterial` belongs here** (host-supplied identity data,
  read only by `IncusIdentitySecretManifestsUnit` during synthesis).
- **At least one reader is OUTSIDE `bind`** (a host-facing `@Component` invoked through the port,
  e.g. via `awaitService`) → use `@Reference` injection / explicit passing. These are *SERVICES*,
  valid everywhere. **`NodeEnvContributorRegistry` belongs here** and stays a service: its two
  consumers are `RKE2LabEnvConfigManifestsUnit` (under bind, OK) AND `DefaultNodeEnvOverlayService`
  (a host-facing `@Component`, invoked by the host via `awaitService` with NO active bind — see
  `HostSeamEmbeddedFelixTest` "couture 1"). Putting the registry in the ThreadLocal would return the
  empty DEFAULT for that second reader — silently wrong. So the ownership invariant DISQUALIFIES the
  ThreadLocal for it.

This is NOT two models for the same subject (which the user forbids): it is two distinct NATURES
(synthesis-data vs service), each with its single canonical channel, separated by the objective
ownership test — not by convenience.

The real host→OSGi frontier is **`ManifestSynthesisRequest`** (a `manifests-port` type). The
ThreadLocal is only an OSGi-internal relay the synthesis service fills from the request; the host
NEVER references `ManifestSynthesisContext`. New synthesis data → add a `manifests-port` record + a
`with*()` on the request + a slice on the context, mirroring `ImageState`.

See [[capn-cert-ownership-incoherence]] [[osgi-runtime-r4-resume-state]] [[osgi-system-export-resolution-only]].
