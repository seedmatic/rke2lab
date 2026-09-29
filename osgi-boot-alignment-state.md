---
name: osgi-boot-alignment-state
description: "SHIPPED to design/pre-integration (two squash merges, 2026-06-23). The osgi-boot-alignment chantier, both increments integrated. Increment 1: single-sourced the embedded-OSGi boot — ONE model module osgi/boot/boot-discovery, bundles located by what they DECLARE (BSN third-party / embed-capability LDAP ours; filename heuristic GONE), type=seam guard, BSN→capability migration. Increment 2 (the BOOT DECOMPOSITION): pure BootPlanner→BootPlan vs effectful FrameworkLauncher→BootedFramework, BootPipeline grammar, SeedRuntime+OsgiRuntime DELETED, shared boot-logging, pax/JCL `optional` scope, unified BundleIndex.closeOverImports. Durable model = the spec docs/architecture/osgi/osgi-boot-decomposition-spec.adoc. jul→slf4j DONE. Remaining OPEN: only the ★ deferred proof obligation (seam Class identity across JCL/BCL) — see [[boot-decomposition-state]]. The exec-jar DS-API gate was done by [[osgi-staging-extension-chantier]]."
metadata:
  node_type: memory
  type: project
---

## What this chantier is

The suivi left by ssh-to-age-edge, merged into one ordered session because the common thread is bundle
DISCOVERY: locate by what the artifact DECLARES, never by a name a caller keeps in sync. It aligns the
test boot topology (`FelixFrameworkExtension`) onto prod (`OsgiRuntime`) by making both consume ONE
shared boot model. Worktree `refactor/osgi-boot-alignment`, base design/pre-integration @ `4d99f599`.

## DONE — 4 commits, all reactor-GREEN (per-step, will squash at integration)

1. `81201d2a` — single-sourced the boot-install table IN OsgiRuntime (unify the two capability
   readers' filter; enum BootStackJar; fixed a latent pax-config defect: the StaticLogbackContext +
   org.slf4j-drop were gated on the classpath-only list, so the EMBEDDED boot silently skipped them).
2. `540e3083` — NEW module `osgi/boot/boot-discovery` (under a new `osgi/boot/` aggregator) — the boot
   MODEL, JDK-only deps (no felix/pax). Why a module not a dep on osgi/runtime: the testkit only needs
   to FIND bundles, not BOOT them; depending on runtime (the executor) dragged pax-logging-api, a 2nd
   org.slf4j provider, which broke jGiven in-container resolution.
3. (folded into the testkit commit) — OsgiRuntime + FelixFrameworkExtension wired onto boot-discovery;
   `@FrameworkLog(Level)` annotation → felix.log.level (debug a boot from the test class, not a builder
   verb — rides on tests using the shared JGivenTestkit.felix() factory); resolve() no longer blind —
   on false it logs each unresolved bundle's unsatisfied requirements (slf4j, not System.err).
4. `0ffc8190` — THE BIG ONE: locate by declared identity, kill the filename heuristic. See below.

### The model (osgi/boot/boot-discovery) after commit 4
- `BundleIndex` — ONE index over two SOURCES: `ofClasspath()` (singleton) / `ofStagedBundles(loader)`.
  Scans once, indexes each bundle by its `Bundle-SymbolicName` AND its parsed embed capability.
  Replaced the two near-twin `ClasspathBundleIndex`+`EmbeddedBundleIndex` (DELETED).
- `BundleLocation` (sealed: `OnClasspath(Path)` | `Staged(loader,resourceName)`) — the ONLY thing that
  differs between sources: where the bytes are + how to read/install. (User's Path/File/Stream
  inference, made a type. NOT renamed BundlePath: Staged has no Path.)
- `EmbedCapability` — parses `Provide-Capability` into attributes; `matches(Filter)` via
  `FrameworkUtil.createFilter` (works pre-boot; osgi.core spec jar added, no felix).
