---
name: domain-ports-deseamed-state
description: "The de-seam sweep (2026-07-11, COMMITTED 58cc01b4b): all 6 domain -port modules (auth/cluster/systemd/incus/netplan/manifests) fused into -contract (type=seam→type=contract, system-export dropped, installed bundle-to-bundle). Doctor was already done (doctor-contract, the template). cluster/systemd -bdd retrofitted onto the bbox scion; cluster-edge + dbus-systemd-edge boot tests retrofitted to in-container passengers. Testkit gained slf4j-default-export + SCR-default-on (withoutScr opt-out). OSGi side FULLY GREEN. Only TWO seams remain in osgi/: pipeline (shared engine, next to de-seam via BDD-scenarios migration) and seed-broker-port (the one true host↔OSGi membrane, stays a seam). host/exec Java intentionally broken (deferred to the scenario-rewrite-from-specs chantier)."
metadata:
  type: project
---

**What shipped (uncommitted working tree, 2026-07-11, OSGi side green).** Replicated the doctor
`-contract` template across every domain `-port`: module `X-port`→`X-contract`, package root
`io.seedmatic.rke2lab.X.port`→`.contract`, bnd `type=seam`→`type=contract` (⇒ installed bundle, boot's
`deriveSystemExports` no longer system-exports it — auto-derived, no allow-list), dependents recabled
(poms + imports).

- **auth** — auth-contract (`AuthTokenContact`/`AuthTokenSource`); rewired auth-edge. Template-setter.
- **cluster** — cluster-contract; rewired cluster-core/-edge; **cluster-bdd retrofitted onto the bbox
  in-container scion** (new `cluster-bdd-test` fragment + `@OsgiWorld` proxy `ClusterBddInContainerTest`,
  old out-of-container `ClusterReadinessScenarioInContainerTest` DELETED).
- **systemd** — systemd-contract; rewired systemd-core/dbus-edge + doctor-core + manifests-core(-test);
  **systemd-bdd retrofitted onto the scion** (systemd-bdd-test fragment + proxy).
