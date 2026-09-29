---
name: external-edges-chantier-handoff
description: "RESUME POINT (2026-06-22) for the external-edges chantier — replicating the <target>-edge pattern across the 6 system boundaries. INTEGRATED into design/pre-integration: pulumi-edge (template), the port/edge/domain doc, osgi-boot-single-source, and ssh-to-age-edge @ 4d99f599 (squash, build GREEN). NEXT = osgi-boot-single-source v2 (the 3 boot-registry improvement requests left by ssh-to-age-edge) — REUSE the feature/ssh-to-age-edge worktree, reset --hard to the pre-integration tip first. Then dbus-systemd (host) → incus/cluster/host-filesystem (playable). Integrate into design/pre-integration, NEVER main (main = final destination only)."
metadata:
  node_type: memory
  type: project
---

## What this chantier is

Replicate the *external edge* extraction (the `<target>-edge` template) across every system boundary,
per `docs/architecture/patterns/frontier-playability-model.adoc` +
`docs/architecture/patterns/port-edge-domain-ownership.adoc`. An edge = a domain/consumer's face toward
a system OUTSIDE ours, mutualised by target, reached through a port the *consumer* owns (the edge
implements it, owns no port). The 6 targets: pulumi, ssh-to-age, dbus-systemd, incus, cluster, host-fs.

## State (2026-06-22) — design/pre-integration @ 4d99f599 (ssh-to-age-edge integrated)

SHIPPED + integrated into design/pre-integration:
- *pulumi-edge* (the TEMPLATE) — `host/pulumi-edge` consolidates every Pulumi contact (read+write),
  the stray `PulumiInterventionLedgerWriter` rapatriated. Handoff
  `docs/architecture/osgi/pulumi-edge-handoff.adoc` = the extraction recipe.
