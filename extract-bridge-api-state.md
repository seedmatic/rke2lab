---
name: extract-bridge-api-state
description: "REFACTOR slice (worktree refactor/extract-bridge-api, off design/target-module-layout @b503b423): the prerequisite to R4. ★ CODED + GREEN 2026-06-19. Lifted the BRIDGE-API ports out of the OSGi impl bundles into dedicated HOST modules so the host compiles ONLY against the port. Created host/manifests-bridge-api + host/netplan-bridge-api; moved the sorted port types in; re-pointed manifests-core/netplan (impl) + seed-master at the new modules. ★ SPLIT-PACKAGE BIT (as the brief foresaw): keeping the original packages left io.seedmatic.rke2lab.manifests[.node|.profiles] + …netplan straddling two modules (bnd 'Split package' + shade 'overlapping classes' on 4 pkgs) → applied the pre-authorised fallback: moved types renamed into a .bridge namespace (…manifests.bridge[.node|.profiles], …netplan.bridge). Also renamed the 4 META-INF/services SPI registration files to the new FQNs (ServiceLoader dual-path). Full -Posgi clean package GREEN (cache skipped, BUILD SUCCESS, no split/overlap warnings, 12 surefire reports / 18 tests, 0 fail). The R4 work (system.packages.extra re-export + the 3 inversions) is UNCHANGED and still pending. The full per-type sort + naming live in [[api-extraction-tri-carto-state]]. ★ SHIPPED to design/target-module-layout (squash merge 3279fe39, 2026-06-19); worktree torn down. Re-verified from the integration worktree before merge (-Posgi clean package green, 34 modules, 18 tests/12 reports/0 skipped). ★ FOLLOW-UP OWED: rename `-bridge-api` → `-contract` (the Pohl OSGi paper uses 'Bridge' for a runtime hot-swap object, not our port — see [[api-extraction-tri-carto-state]] §'Why NOT bridge'); the merged code is still `-bridge-api`/`.bridge`, the design note says `-contract` — a deliberate temporary divergence the rename slice reconciles."
metadata:
  node_type: memory
  type: project
---

## Read first

The design is DONE and lives in **[[api-extraction-tri-carto-state]]** (the per-type sort table + the
naming convention) and the atlas runtime view + the **[[system-space-world-universe-glossary]]**
(system / space / world / universe; osgi describes, host actualises, exec materialises). Do NOT
re-decide the sort or the names — execute them. This note is the execution brief.

## What this slice does (and does NOT)

DOES: the MAVEN + PACKAGE move that creates the compile-time api/impl frontier.
- Create `host/manifests-bridge-api` and `host/netplan-bridge-api` (new modules in the HOST space).
- MOVE the bridge-api port types (the table below) into them.
- Re-point: `manifests-core` (impl) + `netplan` (impl) depend on their `*-bridge-api` to implement the
  ports; `exec/seed-master` (+ any host module) depends on the `*-bridge-api`, NOT on the impl, for the
  port types.
- Prove the host compiles ONLY against `*-bridge-api` (the fail-fast: removing the impl dep from the
  host must still compile for the ports).

Does NOT (these are R4, [[osgi-runtime-r4-boot-seam-state]]):
- the runtime `system.packages.extra` re-export of the bridge-api package to the OSGi world;
- inverting the 3 wrong-direction host usages (`ManifestYaml`, `NodeEnvContributorRegistry`,
  `FloxRuntimeAssets`) — they STAY in manifests-core and the host keeps using them as today for now
  (this slice only lifts the genuine ports; the inversions ride with R4).
- the Felix boot. NO -Plive.

## The bridge-api port set to MOVE (from [[api-extraction-tri-carto-state]], settled)

**manifests-bridge-api** (from `osgi/manifests/manifests-core`, pkg `io.seedmatic.rke2lab.manifests` +
`.node` + `.profiles`):
- 3 SPIs: `ManifestSynthesisService`, `ManifestExplodeService`, `ManifestUpdateGate`
- 4 gateway records: `ManifestSynthesisRequest`, `ManifestSynthesisResult`, `ManifestExplodeRequest`,
  `ManifestExplodeResult`
- `node.NodeEnvContext` (interface — host provides the impl), `node.NodeEnvContributor` (interface;
  consumed host AND intra-OSGi → lives in host bridge-api, re-exported to OSGi at R4)
- `ManifestDomainPolicy`, `profiles.ComponentVersions`, `profiles.FloxDebugPolicy`,
  `ManifestDomainCatalog`, `ManifestAnnotations`

