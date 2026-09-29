---
name: node-bootstrap-objects-need-instance-recreation
description: "Un objet rendu avec node-bootstrap n'est PAS livré par Flux ni par un reboot — cloud-init l'écrit une seule fois, à la création de l'instance. Le changer exige de supprimer l'instance et de re-grow"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-23T06:52:49.778Z
---

Découvert le 2026-09-23 en voulant rendre live le correctif cilium (`devices: lan0 vmnet0`).

## La règle

Un objet que le rendu marque `io.seedmatic.rke2lab/node-bootstrap: "true"` ne voyage **par aucun
render vers la branche**. L'exploder le carve dans `.bootstrap/rke2lab-bootstrap.yaml`, **hors** de
l'arbre committé (`NodeBootstrapArtifact.MANIFESTS`), et il arrive sur le nœud par un **`write_files`
de cloud-init** (`InstanceGrow:623`, mode `0600`).

Or cloud-init **ne rejoue pas `write_files` au reboot** — le commentaire du code le dit lui-même.
Donc :

| geste | livre le changement ? |
|---|---|
| `flux reconcile` | ❌ l'objet n'est pas sur la branche |
| reboot du nœud | ❌ cloud-init ne rejoue pas |
| `pulumi up` sur une instance existante | ❌ |
| **supprimer l'instance + re-grow** | ✅ |
| pour un nœud managé : re-provisionnement par CAPI | ✅ (le bundle passe par un Secret, `ClusterApiCrRenderer:159`) |

## Comment le reconnaître sur le cluster vivant

L'objet live porte des labels **wrangler**, pas Flux :

```
owner-gvk:  k3s.cattle.io/v1, Kind=Addon
owner-name: rke2lab-bootstrap
```

C'est le deploy controller d'rke2 qui le rejoue depuis
`/var/lib/rancher/rke2/server/manifests/rke2lab-bootstrap.yaml`. Le `mtime` de ce fichier date du
**premier boot de l'instance** — le comparer à l'heure du commit dit immédiatement si le changement
est live.

⚠️ Piège de diagnostic : `manifests-cli synthesize` écrit le fichier de **groupe** qui LISTE l'objet
parmi ses `members` (`helm.cattle.io/v1|HelmChartConfig|kube-system|rke2-cilium`) sans écrire l'objet
lui-même. Un répertoire de package qui ne contient que son `.*.group.yml` n'est pas un rendu
incomplet : c'est la signature d'un paquet node-bootstrap.

⚠️ `rke2lab-rke2-config` (le oneshot `nix run <branche>#install-rke2-config`) ne sert PAS à ça — il
n'installe que les drop-ins `config.yaml.d` du serveur rke2. Espoir naturel, faux.

## Ce que ça coûte

Un changement d'une ligne dans une valeur Helm livrée en node-bootstrap coûte un **cold start**. Donc
il faut le grouper avec les autres changements du même bundle, et vérifier avant de re-grow que ce qui
doit survivre survit (le cert du funnel vit sur son dataset persist, indépendant de l'instance — voir
[[funnel-identity-is-per-cluster]]).

See [[funnel-identity-is-per-cluster]] [[rke2-config-reconciliation-nixrun-delivery]]
[[netplan-projection-described-hosts]].
