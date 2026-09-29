---
name: rke2-peer-join-config-gap
description: "Nos fragments config.yaml.d ne portent NI server: NI token — correct et obligatoire pour un nœud CAPRKE2 (qui les injecte lui-même par rôle), mais une lacune réelle et latente pour un peer grown par l'hôte, qui tenterait d'initialiser son propre cluster au lieu de rejoindre"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-21T20:50:07.472Z
---

Question posée le 2026-09-21 : « la config elle échoue au master, les autres control nodes ne
l'utilisent pas non ? » — l'intuition est juste, mais elle porte sur le cluster de **gestion**,
pas sur celui de charge.

## Ce que la doc RKE2 exige

Vérifié sur https://docs.rke2.io/install/ha :

- Le **premier** serveur n'a **ni** `server:` **ni** `token:` — il établit l'état initial.
- **Tout serveur suivant** doit poser `server: https://<adresse-d-enregistrement>:9345` **et**
  `token: <secret partagé>`. Le port d'enregistrement est **9345** (distinct du 6443 de l'apiserver).
- Les deux ont besoin de `tls-san`.

## Ce que nos fragments portent — et ne portent pas

`RuntimeRke2ConfigManifestsUnit` rend les ConfigMaps **par (cluster × pool)**, jamais par nœud.
Le `.node("master")` du blueprint n'en tire que des valeurs **de cluster** (service/pod CIDR, VIP,
gateway). Le seul endroit où l'identité de nœud entre est `tlsSanSuperset`, qui boucle sur TOUS les
nœuds canoniques pour mettre CHAQUE FQDN mDNS dans la même liste — un sur-ensemble fait exprès pour
qu'un fragment unique serve tous les nœuds.

**Aucun fragment ne contient `server` ni `token`** (grep vérifié : zéro occurrence).

## Deux chemins, deux verdicts

**Chemin CAPRKE2 (cluster de charge) — correct, et l'absence est OBLIGATOIRE.**
`rke2ControlPlaneObj` pose `registrationMethod: "address"` +
`registrationAddress: spec.ControlPlaneEndpoint.Host` (la VIP kube-vip). CAPRKE2 sait donc quelle
machine initialise et lesquelles rejoignent, et écrit `server`+`token` dans le
`/etc/rancher/rke2/config.yaml` **par rôle**. Nos `files` sont des drop-ins `config.yaml.d/`
purement **additifs**. Si on y mettait `server`, on écraserait la décision par rôle de CAPRKE2 —
**y compris sur l'initialisateur**, qui doit l'omettre. C'est exactement ce qui rend légal un seul
jeu `spec.files` pour les 3 répliques.

**Chemin hôte (cluster de gestion) — LACUNE RÉELLE, latente.**
Rien ne rend `server`/`token`. Un peer grown par `InstanceGrow` + `install-rke2-config` recevrait
les mêmes fragments que le master et, faute de `server:`, **tenterait d'initialiser son propre
cluster au lieu de rejoindre**. Ça n'a pas mordu parce que le pool mgmt est à `present=1` : seul
le master existe. `install-rke2-config` filtre par **cluster** (via son hostname `<cluster>-<node>`),
pas par nœud — donc le filtre n'aide en rien ici.

⚠️ **C'est le blocage à lever avant de faire croître le control plane du cluster de gestion** (la
topologie CANONICAL à 4 serveurs). Il faut un fragment par ROLE, pas par cluster : l'initialisateur
sans `server`/`token`, les peers avec `server: https://<VIP>:9345` + le token — et donc une source
pour ce token (le cellier, scellé, comme le reste de l'identité).

See [[workload-grow-foundations-resume]] [[workload-bootstrap-chain-cilium-kubevip]]
[[rke2-config-reconciliation-nixrun-delivery]].
