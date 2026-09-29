---
name: incus-placement-must-be-stated-not-inferred
description: "★ Incus place une instance sans target sur le membre le moins chargé et tranche les égalités AU HASARD. Champ `target` ajouté le 2026-09-26 (seed-incluster CRD + rke2lab render) ; en attente de livraison. Le `scheduler.instance=manual` de ndh protège aujourd'hui en rendant demain IMPLAÇABLE."
metadata:
  node_type: memory
  type: project
  originSessionId: c2c2a472-f982-468d-98de-1b23bbf4e274
  modified: 2026-09-26T13:35:03.033Z
---

Ouvert le **2026-09-26**, quand le cluster incus est passé à deux membres
([[bridges-leave-incus-forced-not-chosen]]).

## La règle d'incus, citée

> « the automatic assignment picks the cluster member that has the lowest number of instances. If
> several members have the same amount of instances, **one of the members is chosen at random** »

Deux membres à zéro instance ⇒ **pile ou face**. Et ce n'est pas cosmétique : un cluster est adressé
sur les segments d'**UN** hôte, donc un nœud né sur le mauvais membre cherche un bail sur un sous-réseau
dont la réservation vit sur l'autre bare-metal, et le plan d'adressage part avec lui.

## L'état constaté, et pourquoi personne ne l'avait vu

Le placement n'était **exprimable nulle part** :

- `InstanceGrow` (Pulumi, le nœud de seed) : zéro `.target(` — mesuré.
- rke2lab ne rend **pas** de `LXCMachineTemplate` ; c'est **seed-incluster** qui le crée depuis une
  `PoolIntention`, dont le spec n'avait aucun champ `target`/`placement`/`location`/`member`.

Invisible jusqu'ici parce qu'un seul membre était éligible.

## Fait le 2026-09-26 (NON LIVRÉ)

- **seed-incluster `1d8804441`** : `target` sur `PoolIntentionSpec` **et** `PoolAdoptionSpec` (il voyage
  par le chemin de `NodeLabels`), projeté dans `poolintention_controller.go`, et posé par
  `lxcMachineSpec(cluster, fingerprint, target)` — donc sur le template **et** sur chaque `LXCMachine`
  par pet, qui partagent ce constructeur. Clé **omise** si vide (un `""` explicite se lirait comme une
  décision). `+optional` par concession de migration : des intentions vivent déjà sur le cluster de
  mgmt et un champ requis les invaliderait à leur prochaine écriture. Les deux CRD régénérées.
- **rke2lab `6fbe87452`** : le rendu le remplit avec `NamePlan.nixosHost`.
  ★ Pas une quatrième orthographe — rke2lab tient déjà cette identité à trois endroits
  (`BootstrapIdentity.incusRemoteName`, `BootstrapConfig.remoteIncus` avec sa surcharge de config, et
  le `NamePlan`). Le `NamePlan` est le bon ici parce qu'il dérive du nom de cluster de la **CIBLE** —
  même raison que le `remoteEndpoint` d'à côté, d'où « a target on another bare-metal needs no special
  case ». ⚠️ La surcharge `cluster.remoteIncus` n'est donc **pas** honorée pour le placement ; elles
  concordent aujourd'hui.
- ★ `nixosHost` gagne un **second usage**, et la distinction est tout : comme **nom à résoudre** il
  n'est correct que sur l'hôte lui-même (un pod qui le composait joignait sa propre loopback), comme
  **identité** rien ne le résout — c'est une clé dans la table des membres d'incus. ndh dérive la même
  chaîne indépendamment (`memberNameOf = "${domain}-nixos"`). Javadoc amendé pour que le prochain
  lecteur ne croie pas la mise en garde enfreinte.

## ⚠️ Le piège que mon propre correctif a posé

`incus-cluster-roles` (ndh `955c847c`) marque `scheduler.instance = manual` sur **tout membre autre que
lui-même**, et il ne tourne que sur le membre d'amorçage. Donc **bioskop est le seul membre éligible de
la flotte**. Pour `bioskop-*` c'est parfait — le placement automatique ne peut que bien tomber, et c'est
ce qui a protégé le cold start. Mais les nœuds de `nikopol-mgmt` naîtraient **sur bioskop**, et sans le
champ `target` on ne pouvait même pas y remédier. **Le correctif a rendu correct le cas du jour en
rendant celui de demain impossible** — acceptable comme étape, à condition de le savoir.

## ★★★ LIVRÉ ET VÉRIFIÉ VIVANT — 2026-09-26

Deux cold starts l'ont établi de bout en bout : `target: bioskop-nixos` est présent sur les
`PoolIntention`, les `PoolAdoption`, les DEUX `LXCMachineTemplate`
(`spec.template.spec.target`) et les DEUX `LXCMachine`. `bioskop-mgmt` **Adopted**.
L'inversion des deux épinglages **n'a pas mordu** : le catalogue `b280b8c58` (⇒ seed-incluster
`1d8804441`) était poussé avant le rendu, et la `FloxEnv` a réalisé la bonne dérivation
(`51gzfdzz…`, identique à ce que l'arbre produit).

★ **Le cold start était OBLIGATOIRE, pas opportun.** `ensure()`
(`seed-incluster internal/controller/controller_shared.go`) crée et ne touche JAMAIS un objet
existant — par choix assumé (« CAPRKE2/CAPN own their evolution »). Le template et la `LXCMachine`
nés avant la nouvelle CRD ne pouvaient donc **pas** gagner le champ par réconciliation. Règle
générale : **un champ ajouté à une intention n'atteint les objets CAPI qu'à leur recréation.**

★ Et la CRD neuve se prouve par la **relecture** du champ : une ancienne CRD aurait élagué
`spec.target` en silence, sans erreur.

⚠️ **Ce qui n'est PAS encore prouvé** : que CAPN *honore* le champ. `bioskop-wrkld-control-plane-2dh2f`
a bien été créé par CAPN sur `bioskop-nixos` avec le `target` posé — mais bioskop est le **seul membre
éligible** (`scheduler.instance = manual` ailleurs), donc l'atterrissage ne discrimine rien. Le test
décisif est un `target` valant `nikopol-nixos`.

## Reste à faire

1. Le test discriminant ci-dessus, puis `nikopol-mgmt` devient plaçable.
2. Le cold start du 09-26 a d'abord buté sur autre chose, réparé depuis :
   [[rke2-rejects-a-loopback-resolv-conf]] — CAPN ne résolvait pas le nom de son endpoint incus.

See [[bridges-leave-incus-forced-not-chosen]] [[nikopol-mgmt-federation-clustermesh-first-case]]
