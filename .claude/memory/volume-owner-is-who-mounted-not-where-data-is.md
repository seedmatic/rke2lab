---
name: volume-owner-is-who-mounted-not-where-data-is
description: "Chez nous tous les nœuds d'un cluster partagent le tank d'un seul bare-metal, donc ownerNodeID dit qui a monté le volume, pas où sont les données — re-estampiller sur le nœud élu récupère sans perte là où le contrôleur prescrit une destruction"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-24T15:56:10.967Z
---

Trouvé le 2026-09-24 en roulant le control plane de `bioskop-wrkld` : le nœud
supprimé a laissé ses deux PV pendants, `seed-incluster` `Pending` pour toujours,
et **neuf Kustomizations** bloquées derrière lui — dont toute la chaîne tailscale,
d'apparence sans rapport.

## Le fait de topologie, énoncé par l'utilisateur

> « mais tous les nodes tournent sur le meme host chez nous, bioskop-nixos. »

C'est ça qui change tout. Mesuré :

```
ZFSVolume funnel-cert   ownerNodeID=<nœud mort>   poolName=tank/rke2lab/wrkld/persist
depuis le NOUVEAU nœud : zfs list tank/rke2lab/wrkld/persist/funnel-cert → 160K
```

Le `poolName` est un dataset de l'**hôte**, et le nœud élu le liste depuis
l'intérieur de son conteneur. Donc `nodeAffinity` local n'est pas un constat
physique ici, c'est une **hypothèse prudente d'openebs-zfs** (nœud-local =
hôte-local) fausse pour une flotte de conteneurs co-tenants.

## Le défaut dans le contrôleur `VolumeIntention`

Son message se contredit lui-même :

```
ZFSVolume funnel-cert already names node "<mort>" but "<élu>" was elected —
the dataset lives on "<mort>"; delete the ZFSVolume and the PV to re-place it
```

Il lit `ownerNodeID` comme *où sont les données* alors que ce champ dit seulement
*qui les a montées en dernier* — et son propre `poolName`, juste à côté, dit que
le dataset est hôte-scopé. Le remède qu'il prescrit **détruit** le cert ; c'est
pour ça que la paire `funnel-cert-backup` / `tailscale-funnel-cert-restore`
existe : réparer une perte évitable.

**Le correctif** : comparer le POOL (hôte-scopé), pas le nœud ; quand le nœud élu
diffère mais que le pool est le même, re-estampiller `spec.ownerNodeID` au lieu
d'exiger une suppression. Code dans `seed-incluster`, pas encore écrit.

## La récupération, vérifiée vivante

`kubectl patch zfsvolume funnel-cert -n openebs` → `ownerNodeID = <élu>` (le
`zfs-driver` ne le réécrit pas), puis supprimer PVC + PV (le PV est en `Retain`,
le dataset survit). Le contrôleur repasse `Placed`, le PVC se relie, **zéro**
Kustomization en attente, cert intact. Le PVC du cache Maven, lui, est jetable
(`reclaim=Delete`, rien d'autre ne le monte) et Flux le re-déclare aussitôt sur le
nœud vivant — c'est le déblocage le moins cher de la boucle.

⚠️ **Une boucle de dépendance que j'ai cru voir et qui n'existe pas** — noté parce
que l'erreur est instructive. J'avais conclu que `seed-incluster`, le contrôleur
qui replace les volumes, était lui-même bloqué par un volume mal placé. **Faux** :
son pod ne monte aucun PVC (deux `emptyDir` et le token), et son Deployment n'a ni
`nodeSelector`, ni `affinity`, ni `tolerations`. Son `Pending` disait « didn't
match Pod's node affinity/selector » — l'affinité du POD, pas celle d'un volume —
et supprimer le pod l'a débloqué ; supprimer le PVC du cache n'y était pour rien.
La cause réelle n'a pas pu être reconstituée : l'ancien pod et l'ancien nœud
avaient disparu avec la preuve. Leçon : deux symptômes concomitants ne font pas une
chaîne causale, et un message de scheduler nomme la sonde qui a refusé, pas la
raison qu'on lui prête.

**Personne ne réclame `manifests-maven-cache`** : c'est le workspace Tekton du
pipeline de rendu (`RenderPipelineManifestsUnit`), monté seulement le temps d'un
`PipelineRun`. Son exposition est donc différée, pas absente — le prochain rendu
après un roll trouverait un PV périmé.

⚠️ **Rien ne ramasse** : quatre datasets `ephemeral/nodes/<nœud mort>` traînaient,
plus un `ZFSVolume` orphelin. Cohérent avec la règle des trois réconciliateurs —
aucun ne sait garbage-collecter.

## Conséquence pour le roll automatique

Livrer le nom de template content-addressed
([[capi-template-is-stamped-not-referenced]]) **sans** ce correctif ferait perdre
l'état du cluster à chaque changement d'image. Les deux vont ensemble.

See [[funnel-identity-is-per-cluster]] [[caprke2-rolls-a-single-replica-control-plane]].
