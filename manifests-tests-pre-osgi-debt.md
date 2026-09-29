---
name: manifests-tests-pre-osgi-debt
description: DONE 2026-06-26 — manifests-core-test in-container fragment built, DefaultManifestExplodeServiceTest migrated to DS injection (6/6 green in Felix); old POJO test deleted. The 5 pure-API tests legitimately stay flat-classpath POJO.
metadata:
  type: project
---

**DONE (2026-06-26).** Built `osgi/manifests/manifests-core-test` mirroring doctor-core-test: a
`Fragment-Host: io.seedmatic.rke2lab.manifests.core` fragment (`type=fixture; suite=manifests;
role=core`), the `ManifestsCoreTests` runner + actor `DefaultManifestExplodeServiceTest` in
`src/main`, the `ManifestsCoreInContainerTest` Felix probe in `src/test`. The migrated test acquires
`ManifestExplodeService` + `YamlMapper` from the registry (`getServiceReference`), no more `new
DefaultManifestExplodeService(new YamlMapper())`. 6/6 green in-container; the old POJO test in
manifests-core deleted. The 5 remaining manifests-core tests (Cdk8sApiObjectResolver, RegistryResolve,
ManifestsVisitOrder, CrossDomainRule, ManifestsUniverse) exercise NO @Component (the `ResolverImpl`
they `new` is a Felix test-classpath resolver) — they legitimately stay flat-classpath POJO in
`src/test`.

**Doubled as the runtime proof of [[cdk8s-carrier-flat-jar-pattern]]:** resolving manifests-core
in-container wired the cdk8s carrier ⇄ jackson and attached the systemd-cdk8s-manifests fragment
(`FRAGMENT WIRE → manifests.cdk8s`) — the bundle-to-bundle cdk8s wiring holds in Felix. The probe's
GRAPH bundle list is hand-written (→ [[derive-incontainer-graph-from-imports-backlog]]); the one real
obstacle was netplan-port needing `inet.ipaddr` (the `com.github.seancfoley.ipaddress` bundle), added
to the graph. Two start-vs-resolve lessons: the JUnit stack `installFromClasspath().start()`s, but the
manifests graph (cross-imports + a fragment with no lifecycle) must be install-without-start +
resolve-as-one-set.

--- original blueprint (kept for reference) ---

`manifests-core` was written BEFORE the OSGi migration, so its tests live in `src/test/java` as
flat-classpath POJO tests that `new DefaultManifestExplodeService(...)` etc. doctor, written
OSGi-native, instead uses the in-container **fragment-test** model: a `-test` Fragment-Host bundle
whose actor `@Test` classes live in `src/main/java` (white-box on the host loader), driven by a
bare-JVM probe that boots Felix and runs them inside the framework, acquiring `@Component` services by
`FrameworkUtil.getBundle(getClass()).getBundleContext().getServiceReference(...)`.

**Why this is debt (the user's framing):** running these tests in JCL/flat-classpath makes the fixtures
heavier — every test hand-wires what DS would inject (the `new YamlMapper()` in
`DefaultManifestExplodeServiceTest` is the live symptom). Now that manifests uses DS correctly (the
`YamlMapper` `@Component`, the services `@Reference`-injected — see [[refactor-statics-on-touch]] and the
YamlMapper commit), the in-container model becomes natural: the test gets the service injected, no `new`.

**How to apply (blueprint exists, mirror doctor exactly — uniformity):**
- New module `osgi/manifests/manifests-core-test`, parent `bundle-test-parent`, `Fragment-Host:
  io.seedmatic.rke2lab.manifests.core`, `Provide-Capability: io.seedmatic.rke2lab.embed; type=fixture;
  suite=manifests; role=core`. Deps: manifests-core (provided), manifests-port (provided), DS
  annotations (provided), jgiven-testkit/jgiven-wrap/byte-buddy/osgi.core (test).
- Actor `@Test` classes → `src/main/java/io/seedmatic/rke2lab/manifests/` (compiled into the fragment);
  a `ManifestsCoreTests` runner (names `JupiterTestEngine.class`, delegates to `InContainerJUnitRunner`).
- The bare-JVM probe `ManifestsCoreInContainerTest` in `src/test/java`: `JGivenTestkit.felix()`
  `.systemPackages("io.seedmatic.rke2lab.systemd.port;version=1.0.0")` `.installFromClasspath(junit
  stack + manifests-port + other domain ports as bundles + junit-testkit)` `.build()`, install fixture
  by filter, resolve host, start, reflectively run the runner.
- Tests that exercise a `@Component` (e.g. DefaultManifestExplodeService, the YamlMapper) acquire it via
  `getServiceReference` (use `withScr()`), NOT `new`. Pure-API tests can stay POJO in src/test.
- Add `manifests-core-test` to `osgi/manifests/pom.xml` modules.

Full investigated blueprint (doctor exemplar paths, pom/bnd verbatim) was produced 2026-06-26; the model
to copy is `osgi/doctor/doctor-core-test/` + `osgi/jgiven/jgiven-testkit/`. See
[[object-graph-navigability-principle]] (why DS injection beats hand-wired fixtures).
