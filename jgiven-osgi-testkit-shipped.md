---
name: jgiven-osgi-testkit-shipped
description: "SHIPPED 2026-06-21 (branch feature/jgiven-osgi-testkit, integrable on main): the jGiven-OSGi spike PROMOTED from throwaway osgi-spike/ to real test-scope modules under osgi/jgiven/. Four modules: jgiven-wrap (jGiven as a first-class bundle), jgiven-testkit (shared jgiven-fragment.bnd include + JGivenTestkit.felix() boot helper, depends on osgi/testkit), and jgiven-probe + jgiven-probe-test (the host+(-test)-fragment pair that doubles as the palier-2/3 @Osgi regression guard, GREEN on Felix 7.0.5). osgi-spike/ deleted, root -Pspike profile removed. Handoff: docs/architecture/osgi/jgiven-osgi-testkit-handoff.adoc."
metadata:
  node_type: memory
  type: project
---

## What shipped

The fragment-test chantier the verdict ([[jgiven-osgi-wrap-spike-verdict]]) anticipated. The proven
spike material moved out of throwaway `osgi-spike/` (gated `-Pspike`) into durable test-scope modules
under a new `osgi/jgiven/` aggregator (registered in `osgi/pom.xml`). jGiven is a BDD concern, not a
JUnit one (scenarios run through the host classloader, not JUnit), so it lives under `osgi/jgiven/`,
DEPENDS ON the generic JUnit testkit (`osgi/testkit`), and is NOT owned by it.

## The four modules

- `osgi/jgiven/jgiven-wrap` — jGiven (jgiven-core + jgiven-junit5 2.0.3) as a first-class bundle. bnd
  recipe promoted verbatim (Export `com.tngtech.jgiven.*`, import the rest as stock bundles, embed
  nothing). Test-scope.
- `osgi/jgiven/jgiven-testkit` — the reusable assets, a PLAIN JAR (build-parent, not a bundle):
  (1) `src/main/resources/jgiven-fragment.bnd` — the shared bnd include carrying the ONE forced
  import bnd can't compute (`com.tngtech.jgiven.impl.intercept`) + wildcard tail + `-noimportjava`;
  (2) `JGivenTestkit.felix()` — a `FelixFrameworkExtension.Builder` pre-loaded with the jGiven boot
  closure (`bootDelegation(sun.misc)` + the 7 dependency bundles + jgiven-wrap + slf4j/junit system
  packages). jGiven-specific knowledge lives HERE, deliberately out of the generic testkit.
- `osgi/jgiven/jgiven-probe` — the guard's host bundle: pure POJO `Vault`, package-private `balance`.
- `osgi/jgiven/jgiven-probe-test` — the guard's `-test` fragment, THE TEMPLATE a host copies: its
  bnd writes only `Fragment-Host` + a one-line reactor-relative `-include` of jgiven-fragment.bnd.

The palier-2/3 harness folded into `jgiven-testkit/src/test` (`JGivenTestkitGuardTest`, `@Osgi` not
`@OsgiSpike` → runs in the DEFAULT reactor as a lasting guard). The separate `-tests` module was
dropped (4 modules, not 5). probe + probe-test MUST stay bnd-built modules — bnd-maven-plugin is one
manifest per module, and the guard's whole point is to exercise the REAL bnd-computed fragment
manifest the shared include produces, not a hand-rolled one.

## Verified (the brief's discipline)

`./mvnw -pl :jgiven-testkit -am clean test -Dmaven.build.cache.skipCache=true -DskipTests=false` →
`Tests run: 2, Failures: 0` on real Felix 7.0.5; reactor built all 4 modules + testkit from source,
NO `mvn install`. Proof the include WORKED (not just that the build passed): the fragment's built
`Import-Package` ends with `com.tngtech.jgiven.impl.intercept` and contains ZERO byte-buddy packages
(bnd computes those onto the wrap bundle) — matches the spike report exactly.

## bnd include mechanism (gotcha for future hosts)

A true cross-jar classpath `-include` is a bndtools-WORKSPACE feature, ABSENT in plain
bnd-maven-plugin. So the host's `-test` fragment uses bnd's `${.}` (dir of the including bnd file) +
a reactor-RELATIVE path to `jgiven-testkit/src/main/resources/jgiven-fragment.bnd`. Documented in the
handoff. Probe-test's path is `${.}/../jgiven-testkit/src/main/resources/jgiven-fragment.bnd`.

## First real client

doctor ([[doctor-internal-edge-debt]], PARKED on refactor/doctor-internal-edge): its 5 HOST-parked
value-type tests + the `ReferralReplies` Maven cycle dissolve when its `-test` fragment adopts this
model. That integration is the next step — it was gated on THIS landing.

See [[jgiven-osgi-wrap-spike-verdict]] [[osgi-testkit-framework-injection-idea]]
[[osgi-system-export-resolution-only]] [[doctor-internal-edge-debt]] [[bdd-jgiven-test-strategy]].
