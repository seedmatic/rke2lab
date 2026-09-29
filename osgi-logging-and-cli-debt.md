---
name: osgi-logging-and-cli-debt
description: "OSGi-IDIOM debt found 2026-06-18 auditing the refactored osgi/ bundles. ★ TRIAGED 2026-06-18 (read-only impact pass on integration @211063cd): most of it is resolved or is the runtime trunk, not independent debt. (1) Log → logback HALF already purged from bundles (lives in the 3 exec/ CLIs, correct); slf4j-api in 4 manifests-core classes is a FAÇADE not shadowing — target = OSGi LogService NATIVE via Pax Logging (pax-logging-api implements slf4j → routes to LogService), cadenced at R4/R6 (the executables boot Felix), core keeps the slf4j API unchanged. (2) ServiceLoader → DS is the osgi-runtime TRUNK (R2 declared @Component, R3+ consumes) — not separate debt. (3) Config Admin/Metatype: the 7 getResourceAsStream sites are static template/cert loading = LEGITIMATE, not debt. ★ The rk2lab typo BUG is DEAD (purged step 5, grep-confirmed). Net: no standalone debt chantier remains; verdicts recorded, work folds into R4/R6."
metadata:
  node_type: memory
  type: project
---

## ★ TRIAGE VERDICTS (read-only impact pass, 2026-06-18, integration @211063cd)

A full impact sweep was run before launching any "debt" workspace. Conclusion: there is **no
standalone OSGi-debt chantier left** — each axis is either already resolved, a legitimate idiom, or the
osgi-runtime trunk. The verdicts:

1. **rk2lab typo BUG — DEAD.** The netplan services file is correct
   (`io.seedmatic.rke2lab.netplan.api.NetplanSynthesisService` → `…netplan.DefaultNetplanSynthesisService`)
   and `grep -rn rk2lab osgi/ exec/` returns NOTHING. Purged at step 5. Nothing to do.

2. **Log / logback — heavy half already purged.** Zero logback in the core bundle poms, zero
   `logback.xml` embedded in bundles. logback lives only in the 3 `exec/` CLIs (seed-master,
   manifests-cli, netplan-cli), each with its own `logback.xml` + shade exclude/relocate — the CORRECT
   place (backend belongs to the executable). Only `slf4j-api` remains in the core: 4 classes of
   manifests-core import it (`DefaultManifestSynthesisService`, `DefaultManifestExplodeService`,
   `systemd/BootstrapInfrastructureSynthesizer`, `systemd/SynthesisTopicRunner`), all via
   `static final Logger LOG = LoggerFactory.getLogger(<Class>)`; netplan-core imports zero. slf4j-api is
   a FAÇADE (an API package), NOT a backend → there is NO shadowing. Not debt.

3. **ServiceLoader → DS — the runtime TRUNK, not separate debt.** The 5 META-INF/services files + 7
   `ServiceLoader.load` sites ARE the osgi-runtime migration surface (spec §2). R2 already declared the
   providers `@Component`; R3 consumes intra-bundle via `@Reference`, R5 deletes the static path. Do NOT
   open a parallel chantier for this — it would dedupe the trunk.

4. **Config Admin / Metatype — legitimate, not debt.** The 7 `getResourceAsStream` sites in manifests-core
   are all `*Assets`/`*Inclusion`/`*ManifestsUnit` classes loading STATIC packaged templates/certs via a
   class anchor (`resourceAnchor.getResourceAsStream(path)` / `getClass().getResourceAsStream(cert)`).
   That is correct resource bundling, not configuration modellable by Metatype/Config Admin. Triage
   closed.

## ★ The slf4j → OSGi LogService decision (SETTLED: A via Pax Logging, cadenced R4/R6)

Decision taken 2026-06-18: go to the OSGi LogService NATIVELY (choice "A"), consistent with the cap
decision ([[osgi-runtime-migration-state]] — OSGi fully integrated into the system). Two facts shaped
the *how* and the *when*:

- **The bridge already exists — no shim to hand-roll.** Pax Logging (`org.ops4j.pax.logging`) is the
  community standard: `pax-logging-api` IMPLEMENTS slf4j-api (+ log4j/jcl/JUL) and routes to the OSGi
  LogService, with `pax-logging-logback`/`-log4j2` as the backend. My earlier "we have no binding and
  would have to bricoler one" was WRONG — Pax Logging *is* the packaged bridge.
