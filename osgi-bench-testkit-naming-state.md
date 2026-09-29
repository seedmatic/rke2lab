---
name: osgi-bench-testkit-naming-state
description: "REFACTOR slice (worktree refactor/osgi-bench-testkit-naming, off design/target-module-layout @70276800): kill the redundant osgi- prefix under osgi/. Rename the dir osgi/osgi-bench → osgi/bench (8-module domain, follows the step-4 domain-DIR rule) AND osgi/osgi-testkit → osgi/testkit (R3 promoted it but kept the redundant prefix). ★ KEY TRAP found in carto: FelixFrameworkExtension.install(artifact) locates bundles by CLASSPATH SUBSTRING (path.contains(artifact)) — so artifactIds must stay DISCRIMINANT. Keep a bench-* / testkit prefix on artifactIds (bench-config, bench-host, …, osgi-testkit→testkit); do NOT shorten to bare config/host/tests (would collide: 'host'→host-parent, 'tests'→any *-tests). Pure mechanical rename (git mv + artifactIds + <module> + GAV dependents); the Java package io.seedmatic.rke2lab.osgi.testkit is already neutral, no Java change. -Posgi green, NO -Plive. ★ SHIPPED to design/target-module-layout (squash merge e58a44e3, 2026-06-19); worktree torn down. artifactIds = bench-* / testkit (user confirmed: drop the SPACE prefix, keep only the DOMAIN prefix — coherent with manifests-core/unitrepo-core/systemd-contract; 'osgi' is in ZERO artifactIds). install call-sites use bare tokens (config/host/schema/scr-*/manifests-core) that stay valid substrings — no string edit needed."
metadata:
  node_type: memory
  type: project
---

## Why (the naming debt)

Under `osgi/`, two modules carry a redundant `osgi-` prefix: `osgi/osgi-bench/*` and `osgi/osgi-testkit`.
Same noise as the `manifests/manifests` doubling we already fixed → `manifests/manifests-core`. The
step-4 domain-DIR rule ([[osgi-leaves-state]]): a domain dir when ≥2 modules — osgi-bench has 7 (config,
host, schema, scr-api/provider/consumer, tests) so it earns `osgi/bench/` with its aggregator. testkit
was promoted to its own module in R3 ([[osgi-runtime-r3-consume-references-state]]) but kept the
redundant prefix; rename it too for uniformity (else `osgi/bench/` clean beside `osgi/osgi-testkit`
noisy). User decision 2026-06-19: do BOTH, in a dedicated work worktree (the design/integration session
does design, not implementation).

## ★ KEY TRAP — artifactIds must stay discriminant (classpath-substring lookup)

`FelixFrameworkExtension.install(artifact)` / `installBundles(...)` locate each bundle on the test
classpath by SUBSTRING: `path.toString().contains(artifact)` (osgi/testkit FelixFrameworkExtension,
~line 219). R3 already generalised this off the old `osgi-bench-` prefix. If the rename shortens
artifactIds to bare generics (`config`, `host`, `tests`, `schema`), substring matching COLLIDES:
`host` matches `host-parent` / `pulumi-automation-ext`; `tests` matches any `*-tests` jar on the
classpath. So:

- **Dir rename:** `osgi/osgi-bench` → `osgi/bench`, `osgi/osgi-testkit` → `osgi/testkit`. Safe.
- **artifactIds:** keep a discriminant prefix. Recommended: `osgi-bench-config` → `bench-config`,
  `osgi-bench-host` → `bench-host`, … (the `bench-` prefix kills the `osgi-` redundancy but stays
  unique); `osgi-testkit` → `testkit` (already unique enough as a substring — but VERIFY no other
  classpath entry contains "testkit"; if worried, keep `osgi-testkit` or use a longer token).
- After renaming, re-check every `installBundles("…")` / `install("…")` / `bundle("…")` call-site
  string in the 4 bench proofs + the R3 manifests-core spike, and update them to the new artifactId
  tokens. THIS is where a silent break hides (a stale substring would match the wrong jar or none).

## Scope (grounded on @70276800)

- Dirs: `git mv osgi/osgi-bench osgi/bench`, `git mv osgi/osgi-testkit osgi/testkit` (+ the inner
  module dirs `osgi-bench-config` → `bench-config`, etc. — do them as git mv so history follows).
