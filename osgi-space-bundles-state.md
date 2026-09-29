---
name: osgi-space-bundles-state
description: "Step 2 of the new module layout (worktree refactor/osgi-space-bundles off design/target-module-layout): MOVE all the pure-OSGi code (manifests + unitrepo-core + unitrepo-handler-api) under osgi/ IN ONE go, AND bnd-ify them now (parent=bundle-parent, Export-Package, Provide/Require annotations by role). ★ This DELIBERATELY OVERRIDES the recorded 'bnd deferred to the loading track' decision in [[hub:docrepo-dag-state]] — see why below. Born 2026-06-18."
metadata:
  node_type: memory
  type: project
---

## Scope (user-decided 2026-06-18, two calls)

ONE sub-branch, two gestures kept conceptually distinct but shipped together:
- **(a) MOVE all pure-OSGi code under `osgi/` at once** (user's intuition: avoid back-and-forth, give
  the unitrepo V1 a coherent base in one step). The three pure modules (verified zero Pulumi/grpc
  imports): `manifests`, `unitrepo-core`, `unitrepo-handler-api`. Pure `git mv`, low risk.
- **(b) bnd-ify them NOW** — parent=`osgi/bundle-parent`, bare bnd-maven-plugin ref, `Export-Package`
  of the public package, and `Provide`/`Require` modelled via the proven bnd annotations
  ([[bnd-annotations-spike-state]]) by each module's REAL role.

## ★ THE OVERRIDE (must be recorded, not silent)

[[hub:docrepo-dag-state]] line ~324 records a GROUNDED user decision: **"bnd DEFERRED to the loading
track"** — Concern A (consume the OSGi resolution API as a LIBRARY = what unitrepo-core is) needs NO
bundle tooling; plain-JAR is correct; bnd belongs to Concern B (produce loadable bundles) at step 5.
**We are now reversing that**, on purpose, because the context changed: we are building the TARGET
OSGi layout (everything in `osgi/` declares its ports in its manifest) and the bnd-annotation pattern
is PROVEN. Nuance that keeps it honest: **bundle ≠ loaded.** bnd-ifying produces a clean manifest
(Export-Package + requires); it does NOT make unitrepo-core a bundle we load at runtime. The "no
loading / standalone resolver" half of the original decision STILL HOLDS; we lift only "no manifest".
If a future session reads "bnd deferred" and is confused — THIS note is the reconciliation.

## Cartography done before the move (verified in code, design/target-module-layout HEAD)

- `manifests`, `unitrepo-core`, `unitrepo-handler-api` = ZERO `com.pulumi`/`io.grpc` imports → pure,
  belong in `osgi/` by the purity axis. None has a `bnd.bnd` yet.
- `unitrepo-core` = 4 files in ONE package `io.seedmatic.rke2lab.unitrepo.core`
  (UnitResolver, UnitResource, CapabilityFilter + test). External imports = ONLY OSGi
  (`org.osgi.resource.*`, `org.osgi.service.resolver.*`, `org.osgi.framework.Filter/FrameworkUtil`,
  `org.apache.felix.resolver.*`). So its bundle role = REQUIRES the OSGi resolver API; it Provides
  `Export-Package io.seedmatic.rke2lab.unitrepo.core`.
- `unitrepo-handler-api` = 1 file `UnitHandler` (SPI interface: `handledType()`, `handle(String)`).
  Its Javadoc: "the handler SPI a unit binds to via its constitutive `osgi.extender` requirement …
  lives on the shared (parent) class loader so a handler loaded from the store casts safely."
- **★ `UnitHandler` is implemented NOWHERE in rke2lab** (grep repo-wide: only its own file). It is an
  ANTICIPATED contract from the docrepo V1 design, not yet wired. So the user's choice "model the
  Provide/Require applicative contract" means: express handler-api as PROVIDING the `osgi.extender`
  /`unitrepo.type.*` handler contract (a `@Capability` or the extender namespace), and any future
  handler REQUIRES it — but there is no concrete implementor to wire YET. Decide in-branch how far to
  model a contract with no implementor (likely: declare handler-api's capability + core's requires,
  leave the handler edge documented, do NOT invent a fake handler).
- **DEAD pom edge to delete (hygiene):** `unitrepo-core/pom.xml` declares a dependency on
  `unitrepo-handler-api`, but core has ZERO Java reference to `UnitHandler`/`unitrepo.handler`. The
  edge is dead — remove it in the same step (never leave dead code, per CLAUDE.md).