- **The OSGi LogService has no static accessor.** `org.osgi.service.log.LoggerFactory` is a SERVICE:
  it needs a launched framework + a registered LogService, injected via `@Reference` → only inside
  `@Component`s. But 2 of the 4 core classes (`BootstrapInfrastructureSynthesizer`, `SynthesisTopicRunner`)
  are PLAIN classes, and the core today also runs framework-LESS in `manifests-cli` (a bare `main()` +
  ServiceLoader) and host-side in seed-master. So rewriting the core onto `@Reference Logger` now would
  BREAK the framework-less consumers.

**Chosen shape — A-pragmatic (Pax Logging), NOT A-fort (rewrite to @Reference):**

- The CORE keeps the slf4j API unchanged (`LoggerFactory.getLogger(Class)`) — zero rewrite, still works
  framework-less. slf4j-api stays as the façade; it is the idiomatic API surface, not debt.
- The LogService-native routing is obtained by making the BACKEND be `pax-logging` (which IS the OSGi
  LogService) instead of logback, IN THE EXECUTABLES, once they boot Felix. At runtime
  `pax-logging-api` makes the core's slf4j calls route THROUGH the LogService — no code touched.
- **Cadence:** this is a rider of the boot slices, not a standalone cleanup. R4 (seed-master boots Felix
  host-side → the host seam gets a LogService) and R6 (the recommended shared `exec/` bootstrap boots an
  embedded Felix+SCR for the CLIs → manifests-cli too). seed-master and manifests-cli CONVERGE on the
  same "embedded Felix in main()" architecture (spec R6 option (a)) — that convergence is exactly what
  makes the Pax Logging backend swap uniform across the executables.

NOTE (corrects an earlier claim in this session): manifests-cli does NOT boot a framework *today* — it
is a bare `main()` doing `ServiceLoader.load`. seed-master likewise is on ServiceLoader today; Felix is
introduced by R4/R6. The "same north-bound OSGi architecture" is the TARGET those slices build, not the
present state.

## Origin (the audit that found this)

The user saw a `logback.xml` in an OSGi bundle and asked to sweep for ALL the standard OSGi services we
missed/shadowed during the layout refactor — the [[hub:check-osgi-standard-before-modeling]] discipline:
the spec usually provides what we hand-roll. Original read-only audit on design HEAD c3cfb58c; the
triage verdicts above are the 2026-06-18 follow-up on @211063cd (post-R2).

## The debt, by OSGi standard service (original inventory, kept for reference)

**1. Log service (`org.osgi.service.log`) — slf4j façade in 4 core classes.** See the SETTLED decision
above (A via Pax Logging, R4/R6). The logback backend was already removed from the bundles at the
exec-aggregator step ([[exec-aggregator-state]]); only the façade remains, which is fine.

**2. Service registry + Declarative Services — the ServiceLoader → DS migration.** This is NOT
independent debt: it is the osgi-runtime trunk. `ServiceLoader`/`META-INF/services` covers
`NodeEnvContributor`, `ManifestSynthesisService`, `ManifestUpdateGate`, `ManifestExplodeService`
(manifests-core), and `NetplanSynthesisService` (netplan). R2 declared all impls `@Component` (geste A,
SHIPPED); R3 consumes the intra-bundle case via `@Reference`; R5 deletes the static path. See
[[osgi-runtime-migration-state]].

**3. Config Admin (`cm`) / Metatype — legitimate resource loading, NOT debt.** See triage verdict 4.

**Not concerned:** EventAdmin (zero listeners), Coordinator, Prefs — unused, no shadow.
**Not debt:** jackson-databind in manifests (compile-time data lib, legitimate).

## ★ Real BUG (was here) — FIXED

The `osgi/netplan` services file was once misspelled `rk2lab` (missing the `e`) → its ServiceLoader
silently never registered. Fixed at step 5 ([[exec-aggregator-state]]); grep-confirmed dead on
@211063cd. Kept here as a single-source-of-truth-mismatch exemplar.

See [[exec-aggregator-state]], [[osgi-leaves-state]], [[hub:docrepo-dag-state]] (ServiceLoader=poor cousin),
[[hub:check-osgi-standard-before-modeling]] (the meta-discipline), [[osgi-runtime-migration-state]] (the
runtime trunk + R4/R6 where the Pax Logging swap lands).