- `osgi/pom.xml` aggregator `<module>osgi-bench</module>`→`bench`, `<module>osgi-testkit</module>`→`testkit`.
- `osgi/bench/pom.xml` aggregator: artifactId `osgi-bench`→`bench`, its 7 `<module>` lines.
- Each module pom: `<artifactId>` + `<name>` (the `<name>` is the relative dir path, so it changes too).
- GAV dependents to update: `osgi/bench/bench-tests/pom.xml` (depends on testkit + the 6 bench modules),
  `osgi/bench/bench-scr-consumer` + `bench-scr-provider` (depend on bench-scr-api), and
  `osgi/manifests/manifests-core/pom.xml:95` (test-dep on `osgi-testkit` → `testkit`).
- Java: package `io.seedmatic.rke2lab.osgi.testkit` is ALREADY neutral (R3 chose it) — NO Java rename.
  The bench test classes are in `io.seedmatic.rke2lab.osgibench` — leave the package (cosmetic, not part
  of this slice) UNLESS trivially clean; the ask is the module/artifact naming, not packages.

## Validation

- FULL `flox activate -- ./mvnw clean package -Posgi -Dmaven.build.cache.skipCache=true
  -DskipTests=false` from the worktree; COUNT surefire reports ([[build-verification-gotchas]]). The
  bench proofs (4) + the manifests-core R3 spike (1) MUST stay green — they exercise the
  classpath-substring install, so a bad artifactId token fails them loudly (the built-in check).
- NO `-Plive`. Pure rename.

## Outcome (2026-06-19)

DONE + committed on this worktree. Build: `clean package -Posgi -Dmaven.build.cache.skipCache=true
-DskipTests=false` → BUILD SUCCESS, 22 osgi-space modules. Surefire counted: bench-tests = 4 reports
(ExtenderContract/MetatypeIntrospection/ScrActivation/ScrUnsatisfiedReference, all 0-skipped),
manifests-core = the R3 NodeEnvContributorRegistryScrSpikeTest green — the classpath-substring install
exercised end-to-end. No install call-site string needed editing (bare tokens already substrings of the
new paths). Naming: `bench-*` / `testkit` (NOT `osgi-bench-*`) — the reactor convention is artifactId ==
leaf-dir-name with the SPACE prefix dropped; `osgi` appears in zero artifactIds repo-wide, only the
DOMAIN prefix is kept (manifests-core, unitrepo-core, systemd-contract). ★ SHIPPED to
design/target-module-layout (squash merge e58a44e3, 2026-06-19); worktree torn down. Re-verified from
the integration worktree before merge (-Posgi clean package green, 22 osgi modules, bench + R3 spike
exercised the substring install). Residual untracked target/ dirs at the old paths were cleaned post-merge.

## Workspace / close discipline

- Worktree `refactor/osgi-bench-testkit-naming`, base `design/target-module-layout` @70276800.
  External-worktree model; `.code-workspace` sibling carries CLAUDE_CONFIG_DIR. sops re-smudged at
  setup. MEMORY dir = `.claude/memory/`.
- Build-time only → inside [[standing-autonomy-except-runtime-config]]. Act without asking.
- CLOSE = commit everything (code AND `.claude/memory/`), build GREEN, then HAND OFF to the
  design/target-module-layout session for the squash-merge — **this session does NOT saw its own
  worktree** ([[merge-from-target-worktree]]).

## BACKLOG — systemd modules mix two naming conventions (user, 2026-06-25)

Same naming debt, still open under `osgi/systemd/`: the modules MIX domain-prefix with techno-in-head.
- `systemd-port`, `systemd-core` (NEW 2026-06-25) — correct: domain prefix.
- `cdk8s-systemd`, `dbus-systemd-edge` — wrong order: should be `systemd-cdk8s`, `systemd-dbus-edge`
  to match the artifactId == domain-prefixed-leaf rule (manifests-core, netplan-core, …).
A pure mechanical rename like the bench/testkit one above (git mv + artifactId + `<name>` + `<module>`
lines + GAV dependents + the capability-filter / classpath-substring call-sites in the edge boot tests
+ the memory `dbus-systemd-edge-spec-state`). NOT done in the specialist-distribution slice (out of
scope, and renaming pre-existing modules mid-atomic-slice would blur the diff). Own chantier when picked
up. Note the BSN changes too (`io.seedmatic.rke2lab.dbus.systemd.edge` → `…systemd.dbus.edge`?), so re-check
every Fragment-Host / Require-Bundle and the embed-capability filters that select these bundles.

See [[osgi-runtime-r3-consume-references-state]] (promoted osgi-testkit; built the substring lookup),
[[osgi-leaves-state]] (the domain-DIR rule), [[build-verification-gotchas]], [[merge-from-target-worktree]],
[[standing-autonomy-except-runtime-config]], [[dbus-systemd-edge-spec-state]].