- the port/edge/domain doc (3 roles, one verb each: domain CALLS, port DECLARES, edge PROVIDES; the
  port is owned by the consumer, the edge owns none; world is DERIVED from the consumer's world).
- *osgi-boot-single-source* — the embedded-bundle hand-list is GONE. Each embeddable bundle declares
  `Provide-Capability: io.seedmatic.rke2lab.embed; type=model`; `OsgiRuntime.scanEmbeddedModelBundles()`
  reads `META-INF/bundles/` manifests and installs whatever declares it (filter on a CAPABILITY, never
  names — names slide on rename). The Java literals are gone: `SeedRuntime.bootingEmbedded()` takes no
  arg, the 3 call-sites pass no jar name. Build green (3 execs + their `EmbeddedBundlesBootTest`). Handoff
  `docs/architecture/osgi/osgi-boot-single-source-handoff.adoc`; the boot-trap NOTE in
  port-edge-domain-ownership.adoc is flagged→resolved. (Open sub-point in its handoff: the shade-`exclude`
  can't be scanned (shade runs pre-boot) — derived from staging, or documented as the two pom faces of one
  declaration.) This was the PREREQUISITE that unblocked ssh-to-age-edge.
- *ssh-to-age-edge* — the FIRST external edge in the OSGi WORLD (squashed @ 4d99f599; re-verified at the
  integrated tip: seed-master 68, manifests-core 24, CLIs 1+1, 0 fail; 1 skip = preexisting `@Disabled`
  `RealGraphInjectionTest`, unrelated). `osgi/ssh-to-age-edge` (`ProcessBuilderSshToAgeConverter @Component`)
  provides the consumer-owned `SshToAgeConverter` seam + `SopsAgeMaterial` profile in `manifests-port`; NO
  `-core`, NO ssh-to-age-port. `DefaultManifestSynthesisService` runs a PRE-SYNTHESIS pass
  (`SopsAgeMaterialResolver`: typed Jackson read of keys.yaml — regex GONE — then the converter), binds
  `SopsAgeMaterial` on `ManifestSynthesisContext` (profile holder, NOT ManifestsUnitContext);
  `SopsAgeSecretManifestsUnit` only renders. `@Reference` MANDATORY → fail-fast. Packaging: 1 staging
  artifactItem + `Provide-Capability: …embed; type=edge; edge=ssh-to-age` per its bnd (cores retrofitted to
  `type=model; model=<id>`). Handoff `docs/architecture/osgi/ssh-to-age-edge-handoff.adoc`. Two traps closed:
  `locateOnClasspath` matched a path substring (worktree NAMED ssh-to-age-edge poisoned every entry) → match
  the leaf/module dir; the host-seam test named its bundles → now `OsgiRuntime.embeddableBundlesOnClasspath()`
  discovers them by the SAME embed capability as the boot scan. See [[prefer-osgi-edge-three-reasons]].

## OPEN — order matters

1. **refactor/osgi-boot-alignment (NEXT — worktree CREATED at `rke2lab.d/refactor/osgi-boot-alignment`,
   branched on 4d99f599, sops re-smudged, `.code-workspace` written).** ONE chantier carrying ALL the
   follow-up ssh-to-age-edge surfaced — the 3 boot-registry requests (`ssh-to-age-edge-handoff.adoc`
   §"Improvement requests") PLUS the test-logging chantier it spawned ([[test-logging-chantier]]). User's
   call to fold them: the common thread is **bundle DISCOVERY — locate by capability not name, ONE `locate`
   not two** — aligning the TEST boot topology (`FelixFrameworkExtension`) onto the PROD one (`OsgiRuntime`)
   that ssh-to-age just straightened. (NB: test-logging.md said "distinct chantier, own branch" — consciously
   overridden here.) North-star throughout: "read the real artifact, never maintain a parallel list."
   Ordered prod → test → visibility → close-out:
   - **(1) Install table — `OsgiRuntime`, prod.** Two columns of one question (*how does it know what to
     install*): **ours → one capability reader** (unify `scanEmbeddedModelBundles()` source=staged
     `META-INF/bundles/` + `embeddableBundlesOnClasspath()` source=reactor classpath — same
     `io.seedmatic.rke2lab.embed` cap, two duplicated loops → one reader, source as a param); **3rd-party →
     a typed `enum BootStackJar(artifactId, stagedFileName)`** for the 4 loose `…_JAR` constants
     (pax-logging-api/logback, felix.scr/resolver) so `embeddedBootStack()` iterates. HONEST FRONTIER (real
     jars): these are NOT ours — pax-logging-api declares NO `Provide-Capability` — so they STAY named, not
     by capability. The split is correct, not an inconsistency to erase.
   - **(2) Testkit alignment — `FelixFrameworkExtension` (osgi/junit-testkit).** Fix the TWIN substring bug
     in `locateBundle` (~L238, same `path.contains(artifact)` ssh-to-age just fixed in `locateOnClasspath`)
     → leaf/module-dir match; wire its discovery onto the same capability model as (1). The heart of "the
     alignment."
   - **(3) Log visibility — junit-testkit + BOM** ([[test-logging-chantier]]): jul→slf4j bridge
     (`SLF4JBridgeHandler`+`LevelChangePropagator`), pax-logging where the `FelixFrameworkExtension` topology
     lacks it, a Jupiter level extension (idiom of `GrpcChannelNoiseCapture`) for per-test SCR/felix levels;
     CONFIRM whether the bridge makes `GrpcChannelNoiseCapture` obsolete before retiring it.
   - **(4) Close-out #3:** the shade-`exclude` ↔ staging-`artifactItem` duplication (two pom faces of one
     declaration; shade runs pre-boot so it can't derive from a runtime scan). Mojo, or document as
     irreducible — state it once, authoritatively.

2. **The remaining edges.** SHIPPED so far: pulumi (template), ssh-to-age (first OSGi), **dbus-systemd**
   (edge #3, SHIPPED 2026-06-23 — first BOTH extracted AND migrated to OSGi; see
   [[dbus-systemd-edge-spec-state]]). LEFT: **incus / cluster / host-fs**. Use the sonde-vs-porte lens
   ([[dbus-systemd-edge-spec-state]] holds it):
   - **cluster-edge = a SONDE** (not a porte): `ClusterReadinessProbe`→`Observation` + `ClusterSpecialist`
     ALREADY exist in doctor; just extract the kubectl contact (`ClusterBootstrapReadinessVerifier`) out
     of seed-master. NO `cluster-core`. Name STAYS `cluster` (one k8s cluster — unambiguous; k8s-cluster
     rename rejected).
   - **incus-edge** — SDK already under `sdks/incus/`; playable. Brainstorm first.
   - **host-fs** — likely NOT a sonde (no fs specialist/observation in doctor); the seed-master fs touch
     is DIFFUSE. Brainstorm whether there's a single extractable contact at all, or host-internal plumbing
     that should NOT become an edge.
   Each still needs a short brainstorm (contact? port-by-concern? sonde/porte? snapshot→fact?) — the
   pattern is now established + documented (the keystone section of port-edge-domain-ownership.adoc).

## Working rules (learned this session)

- *Commit per step, squash at integration.* Each volet/step that builds GREEN gets its OWN commit
  with a detailed body (the detail feeds the eventual squash message). These per-step commits are
  bisection + rollback points DURING the work; they are NOT the integration history — every chantier
  squash-merges into design/pre-integration (see the "squash of …" commits in the log). So commit
  freely after each verified-green step; don't hoard changes into one big commit, and don't fret about
  intermediate-history tidiness — the squash handles that. NEXT SESSIONS: do the same.
- *Check the workspace before integrating* — the jGiven "merge on main" episode was because the active
  workspace was main, not pre-integration. 1 workspace = 1 worktree = 1 conversation.
- Integrate into `design/pre-integration`, NEVER `main` (main = final destination, only when the whole is
  done). Branch each feature DIRECTLY off design/pre-integration → squash-merge has no false conflicts
  (the doctor merge exploded because design & refactor were independently rebased).
- Never `mvn install`; verify by reactor `-am clean package -Dmaven.build.cache.skipCache=true
  -DskipTests=false` + count surefire; no @Deprecated/shim (delete the old path same change); each module
  keeps its `<description>`; no runtime mutation.

See [[doctor-internal-edge-debt]] (the prior chantier, integrated) [[jgiven-osgi-testkit-shipped]]
[[osgi-system-export-resolution-only]] [[osgi-runtime-r4-boot-seam-state]].
