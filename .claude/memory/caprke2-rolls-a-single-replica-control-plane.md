---
name: caprke2-rolls-a-single-replica-control-plane
description: "Un RKE2ControlPlane à un seul replica SAIT rouler — maxSurge=1 par défaut crée la nouvelle machine avant de drainer l'ancienne ; mesuré vivant le 2026-09-24"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-24T11:15:34.644Z
---

Question de l'utilisateur, tranchée par l'expérience : *« a moins que le control
plane de rke2 sache faire :) »*. **Il sait faire.**

J'avais affirmé « on ne peut pas en single-node ». C'était faux. CAPRKE2 défaute
`rolloutStrategy: RollingUpdate` avec `maxSurge: 1`, donc un control plane à **un**
replica surge : il monte à 2, la nouvelle machine rejoint etcd, et seulement ensuite
l'ancienne est drainée puis supprimée.

## Ce qui a été observé vivant

Sur `bioskop-wrkld`, en repointant `spec.machineTemplate.spec.infrastructureRef.name`
vers un template sonde :

```
bioskop-wrkld-control-plane-pgdvh   Running        bioskop-wrkld-control-plane-bdjc9
bioskop-wrkld-control-plane-wh7lh   Provisioning   <none>
replicas=2 ready=1
```

Une `LXCMachine` neuve est apparue, **nommée d'après le template** (`<template>-vbldr`)
— ce qui est en soi la confirmation que le nom du template voyage dans l'identité
de la machine.

⚠️ Patcher l'`infrastructureRef` **avant** de créer le template produit une boucle
d'erreur de réconciliation propre et non destructrice (`failed to retrieve
LXCMachineTemplate … not found`, le cluster intact) — c'est un ordre sûr, pas une
faute.

Le provisioning a ensuite échoué pour une raison sans rapport avec le rollout :
l'empreinte d'image épinglée n'existait plus, voir
[[capi-template-is-stamped-not-referenced]].

## Pourquoi ça compte

C'est la pièce qui rend jouable le remède de la dérive d'image : si le nom du
template est fonction de son contenu, une image neuve frappe un template neuf,
l'`infrastructureRef` bouge, et **même un cluster mono-nœud se met à jour tout
seul**. Le blocage supposé du single-node n'existait pas.
