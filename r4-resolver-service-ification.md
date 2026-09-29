---
name: r4-resolver-service-ification
description: "R4 WI-C0 design decision (2026-06-20): service-ify the Felix Resolver instead of using it as a flat library. UnitResolver was new ResolverImpl(new Logger()) — reaching into felix.resolver's IMPL package (org.apache.felix.resolver), which the bundle does NOT export. Decision: prod consumes the injected org.osgi.service.resolver.Resolver service (felix.resolver's own Bundle-Activator already registers it); new ResolverImpl leaves prod; unitrepo-core becomes a clean bundle importing only org.osgi.service.resolver, felix.resolver → test scope. Tests provide their own new ResolverImpl (test owns its dependency, never the reverse). Seam test hardened to drive a real resolution through the SCR-injected Resolver."
metadata:
  node_type: memory
  type: project
---

## The discovery (R4 WI-C, carto before packaging)

`UnitResolver` (osgi/unitrepo/unitrepo-core) did `new ResolverImpl(new Logger(LOG_ERROR))` —
reaching into felix.resolver's **impl package** `org.apache.felix.resolver` (`ResolverImpl`,
`Logger`). The felix.resolver bundle exports ONLY `org.apache.felix.resolver.reason` and
`org.osgi.service.resolver` — NOT the impl package. So felix.resolver was never installed as a
bundle; it was served FLAT (mirrored system-export off the classpath) and "worked" because
UnitResolver used it as a plain library, not an OSGi service. The C-bundles plan to bundle-ify
felix.resolver would have broken (impl not exported) or forced a double-exporter on
`org.osgi.service.resolver` (the R8 system bundle already exports it) → the R1 split scar.

## Decisions (user, 2026-06-20)

1. **Prod is service-only.** `UnitResolver(List<UnitResource> universe, Resolver resolver)` — single
   ctor, resolver INJECTED. `new ResolverImpl` LEAVES production code. felix.resolver's own
   `Bundle-Activator` already does `bc.registerService(Resolver.class, new ResolverImpl(new
   Logger(logLevel)))`, so installing it as a bundle publishes the service automatically — NO new
   activator module, NO fragment needed.
2. **Thread the service.** `DefaultManifestSynthesisService` (@Component) gains `@Reference Resolver`;
   thread it through `ManifestsDomainRegistryBuilder` → `ManifestsDomainRegistry.resolve()` →
   `new UnitResolver(closure, resolver)`. Single prod construction site is
   ManifestsDomainRegistry.java:111 (resolve()); builder at DefaultManifestSynthesisService.java:609.
3. **unitrepo-core becomes a clean bundle.** Prod imports only `org.osgi.service.resolver`;
   felix.resolver demoted compile→**test** scope. unitrepo-core can then be a real embedded bundle.
4. **Tests own their dependency, never the reverse** (user principle, verbatim: "il n'y a pas de
   raison que les tests posent des requirements sur la codebase, ça doit toujours être l'inverse").
   `UnitResolverTest` + `ManifestsVisitOrderTest` (manifests-core) inject `new ResolverImpl(new
   Logger(...))` themselves — NOT a mock (they verify REAL resolution; a mock resolves nothing).
   They stay fast unit tests; embedding the framework adds zero coverage (the ResolverImpl obtained
   via the framework is bit-identical to one built directly — resolution is over pure
   org.osgi.resource data, no bundle classloaders).
5. **Harden the seam instead.** Service-ification opens a gap: nothing proves the
   `@Reference Resolver` injection end-to-end (HostSeamEmbeddedFelixTest resolves services typed but
   never calls resolve()). Add felix.resolver to its topology + drive a real synthesis/resolution
   through the SCR-injected Resolver. THAT is where the real framework earns its keep.

## C-bundles scope, corrected

Embedded bundles = **manifests-core + felix.scr + felix.resolver + pax-logging-api/logback**.
unitrepo-core + cdk8s-systemd stay **flat** (pure libraries, ZERO @Component, no bundle consumer) —
UNLESS service-ification makes unitrepo-core clean enough to bundle too (it does: after WI-C0 it
imports only org.osgi.service.resolver). cdk8s-systemd stays flat (imports only jsii
software.constructs). Generalized export-subtraction in OsgiRuntime: never mirror as a system-export
any package an installed bundle already exports (replaces the slf4j-only removeIf special case from
WI-A) — the single-exporter rule applied to path 2.

See [[osgi-runtime-r4-resume-state]] [[osgi-system-export-resolution-only]] [[dual-path-inline-until-r5]].
