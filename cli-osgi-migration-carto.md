---
name: cli-osgi-migration-carto
description: "SHIPPED to design/target-module-layout (squash merge 2026-06-20); worktree torn down. The R4 boot-seam model GENERALISES to standalone main() entrypoints: manifests-cli + netplan-cli now boot embedded Felix and read their -port service from the registry, fixing the WI-C0 synthesis breakage (off-framework ServiceLoader → null Resolver). This note was the carto/brief; collapsed to a SHIPPED pointer + the durable rules the work proved. The blow-by-blow is in git history (6 commits, a925e877..3e9c06a8)."
metadata:
  node_type: memory
  type: project
---

## Shipped — what the system IS now

The three exec entrypoints boot an identical embedded Felix and consume the registry; the per-entrypoint
variation is only WHICH model bundle they embed and what their tail does once booted:

- **Common boot stack** extracted to `OsgiRuntime.embeddedBootStack()` (pax-logging + felix.scr +
  felix.resolver), with the boot-stack jar names as `OsgiRuntime.*_JAR` constants (single source of
  truth the `stage-embedded-bundles` poms mirror). `hasEmbeddedBundles()` probes felix.scr (common to
  all three), not the per-entrypoint model bundle.
- **manifests-cli** embeds manifests-core; **netplan-cli** embeds netplan-core; **seed-master** embeds
  manifests-core. All boot via `embeddedBootStack().embeddedBundle(X).build().boot()` then
  `awaitService(...)` — single path, fail-fast, NO flat fallback (the ServiceLoader path is deleted).
- **All three** declare the model `-core` at **runtime scope**, the `-port` contracts at compile —
  the compiler now machine-enforces path 2 (a host→impl import fails the build). The realgraph fixture
  that once blocked seed-master's demotion is gone, so seed-master demoted too (uniform).
- The **8 orphaned `META-INF/services`** SPI files are deleted; the only `ServiceLoader.load` left
  resolves `FrameworkFactory` (standard OSGi discovery, permanent). SCR `@Component` is the sole
  discovery mechanism. This is the CLI half of R5.

Proven for real (no stack needed — CLIs read system properties, not a Pulumi engine): `java -jar
netplan-cli synthesis` and `java -jar manifests-cli synthesize` both EXIT 0, synthesis completes under
embedded Felix, provider resolved from the registry. `netplan-cli yamlExport` runs flat (no Felix).

## Durable rule this work PROVED — single-exporter for a CONSUMED contract

A service contract a host/CLI consumes typed across the seam MUST live in a module the bundle
**imports-but-does-not-export** (like `manifests.port`). netplan broke this: `netplan.api` (the
`NetplanSynthesisService` contract) lived INSIDE netplan-core and was exported by it. Embedding the
bundle then forces a dilemma — drop `netplan.api` from the flat classpath (CLI cannot classload) OR
keep it flat AND bundle-exported (split → typed `awaitService` misses). FIX: moved `netplan.api` (4
types + its test) into the **netplan-port** module; netplan-core now imports it, netplan-port is its
sole exporter. Folded into [[osgi-system-export-resolution-only]] as the single-exporter corollary for
consumed contracts.

## Build gotcha this work surfaced — `clean` is mandatory after a package `git mv`

A `package` (no `clean`) after moving sources between modules leaves the old `.class` files in the
origin module's `target/classes`; bnd then inlines them as **Private-Package**, recreating the very
split the move was meant to cure (netplan-core re-grew a private `netplan.api`, the service published
under its copy, the host saw null at the 5s timeout). Always `clean` after a cross-module `git mv` of
a package before trusting a bnd manifest.

## Backlog this work spun off (NOT in scope here)

- **Pre-existing `ManifestSynthesisRequest.fromSystemProperties` defect:** the outdir path builds with
  `ComponentVersions.empty()` (→ `release-.yaml`, classpath-not-found), while the ephemeral (no-outdir)
  path uses `ComponentVersions.defaults()`. A manifests-port bug, unrelated to OSGi wiring — fix when
  manifests CLI inputs are next touched.
- **The contributable bootstrap pipeline** (user's framing, the next chantier) →
  [[bootstrap-pipeline-contributable-vision]].

See [[osgi-runtime-r4-boot-seam-state]] [[osgi-system-export-resolution-only]] [[dual-path-inline-until-r5]]
[[osgi-runtime-r4-resume-state]] [[bootstrap-pipeline-contributable-vision]] [[merge-from-target-worktree]].
