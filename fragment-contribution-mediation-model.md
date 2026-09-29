---
name: fragment-contribution-mediation-model
description: "DESIGN settled 2026-06-23 (brainstorm, NOT yet built) — the keystone is DEAD, replaced by peer-to-peer mediation through the OSGi registry. A domain contributes a FRAGMENT to a host (cluster → doctor-core); the fragment carries a live SCR @Component (Mediator, produces the fact) AND a Specialist (reasons), bringing its own Import-Package so the host stays blind. First use case = cluster-edge (cluster-first scope). This note holds the model + the open proof obligation; the durable form goes into port-edge-domain-ownership.adoc."
metadata:
  node_type: memory
  type: project
---

## What was settled (a long brainstorm, design only — no code yet)

The cluster-edge chantier turned into a model revision that **supersedes the keystone**. Captured here
because it CONTRADICTS just-shipped docs (`port-edge-domain-ownership.adoc` § the keystone, and the dbus
spec's keystone section) — the docs must be rewritten in this chantier.

### 1. The keystone is DEAD — peer-to-peer, not a central mediator

The keystone thesis was *"mediation between facets is a property of the executable, never a domain."*
**Reversed.** With fragments contributing and the OSGi registry resolving, NO ONE is at the center. Tell
apart two controls the keystone conflated:
- **control of MEANING** (who relates which facets) — fully DISTRIBUTED, peer-to-peer. Each domain
  relates its own facets in its own fragment. DEAD as an executable property.
- **control of TIME** (boot Felix, retry, preview, the `SeedNodeBootstrapWatcher` poll) — nobody's
  domain, so it STAYS in the executable, demoted from keystone to a *bootstrapper + time-driver*.

Consequence: the ordering "cluster presupposes systemd" stops being a sequence a chief decides — it
becomes a DATA dependency inside the cluster fragment (its Mediator reads the systemd node-fact itself).
Sequencing dissolves; only the temporal poll remains host-side. The user's frame: *"chacun son rôle selon
ce qu'il connaît, et moins on connaît mieux on fait"* — least-knowledge as structure. (Term for the
replaced section: candidates were subsidiarity / distributed-mediation / peer-to-peer — pick at write
time.)

### 2. The mechanism — a domain contributes a FRAGMENT to a host

The role that was missing: a **contribution**. cluster does NOT get a `mediation-host` module, and the
doctor is NOT bypassed — **the doctor IS the host**. cluster contributes a *fragment of doctor-core*
(`Fragment-Host: io.seedmatic.rke2lab.doctor.core`), exactly the fragment-test model
(`doctor-core-test`/`doctor-port-test`/`jgiven-probe-test` already do this) PROMOTED to production.

Corrected mechanics (I was wrong twice; the user was right both times):
- A fragment **DOES** carry live DS components — SCR processes the `Service-Component` header of a bundle
  AND its attached fragments (DS spec 112.4.1); the fragment's `@Component` activates in the HOST's
  bundle context. So the **Mediator** face (a `@Component` that `@References` cluster-edge + systemd-port
  to PRODUCE the Observation) lives in the fragment fine.
- The flow is the user's: a fragment **resolves BEFORE activation**, so the host knows its fragments at
  activation and **adapts** — doctor-core switches from the hard-wired `roster.add(new ClusterSpecialist())`
  to a DYNAMIC roster (`@Reference List<Specialist>`, or enumerating woven components).
- A fragment brings its OWN `Import-Package` (cluster-port, systemd-port) → **the fragment carries the
  coupling; doctor-core's manifest names no domain.** This is the answer to "isn't coupling a domain to
  its peers bad?" — the coupling lives in the fragment, whose definition IS to bridge; the host stays
  blind. Sharing the host classloader, `ClusterSpecialist` can stay package-private.

### 3. Both faces, one fragment, two decoupled times

The cluster fragment carries BOTH: the **Mediator** (`@Component`, composes cluster-edge + the systemd
node-fact → produces the `Observation`, time 1 — measures) and the **Specialist** (reasons over the
observation, time 2 — `ClusterSpecialist`, moved out of doctor-core into the fragment). Problem 4
("doctors do not open doors") holds: the Specialist never probes; the Mediator (a different object) does.

### 4. B3 (the generic contribution-host abstraction) is DEFERRED

B3 = "doctor and a mediation-host are the same SPECIES, a fan-out host of contributions; extract the
abstraction." Deferred — you do NOT extract an abstraction from a single instance (only one host exists:
the doctor). When a SECOND real host appears (a concrete need, not supposed), the common shape extracts
itself. Forcing it now = speculative abstraction (CLAUDE.md forbids). Revisit then.

## The cluster modules (first use case — cluster-first scope)

- **cluster-port** (new, `osgi/cluster/cluster-port`) — `ClusterReadinessProbe` seam (the edge contact,
  purified: a pure `ClusterProbeRequest` record à la `SystemdProbeRequest`, NOT `BootstrapConfig`);
  `ClusterReadinessPhase` MOVED from doctor-port (it squats there today — latent debt); the phase↔`SchemaRef`
  enum (the `cluster/kubeconfig/v1` … literals loose in `ClusterSpecialist` today — the SystemdUnitId move).
