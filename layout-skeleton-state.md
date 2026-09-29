---
name: layout-skeleton-state
description: "Step 1 of the new module layout (worktree refactor/layout-skeleton off design/target-module-layout): DONE — built the osgi/ + host/ skeleton, moved osgi-bench under osgi/ (first pilot git mv), AND dissociated the aggregator role from the parent role at every level (build-parent + per-space parents osgi/bundle-parent, host/host-parent). Full -Posgi build green, OSGi spikes ran. Born 2026-06-18 after the bnd-annotations spike merged; scope grew mid-implementation on the user's call."
metadata:
  node_type: memory
  type: project
---

## What this step delivered

Step 1 of the target layout ([wip/specs/2026-06-17-target-module-layout.adoc]) — the SKELETON + the
first pilot MOVE, plus a scope expansion the user chose mid-implementation. All on
`refactor/layout-skeleton`, build green (`clean package -Posgi -Dmaven.build.cache.skipCache=true
-DskipTests=false`, 23 modules SUCCESS; `clean test -Posgi` ran the osgi-bench spikes — Tests run, 0
skipped).

### a. The two spaces + the pilot move (original scope)
- `osgi/` and `host/` aggregator dirs, wired into the root `<modules>`.
- `git mv osgi-bench/ → osgi/osgi-bench/` (pure, zero-Pulumi, disposable → right pilot). Git tracked
  every file as a rename. Leaf `<name>` now `osgi/osgi-bench/…`; `<relativePath>` depth +1.

### b. Aggregator role dissociated from parent role (scope GROWN mid-session — the key decision)
User insight while reviewing: *aggregator (`<modules>`) ≠ parent (`<parent>` config inheritance)* — two
Maven relations I had conflated. We separated them everywhere, because design is the place to integrate
proven patterns (not prod yet; refactor ← design, so we move at our own pace):

- **`build-parent/pom.xml`** — NEW. Holds ALL global config (properties, depMgmt importing the BOM +
  inter-module versions, common test deps, repos, profiles, pluginMgmt). NO `<modules>`, no parent.
  This is the old root pom's body, minus aggregation, minus bnd.
- **root `pom.xml`** — now a PURE aggregator, `artifactId` renamed `parent` → **`rke2lab`**,
  `parent=build-parent`. Lists `build-parent` + every module + the two spaces.
- **`osgi/bundle-parent/pom.xml`** — NEW. Parent of the OSGi BUNDLES. Carries bnd (`bnd.version`,
  `bnd-process` execution) + jar `manifestFile`, both as **pluginManagement**. bnd left the root
  entirely (it is osgi-specific, was only ever used by the bench).
- **`host/host-parent/pom.xml`** — NEW, deliberately EMPTY. Counterpart of bundle-parent; will carry
  host-specific build (shade exec-jar, exec-plugin, com.pulumi/incus depMgmt) when the first host
  module migrates. Posed NOW so the next slice inherits a ready parent and is FORCED to refine it,
  rather than having to remember to create it (user's rationale).

### c. The load-bearing pattern this proved: "being a bundle is a per-module property"
The 3 bench bundles (config/host/schema) → `parent=bundle-parent`, and their `<build>` is now just a
**bare `bnd-maven-plugin` reference** (version + execution + manifestFile all inherited). The 2 fixtures
(testkit/tests) → `parent=build-parent` (NOT bundle-parent) → they get NO OSGi manifest. Verified:
config/host/schema emit `Bundle-SymbolicName` in target MANIFEST.MF; testkit/tests have none. bnd can't
be hoisted via `<build><plugins>` in a shared parent (jar runs for all → fixtures would seek a manifest
they lack) — `pluginManagement` + per-bundle opt-in is the only clean cut.

## Parent map (final, all relativePaths resolve)
- `build-parent` = no parent. `bom` = no parent (deliberate, breaks the import cycle — unchanged).
- everything else (netplan, systemd-contract, cdk8s-systemd, sdks/incus, pulumi-automation-ext[-testkit],
  manifests, unitrepo-*, seed-master, osgi, host, root, osgi/bundle-parent, osgi/osgi-bench,
  host/host-parent, bench testkit+tests) → `parent=build-parent`.
- 3 bench bundles → `parent=bundle-parent`.

## Method (held to)
- MOVE by `git mv`, never duplicate. CLI selectors stay the unprefixed artifactId (`-pl :osgi-bench-config`).
- Inter-module deps via the REACTOR (`-am`), never installed jars.
- Build-verify FULL `-Posgi -Dmaven.build.cache.skipCache=true -DskipTests=false`
  ([[build-verification-gotchas]]). A boundary that fails to resolve = a wrong boundary — all resolved.
- One sub-branch per layout step for a legible history.
- GNU sed in flox rejects `-i ''` (BSD-ism) — use `perl -0pi -e` for in-place pom edits.

## State / next
- Branch `refactor/layout-skeleton`, base `design/target-module-layout` (HEAD 1eaaeff7). Build green.
- Two stale refs to the old `io.seedmatic.rke2lab:parent` artifactId remain ONLY in
  [wip/plans/2026-06-17-osgi-bench-slice2.md] — a finished slice's historical journal, left as-is
  (convention lives in CLAUDE.md, not in old plans).
- Merge plan: squash/ff into `design/target-module-layout` (solo, no PR — [[rke2lab-solo-no-pr-merge-direct]]);
  remove worktree + delete branch after.
- NEXT step (own sub-branch): adopt the bnd-annotation pattern in PROD config (InfraDomain/Rke2labConfig)
  by MOVE — see [[bnd-annotations-spike-state]] for the proven pattern, [[step2-decomposition-state]] for
  the roster + roadmap. When the first host module migrates, FILL `host-parent` (it is the waiting hook).

See [[bnd-annotations-spike-state]] (the just-merged proof), [[step2-decomposition-state]] (parent
chantier + module roster), [[hub:check-osgi-standard-before-modeling]], [[build-verification-gotchas]],
[[hub:external-worktree-operating-model-state]].
