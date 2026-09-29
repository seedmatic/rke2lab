---
name: terminology-refactor-state
description: Current state of the manifests-module terminology refactor (Component/Unit/Domain/NodeEnv)
metadata: 
  node_type: memory
  type: project
  originSessionId: 28d6a117-e5b7-4227-bca3-9d64e719c38b
---

Ongoing terminology refactor of the `manifests` module to remove conceptual confusion.
Final terminology (all DONE as of commit 8b235492, 2026-06-03):

- `Component` + `ManifestUnit` merged into ONE class `*ManifestsUnit` (plural — one unit emits
  multiple K8s manifests). Base class `AbstractManifestsUnit`.
- `LayerDomain` → `ManifestsDomain` (functional grouping of units by concern).
- `LayerEnv*` → `NodeEnv*`, relocated to package `io.seedmatic.rke2lab.manifests.node`:
  NodeEnvContext, NodeEnvContributor, NodeEnvContributorRegistry, DefaultNodeEnvContext.
  The node-identity contributor is `NodeEnvIdentityContributor` (renamed from NodeLayerEnvContributor
  to avoid colliding with the NodeEnvContributor interface). Domain contributors are `*NodeEnvContributor`.
  Method `layerId()` → `domainId()`. SPI service file fixed (was pointing at stale `manifests.layers.*`).

Reserved term: **`BootstrapPhase`** for temporal bootstrap stages (seed-master) — NOT yet introduced.

Still pending (separate tasks, not started):
1. Introduce `BootstrapPhase` terminology in seed-master
2. Reimplement DomainRegistrars — `DefaultManifestSynthesisService.buildDomainRegistry()` throws
   UnsupportedOperationException
3. Fix `CiliumConfigManifestsUnit.apply(ManifestsUnitContext)` self-instantiation bug
4. Delete dead null-scope constructor in `AbstractManifestsUnit`

Detailed state in `.claude/terminology-refactor-plan.md`. Doc cleanup tracked in
[[manifests-doc-consolidation]].
