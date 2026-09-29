---
name: osgi-frontier-underpopulated-chantier
description: "★ DISCOVERY 2026-06-23 (cluster-edge session) — we believed we were running the OSGi version, but only 3 domains + 2 edges are actually installed IN the embedded Felix; everything else (jgiven, cdk8s-systemd, unitrepo-core, all the third-party) runs FLAT on the host JCL. The peer-to-peer / clone / fragment-contribution model PRESUPPOSES domains living in the framework — so populating the host/OSGi frontier is now a PREREQUISITE chantier, not part of cluster-edge. NEXT SESSION starts here, fresh."
metadata:
  node_type: memory
  type: project
---

## The trou, named (the session's key discovery)

The user believed the system already ran "the OSGi version" — domains installed in the embedded
Felix. The uber-jar (`seed-master-*-exec.jar`) inventory says otherwise: the frontier was drawn far
LOWER than assumed. Only a handful is truly in the framework; the bulk is flat on the host JCL. This
is why DS / the registry / the whole peer-to-peer mediation model felt both necessary and not — the
substrate they assume (domains as live bundles) largely does not exist yet.

## Verified inventory (uber-jar, 2026-06-23)

STAGED as bundles, truly IN the framework (`META-INF/bundles/*.jar`):
- ours: `manifests-core`, `doctor-core` (embedded THIS session, b4c9535c), `dbus-systemd-edge`,
  `ssh-to-age-edge`.
- boot stack: felix.scr, felix.resolver, osgi.service.component, osgi.util.promise/function,
  pax-logging-api/logback.
- That is **3 domains + 2 edges**. Nothing else of ours.

FLAT on the JCL — split into two kinds, DO NOT confuse them:
- **Legitimately flat (NOT the trou):**
  - the `-port` seams (`doctor-port`, `manifests-port`, `systemd-port`, `netplan-port`, `netplan-api`)
    — `type=seam`, MUST be system-exported / on the JCL, shared host↔framework. Correct by design.
  - the host itself (`controlplane/*`, seed-master, the boot machinery `osgi/boot`) — boots the
    framework, flat by nature.
- **The real trou (should be IN the framework, isn't):**
  - `jgiven-wrap` + `jgiven-probe` — jGiven is used ALL OVER production (the runbook: every
    `controlplane/pipeline/` stage, the scenarios). Its classes (`com/tngtech/jgiven`) sit FLAT in the
    uber-jar, not as a staged bundle. (I wrongly filed jgiven as "test only" earlier — it is prod.)
  - `cdk8s-systemd` — re-challenge: synthesis lib, but is it prod-runtime? flat today.
  - `unitrepo-core` (+ `unitrepo-handler-spi`) — runtime-scope dep of seed-master, consumed by
    `manifests-core/ManifestsDomainRegistry`; the seed-master pom literally says "WI-C will embed it
    intact as an OSGi bundle" (lines 54-66) — planned, never done.
  - all the third-party (byte-buddy, gson, guava, grpc, jackson, jgit, logback, pulumi, snakeyaml…)
    flat — some legitimately host (pulumi non-playable, grpc), some needed-as-imports once jgiven et al.
    move into the framework (jgiven→byte-buddy is the documented non-trivial case in jgiven-wrap/bnd.bnd).

## Why this is a chantier of its own (not a cluster sub-step)

This is a host/OSGi frontier RE-POPULATION — sibling of [[osgi-runtime-migration-state]], not of
cluster. Per domain it means: decide it belongs in the framework, add `embed; type=model|edge`, and
VERIFY it RESOLVES its third-party imports inside Felix (not just on the test classpath where jgiven
runs today) — without breaking the seams or the host. jgiven-wrap is the hard one (byte-buddy + the
report/asciidoc deps). Do NOT blanket-add the namespace to every bnd: fixtures/test
(`bench/*`, `*-test`, `junit-testkit`, `jgiven-probe-test`) and the seams stay as they are.

## Order for the NEXT session (fresh context)

1. Build the EXHAUSTIVE verified table: every `osgi/` module → prod|test usage, framework|flat today,
   verdict (embed | seam-stays-flat | host-stays-flat | test). Read, don't assume (I mis-categorized
   twice this session: portée, then jgiven).
2. Embed the real holes one at a time, reactor-green + staged-in-uber-jar check after each (the
   doctor-core recipe, b4c9535c, is the template): unitrepo first (WI-C, has a consumer), then jgiven
   (verify byte-buddy resolution), then re-challenge cdk8s-systemd.
3. THEN resume cluster on a frontier that is actually populated.

## How this reframes everything from this session
- The fragment-contribution PROOF (committed ee072dab) still holds and is still the green gate.
- "DS adds nothing" was true for the FLAT host bootstrap, false for the in-framework / clone target —
  the confusion came from the frontier being under-populated. See [[fragment-contribution-mediation-model]].
- doctor-core embedding (b4c9535c) was the FIRST correct step of THIS chantier, done early by luck.

## 2026-07-07 — this chantier is now the BLOCKER of the BDD-pipeline migration

The ClusterSeed BDD migration reached a STABLE PLATEAU (Task 8 committed `f99e1b7b2` on
`feature/cluster-seed-scenario`, NOT yet integrated into design/pre-integration; the fluent world
deleted, build green). The user then SUSPENDED the rest — the 6 other pipelines AND the offline
exercise of ClusterSeed — on a project-sequencing decision that is NOT derivable from code/git:

*we cannot exercise a pipeline fully OFFLINE until every domain it touches lives in the OSGi world
with a well-defined fake.* A fake is a fragment `@Component` attached to a **host bundle** ([[per-domain-osgi-fakes-chantier]]);
if a domain has no OSGi bundle, there is no host to attach a fake to. Verified 2026-07-07:
**`incus` has NO module anywhere under `osgi/`** — provisioning still lives host-side
(`IncusResourceBootstrap`, `incus exec` in `SeedSystemdAdapterEndpointGate`). That is the wall the
`NestedRunbookTest` `@Disabled` debt and the deferred `ResourcesStageTest` both hit (no offline
BootstrapResult because incus provisioning is host-side and un-faked).

So the forward order is now: **this frontier chantier + the incus external edge FIRST**, then
[[per-domain-osgi-fakes-chantier]] (a fake per newly-in-framework domain), and ONLY THEN resume the
BDD offline work (re-enable NestedRunbookTest, migrate the other 6 pipelines). The incus edge is the
top priority hole — above unitrepo/jgiven from the 2026-06-23 order — because it is what blocks the
offline pipeline TODAY. See [[external-edges-chantier-handoff]] (incus/cluster/host-fs edges remain)
and [[incus-edge-placement-backlog]].

See [[fragment-contribution-mediation-model]] [[external-edges-chantier-handoff]]
[[osgi-runtime-migration-state]] [[bundle-on-jcl-is-wrong-classpath]] [[unitrepo-design-unification-state]]
[[per-domain-osgi-fakes-chantier]] [[cluster-seed-execution-state]] [[incus-edge-placement-backlog]].
