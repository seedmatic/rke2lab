---
name: osgi-testkit-framework-injection-idea
description: "Future increment idea (user, 2026-06-20, NOT in R4 scope): extend the OSGi JUnit testkit (FelixFrameworkExtension) with a Jupiter ParameterResolver/TestInstancePostProcessor that injects test fixtures — and the classes they reference that define injectables — from the booted framework's registry, instead of tests constructing dependencies by hand. The user calls fixture + referenced-injectable injection 'a must'."
metadata:
  node_type: memory
  type: project
---

## The idea

The repo already has an OSGi JUnit extension (the test-only `FelixFrameworkExtension`, and now the
production `OsgiRuntime`). User's idea (2026-06-20): adjoin a **Jupiter component** (a
`ParameterResolver` and/or `TestInstancePostProcessor`) that resolves a test's dependencies from the
**booted framework's BundleContext** — so a test declares `@Test void x(Resolver r)` or an injected
field, and the framework supplies the service, rather than the test doing `new ResolverImpl(...)`.

User, verbatim (2026-06-20): "notre extension osgi, on pourrait l'etendre ou lui adjoindre un autre
composant jupiter, pour injecter les tests via le framework OSGi" and the must-have refinement:
"l'injection des classes de fixture, ainsi que des classes qu'elle reference et qui definissent des
elements injectable est un must" — i.e. inject the fixture classes AND the classes they reference
that themselves define injectable elements (transitive injectable resolution from the framework).

## Why it matters / how it'd apply

It reconciles the tension settled in [[r4-resolver-service-ification]]: today `UnitResolverTest` /
`ManifestsVisitOrderTest` / `RegistryResolveTest` inject their own `new ResolverImpl(...)` (the test
owns its dependency — correct, never the reverse). With this testkit feature they'd instead receive
the `org.osgi.service.resolver.Resolver` from the framework — exactly "Resolver is an OSGi
interface, accessed via the framework" — without imposing anything on production.

## Scope discipline (why NOT now)

This is a **testkit increment of its own** (design the extension, the shared-framework lifecycle
across tests, field/param resolution, transitive injectable discovery) — OUTSIDE the R4 critical
path (prove the boot seam). Analysis agreed: embedding the framework in the pure-algorithm unit
tests adds ZERO coverage (the ResolverImpl obtained via the framework is bit-identical; resolution
is over pure org.osgi.resource data). The real robustness win was hardening the SEAM test to drive
the SCR-injected Resolver end-to-end — already done in R4. Pick this up as a separate increment.

## The test-fragment model (the bigger sibling) + its TWO motivations

Surfaced during doctor Placement 2 (2026-06-21). Two coupled moves: (1) jGiven & co resolve IN the
framework as bundles (so scenarios can play in-container); (2) test modules contributed as OSGi
**fragments** with a `Fragment-Host`, attached to the bundle under test, sharing its classloader → they
see `internal`/package-private types white-box INSIDE the framework, and play jgiven scenarios against
the real wiring. The fragment is the **in-framework twin of package-private**: package-private =
white-box in the bare JVM; fragment = white-box in OSGi. Implication graved: this is the *execution
substrate of the designer-runbook's "live" column* for OSGi-resident capabilities — gated on the
orchestration→OSGi migration ([[pipeline-orchestration-osgi-vision]], [[orchestration-purity-benefit]]).

TWO independent motivations now justify it (not one):
- *Play scenarios in-container against the real OSGi wiring* (the original idea).
- *Dissolve a real Maven cycle* — proven by [[doctor-internal-edge-debt]] commit 2: a shared test
  fixture (`ReferralReplies`) building a port type cannot live in a module the port's own tests depend
  on (`doctor-port ↔ doctor-testkit`, Maven module-level, scope-blind). Root cause: our tests run in the
  bare-JVM flat classpath yet cover code that lives in classloader-isolated bundles. As a fragment, the
  fixture is its host → no module, no cycle. Cost today: 5 value-type tests parked in HOST.

The jGiven mechanism was SPIKED and the model ADOPTED (2026-06-21,
[[jgiven-osgi-wrap-spike-verdict]]): jGiven wraps clean as a first-class bundle, cost LOCAL (one
`bootdelegation=sun.misc`, one forced fragment import, no `DynamicImport-Package: *`); palier 3
demonstrated a fragment reading a host's package-private field white-box and running a full
Given/When/Then in-container. The user decided to PIVOT the test model to full-OSGi. The reusable
templates are the `jgiven-wrap` bnd recipe + the `Fragment-Host` shape + the testkit `bootDelegation`
verb (already on `FelixFrameworkExtension`).

**SHIPPED — infra on main ([[jgiven-osgi-testkit-shipped]]), first client integrated**
([[doctor-internal-edge-debt]], chantier B in design/pre-integration `4e3e1427`): doctor's `-test`
fragments run in-container, the cycle dissolved, the 5 HOST tests rehomed. The `InContainerJUnitRunner`
in `osgi/junit-testkit` solved the 3 classloader crossings (TCCL on a dedicated thread; engine passed
as a `Class` so bnd wires it + `@Osgi`/`@OsgiSpike`; `BundleWiring.listResources` for class discovery).
DX bonus: in-container tests surface as VSCode-clickable `DynamicTest` nodes (after a first warm-up run
of the `@TestFactory` class — dynamic tests aren't statically discovered). Still FUTURE: the reusable
Jupiter-injection half (resolve a test's deps from the booted registry, the original 2026-06-20 idea).

See [[r4-resolver-service-ification]] [[osgi-runtime-r4-resume-state]] [[doctor-internal-edge-debt]]
[[osgi-system-export-resolution-only]] [[pipeline-orchestration-osgi-vision]].
