---
name: seed-incluster-crd-and-binary-travel-by-two-pins
description: "La CRD de seed-incluster vient de l'input flake, son BINAIRE vient du flox-catalogue — deux épinglages indépendants. Bumper l'un sans l'autre donne un schéma qui accepte un champ que le binaire ignore (mesuré 2026-09-23)"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-23T13:10:16.880Z
---

★ **Le même contrôleur voyage par deux canaux aux épinglages indépendants.** C'est la
cause d'une classe de faux « ça ne marche pas » : le champ est accepté à l'admission, donc
tout semble livré, et pourtant rien ne se passe.

| pièce | canal | épinglé par |
|---|---|---|
| **CRD** (+ ClusterRole) | input flake `seed-incluster` de rke2lab, re-exporté en `seed-incluster-crds`, stagé dans `manifests-core/target/generated-resources/crds` | `flake.lock` de rke2lab → `nix flake update seed-incluster` |
| **BINAIRE** | env flox `cluster-api/seed-incluster` du **flox-catalogue** — `manifest.toml` : `flake = "path:../../..#seed-incluster"`, et le flake du catalogue fait `seed-incluster.follows = "rke2lab/seed-incluster"` | `flake.lock` du **catalogue** + le **lock de l'env flox** |

Le commentaire de `flake.nix` le dit : « The OCI image is NO LONGER re-exported or baked — the
controller rides the flox runtime (the cluster-api/seed-incluster flox env installs the binary
from the flox-catalogue), so only the binary + CRD are needed here. »

## ★★★ La conséquence réelle : l'ANCIEN binaire a une fenêtre, et il crée le CR-set

Mesuré au re-grow du 2026-09-23. Le catalogue **était** bumpé (`6edf419e5` = « bump rke2lab ->
6ced3bf06 (seed-incluster 3c1637315) »), et c'est bien cette révision que la `FloxEnv`
épinglait. Rien ne manquait. Et pourtant :

| horodatage | événement |
|---|---|
| 13:01:14 / 13:01:17 | les deux `RKE2ControlPlane` sont **créés par l'ANCIEN binaire** |
| 13:08:32 | l'env `cluster-api/seed-incluster` est réalisé → le pod redémarre (`restarted-for-lock` `aa35e1e6008d` → `c571bc649bbb`) → **nouveau** binaire |
| après | `PoolAdoption.spec.nodeLabels` se remplit (`CreateOrUpdate`), le RCP reste `null` **pour toujours** (`ensure`) |

★ **La cause est une inversion d'ordre du pipeline** (trouvée par l'utilisateur). Le flake du
catalogue fait `seed-incluster.follows = "rke2lab/seed-incluster"`, donc rke2lab doit être
**poussé d'abord** pour que le catalogue puisse avancer. Mais c'est ce push qui **déclenche le
rendu in-cluster**. Le rendu émet donc la `FloxEnv` épinglée sur la révision de catalogue
**précédente** (l'annotation `flox.seedmatic.io/relock: flox-catalogue@sha1:<rev>`, portée par
le RENDU, résolue via le tarball de source-controller).

⚠️ Donc le retard n'est pas « le bump arrive tard » mais : **l'ancien binaire dispose d'une
fenêtre de quelques minutes au tout début du cluster, exactement quand le CR-set create-only
est créé.** À la naissance d'un cluster, la forme du CR-set est décidée par le binaire qui gagne
la course. Le retard est ensuite gravé dans un objet que le contrôleur ne retouchera jamais.

**Piège de diagnostic que j'ai payé** : lire `PoolAdoption.spec.nodeLabels == null` juste après le
re-grow et conclure « le binaire est l'ancien / le bump manque ». C'était une photo prise avant
le redémarrage. Le discriminateur correct n'est pas la valeur mais **l'horodatage** : comparer
`metadata.creationTimestamp` du RCP à la `lastTransitionTime` du `Ready` de la FloxEnv. Si le
RCP est le plus ancien, il a été créé par l'ancien binaire — quoi que dise le lock maintenant.

## Livrer le binaire (quand il manque vraiment)

Dans flox-catalogue : bumper son input `rke2lab`, puis re-locker l'env — la commande est
documentée dans son propre `flake.nix` (~ligne 293) :

```
nix run .#lock-envs -- cluster-api/seed-incluster
```

`lock-envs` ne pousse rien : il re-locke et **commite** dans le catalogue. Le changement de lock
redémarre le contrôleur. ⚠️ L'ordre des commits du catalogue compte — un `chore(lock)` posé
AVANT le `chore(flake): bump rke2lab` a locké contre l'ancien input.

## Et ensuite : qui se répare seul, qui non

Deux primitives d'application différentes dans seed-incluster, et la distinction décide du
geste :

- `PoolIntention → PoolAdoption` : **`controllerutil.CreateOrUpdate`** → converge tout seul dès
  que le binaire est neuf. Rien à supprimer.
- tout le **CR-set CAPI** (`RKE2ControlPlane`, `LXCMachineTemplate`, Machines) : **`ensure`,
  create-only** → un objet existant n'est jamais touché. Il faut le **supprimer** pour qu'il
  soit recréé avec la nouvelle forme. Voir [[node-env-gated-oneshots-skip-capn-nodes]].

⚠️ **Ne PAS supprimer le `RKE2ControlPlane` du cluster de MGMT** pour ça : il possède les
Machines par ownerRef, donc la suppression cascade Machine → LXCMachine → CAPN → **l'instance
incus du pet**. Et c'est inutile : le nœud mgmt tient ses labels de l'oneshot nixos (vérifié
vivant — `flox.seedmatic.io/enabled`, `instance-name=master`, `instance-kind=server`), donc
l'absence de `nodeLabels` sur SON RCP est inerte. Le geste ne concerne que le cluster de charge,
dont le nœud doit de toute façon être recréé.

See [[node-env-gated-oneshots-skip-capn-nodes]] [[kubeconfig-context-per-cluster-intention]].
