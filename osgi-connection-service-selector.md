---
name: osgi-connection-service-selector
description: "How a host-agnostic BDD stage resolves a FAKE OSGi service in tests without knowing it's in a test — the version-selection seam lives on OsgiConnection.serviceSelector(), produced by the driver, never in the stage or the staging."
metadata:
  type: project
---

**Problem it solves.** Pipeline stages are host-agnostic (run embedded OR in-container OR remote).
A stage reaches a model service via `connection.awaitService(X.class, timeout)` — by TYPE, no filter,
because the stage does not (must not) know it's in a test. So: how does a test make the stage resolve
a FAKE `X` instead of the live one, *in any world* (including in-container, where the live impl IS
staged and you can't just "not install it")?

**The wrong loci (rejected, with why):**
- Filter in the stage (`awaitService(X, "(variant=fake)")`) → puts a TEST concern in PROD code, breaks
  agnosticism. ❌
- Install-scope only ("test doesn't stage the live impl") → works ONLY when the test owns the staging;
  fails in-container/full-closure where live is already present. Too weak as the *principle*. ❌

**The seam (implemented on `OsgiConnection`, scenario-engine).** Version-selection is a property of the
CONNECTION, not the stage, not the staging — because the connection IS "which world + which variant",
and the DRIVER that produces the connection is the sole thing that knows test-vs-prod / world.
- `Optional<String> serviceSelector()` — default `Optional.empty()`; an LDAP fragment ANDed into every
  lookup. `awaitService` builds `(&(objectClass=<type>)<selector>)` via a private `trackerFor(type)`
  (the `ServiceTracker(context, Filter, …)` ctor; plain `ServiceTracker(context, Class, …)` when no
  selector — verified against felix 7.0.5 jar: 4 ctors incl. String/Filter, ServiceReference exposes
  getProperty/getPropertyKeys).
- Factory: `over(ctx, ownsLifecycle, onClose)` delegates to
  `over(ctx, ownsLifecycle, onClose, Optional<String> serviceSelector)`. Immutable at birth (a
  connection doesn't mutate its variant) — user chose "new param to over", NOT a `withSelector`.
- Prod: `embedded()` → no selector → live service. Test driver: `over(felixCtx, …,
  Optional.of("(variant=fake)"))` → the fake wins. Same stage, same `awaitService(X.class)`, portable
  across embedded/attached/remote.

**The producer chain (answers "who produces the state?"):** the SERVICE never rides a scenario-state —
the CONNECTION does (`@ExpectedScenarioState OsgiConnection`), and the stage pulls the service THROUGH
it on demand. The DRIVER produces the connection state: `store.put(HostSeeder.NS, CONNECTION, conn)` →
`HostSeeder.postProcessTestInstance` → `acceptConnection` → scenario `@ProvidedScenarioState` → stage
`@ExpectedScenarioState`. Prod driver = ClusterSeedTopic (Task 8); test driver = the test harness.

**The fake producer:** a standalone SCR fixture BUNDLE (template `bench-scr-consumer`, NOT a fragment)
publishing the fake `@Component`s with `property = "variant=fake"`. Bundle not fragment because: (a)
the 3 fakes implement interfaces from 3 DIFFERENT bundles (ReadinessAuthority←world-gateway,
SystemdRuntimeProbe←systemd-port, ConsultingService←doctor-port) — one fragment attaches to ONE host;
(b) the port bundles do NOT declare `Service-Component: OSGI-INF/*.xml` (only doctor-core does — verified),
so a fragment on a port would never be SCR-scanned. Fragments are for WHITE-BOX access to sealed
package-private actors (that's why doctor-core-test is a fragment); our fakes need only public exported
port interfaces. See [[cluster-seed-execution-state]] [[jgiven-custom-executor-seam]].

**The fake fragments SHIPPED (2026-07-06, compile+package green, descriptors generated):** named by
HOST (generic, not seed-specific) so every pipeline reuses them:
- `osgi/domains/systemd/dbus-systemd-edge-fake` — `Fragment-Host: …dbus.systemd.edge`, publishes
  `FakeSystemdRuntimeProbe` (`@Component service=SystemdRuntimeProbe property="variant=fake"`, healthy
  snapshot → endpoint reachable → happy path green offline). REQUIRED a prod-touch: dbus-systemd-edge
  bnd now declares `Service-Component: OSGI-INF/*.xml` (wildcard, like doctor-core) so SCR scans the
  fragment's descriptor. Package SHARED with host (`…dbus.systemd.edge`) — has its own package-info,
  tolerated (doctor-core-test precedent).
- `osgi/domains/doctor/doctor-core-fake` — `Fragment-Host: …doctor.core`, publishes
  `FakeReadinessAuthority` + `FakeConsultingService` (both `variant=fake`, encode Documents via the
  fragment's own `DocumentCodec`). Own package `io.seedmatic.rke2lab.doctor.fake` (NOT doctor-core's
  sealed package — implements public gateway seams, no white-box needed).
Capability filters: `(&(type=fixture)(suite=systemd)(role=probe-fake))`,
`(&(type=fixture)(suite=doctor)(role=gateway-fake))`.
REUSE is imminent (user flagged it): Task 6 cluster-readiness consults the SAME doctor seams; the 6
other pipelines reuse both fragments + the selector; the prod `fragment-contribution-mediation-model`
(cluster contributes a fragment to doctor-core) is their live twin.

**DEFAULT-SAFETY — a fake must NEVER win a plain lookup (user's guard):** two barriers.
(1) PROD: `EmbedCapability.INSTALLABLE = (|(type=model)(type=edge)(type=record)(type=library))` —
`type=fixture` is EXCLUDED (verified at source, "installed only by the test harness"), and the fakes
are test-classpath deps only, never seed-master runtime deps → the live boot's registry never holds a
fake. (2) TEST: the fragment attaches to its host, which ALSO publishes the live @Component → both
coexist. `awaitService(Class)` with NO selector would tie at `service.ranking=0` and pick by
`service.id` (registration order) — NON-DETERMINISTIC. FIX shipped: every fake carries
`service.ranking:Integer=-1000` alongside `variant=fake`, so even a nude lookup DETERMINISTICALLY
prefers the live; a fake is reached ONLY by the explicit `(variant=fake)` selector. Two layers:
variant gates by opt-in, negative ranking guarantees the default.

**FUTURE EVOLUTION — the MOCK variant (user flagged, NOT built):** today's fakes are STATIC (fixed
response: healthy snapshot, continue-degraded verdict) — real simplified impls, not per-test
configurable. The evolution is a MOCK variant: behaviour injected/verified per test ("on THIS
checkpoint return STOP", "assert consult called once"). It rides the SAME selector seam for free —
add `variant=mock` beside `variant=fake` and the live default, no change to the stage / live / static
fake. YAGNI now (static fakes dissolve island 1); the door is tooled: new variant, same mechanism.

**TWO PROBE AXES, both alive — NOT redundant (settled with user 2026-07-06):** F1 (inject-the-probe)
is NOT superseded by the OSGi fakes; they serve orthogonal phase natures.
- Axis 1 — the injected application probe (`SeedProbes` = preflight/bbox/incus, `probed_by`): a PURE
  phase, host-only logic, NO OSGi service to reach → injected offline via the store, live/fake. This
  keeps the 3 pure phases OFFLINE (Task 4's property). `SeedProbes` carries NO systemd probe.
- Axis 2 — the OSGi registry service (`SystemdRuntimeProbe`, dbus seam): a phase that DIALOGUES with
  a real OSGi service → `SystemdAdapterStage.liveProbe()` does `awaitService(SystemdRuntimeProbe)` and
  BUILDS the application `SystemdAdapterProbe` from it. This is what the `dbus-systemd-edge-fake`
  fragment fakes. Chain: `awaitService(SystemdRuntimeProbe)` → `SeedSystemdAdapterEndpointGate.live`
  → produces a `SystemdAdapterProbe`.
The asymmetry is the map of the terrain (playability/edge frontier): a pure phase has no OSGi service
to fake; an OSGi-dialoguing phase does. UNIFORMISING (putting systemd into SeedProbes) was REJECTED —
it would re-introduce the host dressing a fake as a service (the refused fiction) and deny that
systemd genuinely crosses to dbus. Task 6 cluster-readiness is axis-2 like systemd (kubectl service).

**PureStagesTest EVOLVES (user chose "tout dans un vrai Felix"):** once ClusterSeedScenario chains
`.and().systemdAdapter()`, the scenario CROSSES the frontier — the old inert-connection proxy
(throws-if-touched, no fragments) can no longer play it whole (systemdAdapter's awaitService → throws
→ FAILED). The test now boots a REAL Felix with the fake fragments attached (live + fakes coexist —
the Felix is HEAVIER than the live boot on this axis, not "lighter"). F1 survives: the 3 pure phases
still read injected inert probes, never awaitService, so they stay offline even with a Felix present.

**`service.ranking` is also the tooled door for coexistence:** if a full-closure integration test
must prefer a fake by default, bump its ranking positive — still producer-side, stage unchanged.
