---
name: osgi-runtime-r4-boot-seam-state
description: "DESIGN/CARTO for IMPL slice R4 (the architectural lift): boot Felix inside seed-master's Pulumi callback + wire the host consume-seam onto the registry. Read-only carto done 2026-06-19 on integration @4d5521e4 — NOT yet coded, NO worktree yet. ★ KEY FINDING: the seed-master exec-jar SHADES manifests-core/netplan FLAT (classes merged, ServicesResourceTransformer fuses META-INF/services, ManifestResourceTransformer rewrites ONE manifest → the bundles' OSGI-INF/Service-Component are drowned). And Pulumi.yaml launches the program via `binary:` = ONE exec-jar (not a -cp with a bundles/ dir). So Felix CANNOT installBundle() separate bundle entities in the deployed process — the R1-R3 reactor-classpath install pattern does NOT carry to the shaded deployment. This is THE knot R4 must solve. ★ DECISION TAKEN (user principle 2026-06-19): self-contained artifact — EMBED the osgi/ bundles INTACT inside the seed-master exec-jar (not shaded flat, not an external dir), Felix extracts+installs them at boot; the environment supplies only config. Proof path is pulumi PREVIEW (a dry-run, in-scope per CLAUDE.md — NOT -Plive up), which really boots Felix in the Pulumi callback without mutating master. Cap decision still settled: go to the runtime. NEXT = spin the R4 worktree with this note as its brief."
metadata:
  node_type: memory
  type: project
---

## Why this note exists

R4 is the architectural lift of the OSGi runtime migration (spec §5 + §4). Bigger and riskier than
R1-R3, so the user asked for a read-only carto pass BEFORE coding (the same discipline that caught the
manifests-core rename and the R3 dual-path). This note records what the carto found and the design
choices it forces; it becomes the R4 worktree's startup note once the packaging decision is taken.

## The proof path — pulumi PREVIEW, not -Plive up (user reframing, 2026-06-19)

The spec gated R4's final proof on `-Plive` (real `pulumi up` against master). The user reframed: a
`pulumi preview` (dry-run) REALLY boots Felix inside the Pulumi callback, runs SCR, exercises the host
consume-seam over gRPC to the engine — WITHOUT mutating master. CLAUDE.md explicitly allows `pulumi
preview` directly ("dry-runs like pulumi preview are fine to run"). So R4 validates in two in-scope
steps: (1) embedded-Felix host-scope test (seam wires + resolves, like R1-R3); (2) a real `pulumi
preview` (the boot in the actual Pulumi callback). The real `pulumi up` (provision/update master) stays
the USER's gesture — now an optional final confirmation, no longer a slice prerequisite. The `-Plive`
frontier dissolves; R4 need NOT be split into R4a/R4b.

## ★ THE KNOT — how do the osgi/ bundles exist as installable bundles in the deployed process?

Three grounded facts (integration @4d5521e4):