- **cluster-edge** (new, `osgi/cluster/cluster-edge`) — `@Component implements ClusterReadinessProbe`,
  kubectl via ProcessBuilder, the contact for the api-ready + controllers-effective phases. PURE
  ProcessBuilder + java.nio → **zero embedded jar** (simpler than dbus, which embedded 3 for ServiceLoader).
- **cluster fragment of doctor-core** — the Mediator `@Component` + `ClusterSpecialist`.
- **NO cluster-core** — the fragment carries the composition.
- **doctor-core** — `ClusterSpecialist` LEAVES for the fragment; roster goes dynamic. dbus/network
  specialists STAY hard-wired for now (cluster-first; their migration = a later increment, per-pattern
  uniformity like pulumi-edge was the first edge before the rest). `units/cluster` in manifests UNTOUCHED
  (different concern; shared `"cluster/"` prefix is a name collision, not shared vocabulary).
- **seed-master** — keeps only the WHEN: the `SeedNodeBootstrapWatcher` retry/timeout poll, preview/dry-run,
  Pulumi-resource wrapping, RunbookRenderer, the CLI tools. The composition/scenario leaves `controlplane/bdd/`.

## ★ REVISIONS (2026-06-23, later in the same brainstorm — supersede the above where they conflict)

1. **NO package split, ever.** "pulumi distributed across two worlds" / `osgi/pulumi/pulumi-edge`
   beside `host/pulumi/pulumi-edge` = a SAME package exported by two bundles = split-class. REJECTED.
   If a contact has a playable half and a non-playable half, they are TWO edges (two targets, two
   packages, two names), never one edge in two worlds. And today they are NOT even separable:
   `StackHandleSnapshotSource` reads through `StackHandle` which imports `com.pulumi.automation` — the
   disk-reader is SOLDERED to the Automation contact. So pulumi-edge is ONE non-playable host jar; no
   dénouage in the cluster chantier.
2. **How a non-playable host edge reaches the OSGi world — host-publish onto a type-seam.** Verified:
   `pulumi-edge` is a PLAIN host jar (no bnd, no BSN, not a bundle). Its impls implement
   `doctor-port` types (`SnapshotSource`/`MedicalRecordRegistry`/`InterventionLedgerWriter`) — and
   `doctor-port` IS `type=seam` (one shared copy JCL↔bundles). The seam carries the TYPE across
   classloaders; the edge IMPL never crosses. So the host publishes the INSTANCE behind the shared
   type: `bootedFramework.context().registerService(SnapshotSource.class, hostInstance, null)`. The
   `BootedFramework.context()` BundleContext is already exposed (nothing host-publishes yet — this is
   the new wiring). A consumer `@Reference SnapshotSource` binds it, typed. NO migration of pulumi-edge,
   NO pulumi-core (forbidden: Pulumi is not our domain), NO split.
