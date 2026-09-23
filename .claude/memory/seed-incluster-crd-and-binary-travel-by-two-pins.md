---
name: seed-incluster-crd-and-binary-travel-by-two-pins
description: "La CRD de seed-incluster vient de l'input flake, son BINAIRE vient du flox-catalogue — deux épinglages indépendants. Bumper l'un sans l'autre donne un schéma qui accepte un champ que le binaire ignore (mesuré 2026-09-23)"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-23T13:05:20.969Z
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

## Le symptôme mesuré (2026-09-23)

Après `nix flake update seed-incluster` dans rke2lab et un re-grow complet :

- CRD `poolintentions` : porte bien `nodeLabels` ✅
- `PoolIntention.spec.nodeLabels` : `["flox.seedmatic.io/enabled=true"]` ✅ (le rendu Java)
- `PoolAdoption.spec.nodeLabels` : **null** ❌
- `RKE2ControlPlane.spec.agentConfig.nodeLabels` : **null** ❌

⚠️ **Le discriminateur** : `PoolIntention` rempli + `PoolAdoption` vide ⇒ ce n'est PAS le rendu,
c'est le **binaire** qui ne connaît pas le champ. L'annotation du Deployment le confirme :
`flox.seedmatic.io/environment.controller: cluster-api/seed-incluster` +
`flox.seedmatic.io/restarted-for-lock: <hash>` — le binaire suit le **lock de l'env**.

## Livrer le binaire

Dans flox-catalogue : bumper son input `rke2lab` (pour que le `seed-incluster` transitif
avance), puis re-locker l'env — la commande est documentée dans son propre
`flake.nix` (~ligne 293) :

```
nix run .#lock-envs -- cluster-api/seed-incluster
```

Le changement de lock redémarre le contrôleur (le mécanisme `restarted-for-lock`).

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
