---
name: classrealm-adaptable-pattern
description: "The ClassRealm pattern (socle, being built 2026-07-06): a classloader-bounded world materialized as a type that offers capability faces via Optional<T> adapt(Class<T>). Unifies two pre-existing, unnamed mechanisms — HostClassRealm (flat JCL, ex-HostClassLoaderView) + a coming BundleClassRealm (delegates to Bundle.adapt) — under one contract. Named ClassRealm in the Plexus ClassWorlds sense (classloader-isolated space), NOT the security sense. Emerged mid ClusterSeed migration; ClusterSeed suspended to build it because it's socle."
metadata:
  type: project
---

**What & why.** A recurring need surfaced (the user felt it 3×): "get face T from a given world". It was
real but anonymous+scattered — `HostClassLoaderView` (host), `OsgiConnection`/`Bundle.adapt` (OSGi),
`ConsultingService.adapt` (domain). Materialize the shared contract so a reader lands on ONE door.

**The contract (promoted, not invented).** `ConsultingService.adapt` already had the exact shape —
`default <T> Optional<T> adapt(Class<T> type) { return type.isInstance(this) ? Optional.of(type.cast(this)) : Optional.empty(); }`.
Promoted to `interface ClassRealm` in `boot-discovery` (pure module: osgi.core + JDK, visible host AND
OSGi, `scenario-engine` already depends on it — ideal foyer).

**Naming (settled after real investigation).** `Realm` alone = false-positive security connotation
(Tomcat/JAAS users+roles). But the term traces to **Plexus ClassWorlds** (`ClassRealm` = an isolated
classloader; `plexus-classworlds` IS in Maven's own boot) — the sense the specs already use ("a class
references only types reachable in its own classloader realm", `DuplicateRealmClass`, `REALM_BOUNDARY`
gate). So `ClassRealm` is idiomatic + anchors the classloader meaning; the `Class` prefix kills the
security reading. No collision (Plexus `ClassRealm` never on our compile classpath). Renamed
`HostClassLoaderView` → `HostClassRealm implements ClassRealm`.

**Semantics B (settled).** A world adapts what it HAS, `Optional.empty()` otherwise. Asymmetry is REAL,
not a defect: host realm offers few faces, OSGi realm many. `empty` is the correct answer, not a gap.

**Migration line — INVENT NOTHING.** Migrate to `ClassRealm.adapt` only what is ALREADY a `.adapt(Class)`
OSGi call: `JUnitLauncherCore.wiringOf` (the `instanceof BundleReference` selector → becomes the entry
point) + `bundle.adapt(...)`. Do NOT twist `HostClassRealm.resolves()/stagedBundles()` into
`adapt(Capability.class)` — those are host-world QUESTIONS, not faces. They stay named methods.

**Per-bundle is the load-bearing truth (user, 2026-07-06) — settled the whole design.** In OSGi each
bundle OWNS its classloader → each bundle IS a class realm (Plexus, materially). So `BundleClassRealm.of(
bundle)` is a PER-BUNDLE entity (parameterized by the bundle), NOT a "the OSGi world" monolith — a correct
model, not a commodity. This is what legitimized building it: I nearly reverted it as YAGNI ("wiringOf is
test-only, no payer"), but that was wrong twice — (1) `ClassRealm.of(loader)` is the scenario-engine's
MEMBRANE primitive (the 3rd of JUnitLauncherCore's "three OSGi crossings": the host bundle's `BundleWiring`
in-container), and in-container = `InContainerJUnitRunner` = how rke2lab tests itself = dogfooded product,
not throwaway; (2) the abstraction models a real OSGi truth instead of inventing one.

**TWO OSGi mechanisms, distinct by NATURE — do NOT fuse under "realm" (settled II).** (A) `adapt(Class)` =
classloader-BOUNDED (Plexus): `getBundle(0).adapt(FrameworkStartLevel)` = the system-bundle realm,
`bundle.adapt(BundleWiring/...)` = an app-bundle realm — literally two realms. (B) the service registry
(`awaitService(HealthSystem/...)`, what the RUNTIME really uses via `BootedFramework.awaitService`) crosses
ALL bundles → NOT classloader-bounded → NOT a class realm. Kept separate: B is a future `ServiceBroker`,
distinct type. Note: `OsgiConnection.awaitService` does NOT exist (whiteboard was wrong) — `awaitService`
lives on `BootedFramework` + the testkit extension; `OsgiConnection` has only context()/ownsLifecycle()/
close(). So the earlier "OsgiConnection IS the realm" idea was dropped.

**Migration line = migrate NOTHING by force (settled step 5).** The self-cast group (`bundle.adapt(
FrameworkStartLevel/BundleWiring)` where the caller ALREADY holds its bundle and the face is ALWAYS present,
no null-check today) STAYS native — wrapping it adds an Optional/orElseThrow for a never-absent value, the
exact "don't twist, invent nothing" the pattern forbids. `ClassRealm.of` pays only where you start from a
RAW loader and must DECIDE the world (wiringOf). Door left tooled, not opened: resolve a realm from ANY type
via `FrameworkUtil.getBundle(clazz)` ("which world does THIS type live in?") — `of(loader)` already takes
any loader.

**Progress (2026-07-06) — SOCLE COMPLETE, committed.** `00c2441` (interface + HostClassRealm rename) then
`8ccc8ed` (BundleClassRealm + `ClassRealm.of(loader)` + wiringOf collapsed to `of(loader).adapt(BundleWiring)`
+ 2 tests). 6/6 green: ClassRealmTest (self-cast), BundleClassRealmTest (delegate + null→empty),
ClassRealmOfLoaderTest (the membrane selector: flat→Host, BundleReference→Bundle). Chantier CLOSED. NEXT
(back to ClusterSeed): rework RunMode ([[runmode-livegate-pulumi-abstraction]]). See
[[engine-lifecycle-socle-state]] [[cluster-seed-execution-state]] [[collaborative-design-method]]
[[single-source-of-truth-before-logic]].
