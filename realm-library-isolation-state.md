---
name: realm-library-isolation-state
description: Realm library isolation increment (feature/cluster-edge, before world-gateway 2D). Each world loads its own jackson — host flat (JCL), OSGi as an installed bundle — ending the system.packages.extra share that 2C's String-only seam made unnecessary. Spec 3aaa66fb; plan wip/plans/2026-06-30-realm-library-isolation.md. 4 mechanism deltas + gate-law change, all derived (no hand-list). NEXT = execute subagent-driven, then resume the 2D plan.
metadata:
  type: project
---

## Why (user-driven, 2026-06-30)

The user spotted it while reviewing the 2D plan's bom additions: jackson lives in BOTH realms but is
SHARED across the host/OSGi boundary via `system.packages.extra` (BootPlanner.deriveSystemExports
mirrors each model bundle's Import-Package; the system bundle re-exports the host's flat jackson to
the bundles). Since 2C the seam carries only opaque `Document(domain, coordinate, payload:String)` —
no Java type crosses — so sharing the jackson *classes* contradicts the separation 2C locked. networknt
(added by 2D) must follow the corrected rule, so jackson is fixed FIRST and networknt inherits it.

Isolation is the OSGi framework's native job (separate bundle classloaders) — the current state
DEFEATS it via the mirror. In embedded it is framework-enforced (proof: in-container suite would
raise LinkageError/ClassCastException if a type crossed); in remote (the capstone) it is in-situ
(two processes, no shared classpath). The user corrected my wrong claim that embedded "can't prove"
isolation — it can; the framework isolates by classloader. See [[reconsider-choices-when-revisiting]].

## The design (CONVERGED + user-approved, both AskUserQuestion forks)

- **Derived rule, NO hand-list** (fork 1): a *realm library* = a third-party OSGi bundle (has
  Bundle-SymbolicName, not io.seedmatic.rke2lab.*, not launcher, not type=seam) whose export a
  model/edge/record (`EmbedCapability.isDomain()`) bundle imports. It is STAGED as a bundle (OSGi's
  own copy) AND kept FLAT where the host imports it. Today selects jackson's 5 jars (core, databind,
  annotations, dataformat-yaml, snakeyaml); 2D's networknt+itu self-include with zero code change.
- **Gate law derived from seam purity** (fork 2): DUPLICATE_REALM_CLASS exempts a flat∧staged package
  iff it is ABSENT from the union of all type=seam bundles' Export-Package (the seam surface). The
  user's invariant "the lib does not transpire into world-gateway" BECOMES the check — self-enforcing
  (a seam importing jackson breaks the build). Replaces the `org.slf4j` ALLOWED_SHARED_ROOTS hand-list.

## The 4 deltas (all TDD; plan has full code, no placeholders)

1. `StagingClosure` — seed realm libraries (a third-party OSGi bundle exporting a domain import is
   staged even though host-flat); add `realmLibraryGas()` + `shadeExcludeGas()` (staged minus realm
   libs). Record gains a 3rd component `realmLibraryGas`.
2. `StagingExecutionStrategy.injectShadeExcludes` — iterate `closure.shadeExcludeGas()` not
   `stagedGas()` → a realm library is staged AND kept in the shade (flat∧staged). `injectStaging-
   ArtifactItems` still reads `staged()`, so it IS staged.
3. `BootPlanner.deriveSystemExports` — remove from the mirror any package exported by ANY installed
   bundle (via `discovery.all()`+`manifestOf`), not only domain bundles. Once jackson is a staged
   bundle, doctor-core's import wires bundle-to-bundle. ONE broadened removeIf.
4. `DuplicateRealmClass(Set flatPackages, Set seamSurface)` — exempt flat∧staged absent from
   seamSurface; delete ALLOWED_SHARED_ROOTS/isAllowedShared. Call site in enforceGates builds the
   seam surface from `resolved` (b.embed().isSeam()).
   Plus Task 5: controlplane package-info drops the dead `@GovernedBy(DUPLICATE_REALM_CLASS, WARN)`
   (cdk8s now exempt by derivation → no-dead-code). Task 6 = full-reactor `-Pall-worlds` gate.

