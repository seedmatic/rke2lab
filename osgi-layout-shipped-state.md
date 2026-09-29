---
name: osgi-layout-shipped-state
description: The osgi/ aggregator re-layout SHIPPED on feature/cluster-edge (2026-06-30, commits a05dd52c..a0f64f2a, full reactor + all tests GREEN). osgi/ is now 3 nature-groups — foundation/{domain-annotations, world-gateway, pipeline/{pipeline-port,pipeline-jgiven,pipeline-testkit,pipeline-probe,pipeline-probe-test}}, runtime/{boot, launcher, junit-testkit, bench}, domains/{doctor,cluster,systemd,manifests,netplan,unitrepo,ssh-to-age-edge} — root <modules> = 5 entries. This was the LAYOUT-FIRST increment that had to land before world-gateway 2C (2C is written on the world-gateway names). NEXT: plan 2C. See [[osgi-aggregator-layout-spec-state]] [[jgiven-dissolution-is-a-realm-change-backlog]] [[world-gateway-2c-peer-model-design]].
metadata:
  type: project
---

## What shipped (7 commits, a05dd52c..a0f64f2a)

Executed via subagent-driven-development from `wip/plans/2026-06-30-osgi-aggregator-layout.md`.
Spec: `docs/architecture/osgi/osgi-aggregator-layout-spec.adoc`. Each task gated by a green full
reactor `package -Pall-worlds` (a layout has no unit tests — a boundary that doesn't resolve is the
failing test); the USER also ran the full suite with all tests enabled → SUCCESS.

- `a05dd52c` domains/ group (6 domains + ssh-to-age-edge flat leaf)
- `1ae89a1a` foundation/ group (domain-annotations, gateway, pipeline, jgiven — moved as-is)
- `31f81da4` runtime/ group + collision-break (leaf → osgi/runtime/runtime/, temp artifactId runtime-host)
- `1f408fe7` leaf named **launcher** (user rejected the runtime-host collision-patch; FrameworkLauncher
  + BootPipeline.embedded = the ACT of launching, pairs with boot/ the decision) + 3 exec consumers wired
- `442e3894` **gateway→world-gateway** (module + package io.seedmatic.rke2lab.world.gateway.port +
  GatewayCatalog→WorldGatewayCatalog; singleton aggregator reduced; bnd type=seam kept; in-container
  tests prove the rename resolved across the realm boundary)
- `46c7cdf0` + `a0f64f2a` **pipeline regroup** (jgiven under a pipeline/ aggregator: pipeline-port +
  pipeline-jgiven + pipeline-testkit + pipeline-probe + pipeline-probe-test). SAFE half of §5.4 only —
  NO export fusion (pipeline-port exports only io.seedmatic.rke2lab.pipeline/type=seam; pipeline-jgiven a
  separate bundle exporting com.tngtech.jgiven.*); packages+BSNs unchanged. Export-fusion still deferred
  → [[jgiven-dissolution-is-a-realm-change-backlog]]. `aa74c6f9` doc/memory reconciliation.

## Two deliberate divergences from the spec (recorded, not drift)

1. The runtime leaf is **launcher**, not the spec's `runtime-host` (§5.2). Collision-patch → real name.
2. The **pipeline regroup WAS done** (spec §5.4 said dissolve but §8 said no-fusion — contradiction);
   we did the SAFE regroup and deferred only the dangerous export-fusion. Both recorded in the spec.

## The controller error worth remembering (process lesson)

Commit `46c7cdf0` recorded the git-mv renames but NOT the content edits (the implementer `git add`ed
BEFORE editing, so artifactId/relativePath/-include edits stayed uncommitted). The FIRST Task-5b
reviewer read the committed diff and correctly flagged it; I "refuted" it by reading the WORKING TREE
(which had the uncommitted edits) and wrongly called the reviewer fabricated — the re-reviewer repeated
my mistake. Caught by `git status` at Task 6 close (14 uncommitted files); fixed by committing the
completion as `a0f64f2a`. **LESSON: when a reviewer finding contradicts a green build, check the
COMMITTED state (`git show HEAD:path`), not the working tree; and run `git status` after every
implementer commit BEFORE marking a task done.** (The working tree was always correct — every build +
the user's full-test run was green — only the intermediate commit was an incomplete snapshot.)

## CLI selector changes (CLAUDE.md: unprefixed artifactId)

`-pl :runtime` → `-pl :launcher` · `-pl :gateway-port` → `-pl :world-gateway` · `-pl :pipeline` →
`-pl :pipeline-port`. Module dirs moved but artifactIds drive resolution, so inter-module deps were
transparent except where the artifactId itself was renamed (launcher, world-gateway, pipeline-port,
pipeline-jgiven, pipeline-testkit, pipeline-probe, pipeline-probe-test).