3. **Edge ≠ seam; the seam is always the PORT.** An edge is never a seam — it IMPLEMENTS a seam (a
   consumer's `-port`). pulumi has no port of its own; its edge borrows `doctor-port`.
4. **The coupling signature that tells edge from mediator (capability is universal, USE is minimal).**
   All ports are seams → visible from any bundle. But seeing a port = coupling, so each role sees only
   what its job needs: domain-core sees the ports it CALLS; port sees nothing (passive); **edge sees ONE
   port (the one it implements) + contacts one external target**; **mediator sees SEVERAL ports (the
   ones it relates) + no external target**. Verified: pulumi-edge imports exactly ONE port (doctor-port).
   An edge seeing 2+ ports is a mediator in disguise.
5. **Patient is a Pulumi fact, not run-config.** `Patient` (org/project/stack) comes from
   `Deployment.getInstance()` — a Pulumi contact stray in `DoctorAssembly.currentPatient`. It is a
   4th inbound pulumi wire, host-published like the others (value at boot, or a lazy source). Gap-2
   (a separate Patient channel) DISSOLVES into the one host-publish mechanism.

**Scope consequence (the cluster chantier):** the doctor does NOT strictly need to be a full component
for cluster — but the user chose "jusqu'au bout / comble le trou du doctor": doctor-core BECOMES an SCR
host (a `@Component` publishing `DoctorConsultingService`, `@Reference List<Specialist>` dynamic), its
non-playable pulumi wires host-published via `registerService` onto their doctor-port type-seams, its
Specialists fanned out from the registry (cluster contributes its own as a fragment). pulumi-edge stays
a plain host jar, untouched. dbus/network specialists MAY stay hard-wired transitionally (cluster-first)
or move too — decide at build time.

## ★ PROOF DONE (2026-06-23) — the model holds, with 4 hard-won conditions

PROVEN green on Felix SCR 2.2.x / felix.framework 7.0.5 by `FragmentContributedComponentTest` in
`osgi/bench/bench-tests` (host = `bench-fragment-host`, fragment = `bench-fragment-contribution`).
A `@Component` that lives in a FRAGMENT reaches state **ACTIVE** (read from SCR's own
`ComponentConfigurationDTO`: state=ACTIVE, unsatisfiedReferences=[], failure=null) in the HOST's
context. The non-obvious conditions (took 7 iterations — do NOT relitigate, just apply):

1. The fragment carries the `@Component` → bnd generates `OSGI-INF/*.xml` + the `Service-Component`
   header in the FRAGMENT jar. (Confirmed in the jar.)
2. **The HOST must declare `Service-Component: OSGI-INF/*.xml`** (wildcard) in its OWN bnd. SCR reads
   the header off the host bundle (`getHeaders()` does NOT merge fragment headers), but the wildcard
   resolves against the host's ENTRIES via `findEntries`, which DOES span attached fragments. Without
   this line on the host, SCR never sees the fragment's descriptor.
3. **The HOST must declare `Require-Capability: osgi.extender;filter:="(osgi.extender=osgi.component)"`**
   by hand — a host with no `@Component` of its own makes bnd emit no extender requirement, so SCR
   does not track it. (`immediate=true` on the component was NOT necessary once these were right.)
4. Lifecycle: `installFixtureWithHost` → `resolve(host)` (attaches the fragment, OSGi Core §3.14) →
   `host.start()`. The fragment is never started.

5. **The host RECEIVES the contribution into a DS collection** — not just "the fragment component
   activates". The test added a host `@Component ContributionCollector` with
   `@Reference(cardinality=MULTIPLE, policy=DYNAMIC) List<ContributedService>` (via bind/unbind), and
   asserts via SCR's DTO that the collector's reference has a bound service. GREEN: DS binds the
   fragment-contributed service into the host's dynamic list. So the roster is the **declarative
   `@Reference List`** — NOT a `ServiceTracker`. (The ServiceTracker route — `new
   ServiceTracker<>(ctx, Itf.class, null); open(); getServices()` — is the imperative alternative,
   valid if fine-grained filtering/order is ever needed; for a plain "everything contributed" roster
   the DS List is more idiomatic, and the bench proves it works in the fragment→host boot topology.)
   This is exactly doctor-core's `@Reference List<Specialist>`.

Observation gotcha (cost 2 iterations): do NOT observe the published service via a test-loaded
`Class` when the host exports the service package AND it is also system-exported — two copies split
the type and `awaitService(Class)` never matches. Read the proof from SCR's DTO (`state==ACTIVE`), or
observe by service-name. For the doctor: the observable type lives in a `-port` type=seam (system
bundle SOLE exporter), so this split won't arise — the host must NOT also export it.

This is the green gate for: doctor-core becomes the host (declares the two headers above), the cluster
fragment carries `ClusterSpecialist` + the Mediator `@Component`, both activate in doctor-core's
context.

## (historical) the proof obligation — do this FIRST, before any code move

PROVE that Felix SCR (this boot topology) activates a fragment-contributed `@Component` and that its
`Specialist` shows up in the host's roster. Precedent = `DbusSystemdEdgeBootTest`. A small boot test that
asserts the fragment's Specialist is present validates the model MECHANICALLY before committing to the
move. If it fails, the whole model is wrong — do not assume.

## Surface inventory (verified in the reactor 2026-06-23, this brainstorm)

- kubectl contact = `ClusterBootstrapReadinessVerifier` (`exec/seed-master/.../controlplane/readiness/`);
  only API_READY + CONTROLLERS_EFFECTIVE actually shell kubectl. KUBECONFIG_PUBLISHED = file poll (java.nio)
  + the systemd phase-0 gate — both stay host (orchestration, not contact).
- `ClusterReadinessProbe` interface + `ProductionClusterReadinessProbe` + `SimulatedClusterReadinessProbe`
  all in `controlplane/bdd/`. The prod impl FOLDS host concerns (systemd gate, policy→controllerRefs,
  VerificationResult projection) that CANNOT move into the edge — only the kubectl contact does.
- Specialist recruit seam already exists: `Specialist` in doctor-port ("AI-ready seam"),
  `Doctor.consultingService(... List<Specialist> hostSpecialists ...)`, but `Doctor` hard-wires
  `DbusTcpSpecialist` + `NetworkSpecialist` + `ClusterSpecialist` via `new`. That `new` → registry/fragment
  discovery IS the new work.
- Lateral domain→port dep precedent CONFIRMED: `doctor-core/DbusTcpSpecialist` imports `SystemdUnitId`
  from systemd-port; doctor-core + doctor-port both declare systemd-port. So cluster→systemd-port is no
  new Rubicon.

See [[external-edges-chantier-handoff]] [[dbus-systemd-edge-spec-state]] [[prefer-osgi-edge-three-reasons]]
[[doctor-internal-edge-debt]] [[osgi-testkit-framework-injection-idea]].
