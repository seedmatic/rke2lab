---
name: refactor-pipeline-candidates
description: "Plan multi-session pour refactorer les méthodes trop longues / trop d'args en fluent grammar, builders, records"
metadata: 
  node_type: memory
  type: project
  originSessionId: 28d6a117-e5b7-4227-bca3-9d64e719c38b
---

Audit du 2026-06-03 : 9 méthodes/constructeurs candidats au refactor (fluent pipeline grammar, builder, record) dans `manifests/` et `seed-master/`. Le user veut **tout** traiter, possiblement sur plusieurs sessions sans rien oublier.

Plan détaillé + checklist de suivi : [.claude/refactor-pipeline-candidates-plan.md](../../../../../private/var/lib/git/nxmatic/rke2lab/.claude/refactor-pipeline-candidates-plan.md) (dans le repo).

Ordre : B1 `synthesizeInContext` (manifests, 195 lignes, 8 étapes) d'abord → records WaitConfig/YamlSummaryContext → HostSlotManifest builder (12 params) → méthodes longues Incus. Vérif après chaque item : compile + `synthesize` produit la même preview.

Lié à [[terminology-refactor-state]].
