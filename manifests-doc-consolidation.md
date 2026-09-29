---
name: manifests-doc-consolidation
description: "DONE 2026-06-03: consolidated the 9-doc manifests cluster in docs/ into a hub + 4 companions"
metadata: 
  node_type: memory
  type: project
  originSessionId: 28d6a117-e5b7-4227-bca3-9d64e719c38b
---

**Completed 2026-06-03.** The manifests documentation cluster was consolidated.

Final structure:
- **Hub**: `docs/manifests-architecture.adoc` — canonical reference (nomenclature, ManifestsUnit/
  ManifestsDomain model, Chart-vs-Construct rule, dual K8s+systemd synthesis flow). Absorbed and
  superseded the four deleted docs.
- **Companions kept + de-staled**: `manifest-apply-flow`, `manifest-conditional-inclusion`,
  `manifest-domain-catalog-pattern`, `systemd-architecture`.
- **Deleted** (`git rm`): `manifest-system-architecture`, `manifest-synthesis-architecture`,
  `cdk8s-chart-vs-construct-pattern`, `manifest-nomenclature`.
- Repointed stale links in 8 other docs + README + cross-reference-navigation to the hub.
- Added **15 `package-info.java`** to the manifests module (5 core: root, domain, node, profiles,
  systemd; 10 `units/<domain>`). Each uses `{@link}` for types (compiler-checked) + relative
  `<a href>` to docs (root pkg = 8 `../`, depth-9 pkgs = 9, `units/<x>` = 10). The docs link back:
  hub has a "Package Overviews" section, cross-reference-navigation restored the orange package-info
  nodes/patterns. Bidirectional loop verified. No `package-info` existed before this. Convention:
  `// @codebase` marker first line; google-java-format reflows them on save.

Ground-truth facts captured during the work (verified against code, useful later):
- Java package is `io.seedmatic.rke2lab.manifests` (the user fixed `rk2lab`→`rke2lab` this session).
- Units live in `units/<domain>/`, domain registrars in `domain/` (e.g. `GitopsDomainRegistrar`),
  NodeEnv SPI in `node/`. No `layers/` or `components/` dirs.
- Registrars EXIST and use `new ManifestsDomain(ManifestDomainCatalog.GITOPS, deps,
  List.of(ManifestsUnit.lazy(...)))`. The exception message saying they were "deleted" is itself
  stale.
- **Still dormant**: `DefaultManifestSynthesisService.buildDomainRegistry()` throws
  `UnsupportedOperationException` (registrar imports commented out) — so the registrar→registry
  synthesis path does NOT run. The hub documents this honestly under an anchor
  `#domain-registry-status`. Conditional-inclusion's `domain(ManifestDomainPolicy)` + the Porch
  example are likewise aspirational (no `PorchResourcesManifestsUnit` / `catalog.porch()` exists).
- `AbstractManifestsUnit` still keeps a legacy null-scope constructor alongside the canonical
  `(scope, id, manifestUnitId, deps)` — flagged in the hub as a uniformity violation to remove.

See [[terminology-refactor-state]] for the related code-rename status.
