---
name: osgi-cleanup-slice-state
description: "REFACTOR slice (worktree refactor/osgi-cleanup, off origin/design/target-module-layout @4c2631f8, set up 2026-06-19): the OSGi-cleanup increment — re-place the contract modules host/→osgi/ beside their impl facet + bundle-ify them + per-package @Version across ALL osgi/ + turn on bnd baselining. Bundles ONE coherent OSGi-hygiene unit of work decided by the user ('on demarre un nouvel increment de travail pour le cleanup OSGi'). The contracts shipped as PLAIN JARS in host/ (rename 4c2631f8) — that re-confirmed the WRONG placement (purity-axis + hexagonal-DIP error: the PORT belongs to the DOMAIN osgi/, not the adapter host/). Two design cartos fully specify it: [[contract-placement-and-versioning-carto]] (placement + independent per-contract version) and [[osgi-package-versioning-carto]] (generalise @Version to every exported osgi/ package + baseline). ★ SHIPPED to design/target-module-layout (squash merge 2026-06-19); worktree torn down. The slice GREW beyond the brief: `contract`→`port` rename ([[rename-contract-to-port-state]]) + `unitrepo-handler-api`→`-spi` + the `realgraph` fixture tombstone (duplicated-source-of-truth drift, deleted at R4). Build-only, -Posgi green (35 modules / 18 tests / 12 reports / 0 skipped), the port import is now VERSIONED. NO -Plive."
metadata:
  node_type: memory
  type: project
---

## ★ SHIPPED 2026-06-19 (squash merge to design/target-module-layout; worktree torn down)