1. **seed-master shades the bundles FLAT.** `exec/seed-master/pom.xml` depends on `manifests-core`,
   `netplan`, `systemd-contract`, `unitrepo-core` and shades them into one `-exec.jar`. The shade
   `ServicesResourceTransformer` fuses all `META-INF/services`; the `ManifestResourceTransformer`
   writes a SINGLE MANIFEST.MF (host's). → the bundles' own `Service-Component:` headers and the
   per-bundle manifests are gone; `OSGI-INF/*.xml` classes are present but no longer a bundle entity.
2. **Pulumi launches ONE binary.** `Pulumi.yaml` → `runtime: java, options.binary:
   exec/seed-master/target/seed-master-0.1.0-SNAPSHOT-exec.jar`. The Pulumi Java runtime runs ONE jar,
   not a `-cp dir/*`. The release path (`nix run .#deploy`) swaps that `binary:` to the nix-store jar.
3. **The R1-R3 install pattern is reactor-only.** `FelixFrameworkExtension.install(artifact)` finds a
   bundle by scanning `java.class.path` for a jar/`target/classes` dir carrying a manifest — works in
   the reactor TEST classpath, but in the shaded exec-jar there is no separate bundle artifact to find.

So Felix-in-the-process cannot `installBundle(file:…)` a `manifests-core` bundle that no longer exists
as a discrete entity. **R4 must choose a packaging that gives the running process installable bundles.**

## ★ DECISION (user principle, 2026-06-19): self-contained artifact — bundles EMBEDDED INTACT

User principle: *the runtime should EMBED every component it needs; the environment supplies only
CONFIGURATION. A self-contained artifact is a health sign and makes the system more deterministic.*
This discriminates the options:

- **CHOSEN — embed the osgi/ bundles INTACT inside the seed-master exec-jar, install at boot.** Instead
  of the shade FUSING `manifests-core.jar`/`netplan.jar` flat (which destroys them as bundle entities),
  package them UNCHANGED as internal resources of the seed-master jar (e.g. under `bundles/` inside the
  jar, each with its own MANIFEST + OSGI-INF preserved). At boot, Felix extracts them to a temp dir and
  `installBundle(file:…)`. ONE artifact carries everything; the environment provides only config
  (`PULUMI_*`, `.secrets`, the stack). `Pulumi.yaml`'s single `binary:` is UNCHANGED, and NO external
  `bundles/` dir is provisioned — so this is MORE deterministic, not less. The host + Felix + felix.scr
  stay flat (gRPC stays flat, invariant #1565); only the pure model bundles are embedded-intact.
  This is a refinement of the old option (C), reframed by the self-containment principle.
- *Rejected — (A) bundles in an EXTERNAL dir beside the jar:* violates the principle (the environment
  would supply a bundle dir alongside the artifact; two things to provision + keep in sync; path
  resolution differs between preview-at-repo-root and deploy-at-store-path). Less deterministic.
- *Rejected — (B) Felix reads the flat classpath:* the drowned Service-Component/OSGI-INF must be
  reconstructed, fights the shade transformers, erodes bundle isolation. Not self-describing.

## ★ DECISIONS REFINED on the worktree (2026-06-19, implementation carto)

Three decisions taken WITH the user while cartographing the real code on the R4 worktree — they refine
(do not contradict) the embed-intact decision above:

1. **Path 2 — PROVIDE THE BUNDLES TO THE FRAMEWORK** (not path 1, everything via system-export). The
   user chose to split seed-master's classpath into two worlds rather than serve the model flat: a minimal
   flat HOST world (Felix + Pulumi + grpc-netty + seed-master code + the shared `-port` packages + the
   not-designed-for-OSGi jars) and the OSGi world (intact bundles Felix installs + resolves against each
   other). Embed mechanics are trivial (maven-dependency-plugin `copy` → `META-INF/bundles/*.jar` resources;
   pom already does the analogous `unpack` for manifests-d). Detail in [[osgi-system-export-resolution-only]].
2. **Criterion = DESIGNED-FOR-OSGi, not passive-vs-active.** What goes to `system.packages.extra` is "a jar
   never conceived as a bundle" (`org.cdk8s`, `software.constructs` — jsii, unversioned imports → flat is
   their natural place). Everything designed for OSGi — our `-core`/`-port` AND already-bundle libs
   (jackson, snakeyaml, slf4j, commons-compress, versioned) — goes into the bundle world. A system export
   carries CLASSES only, never the bundle's OSGi behaviour (SCR/capabilities/metatype/lifecycle) — the
   invariant the user asked to document ([[osgi-system-export-resolution-only]], spec §4.1 IMPORTANT box).
3. **ORDER FLIPS to B → A → C.** Path 2 wants `manifests-core` to retire ENTIRELY into the bundle world,
   but the host still imports 3 impl types from it — `ManifestYaml`, `NodeEnvContributorRegistry`,
   `FloxRuntimeAssets` — which are EXACTLY the 3 Milestone-B inversions. While those leaks exist
   manifests-core would have to be flat (for the host) AND a bundle (for SCR) = the split-package hazard.
   So B (cut the 3 leaks → host depends only on `-port`) must land BEFORE A (boot + retire manifests-core
   into the bundle world). Confirmed by carto: seed-master's osgi-world imports are ALL `-port` except
   those 3 impl types. The brief's A→B→C order held only under path 1; path 2 makes B a precondition of A.
   The go/no-go behavioural proof (`pulumi preview`) stays at the END, on the bundle-clean seam.

## ★ MILESTONE B reshaped — 3 COUTURES, not 3 isolated classes (carto + user, 2026-06-19)

The user flagged the right symptom: `ManifestYaml` used on BOTH sides of the frontier is a bad smell —
it means **the host is doing manifest DESCRIPTION work, which belongs to the manifests world** ("OSGi
describes, host actualises", [[hub:model-substrate-alignment]]). So the 3 wrong-direction leaks are NOT 3
classes to relocate; they are 3 distinct seams to re-sew:

- **Couture 1 (B1+B2 together):** `IncusResourceBootstrap.RuntimeEnvControlplaneOverlayWriter.write()`
  (one caller, :2749) BUILDS + RENDERS a ConfigMap overlay and orders the contributors — host-side
  manifest synthesis. It aggregates BOTH leaks: `ManifestYaml.writeDocument` (B1) AND
  `NodeEnvContributorRegistry.forServiceLoader()` (B2) live in that one method. DECISION (user): repatriate
  it into manifests-core as a `@Component` behind a port; the host calls it via the registry and only
  handles objects/results. B1+B2 fall together. Returns a "registry snapshot" `Map` (ordered contributors
  + aggregated vars) — shape to fold into the port's result type.
- **Couture 2 (the 2nd `ManifestYaml` use — a READ):** `CloudConfigSecretRenderer.parseYamlDocument()`
  (:3055) PARSES a produced manifest YAML into a `Map` to extract a secret payload — legitimate host
  ACTUALISATION, but needs the deterministic parser (64 MiB limit, coercion). DECISION (user): the
  deterministic YAML format IS a guarantee of the manifests world, NOT a generic lib — so even a READ goes
  through the manifests service. The synthesis port (or a dedicated manifest-document port) ALSO exposes
  deterministic parse/read ops; `ManifestYaml` stays 100% internal to manifests-core. Zero host→impl leak.
  (Rejected: moving `ManifestYaml` into manifests-port — would pollute the pure contract with jackson+
  snakeyaml deps AND keep a static call on the host, against the instance-passing discipline.)
- **Couture 3 (B3 — `FloxRuntimeAssets`):** different motif — the host NEW-s an impl: `SystemdTarget:65`
  `FloxRuntimeAssets.builder().build()`, read back at `IncusResourceBootstrap:2302` via
  `getFloxRuntimeAssets()`. Inversion: the host RECEIVES the assets (via service/port), does not construct
  the impl. (Not yet cartographed in depth — own commit.)

## ★ SCOPE-DEMOTION belongs AFTER C — found via the user's "compile vs runtime?" question (2026-06-19)

The user asked whether keeping `manifests-core` at `compile` scope in seed-master is dangerous (path 2
wants it on the RUNTIME classpath, loaded by Felix, NOT compiled against). Correct instinct — demoting it
to `runtime` scope would MACHINE-ENFORCE path 2 (the compiler then forbids importing an impl type). But the
carto found the demotion can't happen yet:

- seed-master MAIN is clean — compiles ONLY against `-port` (B holds for production code). ✓
- BUT the test fixture `exec/seed-master/.../unitrepo/realgraph/ManifestsUniverse.java` still imports 14
  manifests-core IMPL types (`ManifestsDomainRegistry`, `ManifestsDomain`, `ManifestsUnit`, the 10
  `*DomainRegistrar`). That fixture is EXACTLY what Milestone C deletes (`@Deprecated(forRemoval=true)`).

So the order is: **C (delete realgraph) THEN demote manifests-core compile→runtime** — the demotion is the
final move that machine-enforces "host = ports only, manifests-core is a runtime bundle". The rest of A
(OsgiRuntime boot, seam, host-scope test) does NOT depend on the scope — manifests-core stays on the
classpath at `compile` meanwhile, so the host-scope test resolves it fine. `runtime`/`testkit`/`felix.scr`
test-deps were added to seed-master's pom for the host-scope test; the manifests-core scope is left at
`compile` until after C. (Side-find: the realgraph fixture is 8 files, not the brief's 7 — recount at C.)

**The build change this implies (for the R4 worktree to work out):** the seed-master shade must STOP
pulling `manifests-core`/`netplan`/(the pure bundles) into the flat classpath, and instead stage their
INTACT jars as resources inside the exec-jar. Host-only deps (pulumi, grpc-netty, incus, cdk8s used by
the host, jackson, felix.framework, felix.scr) stay flat. The bnd-emitted bundle metadata R1-R3
produced is exactly what makes the embedded jars installable — it pays off here. Verify the flake
`seedMasterJar` derivation still produces ONE jar (it copies `seed-master-*-exec.jar` — unchanged if
the bundles are embedded INSIDE it, not staged beside it).

## What is already known + easy (the non-knot parts)

- **Where Felix boots:** `ApplicationPipeline.run()` calls `Pulumi.run(ctx → body)` when
  `PULUMI_MONITOR` set (`ApplicationPipeline:39`). The composition root is inside that callback. Felix
  `init().start()` + install bundles + start felix.scr goes there, BEFORE the bootstrap pipeline runs.
- **The host seam (3 sites):** `IncusResourceBootstrap.singleSpiProvider(Class<T>)` (line 644) is
  called at :518 (ManifestSynthesisService) and :524 (ManifestExplodeService);
  `EntryGatePolicyEnforcer.enforceManifestUpdateGate` (:50) loads `ManifestUpdateGate` (the R3-deferred
  gate). All three move from `ServiceLoader` to `getServiceReference`/`getService` on the booted
  framework's BundleContext, typed via `system.packages.extra` exporting the API package from the system
  bundle (the bench/R1 single-exporter trick). `IncusResourceBootstrap` is built at `IncusStage:23`,
  inside the pipeline → it needs the BundleContext threaded in (constructor or a host-side Service
  accessor). `singleSpiProvider`'s "exactly one" check maps to a 1..1 registry lookup.
- **gRPC stays flat (invariant, do NOT break):** issue #1565 — gRPC transport discovery is a
  ServiceLoader on the TCCL that bundle isolation breaks; the `spike/osgi-grpc-host` test proved a
  TCCL-pinned host seam works. That spike is NOT in the tree (was a throwaway branch) — its LESSON is
  the constraint: no gRPC type ever crosses into a bundle; the seam reads model services only. The host
  (IncusResourceBootstrap/EntryGatePolicyEnforcer) must NOT become bundles.
- **Dual-path from R3:** `NodeEnvContributorRegistry.forServiceLoader()` + the framework-less `new`
  callers still work; R4 does not retire ServiceLoader (that's R5). R4 ADDS the booted-Felix consume
  path at the host seam.

## Validation (when coded)

- Embedded-Felix host-scope test (reuse osgi/testkit) proving the seam resolves the 3 services from a
  booted framework. Then a REAL `pulumi preview` from the repo root (flox) — boots Felix in the Pulumi
  callback, SCR publishes, the seam consumes, preview completes with no master mutation. COUNT surefire
  ([[build-verification-gotchas]]). The `pulumi up` confirmation is the user's, optional.

## Workspace / next step

- NO worktree yet — this is the design/carto output, sitting on integration @4d5521e4. The packaging
  decision is now TAKEN (embed bundles intact, self-contained jar — see the DECISION section). Next:
  spin the R4 worktree off design/target-module-layout with this note as its startup brief, and code
  there (the design session does design, not impl).
- The packaging change touches the seed-master shade + verifies the flake derivation still emits ONE
  jar; `Pulumi.yaml` stays unchanged (single `binary:`), so the runtime-adjacent surface is minimal and
  the self-containment principle keeps the environment to config-only.

★ DELETE at R4 — `exec/seed-master` test package `io.seedmatic.rke2lab.unitrepo.realgraph` (7 files:
`ReactorModuleCatalog`, `ManifestsUniverse`, `UniverseBuilder`, their 3 `*Test`s, `RealGraphResolutionTest`,
plus `package-info`). All carry `@Deprecated(forRemoval = true)`. It is the STANDALONE-resolver proof: it
hand-builds a fake `UnitResource` universe (modules + domains + units transcribed by hand) to feed
`UnitResolver`. Once R4 boots Felix for real and resolves ACTUALLY-INSTALLED bundles, this hand-fed
universe is redundant → delete the whole package (don't repair it; its transcribed module ids already
drifted at the `-core`/`-port` split and are deliberately left stale). KEEP `UnitResolver` itself — it
wraps Apache Felix `ResolverImpl` and stays in production (`ManifestsVisitOrder`, `ManifestsDomainRegistry`).
Recorded in [[rename-contract-to-port-state]] + [[java-cleanup-backlog]].

See [[osgi-runtime-migration-state]] (spec §4 is the runtime-target design this implements),
[[osgi-runtime-r3-consume-references-state]] (dual-path + the deferred gate), [[osgi-runtime-r1-scr-state]]
(embedded-Felix proof + typed-access trick), [[hub:docrepo-dag-state]] (#1565 gRPC/TCCL),
[[hub:model-substrate-alignment]] (OSGi describes, host actualises), [[merge-from-target-worktree]].
