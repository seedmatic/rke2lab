---
name: capi-template-is-stamped-not-referenced
description: "Un LXCMachineTemplate est estampillé à la création de la machine, pas relu — et l'empreinte d'image épinglée qu'il porte peut être collectée, ce qui retire silencieusement au cluster sa capacité à se réparer"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-24T11:15:18.905Z
---

Mesuré le 2026-09-24 sur `bioskop-wrkld`, en tentant le rollout d'un control plane
à un seul replica.

## Les deux moitiés

**1. Un template CAPI est un MOULE, pas une référence vivante.** CAPN recopie
`spec.template.spec.image.fingerprint` dans la `spec` de la `LXCMachine` au moment
où il la crée. Patcher le template ensuite est **inerte** pour toute machine déjà
née — c'est corriger le moule après avoir coulé la pièce. Le levier est la
`LXCMachine` elle-même (`kubectl patch lxcmachine … spec.image.fingerprint`),
relue par CAPN à chaque boucle (~40 s).

**2. L'empreinte épinglée peut ne plus exister.** Le template avait été écrit par
`seed-incluster` à 10:26 avec `6bc7fd0f…` ; le re-grow a reconstruit l'image
(`75037325b2a8…`, alias `node-base`) et **collecté l'ancienne**. `incus image info
6bc7fd0f…` → `Image not found`. La `PoolIntention` **et** la `PoolAdoption`
portaient la bonne ; seul le template était resté en arrière, parce que
`ensure()` est create-only (voir [[node-bootstrap-objects-need-instance-recreation]]).

## Pourquoi c'est grave et pourquoi ça ne se voyait pas

Le nœud vivant `bdjc9` portait lui aussi l'empreinte morte. Il tournait très bien —
il était né quand l'image existait. Mais **plus aucune machine ne pouvait naître** :
s'il était mort, CAPN aurait bouclé sur `failed to create instance: … Image not
provided for instance creation`. Le cluster avait perdu sa réparabilité,
silencieusement. Il a fallu *tenter une création* pour l'apprendre — aucune
condition, aucun statut ne le disait.

Deux défauts anodins séparément, composés en perte totale :

| défaut | conséquence |
|---|---|
| `ensure()` create-only | le template garde une empreinte périmée |
| le grow collecte les vieilles images | cette empreinte disparaît du daemon |

C'est la famille « vrai par accident historique » — cinq autres instances le même
jour, voir [[node-env-gated-oneshots-skip-capn-nodes]].

## Le remède (nommé, pas encore fait)

**Le nom du template doit être fonction de son contenu.** Une empreinte neuve
frappe alors un template neuf, l'`infrastructureRef` du `RKE2ControlPlane` bouge,
et CAPRKE2 roule le nœud de lui-même — ce qui contourne le create-only au lieu de
le combattre (on ne mute jamais un template, on en crée un autre : la sémantique
CAPI le veut ainsi). Corollaire : **ne jamais collecter une image qu'un template
vivant épingle encore**.

La dernière pièce manquante est levée — voir
[[caprke2-rolls-a-single-replica-control-plane]].
