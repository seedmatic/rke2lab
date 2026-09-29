---
name: osgi-aggregator-layout-spec-state
description: IMPLEMENTED (2026-06-30, commits a05dd52c..46c7cdf0 on feature/cluster-edge). Re-laid-out the osgi/ aggregator into 3 groups by nature (foundation/runtime/domains). Shipped layout-first before world-gateway 2C, with two deliberate divergences from the spec — the runtime leaf is named launcher (not runtime-host), and the jgiven regroup was SAFE-only (layout under pipeline/, no export fusion; fusion remains deferred as a realm-change backlog).
metadata:
  type: project
---

A design produced out-of-band (2026-06-28) while this branch worked world-gateway 2B, **IMPLEMENTED
2026-06-30** (6 commits a05dd52c..46c7cdf0 on feature/cluster-edge). Shipped layout-first, with the
spec guiding the work but two deliberate divergences recorded.

**The target layout** — stop `osgi/` mixing 6 natures in one flat `<modules>`. Three intermediate
groups + 2 flat Maven parents:

- `foundation/` (compile-time shared): domain-annotations, world-gateway (was gateway-port), pipeline.
- `runtime/` (boot/exercise): boot, runtime-host (was runtime — name collision), junit-testkit, bench.
- `domains/` (métier): doctor, manifests, systemd, netplan, cluster, unitrepo; `ssh-to-age-edge` stays
  a flat leaf under `domains/`.

**Rules:** a testkit lives WITH the bundle it tests (`testing/` is NOT a nature); an aggregator exists
iff ≥2 modules (singletons reduced — world-gateway, ssh-to-age-edge stay leaves).

**2 deliberate divergences** from the spec (user decisions, not drift):

1. **The runtime leaf is named `launcher`, NOT `runtime-host`** (§5.2 spec). User rejected
   `runtime-host` as collision-patch; chose `launcher` (the act-of-launching, pairs with boot/,
   short). Dir is `osgi/runtime/launcher/`, artifactId `launcher`; 3 exec consumers (seed-master,
   manifests-cli, netplan-cli) depend on `launcher`.
   **RENAMED 2026-07-05 → `framework-launcher`** (dir + artifactId + package
   `io.seedmatic.rke2lab.osgi.runtime` → `io.seedmatic.rke2lab.osgi.runtime.framework`), when the sibling
   `engine` → `scenario-engine` rename made both runtime leaves domain-specific rather than generic.
   Bare `launcher`/`engine` no longer exist. See [[engine-lifecycle-socle-state]].

2. **The jgiven→pipeline regroup WAS done (SAFE half), export-fusion STILL deferred**. §5.4 spec said
   "dissolve jgiven into pipeline"; §8 said "no fusion" (contradiction). What shipped: jgiven REGROUPED
   under a `pipeline/` aggregator as pipeline-port (grammar seam, was `pipeline`), pipeline-jgiven (was
   jgiven-wrap), pipeline-testkit, pipeline-probe, pipeline-probe-test — layout-only, NO export fusion.
   pipeline-port exports ONLY `io.seedmatic.rke2lab.pipeline` (type=seam), pipeline-jgiven stays a
   separate bundle exporting `com.tngtech.jgiven.*`. Packages and BSNs unchanged (jgiven name survives
   in package/BSN; only Maven artifactIds renamed). The DANGEROUS part — making the seam export
   com.tngtech.jgiven.* (two realms → LinkageError) — is STILL deferred.

**3 renames (the surgical part):** `gateway`→`world-gateway` (the door to the OSGi world; survives the
embedded→remote RSA evolution; `-port` suffix drops from the MODULE name but the bundle stays
`type=seam` and its package becomes `io.seedmatic.rke2lab.world.gateway.port`; `GatewayCatalog`→
`WorldGatewayCatalog`); `runtime`→`launcher` (actual); `jgiven-*`→`pipeline-*` (layout regroup only).

Spec: `docs/architecture/osgi/osgi-aggregator-layout-spec.adoc` (12+ C4/Mermaid figures, §5 sub-decisions,
§6 migration mechanics). Prompt (§A integrate / §B implement): `…-spec.prompt`. Absorbs the
[[jgiven-domain-into-pipeline-debt]] and the unitrepo-embed backlog (the layout tranches them). See
[[layout-skeleton-state]] [[world-gateway-document-design]] [[cdk8s-carrier-flat-jar-pattern]]
[[federated-unitrepo-p2p-design]].
