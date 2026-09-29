---
name: cluster-owned-decision-cannot-live-where-grow-reasserts
description: "★★ Règle mesurée le 2026-09-28 : une décision possédée par le CLUSTER ne peut pas vivre là où le grow réassère. Les deux emplacements plausibles sont disqualifiés — le miroir PoolAdoption est réécrit en entier à chaque réconciliation (CreateOrUpdate), et l'ImageState enregistrée sur la branche est écrasée par tout GROW (précédence « a live seeded image wins »). D'où : un état in-cluster veut son propre objet."
metadata:
  node_type: memory
  type: project
  originSessionId: 635c1dc0-8df2-4b0a-95ae-504dc3770308
  modified: 2026-09-28T05:58:13.036Z
---

Établi en cherchant où une **campagne d'upgrade** pourrait tenir sa décision (rke2lab
`b11805cf3`). Les deux joints qui *ressemblent* à des frontières de propriété n'en sont pas, et
c'est vérifié dans le code, pas supposé.

## Les deux emplacements disqualifiés

1. **Le miroir `PoolAdoption` est une COPIE, pas une frontière.**
   `poolintention_controller.reconcileSteps` fait un `CreateOrUpdate` dont la closure assigne
   `adoption.Spec = specFromPoolIntention(pi)` — **en entier, à chaque passe**. Donc une écriture
   in-cluster sur l'Adoption est révoquée en secondes, et sur l'Intention au prochain sync Flux.
   ⚠️ Le couple Intention/Adoption *paraît* être le seam de propriété (c'est ce que son nom
   suggère) ; c'est une projection totale.

2. **La branche porte bien un état durable, mais le grow le réassère.**
   La racine de branche enregistre un contexte de rendu `{source, facet, image}` — donc
   l'`ImageState` entière survit là, et un rendu de régime permanent la rejoue au lieu de vider le
   CR-set (le scion incus ne tourne qu'au grow ; lire→décoder→ré-sérialiser est un point fixe,
   donc pas de churn de commit). **Mais la précédence est « a live seeded image (a GROW) wins;
   else replay HEAD »** — la branche ne protège donc que contre les rendus *in-cluster*
   (`RenderMode.Verb.UPDATE` = HEAD gagne).

## La règle

> Une décision possédée par le cluster ne peut pas vivre à un endroit que le grow réassère.

Corollaire pratique : un état transient du cluster (un bump de nœuds, un roulement) veut **son
propre objet**, avec un statut — pas un champ recopié ni un sous-arbre du facet. Et de toute
façon une campagne n'est pas un champ : c'est de l'ordre, un nœud à la fois, une porte de santé,
un retour arrière.

★ Même forme que le piège déjà payé sur la Service `spec.externalName` de tailscale (deux
propriétaires sur un champ, le force-apply SSA de Flux la faisait battre) — voir
[[nikopol-mgmt-federation-clustermesh-first-case]] § *Propriété, pas Flux*.

⚠️ Piège de lecture à ne pas refaire : `RenderMode.overrides` est un `Map<String, Boolean>`
(booléen **délibérément**, « so the contract stays jackson-free ») et ses chemins atteignent
`debug`/`delivery`/`workloadTargets` — la sous-map `Facets`, **pas** l'`image` enregistrée à côté
d'elle. J'ai d'abord écrit « ni dans le facet », ce qui était faux.

See [[node-image-published-as-cr]] [[single-owner-rule]]