- Consumers to update after the move: poms referencing `unitrepo-core`/`unitrepo-handler-api` =
  root `pom.xml`, `seed-master/pom.xml`, `manifests/pom.xml` (+ each module's own).

## docrepo context (the user asked to re-read docrepo-dag-wip)

`/private/var/lib/git/nxmatic/docrepo-dag-wip` (separate local repo, branch `wip/docrepo-v1-spec`).
The reste-à-faire is ALREADY DEFINED — no need to redefine, just to wake it:
- V1 spec `docs/.wip/specs/2026-06-13-docrepo-dag-v1-design.md`: seed-master AS level-0 node, LATENT;
  three modules `unitrepo-handler-api` + `unitrepo-core` + **`unitrepo-pulumi`** (the ACL/mediation
  seam — NOT YET created in rke2lab; the 3rd module the move does not yet have). Three caps + one
  proof: ingest checkpoint→Visit-unit, resolve standalone (Felix), load handler from store.
- The RESOLUTION track (retire the walker) is ALREADY SHIPPED to origin/main; the MIGRATION track
  (decomposition into bundles) is what THIS layout work executes. Full state in [[hub:docrepo-dag-state]].
- This move is migration-track step "4 unitrepo-core/-handler (born bundles)" + the manifests bundle,
  brought forward and made concrete on the target layout.

## Method (held to — same as step 1)

- MOVE by `git mv`. Sequence in-branch: commit the MOVE first (build green) THEN bnd-ify module by
  module, build-verify each so the resolver validates each frontier in isolation. `manifests` is big
  (~100 classes) — do it as its own commit.
- Build-verify FULL `flox activate -- ./mvnw clean package -Posgi -Dmaven.build.cache.skipCache=true
  -DskipTests=false` ([[build-verification-gotchas]]; partial -pl gives false failures). A frontier
  that fails to resolve = a wrong frontier.
- Bundle vs fixture: only modules that ARE bundles get parent=bundle-parent + bare bnd ref (emit
  Bundle-SymbolicName); see the step-1 pattern in [[layout-skeleton-state]].
- GNU sed in flox rejects `-i ''` — use `perl -0pi -e` for in-place pom edits.

## Merge (per [[merge-from-target-worktree]])

Squash merge into `design/target-module-layout`, run FROM the design worktree; the integration-status
line goes INSIDE the merge commit (amend, hash-free); commit everything before teardown.

## DONE 2026-06-18 — both gestures shipped on the sub-branch, full -Posgi build green (23 modules,
tests ran 0-skipped: UnitResolverTest + osgi-bench P1/P2 + seed-master realgraph)

Four commits on `refactor/osgi-space-bundles` (base `design/target-module-layout` @3d679009):
- **(a) the MOVE** — `git mv` manifests + unitrepo-core + unitrepo-handler-api under `osgi/`.
  KEY FACT confirmed: inter-module deps resolve by **GAV, not relativePath**, so the move touched
  only the aggregator wiring (root `<modules>` drop the 3; `osgi/pom.xml` gain the 3) + each moved
  pom's `<parent>` relativePath depth +1 and `<name>` prefixed `osgi/`. seed-master/build-parent
  poms needed NO path edit.
- **(b1) handler-api** = the contract PROVIDER. `@Capability(osgi.extender=unitrepo.handler, 1.0)`
  on the `UnitHandler` interface → bnd generates `Provide-Capability`. Providing half ALONE (no
  implementor yet — the requiring half lands with the first specialist unit).
- **(b2) unitrepo-core** = REQUIRES the resolver API honestly. bnd computes `Import-Package`
  (org.osgi.resource/.service.resolver/.framework/felix.resolver) from bytecode — NO `@Requirement`
  (it instantiates `ResolverImpl` directly → a package import, not a service require). Dead pom edge
  core→handler-api DELETED.
- **(b3) manifests** = LIBRARY bundle. `Export-Package` = the exactly-5 packages seed-master imports
  (manifests, .domain, .node, .profiles, .units.runtime.flox); glue `io.seedmatic.rke2lab.unitrepo`
  stays PRIVATE. bare bnd ref coexists with the existing assembly (`manifests-d.zip`) + shade
  (`exec.jar`) — all 3 artifacts still produced; jar picks up bnd's manifest via inherited
  manifestFile. NO `@Capability`: manifests' capability-PROVIDING role (specialist units publishing
  via the handler contract) is a FUTURE chantier — declaring one now = a hand-typed lie no Java backs.

Bundle pattern (all 3) = `parent=bundle-parent` + bare `bnd-maven-plugin` ref + `bnd.bnd`
identity-only (`Bundle-SymbolicName`/`Export-Package`/`-noimportjava: true`). Packages UNCHANGED:
the `osgi/` axis is a Maven/purity axis, not a Java-package axis (the bench proves it —
`io.seedmatic.rke2lab.osgibench.*` under `osgi/osgi-bench/`). The Maven `<name>` carries the space.

**(c) PER-MODULE DESCRIPTIONS (polish, scope-widened on user's call).** Review caught a leak: bnd
folds an inherited `project.description` into `Bundle-Description`, so `bundle-parent`'s description
appeared verbatim in every moved bundle's manifest. The user set the target straight — we WANT a
description per bundle, not none — so the fix is a per-module `<description>`, NOT `-removeheaders`
(an abandoned first attempt, soft-reset out of history). Widened to ALL 14 description-less reactor
modules (prod included) as a justified uniform improvement. New rule recorded:
[[every-module-has-a-description]]. Verified: each bundle now emits its own Bundle-Description.

REMAINING ON THE SUB-BRANCH = the squash-merge into `design/target-module-layout`, run FROM the
design worktree ([[merge-from-target-worktree]]): integration-status line INSIDE the merge commit
(amend, hash-free); commit `.claude/memory/` before teardown. The four refactor commits are clean;
the move commit also swept in the MEMORY.md/state.md changes (acceptable — memory must be committed
before teardown anyway).

## State / next (superseded — see DONE above)
- Branch `refactor/osgi-space-bundles`, base `design/target-module-layout` (HEAD 3d679009). sops
  re-smudged.
- NEXT layout steps (own branches): the host space (fill `host/host-parent` when first host module
  migrates), the `unitrepo-pulumi` ACL/mediation seam, the bdd-core/bdd-ledger split (oracle-validated).

See [[bnd-annotations-spike-state]] (proven pattern), [[layout-skeleton-state]] (step 1 + bundle/fixture
split), [[hub:docrepo-dag-state]] (the overridden decision + the unitrepo V1 roadmap),
[[merge-from-target-worktree]], [[build-verification-gotchas]], [[step2-decomposition-state]].
