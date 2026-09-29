---
name: zfs-dataset-gc-missing
description: "Aucune logique ne récupère les datasets ZFS d'instances disparues — et un nœud provisionné par CAPI porte un nom aléatoire, donc il en fuit un par cycle de greenfield (constaté 2026-09-21)"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-21T14:48:36.409Z
---

Constaté pendant le cold start du 2026-09-21, sur `bioskop-nixos` :

```
tank/rke2lab/control-nodes/master              + /containerd
tank/rke2lab/control-nodes/peer1..peer3        + /containerd
tank/rke2lab/control-nodes/bioskop-wrkld-control-plane-qs5dg  + /containerd
```

Les quatre premiers sont les noms **semés** du dataplan (`DataplanLayout.canonical()` =
`tank/rke2lab/control-nodes/<node>`), donc stables. Le dernier est le nœud workload que
**CAPRKE2 a nommé au hasard** — son instance a été supprimée, son dataset est resté, et ce nom ne
reviendra **jamais** (CAPI en frappe un nouveau à chaque provisionnement).

## Le manque

Rien ne récupère un dataset dont l'instance a disparu. Pour les noms semés ça ne se voit pas — le
dataset est réutilisé au re-grow. Pour un nœud provisionné par CAPI, **il en fuit un par cycle de
greenfield**, avec son enfant `containerd`.

Et comme la purge de réflexion fait du greenfield le **chemin day-0 normal de chaque cold start**
(voir [[workload-bootstrap-chain-cilium-kubevip]]), la fuite est cadencée par les checkpoints, pas
par des accidents.

## Ce que ça révèle du modèle, au-delà du ménage

Le dataplan est clavé sur le **nom d'instance**, ce qui suppose des noms déterministes — vrai pour
les pets semés, faux pour un nœud CAPI. C'est la même racine que le défaut
[[pool-reflection-cycle-and-infra-ref]] (§défaut 2) : *seulement quand nous frappons la paire, pet =
Machine = LXCMachine = instance = dataset*. Donc soit le dataplan se clave sur autre chose (le pool,
pas le pet), soit il faut un GC.

Noté aussi dans la mémoire du chantier : « dataplan mono-cluster (`DataplanLayout.canonical()` …
pas de dim `<cluster>`) » — la dimension cluster manque aussi, c'est la même zone.

## Statut

**Aucun code, aucun design.** L'utilisateur a demandé de le garder en mémoire pour l'instant
(2026-09-21) — à ne pas traiter avant le chantier des fondations workload.
