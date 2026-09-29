---
name: osgi-staging-extension-chantier
description: "INTEGRATED (squash 50150e56) into design/pre-integration — the build-time Maven extension single-sources the shade-exclude <-> META-INF/bundles staging from what bundles DECLARE; the DS-API gate is closed (SCR_API_PACKAGES deleted, trio installed+wired) and the real `pulumi preview` boots green. Two-phase build (install RELEASE tooling maven-embed-staging-ext to ~/.m2, then reactor) re-verified green on the integrated tip. Below: what shipped, the design, and the 3 handoff follow-ups still OPEN (.internal package pattern, two-executor unification, exec/->embed-exec rename)."
metadata:
  node_type: memory
  type: project
---

## STATUS: INTEGRATED (squash 50150e56) into design/pre-integration

The chantier delivered, end to end, on branch `feature/osgi-staging-extension`, and was
squash-merged into design/pre-integration (50150e56) — two-phase build (install the RELEASE
tooling, then reactor clean package skipCache skipTests=false) re-verified green on the
integrated tip. The full reactor is green WITH tests, and the user's real `pulumi preview
--stack dev-preview-staging` boots seed-master on the wired trio (the deployed uber-jar, not
just reactor tests). The "hand-list in disguise" the osgi-boot-alignment chantier left behind
is closed. The 3 handoff follow-ups below remain OPEN.

### What shipped (commits on this branch)
- `d3686f0d` — **BootStackJar moved into bnd-read.** It is the closure's seed BY NATURE
  (nothing of ours imports felix.scr — DS wires by reflection — so no Import-Package closure
  reaches it; it must be named). Now read once by both the build-time extension and the
  runtime; boot-discovery stays its consumer.
- `f9889bbb` + `2673ff94` — **the extension (`maven-embed-staging-ext/staging-extension`)**.
  A `@Named @Singleton @Priority(20) MojosExecutionStrategy` (the ONLY hook that can
  reconfigure a plugin — listener fires too late, model copy is frozen; both proven). It
  resolves the project deps itself (`ProjectDependenciesResolver` — `getArtifacts()` is empty
  until shade runs), computes `StagingClosure`, and mutates BOTH the shade `<excludes>` and the
  dependency-plugin `<artifactItems>` Xpp3Dom before delegating the run (decorates build-cache,
  does not replace it). `ResolvedBundle` reads each jar through the bnd lens.
- `ce922671` — **DS-API gate closed.** `SCR_API_PACKAGES` deleted from OsgiRuntime +
  FelixFrameworkExtension. `DiscoveryPolicy` (all/allExcept/only/onlyMatching) is the ONE
  selection API both executors share. `OsgiRuntime.closeOverImports` + the testkit felix.scr
  fixpoint install the trio as passive bundles (runtime mirror of StagingClosure). The
  scr-ACTIVE assertion replaces the old ServiceComponentRuntime service probe (that package is
  felix.scr-internal, no longer host-visible). Launcher excluded from the index by its
  FrameworkFactory service file (BundleLocation.isFrameworkLauncher).
- `ef692b3f` — **the 3 exec poms emptied** (`<excludes/>` + `<artifactItems/>`): the extension
  derives 9 (seed-master) / 7 (netplan-cli) / 8 (manifests-cli) bundles, both faces in lock-step.

### Closure model — settled the hard way (see git for the dead ends)
Stage ALL exporters of a wired package (multiple installed exporters is LEGAL in OSGi — the
resolver picks the wire). The discriminator is a CLOSURE bounded by what the host serves flat:
seed = our model/edge (embed capability) + boot-stack (BootStackJar); close over MANDATORY
Import-Package; SKIP packages already in `system.packages.extra` (= imported by a model/edge,
host-flat — keeps jackson out) or already exported by a staged bundle (pax re-exports slf4j).
A split DETECTOR (staged-AND-flat) was built then REMOVED: it cannot tell a legitimate shared
provider (org.slf4j, deliberately both sides) from a leaked aggregate — that is the developer's
intent, which a derivation cannot read. The extension DERIVES; the developer keeps the
classpath clean.

### osgi.cmpn — a real find, cut at the source
pax-logging drags `osgi.cmpn` (the OSGi compendium aggregate: ONE bundle, 137 exported packages
incl. the DS-API trio) transitively, because pax declares it in compile scope for a couple of
LogService interfaces. Left flat it re-exported the staged trio's packages off the system bundle
— a split. EXCLUDED on both pax entries in `bom/pom.xml`, with a build-parent maven-enforcer
`<bannedDependencies>` mirroring the cut so a bump cannot silently reintroduce it.

## HANDOFF — 3 follow-ups (decided here, deferred)
1. **`.internal` package pattern (user's call).** A framework-internal package (the DS-API
   runtime) should be recognisable BY ITS NAME (`*.internal`) to distinguish "internal, never
   system-exported" from a seam, without a special case. Today the distinction is implicit
   (only a model/edge import lands in system.packages.extra). "On va avoir du travail." Note it
   as its own chantier.
2. **Two-executor unification** — `OsgiRuntime.closeOverImports` and
   `FelixFrameworkExtension.startScr` now DUPLICATE the runtime closure (the user's own warning:
   "le maintainer en fait évoluer une et pas l'autre"). Fold them onto one engine — joins
   [[boot-pipeline-unification-backlog]]. The `DiscoveryPolicy` API is already shared; the
   closure is the remaining duplication.
3. **Rename `exec/` → `embed-exec/`** (see § DEFERRED below — unchanged, still owed).

---
(Original design brief, kept for the record:)

## Why this chantier exists

Two pom faces describe the SAME fact — "which jars are real OSGi bundles that boot inside Felix vs
flat libraries the host uses directly" — and they are maintained BY HAND, so they drift:

1. `maven-shade-plugin` `<artifactSet><excludes>` — jars kept OUT of the flat uber-jar.
2. `maven-dependency-plugin` `stage-embedded-bundles` `<artifactItem>` — jars copied INTACT under
   `META-INF/bundles/` for `OsgiRuntime` to install.

A jar that should be a bundle must appear in BOTH lists; miss one and it breaks. This is the last
"hand-list in disguise" the osgi-boot-alignment chantier did not close (it closed the Java side —
capability scan, no bundle-name literals — but the pom side remained, explicitly the irreducible
remainder). Both exec-jars (seed-master + the 2 CLIs) carry the duplication.

## The defect that proves it (FOUNDING TEST CASE + validation gate)

The **DS-API crack**, found 2026-06-22:
- felix.scr IMPORTS `org.osgi.service.component;version=[1.5,2)`, `org.osgi.util.promise;[1.1,..)`,
  `org.osgi.util.function` as MANDATORY (verified in its manifest). It does NOT embed them.
- Those 3 are real bundles (each has a Bundle-SymbolicName + versioned Export-Package: component
  1.5.1, promise 1.3.0, function 1.2.0).
- In the prod uber-jar they are NEITHER flattened (0 `org/osgi/service/component/*.class`) NOR staged
  under META-INF/bundles. They fall through the crack — a human staged felix.scr/pax/resolver by hand
  and missed felix.scr's transitive DS-API import.
- Today it "works" only because `OsgiRuntime.SCR_API_PACKAGES` system-exports those packages — but
  that is a MIS-DIAGNOSIS papering over the crack: the packages are framework-internal (the flat host
  reads `ServiceComponentRuntime` BY NAME, never typed → NOT a seam), so they should be STAGED as
  bundles and resolved bundle-to-bundle, NOT system-exported.

**Gate:** the extension is correct when it spontaneously stages the 3 DS-API jars and
`SCR_API_PACKAGES` can be DELETED from both OsgiRuntime and FelixFrameworkExtension with the embedded
boot still green (HostSeamEmbeddedFelixTest + EmbeddedBundlesBootTest). That is the "retombe sur nos
pieds" proof. Do NOT hand-fix the crack first — fixing it by hand re-implements what the extension
must derive (same trap we avoided with the deleted manifests-core spike).

## Design — settled this session (read before coding)

### The discriminator is a CLOSURE, not "has a BSN"
jackson / netty / cdk8s / guava HAVE Bundle-SymbolicNames but the host consumes them FLAT (Pulumi /
cdk8s code calls them directly, outside the framework). Wrapping them would break the flat host. So
"exclude every jar with a BSN" is the SAME over-reach as "every BSN → install" that we rejected for
the -port seam.

The real rule — a closure over the framework's needs:
1. SEED = our embeddable bundles (embed-capability `type=model|edge`) + the boot-stack
   (felix.framework/scr/resolver, pax-logging-api/logback — the `BootStackJar` registry).
2. CLOSE transitively over the `Import-Package` / `Require-Capability` of those bundles. felix.scr's
   import of DS-API pulls the 3 spec jars INTO the closure — and only it (jackson etc. stay out).
3. A jar in the closure that is itself a real bundle (has a BSN) → STAGE intact + shade-exclude.
   Everything else → flat (shaded).

### seam vs stage — by READING bnd headers, NOT java reflection
(User explicitly rejected reflection: "il faut faire de la reflection java" → NO. The chantier rule
is "read what bnd declared, don't compute".) bnd already did the bytecode import analysis at build
and wrote `Import-Package`. So:
- A package a bundle imports that ALSO is exported by an installed bundle → wired bundle-to-bundle.
- A package the flat host shares typed (the `-port`) → seam, system-exported. OUR bundles DECLARE
  this (`type=seam`) — no calc.
- The DS-API trio: imported by felix.scr, host touches it by NAME only → not seam → stage it.

### The membre-gauche that does NOT exist (a corrected mis-step)
I initially thought we needed `Import-Package(host)` to compute `seam = Import(host) ∩
Export(bundles)`. WRONG, and verified: seed-master is NOT a bundle (no bnd, no bnd.bnd, the shaded
uber-jar has no Import-Package/Bundle-SymbolicName header at all). The calculation does NOT need the
host's imports — `OsgiRuntime.deriveSystemExports` already works from the BUNDLES' Import-Package
(membre droit), never the host's. The staging decision likewise is "closure over bundle imports",
host-independent. Do not chase host bnd-analysis — it is a dead end.

### "Are we recoding Equinox?" — no, but know the boundary
The exec-jar IS an OSGi runner (like the Felix/Equinox launcher). Felix already AUTO-computes
`org.osgi.framework.system.packages` (JRE packages by detected EE profile, from its packaged
`default.properties`) — we get that for free, never hand-list JRE packages. What no launcher does is
bridge a FLAT host classloader to the framework (our hybrid topology — the seam). That bridge is
ours to own; the system.packages.extra it needs is already derived by deriveSystemExports. The
extension only decides STAGE-vs-FLAT at build time; it does not re-implement a launcher.

CORROBORATED by the Felix launching-and-embedding doc (read 2026-06-22), which matches our design
point-for-point and means NOTHING is missing in our boot:
- Host shares classes with bundles via `org.osgi.framework.system.packages.extra` — that IS our
  seam, and the doc's caveat "host and bundle must use the SAME class definitions for the service
  interface" is verbatim our seam law (one package = one exporter = one class). So system.packages.
  extra is for SHARED INTERFACES only — which is exactly why the DS-API trio does NOT belong there
  (felix.scr-internal, never shared typed with the host) → stage it, an independent confirmation of
  the gate.
- Felix has NO auto-export of host packages (must be listed) → deriveSystemExports IS our way to
  produce that list; we are not missing a built-in.
- The doc shares host SERVICES via `felix.systembundle.activators` + a HostActivator — Felix-SPECIFIC.
  We deliberately do NOT use it: we read services via `framework.getBundleContext()` + ServiceTracker
  (`awaitService`), which is portable OSGi, not Felix-locked. A point in our favour, not a gap.
- Factory via `ServiceLoader.load(FrameworkFactory).newFramework(config).init/start` is the canonical
  embedding pattern — both our executors already do exactly this. Verdict: nothing to add to the boot.

## Alternatives evaluated and REJECTED — do NOT re-open (decision final)
- **Tycho** — NO. Eclipse/p2 ecosystem only (`eclipse-plugin`/`eclipse-feature`/`eclipse-repository`,
  target-platform, p2 repos); produces no flat uber-jar and is blind to our `ServiceLoader<FrameworkFactory>`
  embedded-Felix launch. Adopting it = rebuilding the whole build around p2. User has explicit bad
  history with p2 — sterile path, NOT to be raised again.
- **bnd resolver alone** (`bnd-resolver-maven-plugin` / `.bndrun` `-runrequires`→`-runbundles`) — NO. Its
  closure logic is EXACTLY what we want (declarative, no reflection, DS-API pulled in transitively), but
  the DECISION (already taken) is to code that logic in JAVA in our own extension reusing `BundleIndex`,
  not to depend on an external bnd launcher that doesn't fit our hybrid host-flat + embedded-Felix + seam
  topology. The bnd resolver is the conceptual proof the approach is sound, not the implementation.

## Shape — SETTLED this session (the design questions above are now resolved)

The live design view is `.claude/claude-preview.adoc` (throwaway; the canonical diagrams migrate to
`docs/architecture/osgi/` once the gate is green). Decisions taken with the user:

- **Aggregator `maven-embed-staging-ext/` at the repo root, NO-parent, RELEASE (1.0.0), OUTSIDE the
  root `<modules>`.** Why outside: a core extension named in `.mvn/extensions.xml` must already be in
  `~/.m2` when Maven starts, but it can only get there by being built — listing it in the reactor is
  the self-loading chicken-egg the devenv audit hit. So it builds in a SEPARATE phase. Two-phase
  build: (1) `./mvnw -f maven-embed-staging-ext/pom.xml install` (release → ~/.m2), (2) `./mvnw -am
  clean package` (reactor; extension loads via extensions.xml, boot-discovery resolves the release).
- **Provisioning = `.mvn/extensions.xml` + GAV** (user's choice, his known mechanism). Release coord,
  so the `no-snapshot-install` guard is satisfied; deliberate install, not the SNAPSHOT work-artifact.
- **No code duplication — EXTRACT, don't copy.** `BundleManifest` + `EmbedCapability` (pure parsing of
  what a bundle DECLARES; the only boot-discovery classes the extension needs) move OUT of
  boot-discovery into a NEW module `maven-embed-staging-ext/bundle-discovery` (built in the extension
  lifecycle, released alongside it). New PACKAGE `io.seedmatic.rke2lab.osgi.bundle.discovery` (NOT the
  old `…boot.discovery`) to avoid a split package — ~5 import lines update in OsgiRuntime,
  FelixFrameworkExtension, HostSeamEmbeddedFelixTest. Extraction verified clean (the 2 classes ref no
  boot sibling). `BundleIndex`/`BundleLocation`/`BootStackJar` STAY in boot-discovery (runtime scan,
  not needed at build time).
- **boot-discovery depends on bundle-discovery as a RELEASE coordinate** (stable foundation like the
  BOM → guard OK, no masking) AND, as a bundle, EMBEDS `bundle-discovery.jar` on its `Bundle-ClassPath`
  WITHOUT exporting those packages: internal consumption only, exported surface unchanged for its
  consumers; it delegates to the embedded classes for whatever it chooses to expose. (User's framing:
  a bundle can put jars on its own classpath via the manifest header — that is the compromise that
  avoids both duplication and a split package.)
- **CORRECTED mis-step (lifecycle direction):** first I had the extension add-source boot-discovery's
  sources (early artifact reaching BACK to a not-yet-built reactor module — backwards). The user's
  "c'est plutôt l'inverse": the shared foundation lives at the EARLIEST point (extension lifecycle),
  and later things (boot-discovery) depend on it. Extract forward, don't reach back.

## Vocabulary taxonomy — SETTLED (replaces the binary "install unless seam")

Validated against the canonical Felix embedding example `apache/felix-dev examples/servicebased.host`
(ConfigUtil system-exports ONLY the one shared interface package; host classes are NOT bundles;
framework via ServiceLoader). Every dependency jar is exactly ONE of FIVE categories:

1. **LAUNCHER** (felix.framework, osgi.core) — flat, NOT installed; becomes system bundle 0 on init,
   Felix AUTO-exports org.osgi.* + the JRE profile. NOT a seam, its own category. Answer to "are there
   others we don't know" = NO, by construction Felix covers the framework's own needs. Touch nothing.
2. **BOOT-STACK** (felix.scr/resolver, pax) — staged, installed by BSN, not system-exported.
3. **DOMAIN ours** (manifests-core, edges) — staged, installed by embed capability, not system-exported.
4. **SEAM ours** (the -port modules) — flat, NOT installed, system-exported, DECLARED `type=seam`. The
   seam law (a package of OURS that crosses the Host↔OSGi frontier MUST be type=seam) applies to THIS
   category ONLY.
5. **FLAT third-party** (jackson, slf4j, logback) — flat, NOT installed; system-exported ONLY if a
   bundle imports it (mirrored). jackson is HERE, not "has no BSN" (it has one) — the discriminator is
   "does our package cross the frontier", never the BSN. The seam law does not police third-party.

The build-time **fail-fast guard** (mirror of runtime `deriveSystemExports`): for each
`io.seedmatic.rke2lab.*` package on the flat set (resolved deps minus staged bundles), if it is owned by
a model/edge bundle (`domainExporterOf` != null) → FAIL at `package`, naming the HOST JAR that pulled
the forbidden dep (NOT the bundle — its type is declared on purpose; the culprit is the host module).

## Seed-closure decision — Option A CHOSEN (B kept in the doc for the record)

At `afterProjectsRead` the reactor jars are NOT built yet; third-party jars (felix.scr, DS-API trio,
pax) ARE in ~/.m2 and readable. **Option A** (chosen): recognise OUR bundles as the closure seed by
reading the embed marker from their bnd SOURCE (available as MavenProject) — consistent with "read what
bnd declared, don't recompute", needs no built jar, no coverage hole. **Option B** (rejected, kept
documented): inject a `ProjectDependenciesResolver` and close only over resolvable third-party imports
— leaves a hole if one of our bundles pulls a unique import. Keeping both in the doc with the rationale
is deliberate (a future reader sees the alternative weighed, not just the outcome).

## DEFERRED to pre-integration (decided, NOT done here — keep this chantier focused)

- **Rename `exec/` → `embed-exec/`.** User wants the clearer term (`embed` is this chantier's scope, the
  word will stick). Decided but delegated to the pre-integration workspace, as its OWN commit (orthogonal
  to the extraction — never mixed). MUST also update `system-space-world-universe-glossary` (currently
  "exec MATERIALISES") so the doc and the term don't contradict. Scope when done: root `<module>`, the 3
  `exec/*/pom.xml` `<name>`, any `relativePath`/doc paths, the `maven.multiModuleProjectDirectory`
  antrun in seed-master. No Java touched — pure structure/paths.

## Step plan (green per step, squash-merge to design/pre-integration NEVER main)

- **Step 0 — DONE (green), richer than planned.** Extracted into module `maven-embed-staging-ext/bnd-read`
  (NOT "bundle-discovery" — that name collided with boot-discovery and lied: it parses, it does not
  discover). New package `io.seedmatic.rke2lab.osgi.bnd`, flat jar, RELEASE 1.0.0, version managed in
  build-parent `dependencyManagement` (BOM-like, not hardcoded). The extraction became a real
  value-object remodel (user: "again a helper" → kill the static fourre-tout, one class one role):
  - `bnd-read` holds `Clause` (record: name+attributes, `asExportClause`), `OsgiHeader` (record:
    `List<Clause>`, `parse`, `names`, `asSystemExports`), `EmbedCapability` (record: a typed VIEW of
    the embed `Clause`, `of(OsgiHeader)`, isDomain/isSeam/matches). Replaces the old static
    `BundleManifest`/`BndHeaders` helpers.
  - boot-discovery's `BundleManifest` is now a RECORD `from(BundleLocation)` — read ONCE, holds the
    parsed headers; `BundleLocation` exposes `readManifest()` (the only byte read); `BundleIndex.Entry`
    collapsed to `(location, BundleManifest)`. boot-discovery depends on bnd-read (release) and exports
    NONE of `io.seedmatic.rke2lab.osgi.bnd` (Private-Package only — bnd stays internal, verified).
  - **DETERMINISM HARDENING (user call):** `felix.bootdelegation.implicit=false` in BOTH executors
    (OsgiRuntime + FelixFrameworkExtension). Felix defaults it TRUE — it stack-inspects to guess when a
    non-bundle instigator's class-load should fall through to the parent (app) CL; that is a silent
    escape hatch that could serve a SEAM package from the flat parent instead of its single declared
    exporter, making a typed-seam proof pass for the wrong reason. Off = every non-wired load fails
    loudly. Full reactor GREEN with it off (no test relied on the leak — the seam shares by real
    `system.packages.extra` wiring, confirmed).
  - **LATENT HOLE, deliberately NOT closed (no test exercises it):** boot-discovery embeds bnd-read on
    its `Bundle-ClassPath` ONLY in design — not yet wired. Today bnd-read reaches consumers as a FLAT
    Maven dep (OsgiRuntime/FelixFrameworkExtension run on the JCL). NO test installs boot-discovery as a
    bundle and calls `BundleManifest` IN-CONTAINER (verified: zero tests reference BundleManifest/bnd;
    boot-discovery has no tests at all). The day one does, `BundleManifest.from()` → `bnd.Clause`
    in-container will throw NoClassDefFound (bnd not exported, not on a Bundle-ClassPath). FIX THEN, with
    that red→green test as the justification — do NOT cable the embed speculatively now.
- **Step 1 — IN PROGRESS. The HOW-TO-HOOK was the hard part; settled by probing (3 dead ends ruled
  out empirically, do NOT re-try them):**
  - The extension lives in `maven-embed-staging-ext/staging-extension` (RELEASE 1.0.0, the build
    tooling root). Its parent chain (build-parent:0.0.0) must be installed to ~/.m2 for it to load —
    `./mvnw -f build-parent/pom.xml install` then `-f maven-embed-staging-ext/pom.xml install`.
  - **DEAD END 1 — core extension `AbstractMavenLifecycleParticipant.afterProjectsRead`:** deps are
    NOT resolved there (`project.getArtifacts()` EMPTY), and felix.scr is reached TRANSITIVELY through
    a reactor module (osgi/runtime), so resolving ourselves means skipping the reactor (unbuilt) →
    felix.scr + the DS-API trio drop out → founding case unreachable. Proven by probe.
  - **DEAD END 2 — mutate `project.getModel()` shade config at prepare-package (a bound Mojo):** the
    mojo runs (sees felix.scr+DS-API: 122 artifacts resolved at this phase ✓) and the exclude count
    goes 6→7, BUT guava stays in the uber-jar. Maven froze shade's config into its MojoExecution
    before this; the Model copy is not what shade reads.
  - **DEAD END 3 — `MojoExecutionListener.beforeMojoExecution` mutate `event.getExecution()
    .getConfiguration()`:** STILL not honoured. Decompiled `DefaultBuildPluginManager.executeMojo`:
    order is `getConfiguredMojo` (populates the shade mojo's fields from the Xpp3Dom) at offset 105,
    THEN `beforeMojoExecution` at 145, THEN `execute` at 152. So by the listener hook shade's fields
    are already set — mutating the DOM is too late. A listener can NEVER reconfigure a plugin.
  - **THE WAY — PROVEN + committed (`f87eaf19`):** `StagingExecutionStrategy`, a `@Named @Priority(20)
    @Singleton implements MojosExecutionStrategy`. Maven obtains the strategy by a SINGLE
    `container.lookup` (highest @Priority wins; decompiled `MojoExecutor.execute`), and the strategy
    DRIVES the execution list, calling `runner.run(execution)` per mojo. We mutate the shade
    execution's Xpp3Dom INSIDE that loop, before its `run` — BEFORE getConfiguredMojo, AFTER the
    earlier executions (resolution done, reactor bundles built; 122 artifacts incl. felix.scr+DS-API).
  - **build-cache cohabitation (SOLVED):** build-cache also registers a strategy at `@Priority(10)`,
    and the lookup is singular, so ours at `@Priority(20)` WINS and would clobber the cache. Fix: we
    DECORATE — inject `Map<String,MojosExecutionStrategy>`, mutate, then DELEGATE the run to the
    highest-priority strategy that is NOT us (build-cache if present, else Maven's default). Cache
    preserved.
  - **PROOF (hard, in the commit):** one injected exclude `com.google.guava:guava` drops guava from
    the seed-master uber-jar 2039 → 0 classes (6 residual com/google/common are `failureaccess`, a
    separate GA — confirms GA-exact exclusion works). Full reactor green, strategy fires on all 3 exec
    jars (seed-master/netplan-cli/manifests-cli), build-cache preserved.
  - **Provisioning recap (so it loads):** `.mvn/extensions.xml` lists `staging-extension:1.0.0`. Its
    parent chain needs `build-parent:0.0.0` in ~/.m2 → two-phase: `./mvnw -f build-parent/pom.xml
    install` then `./mvnw -f maven-embed-staging-ext/pom.xml install`, then the reactor. sisu index
    (`sisu-maven-plugin main-index`) registers the @Named strategy. plugin-tools/ASM needs 3.15.2 for
    JDK25 bytecode (3.13.1 fails "major version 69").
  - Per-exec targeting is by the strategy filtering (execution is shade), NOT pom inheritance — the
    exec-parent experiment was REVERTED (exec/* parent stays build-parent).
  - **Still TODO in step 1 (the business logic; the hard mechanism is now done):** replace the guava
    proof exclude with the real closure (seed = our embed bundles + boot-stack, close over
    Import-Package), the 5-cat classification, deriving BOTH the shade excludes AND the
    maven-dependency-plugin staging artifactItems; then the fail-fast seam guard. The mojo/probe code
    (StageBundlesMojo, ResolvedDependencies, StagingMojoExecutionListener) was DELETED in the pivots —
    only StagingExecutionStrategy remains.
- **Step 2** — empty the 3 exec poms of the 6 hand-lists (shade excludes + staging artifactItems). GREEN.
- **Step 3 (GATE)** — verify the DS-API trio is staged spontaneously; DELETE `SCR_API_PACKAGES` from
  OsgiRuntime + FelixFrameworkExtension; embedded boot green (HostSeamEmbeddedFelixTest +
  EmbeddedBundlesBootTest). The "retombe sur nos pieds" proof.

## Sequence agreed with the user
1. (THIS handoff) scope #4 — done.
2. User relays to the pre-integration workspace; THIS worktree (refactor/osgi-boot-alignment) is
   parked — intact, green, integrable as-is (the seam chantier stands on its own).
3. A NEW dedicated worktree/session designs + builds the extension.
4. THEN come back to the exec-jars: let the extension place the bundles; verify the DS-API gate;
   delete SCR_API_PACKAGES.

## Related
[[osgi-boot-alignment-state]] (the seam chantier this follows; its commit c96fe6c5 doc "The boot
face" defines seam vs domain) · [[boot-pipeline-unification-backlog]] (symmetric executor-unification,
also deferred) · [[single-source-of-truth-before-logic]].
