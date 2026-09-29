---
name: pool-reflection-cycle-and-infra-ref
description: "Deux défauts de PoolAdoption corrigés le 2026-09-21 : la réflexion et le Machine se justifiaient mutuellement (interblocage auto-entretenu), et après un greenfield le nom du pet ne nomme PAS le LXCMachine qui porte le verdict de CAPN"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-21T12:39:02.965Z
---

`bioskop-wrkld` est resté 9 h bloqué, puis a failli rester bloqué une seconde fois pour une raison
différente. Les deux défauts sont dans `pooladoption_controller.go` (dépôt seed-incluster, branche
`seed-incluster`) et viennent tous deux de la **même supposition** : que le nom d'un pet nomme aussi
tous ses objets.

## Défaut 1 — le cycle réflexion ↔ Machine (corrigé `0f2c10d07`)

Symptôme : `PetsPresent=False — NodeMissing: 0/1 present, 1 absent — holding (adopt, not
greenfielding)`, immobile. Le garde-fou « ne jamais greenfielder un rival » est juste **en général**,
mais il tirait aussi quand il n'y a plus AUCUN rival.

Et le maintien était **auto-entretenu**, ce qui est le vrai piège :

* la réflexion (git, `reflections/<cluster>-<pool>.yaml` sur `manifests/<selfCluster>`) nomme le pet ;
* la branche adopt **ré-assure** son `Machine` à chaque passe (15 s) ;
* `observeRoster` (reflector) n'exclut QUE les `Machine` en cours de suppression et les
  non-control-plane — **pas** celui dont l'infrastructure a disparu — donc il observe le fantôme et
  **ré-écrit la même réflexion** toutes les 5 min.

Aucun des deux termes n'est ancré dans l'instance Incus. **Supprimer l'un seul est réparé par
l'autre** — d'où l'immobilité. Correctif : quand **tous** les pets réfléchis sont décidés absents, la
réflexion est vide de sens (aucun survivant à concurrencer) → supprimer leur CR-set et greenfielder.
Une perte **partielle** tient toujours. Et la présence est désormais sondée **avant** d'assurer quoi
que ce soit, sinon on recrée les `Machine` qui maintiennent la réflexion en vie.

Garde-fou conservé : un `LXCMachine` absent se lit *indécis* (pending), jamais *absent* — donc un
cold-start ne peut pas être pris pour une réflexion vide.

## Défaut 2 — le nom du pet ne nomme pas le LXCMachine (corrigé `f384f7252`)

Une fois né, le pool est reparti sur `AwaitingInstances: 0/1 present, 1 pending` **alors que le nœud
tournait**. Parce que :

* seulement quand **nous** frappons la paire, pet = `Machine` = `LXCMachine` = instance ;
* après un greenfield, CAPI les nomme **indépendamment** — `Machine …-mgqbm` possédant
  `LXCMachine …-qs5dg`, l'instance suivant le LXCMachine — et le reflector reflète le nom du
  **`Machine`**.

Donc la branche adopt créait un `LXCMachine` au nom du pet que **aucun `Machine` ne référence** :
CAPN ignore un LXCMachine sans ownerRef, il ne lui pose jamais `InstanceProvisioned`, et la sonde
regardait ce rival à vie. Observé vivant : `LXCMachine …-mgqbm` sans ownerRef ni conditions, à côté du
vrai `…-qs5dg` en `InstanceProvisioned=True`.

Correctif : résoudre l'infrastructure par **`Machine.spec.infrastructureRef`**, et laisser
**strictement tranquille** un pet que CAPRKE2 possède déjà. Sans `Machine`, la référence EST le nom du
pet par construction → la ré-adoption après cold-start ne change pas.

## Leçon de diagnostic qui resservira

Une condition figée sur sa valeur de naissance ne veut pas dire « pas de watch ». Ici CAPN
réconciliait sans arrêt (294 lignes de log) : la réconciliation **échouait avant** d'écrire la
condition. Le discriminant est le **compte de logs**, pas l'horodatage de la condition. Même piège que
[[incus-trust-store-is-destroyable-state]], où l'erreur réelle (`not authorized`) n'était visible sur
aucun CR.

## RBAC

Les verbes `delete` ajoutés aux marqueurs (`machines`, `lxcmachines`, `secrets`) ;
`lxcmachinetemplates` a son propre marqueur SANS `delete` plutôt que d'en hériter. Le ClusterRole est
single-sourcé depuis les marqueurs (`make rbac` → `config/rbac/role.yaml`), stagé par rke2lab depuis
le flake — donc **jamais une copie committée côté rke2lab**, et un bump d'épingle suffit.

See [[workload-grow-foundations-resume]] [[incus-trust-store-is-destroyable-state]].