- `BootStackJar` enum — third-party boot stack (pax/scr/resolver), located by `symbolicName()` (pax BSN
  ≠ artifactId). Dropped `stagedFileName` — the staged name is OURS to choose (pom destFileName) so not
  a reliable key; staged jars are found by BSN too.
- TWO honest keys: `locateBySymbolicName(bsn)` for jars we can't mark (felix/pax/junit);
  `matching(ldapFilter)` for OURS, which self-declare `type`/`suite`/`role`.

### Executors + call sites after commit 4
- OsgiRuntime + FelixFrameworkExtension: builders take `BundleLocation`; ONE source-agnostic install
  (sealed switch: Staged streams bytes, OnClasspath installs by URL). `installFromClasspath(bsn…)` +
  `installBundles(bsn…)` take BSNs; `installMatching(ldapFilter)` replaced installEmbeddableBundles().
- Bench scr fixtures self-declare `type=fixture; suite=scr; role=provider|consumer` in their bnd; the
  two scr tests select by LDAP — the anti-cheat installs `role=consumer` ALONE ("no provider" is IN the
  filter, not an omitted name). All other call sites (doctor port/core, jgiven, manifests-core spike,
  HostSeam) pass BSNs / `(type=*)`.
- junit-testkit bnd: `org.slf4j`, `boot.discovery`, framework-launch imports are `resolution:=optional`
  (JVM-side only, absent in-container).

Build proof (commit 4): `-pl :seed-master,:manifests-cli,:netplan-cli,:bench-tests,:doctor-core-test,
:doctor-port-test,:jgiven-testkit,:manifests-core -am clean package -Dmaven.build.cache.skipCache=true
-DskipTests=false` → doctor-port 34, doctor-core 29, jGiven guard 2, the 4 bench spikes, HostSeam 2,
the 3 EmbeddedBundlesBootTest — 0 fail.

## DONE — commit 5 (squashes away; the DURABLE reference is the doc + this handoff, NOT the SHA)