- **incus** — incus-contract; rewired incus-edge (no scion, no systemPackages).
- **netplan** — netplan-contract, the SPECIAL case: BOTH packages `netplan.port` + `netplan.api` FUSED
  into one `netplan.contract` package (user's call). rewired netplan-core/manifests/bbox.
- **manifests** — manifests-contract, THREE packages KEPT distinct (user's call, NOT merged):
  `manifests.contract` + `.contract.node` + `.contract.profiles`. rewired manifests-core(-test),
  ssh-to-age-edge, manifests-cli.

**The two seams that REMAIN in osgi/ (verified `grep type=seam osgi/`):**
- `pipeline` (`io.seedmatic.rke2lab.pipeline`, foundation/pipeline/pipeline-port) — NOT a domain port: it
  is the shared fluent `Topic`/grammar ENGINE, consumed on BOTH sides (host seed pipeline AND two OSGi
  bundles: `framework-launcher`'s `FrameworkLaunchPipeline` — the boot IS a `Topic.Execution` — and
  `manifests-core`'s whole systemd-synthesis stage tree). PROVEN by experiment: removing all 4
  `pipeline-port` maven deps broke exactly those two OSGi bundles → reverted. It de-seams only when the
  fluent grammar dissolves in the BDD-scenarios migration (all pipelines onto the jGiven-launcher engine).
- `seed-broker-port` — the ONE true host↔OSGi membrane (RunGate + SeedEnvelope). Stays a seam BY DESIGN
  (the host keeps it; everything crosses through the broker).

**Host/exec: poms trimmed to seed-broker ONLY (user: "on garde juste le seed port", "host parle QUE
seed").** Removed from `exec/seed-master` (all 5 domain ports + doctor-contract/-core + bbox-core +
auth-edge + doctor-*-test + pipeline-port), `exec/netplan-cli` (netplan), `exec/manifests-cli`
(manifests still referenced — its boot test breaks), `host/pulumi/pulumi-edge`(-testkit) (doctor-contract).
Kept: seed-broker-port/-codec/-runtime + domain-annotations. **The host Java that still names domain
types does NOT compile — INTENTIONAL, deferred** to the scenario-rewrite-from-specs chantier (user:
"on s'aligne sur la vision scenarios des specs juste apres dans les modules OSGi").

**The retrofit pattern (cluster/systemd scions), from [[mock-service-substitution-pattern]]:** an
out-of-container `-bdd` test that system-exported the port to cross a host-registered mock DIES when the
port de-seams. Replace with the bbox 3-piece in-container scion: `{domain}-bdd-test` FRAGMENT (passenger
registers mocks via `FrameworkUtil.getBundle(...).registerService` — same loader, no seam) + `@OsgiWorld`
proxy whose `.systemPackages(...)` lists ONLY `seed.broker.port` (everything else pulled by
`installImportClosureOf`, wired bundle-to-bundle in-container). User's key insight: "in-container, les
wirings résolvent" — no domain systemPackages needed.

**GOTCHAS (each cost a build):**
- The closure walk `installImportClosureOf` SKIPS seams but PULLS type=contract bundles. When ONLY a
  `-test` fragment (not its host) imports a de-seamed package, you must add the FRAGMENT to the closure
  seed: `installImportClosureOf(host, fixture.fragment())` — else the host resolves without the fragment
  attaching (silent: `resolve()` true, then `ClassNotFoundException` on the runner). Hit in
  `DoctorCoreInContainerTest` (its fragment imports systemd.contract).
- A closure-based `-test` module must DECLARE the de-seamed bundle as a `provided` maven dep so the jar
  is on the proxy classpath for `installImportClosureOf` to find it. Added `systemd-contract` to
  `doctor-core-test` pom.
- Cross-domain consumer proxies that hand-listed a now-de-seamed package in `.systemPackages` must DROP
  it (else double-export = split): fixed doctor-core-test, doctor-contract-test, manifests-core-test
  (systemd), bbox-bdd-test (netplan+doctor stale refs), cluster-bdd's own test.
- The `nxmatic` profile writes to `target~nxmatic`; the build cache replays STALE variants. ALWAYS
  `find . -name 'target~nxmatic' -type d -prune -exec rm -rf {} +` before a `-Pall-worlds,nxmatic` run,
  or you get phantom failures against deleted files (a deleted test recompiled against the old package).
- The dbus-systemd-edge boot test + manifests-cli/netplan-cli boot tests are the CLASSIC boot-seam shape
  (host `awaitService` observes the service TYPED, installs only the edge/core, no closure). They rely on
  the system-export as sole provider. dbus-edge still passes (installs just the edge); the CLI boot tests
  break (host reads manifests/netplan typed) — deferred with the rest of exec/host.

**Edge boot tests retrofitted (user: "si on en laisse, on risque de faire pareil en life").** The
principle: a test that system-exports a de-seamed contract to observe a service TYPED out-of-container
LEGITIMIZES the host doing the same in the live boot. So `ClusterEdgeBootTest` + `DbusSystemdEdgeBootTest`
became in-container passengers ({edge}-test fragment: `{Edge}Tests` runner + `{...}InContainerTest`
passenger resolving via `FrameworkUtil.getBundle` + `@OsgiWorld` proxy). The dbus ServiceLoader-transport
proof (the `no dbus-java-transport found` guard) SURVIVES — calling `probe()` in-container runs the
ServiceLoader inside the edge's own Bundle-ClassPath. Two @Test kept each (SCR-publishes + behavioural).

**Testkit defaults (user: "installer scr et exporter slf4j par defaut", "tout nos tests s'attendent a
scr et slf4j").** `OutOfContainerFrameworkExtension`: (1) system-exports `org.slf4j;version=2.0.0` by
default (skipped if the test already declares its own slf4j export — JGivenTestkit keeps 2.0.17, no
split); (2) `startScr` defaults TRUE + new `withoutScr()` opt-out. `JGivenTestkit.felix()` keeps
SCR-default (a scion's Felix matches live posture; felix.scr is on every -test module via
bundle-test-parent) — only `JGivenTestkitGuardTest` (module pipeline-testkit, no felix.scr) opts out.
So a new edge/scion proxy declares only `.withJUnitRunner()` — SCR + slf4j are ambient.

**NEXT:** align the OSGi PRODUCTION scenarios ({Domain}Scenario classes, NOT test files) on the specs
(the user thought this was already done). THEN the host/exec Java rewrite onto the broker. See
[[mock-service-substitution-pattern]] [[seed-broker-host-adaptation]] [[world-gateway-frontier-discipline]].