**STAYS in manifests-core** (impl — do NOT move): `Default{Synthesis,Explode,UpdateGate}Service`,
`NodeEnvContributorRegistry`, `ManifestYaml`, `FloxRuntimeAssets`, the registries/visitors/resolvers.

**netplan-bridge-api** (from `osgi/netplan`): `ClusterNetworkBlueprint` (record) + whatever contract
types it transitively needs. NOTE `NetplanSynthesisService` is CLI-facing (exec/netplan-cli), NOT
host-facing → it is NOT a bridge-api; leave it on the netplan impl side.

**unitrepo:** untouched (pure intra-OSGi; host imports nothing).

## Naming (settled — [[api-extraction-tri-carto-state]])

`artifactId == leaf-dir-name`, space never in the artifactId, role as suffix. Bridge port =
`-bridge-api` in `host/`: `host/manifests-bridge-api`, `host/netplan-bridge-api`. Impl stays
`manifests-core` / `netplan` (NOT `-impl` — `-core` is the convention, cf. `unitrepo-core`). The user's
rule: the NAME shows the role (so `-bridge-api`, not bare `-api`, since you don't always have the dir).

## Decisions to make WHILE coding (small, recorded so they're not surprises)

- **Java package of the moved types — RESOLVED to `.bridge` (the fallback fired).** Keeping the packages
  was tried first; bnd flagged `Split package, multiple jars provide the same package` on
  `io/seedmatic/rke2lab/manifests`, `…/manifests/node`, `…/manifests/profiles`, `io/seedmatic/rke2lab/netplan`
  (and the exec shade warned `overlapping classes`, 22 manifests + 16 netplan). Cause: the impl bundle
  still OWNS public classes in those packages (`Default*Service`, `ManifestYaml`, `Manifests*`,
  `DefaultNodeEnvContext`, the profile impls, `DefaultNetplanSynthesisService`) while the ports moved out
  → the package straddles two modules, and bnd's Export-Package re-absorbs the bridge classes from the
  classpath. A bundle cannot both EXPORT its own classes of a package AND import that package from the
  host at R4 → blocking. So per the brief's pre-authorisation, the moved types were renamed into a
  `.bridge` namespace: `io.seedmatic.rke2lab.manifests.bridge[.node|.profiles]`,
  `io.seedmatic.rke2lab.netplan.bridge`. Each package now lives in exactly one module; both warnings gone.
  Consequence: the 4 `META-INF/services/<SPI-FQN>` registration files were renamed to the new FQNs too
  (ServiceLoader keys on the FQN — the dual-path `forServiceLoader()` would silently find no provider
  otherwise). Import churn was real but mechanical (~95 files); the build is the proof.
- **host/ parent:** the new modules parent to `host-parent` (like the other host modules). They are
  plain jars (ports — no bnd bundle needed unless R4 wants them OSGi-exportable; a pure-pojo api jar is
  fine, the host exports it via system.packages.extra at R4).
- **host-bridge-api must stay pure contract:** no com.pulumi, no io.grpc, no impl deps — just the
  interfaces + records + value types. That purity is what lets R4 export it to the OSGi world cleanly.

## Validation

- FULL `flox activate -- ./mvnw clean package -Posgi -Dmaven.build.cache.skipCache=true
  -DskipTests=false` from the worktree; COUNT surefire reports ([[build-verification-gotchas]]).
- The fail-fast proof: after re-pointing, `exec/seed-master` must NOT have a compile dependency that
  exposes `Default*Service` etc. — confirm the host depends on `*-bridge-api` for the ports and the
  build is green. (A quick check: grep host imports still resolve, R3 spike + existing tests green.)
- NO -Plive.

## Workspace / close discipline

- Worktree `refactor/extract-bridge-api`, base `design/target-module-layout` @b503b423. External-worktree
  model; `.code-workspace` sibling carries CLAUDE_CONFIG_DIR. sops re-smudged at setup. MEMORY dir =
  `.claude/memory/`.
- Build-only → inside [[standing-autonomy-except-runtime-config]]. Act without asking.
- CLOSE = commit everything (code AND `.claude/memory/`), build GREEN, then HAND OFF to the
  design/target-module-layout session for the squash-merge — **this session does NOT saw its own
  worktree** ([[merge-from-target-worktree]]).

See [[api-extraction-tri-carto-state]] (the sort + naming — THE design), [[osgi-runtime-r4-boot-seam-state]]
(R4, which this de-risks; the inversions + system.packages.extra are R4), [[system-space-world-universe-glossary]]
(vocabulary), the atlas runtime view, [[build-verification-gotchas]], [[merge-from-target-worktree]].