5. `c96fe6c5` (will be squashed) — **type=seam discriminator + centripetal seam guard.** THE
   reference for the boot-face pattern: when in doubt how a -port vs -core/edge loads, read
   `docs/architecture/patterns/port-edge-domain-ownership.adoc` § "The boot face" — it is versioned,
   survives the squash, and is the exemplar to reproduce. What it did:
   - Inverted `OsgiRuntime.deriveSystemExports` from centrifugal (mirror every import → system) to a
     GUARD: a `type=model`/`edge` package reaching `system.packages.extra` is a leak (2nd exporter
     splits the class vs the bundle's own copy) → fail-fast naming the package + owning bundle.
     GREEN by construction on prod (manifests-core imports only -port/seam + flat libs, never a
     domain pkg) — the guard makes a future leak impossible, not a fix for a present break.
   - The discriminator is DECLARED, not inferred: the 4 `-port` bnd files carry
     `Provide-Capability: io.seedmatic.rke2lab.embed; type=seam` (the membrane the flat host shares
     TYPED via system-export — JCL side of the seam). model/edge load on the BundleCL side, never
     system-exported. "which side of the seam frontier does the port load on" + "one package = one
     exporter = one class" are the load-bearing phrases (now in the doc).
   - `EmbedCapability`: `TYPE_*` vocab, `isDomain()/isSeam()`, `INSTALL_FILTER =
     (|(type=model)(type=edge))` — single-sources the prod discovery filter, KILLS the `(type=*)`
     literal (which now also matched seam). `BundleIndex`: scans `Export-Package` per Entry, exposes
     `domainExporterOf(pkg)` over the whole classpath (an uninstalled owner is still attributed).
   - `OsgiRuntime.discovery` is now a constructor-chosen COLLABORATOR FIELD (staged index embedded /
     classpath index reactor) — reading the composition shows the runtime does discovery; boot() +
     guard send it messages, no statics. Static `STAGED` remains only for `hasEmbeddedBundles()`
     (pre-construction exec-jar probe). User's rule sharpened: "en regardant la composition de
     l'objet je vois qu'il fait de la discovery" — composition reveals roles.
   - Build: HostSeamEmbeddedFelixTest 2 + EmbeddedBundlesBootTest 1, 0 fail.

## DONE — commits 6-9 (BSN→capability migration, FULLY COMPLETE). All squash away; doc is the ref.

What looked like a literal-dedup turned out to need the WHOLE chantier thesis carried to its end —
and that was correctly IN SCOPE ("locate by what the bundle declares, never a name recopied"). Going
all the way was what made it safe; stopping half-way would have left a live BSN literal teaching the
pattern. The 4 commits:
  - `50ad8713` — testkit gains INSTANCE `installMatching(filter)->List<Bundle>` (install-only, no
    start). bench-host/config marked `type=fixture; suite=extender; role=host|config`, bench-schema
    `suite=metatype`. ExtenderContract + Metatype tests migrated off BSN literals (select by role;
    anti-cheat installs role=config ALONE).
  - `c9348378` — the 3 `-test` FRAGMENTS marked (`jgiven-probe-test` suite=jgiven role=probe;
    `doctor-port-test`/`doctor-core-test` suite=doctor role=port|core). doctor-core/doctor-port
    themselves UNMARKED (prod libs, not fixtures). Testkit `installFixtureWithHost(filter) ->
    FixtureWithHost(host,fragment)`: selects the fragment by capability, reads its `Fragment-Host`
    to install the host — NEITHER named by a literal (one-source-of-truth: the host comes from the
    fragment's own manifest). Skips start on fragments. `BundleManifest.fragmentHost` re-added (now
    consumed). 3 in-container tests migrated (DoctorCore 29, DoctorPort 34, JGiven guard 2).
  - `d27169a5` — DELETED the unretained `NodeEnvContributorRegistryScrSpikeTest`. TRAP AVOIDED: it
    was a spike we had NOT retained, and we nearly "migrated" it to capability — i.e. re-implemented
    in clean form the very BSN-literal/centrifugal-export anti-pattern the seam guard exists to
    forbid, only to revert. The reflex "do we even keep this?" before migrating saved building our
    own counter-example. Deleted with its spike-only deps (junit-testkit, felix.scr, DS-runtime API
    trio); felix.resolver+osgi.core STAY (ManifestsVisitOrder/RegistryResolve use them). Fixed
    JGivenTestkit javadoc (its canonical example taught the retired pattern).
  - `69261acc` + `98a203b6` — bench: 2 guards promoted `@OsgiSpike`->`@Osgi` (permanent, run by
    default) and renamed off `*SpikeTest` (ExtenderContractTest, ScrUnsatisfiedReferenceTest); 2
    demos (Metatype, ScrActivation) DELETED with their orphaned fixture modules (bench-schema,
    bench-scr-provider); bench-scr-api STAYS.

FRONTIER SETTLED: zero our-bundle BSN literal at any test install/select site. The only `install*`
BSN literal left is `WRAP_BSN` (jgiven-wrap) — boot infrastructure like the third-party stack, via a
single constant. `installBundles(String...)` is now boot-infra/third-party only; OUR bundles select
by capability (model/edge/seam/fixture) or via a fragment's declared Fragment-Host.

FULL REACTOR GREEN with tests (clean package, skipCache, skipTests=false): BUILD SUCCESS, every
module, 0 failures across the whole tree.

## REMAINING (do after the user compacts the conversation)

1. **String-literal duplicates** — DONE for `(type=*)` (now `EmbedCapability.INSTALL_FILTER`). The
   embed namespace `io.seedmatic.rke2lab.embed` is still a literal across bnd files — irreducible
   pom-face (bnd can't reference Java), single Java source already `BundleManifest
   .EMBED_CAPABILITY_NAMESPACE`. Nothing left here — BSN-literal migration fully done above.
2. **SCR_API_PACKAGES is NOT a BOM-dedup — it is a SYMPTOM of the #4 defect. DO NOT patch it by
   hand.** We investigated it this session and it reframed the whole thing:
   - `SCR_API_PACKAGES` (the `org.osgi.service.component;version=1.5` + util.promise/function block,
     byte-identical in OsgiRuntime + FelixFrameworkExtension) system-exports the DS-runtime API.
   - felix.scr IMPORTS those packages MANDATORY `[1.5,2)` / `[1.1,..)` (verified in its manifest);
     it does NOT embed them. They ARE proper bundles (each has a Bundle-SymbolicName +
     versioned Export-Package: org.osgi.service.component 1.5.1, util.promise 1.3.0, util.function
     1.2.0).
   - BUT in the prod uber-jar they are NEITHER flattened (0 `org/osgi/service/component/*.class`)
     NOR staged under META-INF/bundles. They fall through the CRACK. So the prod-embedded boot's
     `system.packages.extra` would export packages whose classes aren't on the flat classpath — the
     `hostResolves()` guard should throw; it works in TEST only (spec jars on the test classpath).
   - SEAM VERDICT (user's "seam ou pas seam"): NOT a seam. The flat host (OsgiRuntime) reads
     `ServiceComponentRuntime` BY NAME (`awaitServiceByName(String)`), never typed — it does not
     share these classes. They are framework-internal API consumed by felix.scr ALONE. So the right
     fix is to STAGE the 3 spec jars intact (like felix.scr/pax) and let felix.scr resolve them
     bundle-to-bundle by their declared Export-Package version — and `SCR_API_PACKAGES` DISAPPEARS
     entirely (it was the symptom of a mis-diagnosis: a human system-exported it instead of staging).
   - This is the FOUNDING TEST CASE of #4: a human kept two hand-lists (shade-exclude + staging) in
     sync and missed a bundle's transitive import. The extension that scans the classpath would have
     closed the crack by construction. Do NOT hand-fix — let #4 fix it, and use "DS-API trio now
     staged + SCR_API_PACKAGES gone" as #4's validation gate (the "retombe sur nos pieds" test).
3. **Logs visibility (volet 3)** — jul→slf4j bridge (SLF4JBridgeHandler + LevelChangePropagator) as a
   Jupiter extension in junit-testkit. KEEP GrpcChannelNoiseCapture (opposite intent: it SUPPRESSES
   host grpc noise; the bridge ROUTES jul). User noted a `logging.properties` on the classpath is the
   lighter alt for mere visibility; the bridge is for UNIFYING into logback. @FrameworkLog already
   ships the felix-internal half. NOTE: felix.log.level revealed the resolve() failures during this
   session — that half is done.
4. **#4 shade-exclude ↔ staging duplication — SPUN OUT to its own chantier/worktree.** Fully scoped
   in [[osgi-staging-extension-chantier]] (the design brief): a build-time Maven core extension that
   single-sources the two pom faces via a CLOSURE over bundle imports (NOT "every BSN jar"); DS-API
   crack as founding test case + gate; seam-vs-stage by reading bnd headers (no reflection). NOTE a
   mis-step was CORRECTED there: the calc does NOT need `Import-Package(host)` (seed-master is not a
   bundle, has none) — `deriveSystemExports` already works from the BUNDLES' imports, host-independent.
   This worktree is PARKED after this; the new chantier is a fresh session.

## Working rules (this chantier)
- Commit per GREEN step with a detailed body; squash-merge into design/pre-integration at integration,
  NEVER main. Never `mvn install` — verify by reactor `-am … -Dmaven.build.cache.skipCache=true
  -DskipTests=false` + surefire counts.
- No static helpers where an instance carries the DAG (user is firm on this — drove BundleIndex being
  an instance, not statics). No @Deprecated/shim — delete the old path same change.
- Bundle identity = Bundle-SymbolicName (ours + third-party) or embed capability (ours only), NEVER the
  Maven file name. This is the chantier's core lesson — every filename heuristic broke a real case.

See [[external-edges-chantier-handoff]] (parent chantier) [[boot-pipeline-unification-backlog]]
[[osgi-logs-flow-to-host]] [[prefer-non-static-inner-keep-the-graph]] [[single-source-of-truth-before-logic]].
