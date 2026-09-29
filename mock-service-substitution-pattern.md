---
name: mock-service-substitution-pattern
description: "The retained test-collaborator pattern (decided + shipped 2026-07-11, doctor world green): NO standing @Component fake in a -test fragment; the CALLER registerService's its collaborators into the real registry. Criterion: does the collaborator cross the seam? yes → inject it, the standing fake dies. Two shapes — out-of-container (test in host JVM boots Felix, registers a mock on the host loader, the in-container scenario resolves it because the seam is type=seam single-exporter) and in-container (a passenger *Test played inside the domain's realm registerService's via its own BundleContext from FrameworkUtil.getBundle). This SUPERSEDES the per-domain-osgi-fakes-chantier (variant=fake fragments) for the OSGi world."
metadata:
  type: feedback
---

**The decision (user, 2026-07-11): kill ALL standing @Component fakes; the caller injects its
collaborators.** A `@Component` in a `-test` fragment is auto-published by SCR = a *standing fake* (a
fixture that exists whether or not a test wants it). We retired them. `doctor-core-fake` (FakeDiagnostician,
FakeReadinessAuthority, FakeConsultingService) DELETED; the whole `dsproof/` dir (FakeMedicalRecordJournal,
FakeInterventionJournal, FakeInterventionLedgerWriter) DELETED. This REVERSES
[[per-domain-osgi-fakes-chantier]] (which prescribed the opposite — a `variant=fake` fragment per domain);
that chantier is CANCELLED for the OSGi world.

**The criterion (binary, per collaborator): does it cross the seam?**
- YES (e.g. `Cellar`, `Specialist`, `ClusterReadinessContact`, `ConsultingService`) → the caller (the test)
  `registerService`s it into the real registry before playing. Same register MECHANISM as SCR would use —
  driven by the caller, not standing.
- NO → it's a plain in-realm object, `new` it.

The register mechanism is uniform; what changed is WHO publishes and WHEN — the test, at play-time, not a
frozen fragment component.

**Why (not just style):** a standing fake is always-on (masks whether a test meant it), and — proven this
session — a "real Specialist" contributed via a domain fragment would create a dependency INVERSION
(doctor-core-test → systemd-core → doctor-spi cycle). Registering a plain mock the test owns avoids both:
the fake exists exactly for the one test that registers it, and no module cycle.

## The two shapes (both are "mock service", differing only by WHERE the test runs)

**Out-of-container** — `ClusterReadinessScenarioInContainerTest`, `SystemdAdapterScenarioInContainerTest`
(`@OsgiWorld`). The test runs in the HOST JVM, boots a real Felix (`JGivenTestkit.felix().withJUnitRunner()`),
then `felix.context().registerService(Contact.class, mock, props)` BEFORE invoking the scenario's
`run()` in-container. The scenario resolves the mock from ITS OWN bundle registry. It works across the
boundary because the seam is `type=seam` = single-exporter, shared FLAT across realms → the mock registered
on the host loader IS the same Class the in-container scenario reads (no ClassCastException). Register fresh
per test, unregister in `finally` (one framework is class-static; a lingering mock would win an oldest-wins
ranking tie).

**In-container** — `HealthSystemContributionTest` (a passenger `*Test` enumerated by `InContainerJUnitRunner`,
played INSIDE doctor-core's realm). It gets its `BundleContext` from `FrameworkUtil.getBundle(getClass())`
and `context.registerService(Specialist.class, mock, tierProps)` directly — same realm, no boundary to
cross. ORDER matters: register the roster member (tier-tagged Specialist) BEFORE the trigger (the Cellar
that unblocks the frontier registries' activation), so it is in the set when the institution activates.
SCR activation is asynchronous after the trigger → await the published service with a bounded
`getServiceReference` poll (use only `org.osgi.framework`, NOT `org.osgi.util.tracker` — a runtime-only
package the fragment host does not import).

**In-container scion (the RETAINED shape for a `-bdd` scion, shipped 2026-07-11, `bbox`).** The third
combination the first two lacked: registry-resolved collaborators, registered IN-CONTAINER (no seam),
AND a harvested runbook. Three pieces:
- `{domain}-bdd` (type=model): the jGiven `{Domain}Scenario` (resolves its contact + RunGate + doctor
  from `FrameworkUtil.getBundle(this)`) + a `{Domain}BddScenarios.run()` front-door (a
  `JUnitLauncherCore.selectClass(scenario)` that returns the serialized `(runbook, consultations)`).
- `{domain}-bdd-test` (a FRAGMENT, `Fragment-Host: {domain}-bdd` — shares the bundle loader): a
  passenger `{Domain}ScenarioInContainerTest` that (a) `FrameworkUtil.getBundle(FrontDoorClass).getBundleContext()
  .registerService(...)` the MOCK contact + mock `RunGate` (+ mock doctor) — same loader, so the mock
  IS the Class the scenario resolves, NO seam — then (b) invokes the front-door `run()`, (c) asserts on
  the decoded envelope. Plus a `{Domain}BddTests.run()` = `InContainerJUnitRunner` entry naming the
  fragment's package.
- `src/test` `@OsgiWorld` proxy `{Domain}BddInContainerTest`: boots Felix, `installFixtureWithHost` +
  `installImportClosureOf`, resolves, `host.loadClass(entry).run()`.
- Reentrancy is safe: the front-door's `JUnitLauncherCore` spawns a fresh worker thread + `LauncherSession`
  per call, so a launcher-inside-a-launcher (the proxy drives the passenger which drives the front-door)
  does not collide.
- jGiven fail-fast trap: a Then that throws (a surfaced failure) skips downstream steps AND leaves the
  stage state unstable — so the `@Test` body must OWN the data sink (a `List`/`Map` created in the method,
  passed to the Given, filled by the When), and read THAT for the post-play consult, never a stage getter.
  (bbox lost the doctor-consult assertion until the outcomes sink moved into the `@Test` body.)

## How to apply

- Writing a new `-bdd` scion test → the IN-CONTAINER SCION shape (the default now). Copy the `bbox`
  three-piece set (`bbox-bdd` + `bbox-bdd-test` fragment + proxy). The scion consults the RunGate (A2)
  and the test registers a mock `RunGate` (`cultivating()` true/false) alongside the mock contact.
- The out-of-container shape (`ClusterReadinessScenarioInContainerTest`) is the PRIOR form — it needs the
  port system-exported (mock crosses on the host loader). cluster-bdd + systemd-bdd are to be RETROFITTED
  onto the in-container fragment shape when their ports are de-seamed (see
  [[port-edge-domain-ownership]] realisation note).
- Proving an SCR contribution / roster collection → in-container passenger shape:
  `HealthSystemContributionTest` (register tier-tagged collaborator, bounded await).
- NEVER add a `@Component` fake to a `-test` fragment for the OSGi world again.
- `StubConnection` + the `variant=fake` selector still exist ONLY host-side (`exec/seed-master` old
  fluent-pipeline tests, DEFERRED) — they die when the host migrates, not before.

See [[seed-broker-host-adaptation]] [[per-domain-osgi-fakes-chantier]] [[cluster-seed-execution-state]].
