---
name: rename-bridge-to-contract-state
description: "REFACTOR slice (worktree refactor/rename-bridge-to-contract, off design/target-module-layout @b9c14a26): rename -bridge-api → -contract everywhere, reconciling the deliberate code/design divergence. WHY: the Pohl & Gerlach OSGi paper uses 'Bridge' for a RUNTIME hot-swap object (setImpl/delegation, GoF Bridge) in our exact domain (OSGi service update); our module is a hexagonal PORT (a build+classloader contract), not that — 'bridge' lies about the role (see [[api-extraction-tri-carto-state]] §'Why NOT bridge'). The extraction shipped as -bridge-api/.bridge (3279fe39); the design note already says -contract; this slice makes code match. TWO dimensions: artifact/module/dir `bridge-api`→`contract` (6 poms + host dirs + aggregator <module>), AND Java package `.bridge`→`.contract` (~98 files + 4 META-INF/services files keyed on the FQN — ServiceLoader dual-path breaks SILENTLY if one is missed). Pure mechanical rename, build-only, -Posgi green, NO -Plive. Set up 2026-06-19, NOT yet coded."
metadata:
  node_type: memory
  type: project
---

## Why (read first)

The extract-bridge-api slice ([[extract-bridge-api-state]]) shipped the host port modules as
`host/manifests-bridge-api` + `host/netplan-bridge-api`, packages `…manifests.bridge[.node|.profiles]` /
`…netplan.bridge`. AFTER it was coded, the user had us read Pohl & Gerlach, *"Using the Bridge Design
Pattern for OSGi Service Update"* (EuroPLoP 2003): their "Bridge" is a RUNTIME indirection OBJECT
(register a generated `FooBar_Bridge` holding `Object impl`, `bridge.setImpl(newImpl)` to hot-swap
without dangling refs) — the GoF Bridge, in OUR exact domain. Our `bridge-api` is NOT that: it is a Maven
module of interfaces + records, a hexagonal PORT decoupling host-world from osgi-world at build +
classloader time. So "bridge" collides with a canonical OSGi term and LIES about the role. Renamed to
`-contract` (the atlas's own word, "the bundle/host contract"). Decomposition was sound; only the name.
The design note [[api-extraction-tri-carto-state]] already carries `-contract`; this slice makes the
merged code match — a deliberate, tracked, temporary divergence being reconciled.

## Scope (chiffré, integration @b9c14a26) — TWO dimensions, both needed

**Dimension 1 — artifact / module / dir: `bridge-api` → `contract`** (decided: `host/manifests-contract`,
`host/netplan-contract`):
- `git mv host/manifests-bridge-api host/manifests-contract`, `git mv host/netplan-bridge-api
  host/netplan-contract`.
- 6 poms carry the `bridge-api` token: `host/pom.xml` (the two `<module>` lines),
  `host/manifests-bridge-api/pom.xml` + `host/netplan-bridge-api/pom.xml` (their own `<artifactId>` +
  `<name>`), and the 3 GAV dependents `osgi/manifests/manifests-core/pom.xml`, `osgi/netplan/pom.xml`,
  `exec/seed-master/pom.xml`. Update artifactId → `manifests-contract` / `netplan-contract`, the `<name>`
  (relative dir), the `<module>` entries, and the GAV `<artifactId>` in dependents.

**Dimension 2 — Java package: `.bridge` → `.contract`** (decided: aligned with the artifact):
- `io.seedmatic.rke2lab.manifests.bridge[.node|.profiles]` → `…manifests.contract[.node|.profiles]`;
  `io.seedmatic.rke2lab.netplan.bridge` → `…netplan.contract`.
- ~98 `.java` files reference `.bridge` (package decls in the moved types + imports in manifests-core,
  netplan, seed-master, and the many manifests units). Mechanical: move the package dirs + rewrite
  `package`/`import` lines. Verify the bnd `Export-Package` in the impl bundles (manifests-core, netplan)
  and any `Import-Package`/`-exportcontents` reference the new package name.

## ★ The trap — 4 META-INF/services files keyed on the FQN (ServiceLoader dual-path breaks SILENTLY)

These 4 files in `osgi/manifests/manifests-core/src/main/resources/META-INF/services/` are NAMED by the
SPI's fully-qualified name, which contains `.bridge`:
- `io.seedmatic.rke2lab.manifests.bridge.ManifestUpdateGate`
- `io.seedmatic.rke2lab.manifests.bridge.ManifestSynthesisService`
- `io.seedmatic.rke2lab.manifests.bridge.ManifestExplodeService`
- `io.seedmatic.rke2lab.manifests.bridge.node.NodeEnvContributor`
`git mv` each to its `.contract` FQN. ServiceLoader looks up the provider by the EXACT FQN path — miss
one and the dual-path `forServiceLoader()` silently finds no provider (no compile error, no test failure
unless a test exercises that path). After renaming, GREP for any stray `.bridge` in resources AND verify
the R3 `NodeEnvContributorRegistryScrSpikeTest` + any ServiceLoader test still green (that is the
behavioural proof the FQN wiring survived). This is the same class of bug as the historical `rk2lab`
typo — a single-source-of-truth string mismatch.

## Validation

- FULL `flox activate -- ./mvnw clean package -Posgi -Dmaven.build.cache.skipCache=true
  -DskipTests=false` from the worktree; COUNT surefire reports ([[build-verification-gotchas]]) — expect
  the same 34 modules / 18 tests / 12 reports / 0 skipped as the extraction merge.
- `grep -rn '\.bridge\|bridge-api' --include='*.java' --include='*.xml'` (excl /target/) returns NOTHING
  after the rename (the completeness check). NO -Plive.

## Workspace / close discipline

- Worktree `refactor/rename-bridge-to-contract`, base `design/target-module-layout` @b9c14a26.
  External-worktree model; `.code-workspace` sibling carries CLAUDE_CONFIG_DIR. sops re-smudged at setup.
  MEMORY dir = `.claude/memory/`.
- Build-only → inside [[standing-autonomy-except-runtime-config]]. Act without asking.
- CLOSE = commit everything (code AND `.claude/memory/`), build GREEN, then HAND OFF to the
  design/target-module-layout session for the squash-merge — **this session does NOT saw its own
  worktree** ([[merge-from-target-worktree]]). After merge, update the atlas/glossary if any still say
  "bridge" as the module name (the conceptual "bundle/host contract" prose stays).

## ★ SHIPPED to design/target-module-layout (squash merge 2a6e25de, 2026-06-19); worktree torn down

Re-verified from the integration worktree before merge: -Posgi clean package green, 34 modules, 18
tests / 12 reports / 0 skipped, no stray `.bridge`/`bridge-api`. The contracts still ship as PLAIN JARS
in host/ — re-placing them to osgi/ as versioned bundles (per-package @Version) is the next OSGi-cleanup
increment ([[contract-placement-and-versioning-carto]] + [[osgi-package-versioning-carto]]).

## DONE 2026-06-19 — coded, build green (pre-merge worktree record)

Executed exactly as scoped. `git mv` host dirs (`manifests-bridge-api`→`manifests-contract`,
`netplan-bridge-api`→`netplan-contract`), the two package dirs (`…/manifests/bridge`→`/contract`,
`…/netplan/bridge`→`/contract`), and all 4 META-INF/services files to their `.contract` FQN. Sed'd
`manifests.bridge`/`netplan.bridge` across the 98 `.java` files and `*-bridge-api` across the 6 poms;
fixed the lying "BRIDGE port" prose in the two host pom `<description>`s (it's a port/contract, not a
GoF Bridge — the whole point). Completeness grep clean (only false positive: `bridgeMacaddr()`
accessor). Build `-Posgi clean package -DskipTests=false` GREEN: **34 modules / 18 tests / 12 reports
/ 0 skipped**, R3 `NodeEnvContributorRegistryScrSpikeTest` green (the ServiceLoader dual-path proof).

★ bnd Export-Package consistency (integration concern) — VERIFIED, **no correction needed**. The
ports moved to host back in 3279fe39, so the impl bundles' bnd never listed `.bridge`:
`manifests-core/bnd.bnd` exports only impl/util packages it still owns (`manifests`, `.domain`,
`.node`, `.profiles`, `.units.runtime.flox` — note `.node`/`.profiles` here are manifests-core's OWN
impl packages, distinct from the contract's `.contract.node`/`.contract.profiles`); `netplan/bnd.bnd`
exports `netplan`+`netplan.api`. Neither exports a `.contract` package. Host contract modules are
plain jars (no bnd) — R4 will do `system.packages.extra`. Green build is the proof (bnd fails on an
absent exported package). NOT touched: bench BSN drift (osgibench→bench/testkit) — separate backlogged
slice. Next: hand to design/target-module-layout for squash-merge; this session does not saw its worktree.

See [[extract-bridge-api-state]] (what shipped as -bridge-api), [[api-extraction-tri-carto-state]]
(§'Why NOT bridge' — the Pohl finding + the -contract decision), [[osgi-runtime-r4-boot-seam-state]]
(R4, which this unblocks with correct names), [[build-verification-gotchas]], [[merge-from-target-worktree]].
