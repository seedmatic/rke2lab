---
name: package-private-sweep
description: "Plan multi-session pour retirer les `private` non essentiels (package-private par défaut) dans tout le repo"
metadata: 
  node_type: memory
  type: project
  originSessionId: 28d6a117-e5b7-4227-bca3-9d64e719c38b
---

Préférence du user (2026-06-03) : **package-private par défaut**, retirer `private` partout SAUF cas légitimes (constructeurs de builder-enforcement per CLAUDE.md, méthodes private d'interface = obligatoires, constructeurs de singleton).

Ampleur : 3131 `private` sur 316 fichiers, 398 ctors privés. **Ne PAS faire en un perl global** — module par module avec build de vérif entre chaque (manifests → netplan → systemd → sdks → seed-master en dernier).

Exemplar déjà fait : `DefaultManifestSynthesisService.java` (tous private retirés). Plan détaillé + checklist : [.claude/package-private-sweep-plan.md](../../../../../private/var/lib/git/nxmatic/rke2lab/.claude/package-private-sweep-plan.md).

Lié à [[refactor-pipeline-candidates]].