Delivered all four moves below — with the contract→**port** rename layered on (the user's call mid-slice:
`contract` is too generic, `port` names the hexagonal nature; see [[rename-contract-to-port-state]] for
the taxonomy `-port`/`-spi`/`-api`). So the re-placement landed as `osgi/manifests/manifests-port`,
`osgi/netplan/netplan-core`+`netplan-port` (netplan promoted FLAT→domain-dir), `osgi/systemd/systemd-port`
(ex-`systemd-contract`, also killing the collapsed `systemdcontract` token + parasitic `.api`), and
`unitrepo-handler-api`→`unitrepo-handler-spi`. `@Version("1.0.0")` graved on every exported osgi/ package;
`bnd-baseline-maven-plugin` wired in `osgi/bundle-parent` with `failOnMissing=false` (no-op until a release
is deliberately frozen — see [[osgi-baseline-install-discipline]] for the deliberate-install model + the
two-orthogonal-coordinates insight that settled open decision #2). Re-verified on integration before merge:
`manifests.port;version="[1.0,2)"` (the lone unversioned import from the carto origin is GONE), 12 surefire
reports / 18 tests / 0 skipped. The two open decisions were both resolved with the user: netplan aggregator
shape (domain-dir + `-core`/`-port`), and the baseline-vs-SNAPSHOT question (failOnMissing=false now,
release-driven baseline repo a future slice). BSN-deducibility hors-bench also folded in
([[java-cleanup-backlog]]). Atlas update (port now in osgi/, versioned) + flipping the two cartos to SHIPPED
remain as design-session follow-ups.

## Mandate (read the two cartos FIRST — they are the spec)

This slice executes, as ONE merge, the design settled across two carto notes. Read both before
touching code:
- [[contract-placement-and-versioning-carto]] — WHERE the contracts go + per-contract independent
  versioning (the settled TARGET section is authoritative).
- [[osgi-package-versioning-carto]] — generalise per-package `@Version` to every exported osgi/
  package + turn on bnd's baseline check.

Nothing here overrides those; this note is the work-list + the open decisions to settle at setup.

## The four moves (one coherent slice)

1. **Re-place the contracts host/ → osgi/, each BESIDE its impl facet by domain.**
   - `manifests`: already a domain dir → `git mv host/manifests-contract osgi/manifests/manifests-contract`
     (drops in as sibling of `manifests-core`; no restructuring).
   - `netplan`: FLAT today (`osgi/netplan` IS the bundle). Adding a contract sibling FORCES the
     FLAT→domain-dir promotion: promote `osgi/netplan` → domain dir with aggregator, rename the impl
     `netplan` → **`netplan-core`** (uniform `-core` suffix, like `manifests-core`/`unitrepo-core`), add
     **`netplan-contract`** beside it. Fan-out: the netplan→host blueprint feed, flake, netplan-cli, any
     GAV referencing the bare `netplan` artifact.
2. **Bundle-ify both contracts** — add `bnd.bnd` (Export-Package the contract packages) +
   `package-info.java`. They become real OSGi bundles at BUILD time, delivered FLAT at runtime via
   R4's `system.packages.extra` (NOT `installBundle` — validated against the osgi.core archetype).
3. **Per-package `@Version` across ALL osgi/** (not only the contracts). Author `package-info @Version`
   on every EXPORTED package (the Export-Package sets from the audit: `manifests`, `netplan`,
   `unitrepo.core`, `unitrepo.handler`, `systemdcontract.api`, `cdk8s.systemd`, the contract packages).
   Independent semver per package, DECOUPLED from the `0.1.0-SNAPSHOT` reactor GAV. Start each at a
   considered version — contracts at `1.0.0` (fresh published API); pick per-package for the rest. Bench
   is disposable → skip.
4. **Turn on `bnd-baseline-maven-plugin`** in `osgi/bundle-parent` so a breaking export without a major
   bump FAILS the build (machine-enforcement; sibling to the -Werror/@NonNullByDefault backlog items).

## ★ TWO open decisions to settle WITH the user at slice start (do NOT guess)

1. **Exact osgi/ destination paths** — confirm `osgi/manifests/manifests-contract` and the netplan
   promotion shape (`osgi/netplan/` aggregator name + `netplan-core` + `netplan-contract`). The carto
   proposes these; the user should confirm the aggregator artifactId before the git-mv fan-out.
2. **What the baseline compares against in a SNAPSHOT-only project.** bnd-baseline needs a "last released
   jar" to diff. There is no release repo. Options: previous reactor build / a pinned baseline repo /
   `failOnMissing=false` to bootstrap (first run has no baseline → seeds it). Needs a SHORT carto of its
   own before enabling — do this design step first, then enable. (Per [[osgi-package-versioning-carto]]
   §Sequencing, "decisions owed".)

## Sequencing note

Per [[osgi-package-versioning-carto]]: this is HYGIENE, not on the R4 critical path — but it UNBLOCKS
R4 cleanly (R4's `system.packages.extra` must list the contract packages WITH version, which only exists
once bundle-ified here). So land this BEFORE R4 boot-seam coding. Bench-BSN-drift
([[java-cleanup-backlog]]) is a SEPARATE backlogged slice, unaffected.

## Workspace / close discipline

- Worktree `refactor/osgi-cleanup`, base `origin/design/target-module-layout` @4c2631f8 (pushed before
  setup). External-worktree model; `.code-workspace` sibling carries CLAUDE_CONFIG_DIR → the worktree's
  own `.claude`. sops re-smudged at setup (`.ndh-ssh.d/keys.yaml`; schema's `ENC[` are comment text).
- Build-only → inside [[standing-autonomy-except-runtime-config]]. Act without asking on code; ASK on the
  two open decisions above.
- Validation: FULL `flox activate -- ./mvnw clean package -Posgi -Dmaven.build.cache.skipCache=true
  -DskipTests=false` from the worktree; COUNT surefire reports ([[build-verification-gotchas]]) — baseline
  is the rename merge's 34 modules / 18 tests / 12 reports / 0 skipped (module count rises with the new
  contract bundles + netplan promotion). Generated manifests: the contract import must now be VERSIONED
  (`io.seedmatic.rke2lab.manifests.contract;version="1.0"`) — that is the behavioural proof of the
  re-placement (the lone unversioned import from the carto origin is gone). NO -Plive.
- CLOSE = commit code AND `.claude/memory/`, build GREEN, then HAND OFF to the design/target-module-layout
  session for the squash-merge — this session does NOT saw its own worktree ([[merge-from-target-worktree]]).
  After merge: update the atlas (contract now in osgi/, versioned) + flip the two cartos to SHIPPED.

See [[contract-placement-and-versioning-carto]], [[osgi-package-versioning-carto]] (the spec),
[[rename-bridge-to-contract-state]] (the prior slice that shipped the name + plain-jar placement this
fixes), [[osgi-runtime-r4-boot-seam-state]] (what this unblocks), [[build-verification-gotchas]],
[[merge-from-target-worktree]], [[standing-autonomy-except-runtime-config]].