## Key verified facts (don't re-derive)

- Prod/live boot uses `DiscoveryPolicy.all()` (BootRequest:31 default; NO `.discover(onlyMatching)`
  on the seed-master path) → anything staged under META-INF/bundles/ is installed by presence alone.
  jackson needs NO embed capability and NO launcher contribution — it enters by the framework
  resolving doctor-core's Import-Package, exactly as felix.scr pulls the DS-API trio. The user
  reasoned this out ("les bundles en dépendance de nos models sont résolus par le framework").
- jackson poms are UNTOUCHED — already compile-scope on seed-master (host flat) + doctor-core +
  manifests-core (OSGi). Only the staging machinery + gate change.
- All 5 jackson jars + networknt 1.5.6 + itu 1.10.3 ship clean OSGi manifests (Bundle-SymbolicName +
  Export-Package) — stage with zero wrapping.
- Model bundles importing jackson: ONLY doctor-core (core+databind) + manifests-core (+dataformat-
  yaml+snakeyaml). No edge/record/seam imports jackson. Seam purity holds today (world-gateway,
  doctor-port, manifests-port import no jackson).
- cdk8s is a GENUINE dual-realm lib, NOT a scope leftover: host uses it at compile scope —
  IncusResourceBootstrap.java:2194-2195 builds org.cdk8s.App/Chart for incus host-slot manifests;
  HostSlotManifest imports cdk8s types. OSGi manifests-core uses its own copy for k8s manifests.
  Backlog (NOT this increment): consolidating host-side manifest synthesis into the OSGi manifests
  world — separate larger question.

## State — INCREMENT COMPLETE + REVIEW-CLEAN (2026-06-30)

Spec 3aaa66fb, plan fe1ee277. Executed subagent-driven, all 6 tasks green. Commits:
8bc21cce(t1 StagingClosure realm-lib) · 17e67a8a(t2 shade keeps realm libs) · 843d3220(t3 BootPlanner
stops mirroring installed-bundle exports — used `stack` not discovery.all(), reviewer-confirmed
better) · fb1decb9+441105e4(t4 DuplicateRealmClass seam-derived + coverage test) · f10a81b7(t5
controlplane WARN drop) · caea2d7b(final-review fix: manifests-cli WARN drop — the twin t5 missed).

GATE GREEN (full reactor package -Pall-worlds): duplicate-realm-class 0/0 (cdk8s exempt by seam
derivation — the WARN backlog cleared to green on BOTH execs), realm-boundary 0/0. jackson flat∧staged
(4 jars under META-INF/bundles + ObjectMapper.class flat; snakeyaml+dataformat-yaml staged too).
In-container green (EmbeddedBundlesBootTest 3/3, DoctorCoreInContainerTest 41/41) = no jackson type
crosses the seam = isolation framework-enforced in embedded. spec-coverage 38 warn = pre-existing.

Final whole-increment review (opus, adversarial): READY after the one Important fix (manifests-cli
twin, done caea2d7b). One Minor deferred → [[bootplanner-slf4j-drop-redundant-backlog]].

Build gotcha LEARNED (cost a debug loop): building the maven-embed-staging-ext aggregator while the
extension is active in .mvn/extensions.xml SELF-POISONS — the live extension decorates its own
rebuild and emits an empty bnd-read jar. Fix: disable the extension in .mvn, reinstall, re-enable
(the user did this). The two-phase build's phase 1 must run with the extension disabled.

NEXT = resume the 2D plan (adds networknt onto this realm-library rule — it self-includes, zero code
change). After 2D: the remote-validation capstone, then the merge. Merge gate: REALM_BOUNDARY ERROR +
(2D) SCHEMA_CONCORD ERROR + the capstone. See [[world-gateway-2c-complete-2d-designed-state]]
[[incus-edge-placement-backlog]].
