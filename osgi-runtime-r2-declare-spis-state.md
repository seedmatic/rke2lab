---
name: osgi-runtime-r2-declare-spis-state
description: "IMPL slice R2: declare the 5 SPIs as @Component — GESTE A ONLY. ★ SHIPPED to design/target-module-layout (squash merge 1a5b3a9f, 2026-06-18); worktree torn down. 10 impl classes annotated @Component(service=…); bnd generated the OSGI-INF descriptors; ServiceLoader + META-INF/services STAY ACTIVE (zero behaviour change, both mechanisms coexist). org.osgi.service.component.annotations (scope provided) added to the two bundle poms. Re-verified from the integration worktree before merge: 10 OSGI-INF/*.xml descriptors (9 manifests-core + 1 netplan) + Service-Component header per jar, ServiceLoader files still present, -Posgi build SUCCESS, NO -Plive. The manifests/manifests→manifests-core rename was already in the base (cherry-picked 4d6dd8c9). NEXT = R3 (consume intra-bundle via @Reference)."
metadata:
  node_type: memory
  type: project
---

## Status — ★ SHIPPED (squash merge 1a5b3a9f, 2026-06-18); worktree torn down

Geste A done. 10 classes annotated `@Component(service=…)`, DS annotations dep added to both bundle
poms. Build SUCCESS; 7 tests green (unitrepo-core 3 + manifests-core 4; netplan + cdk8s-systemd have no
test sources). `unzip` proof (re-verified from the integration worktree before merge): 10
`OSGI-INF/*.xml` descriptors (9 in manifests-core = 3 singletons + 6 contributors; 1 in netplan) + a
`Service-Component:` header listing all of them per jar; `META-INF/services/` ServiceLoader files STILL
present in both jars (both mechanisms coexist, zero behaviour change). Each descriptor
`<provide interface>` is the SPI, no `<reference>` (no `@Reference` — that's R3). NEXT = R3 (consume
the intra-bundle case via `@Reference`: NodeEnvContributorRegistry + DefaultManifestUpdateGate, inside
osgi/manifests-core only — spec §5).

## What R2 is (spec §5, the geste-A declaration slice)

R2 = pure DECLARATION (geste A), the build-time half proven on the bench ([[bnd-annotations-spike-state]]).
Annotate the providers `@Component`; bnd reads the annotation and emits `OSGI-INF/*.xml` +
`Service-Component` manifest header. **The ServiceLoader + META-INF/services machinery STAYS** — it is
still the only active consumer. Both mechanisms coexist; ZERO behaviour change. R3 is what later flips
the intra-bundle consumers to `@Reference`; R5 is what finally deletes ServiceLoader. Do NOT touch
consumption here.

## The exact surface — 10 impl classes (spec §2.1, grounded on HEAD)

Four singleton SPIs (`@Component(service=<SPI>.class)`):
- `osgi/manifests/manifests-core/.../manifests/DefaultManifestSynthesisService` → `ManifestSynthesisService`
- `osgi/manifests/manifests-core/.../manifests/DefaultManifestUpdateGate` → `ManifestUpdateGate`
- `osgi/manifests/manifests-core/.../manifests/DefaultManifestExplodeService` → `ManifestExplodeService`
- `osgi/netplan/.../netplan/DefaultNetplanSynthesisService` → `NetplanSynthesisService`

The aggregate-extender SPI `NodeEnvContributor` — its 6 impls each `@Component(service=NodeEnvContributor.class)`:
- `.../manifests/units/cluster/ClusterNodeEnvContributor`
- `.../manifests/node/NodeEnvIdentityContributor`
- `.../manifests/units/networking/NetworkingNodeEnvContributor`
- `.../manifests/units/ha/HighAvailabilityNodeEnvContributor`
- `.../manifests/units/storage/StorageNodeEnvContributor`
- `.../manifests/units/runtime/env/RuntimeNodeEnvContributor`

(All 6 paths are under `osgi/manifests/manifests-core/src/main/java/`.)

## How to annotate (the pattern)

- `@Component(service = Xxx.class)` on the impl class. Be EXPLICIT with `service=` — several impls are
  `final` classes implementing one SPI but bnd should publish them under the SPI type, not the impl
  type. For the 6 contributors, all publish under `NodeEnvContributor.class`.
- These impls have **no-arg construction today** (ServiceLoader requires it), so a bare `@Component`
  with no `@Activate` constructor is correct — DS will instantiate via the public no-arg ctor. Do NOT
  add `@Reference`s (that is R3). Do NOT add `@Activate`/`@Deactivate` unless a class already has
  lifecycle needs (none do).
- Keep it geste-A pure: no `configuration-policy`, no `@Designate`, no properties beyond `service=`.

## Poms — add the DS construction annotations (scope provided), like osgi-bench-config

Both bundles have bnd wired but NOT the annotations dep. Add to BOTH
`osgi/manifests/manifests-core/pom.xml` and `osgi/netplan/pom.xml` (the BOM already manages the
version, 1.5.1):

```xml
<!-- bnd-consumed construction annotations: @Component declares the DS descriptor bnd generates
     (geste A only — the runtime stays on ServiceLoader until a later slice). -->
<dependency>
  <groupId>org.osgi</groupId>
  <artifactId>org.osgi.service.component.annotations</artifactId>
  <scope>provided</scope>
</dependency>
```

## Validation

- FULL `flox activate -- ./mvnw clean package -Posgi -Dmaven.build.cache.skipCache=true
  -DskipTests=false` from the worktree; COUNT surefire reports ([[build-verification-gotchas]]).
- The geste-A-specific check: `unzip -l` (or jar tf) each of the two bundle jars and confirm
  `OSGI-INF/*.xml` descriptors are present (one per `@Component`) and `Service-Component:` is in the
  MANIFEST.MF. That is the proof bnd consumed the annotations. The 7 contributors+services should each
  yield a descriptor (4 in manifests-core's services + 6 contributors + … verify the count).
- Existing tests must stay green — behaviour is unchanged because ServiceLoader is still the consumer.
- NO `-Plive`. R2 is additive + build-time.

## Already done in the base (do NOT redo)

The doubled `osgi/manifests/manifests` was renamed to `osgi/manifests/manifests-core` (artifactId
`manifests`→`manifests-core`, the 4 GAV dependents, the aggregator `<module>`, the flake.nix
flox-runtime path; ghost target/ removed). That commit (`4d6dd8c9`) is in the integration base this
worktree branches from — the rename is DONE. R2 only adds annotations + the two poms.

## Workspace / close discipline

- Worktree `feature/osgi-runtime-r2-declare-spis`, base `design/target-module-layout` @4d6dd8c9.
  External-worktree model; `.code-workspace` sibling carries CLAUDE_CONFIG_DIR. sops re-smudged at
  setup. MEMORY dir = `.claude/memory/`.
- Build-time + test only → inside [[standing-autonomy-except-runtime-config]]. Act without asking.
- CLOSE = commit everything (code AND `.claude/memory/`), build GREEN, then HAND OFF to the
  design/target-module-layout session for the squash-merge — **this session does NOT saw its own
  worktree** ([[merge-from-target-worktree]]).

See [[osgi-runtime-migration-state]] (parent spec + R1–R7 chain), [[osgi-runtime-r1-scr-state]] (R1
shipped — SCR is in the BOM now), [[bnd-annotations-spike-state]] (the geste-A pattern this applies in
prod), [[build-verification-gotchas]], [[merge-from-target-worktree]],
[[standing-autonomy-except-runtime-config]].
