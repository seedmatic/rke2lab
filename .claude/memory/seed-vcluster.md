---
name: seed-vcluster
description: "Les vclusters sont contrôlés comme les clusters — par CAPI (décision utilisateur 2026-09-22). Et l'opérateur vcluster autonome que nos plans supposaient N'EXISTE PAS en amont : le provider CAPI est le seul chemin déclaratif à CRD, et il n'héberge des vclusters que dans le cluster où il tourne"
metadata:
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-22T06:24:10.622Z
---

Décision de l'utilisateur, 2026-09-22 : « dans notre modèle, on a basé le contrôle des clusters via
capi/capn, donc il est logique que les vclusters soient contrôlés de la même manière. »

## ⚠️ Ce que la mémoire précédente disait de faux

La version du 2026-06-03 posait comme prérequis « créer un `VClusterOperatorManifestsUnit` (HelmChart
CR loft vcluster) » et parlait d'un `VCluster` CR « réconcilié par l'opérateur ».

**Il n'existe aucun opérateur open-source autonome réconciliant une CRD `VCluster`.** Vérifié le
2026-09-22 sur la doc vcluster : les voies de déploiement sont la CLI, Helm, Terraform, ArgoCD, Flux
(HelmRelease), le provider CAPI, et la Platform commerciale. Tout repose sur le chart Helm. Donc
`docs/architecture/cluster-api/vcluster-implementation-plan.adoc` § « Piece 1 — the vCluster operator
as a Tier 1 add-on » décrit un composant inexistant (le README le marquait déjà « outdated » — on sait
maintenant en quoi).

Il ne reste que deux chemins déclaratifs : **HelmRelease par vcluster** (pas de CRD, pas d'API
uniforme) ou **le provider CAPI**. La décision ci-dessus choisit le second.

## Le provider CAPI — faits vérifiés à la source

`loft-sh/cluster-api-provider-vcluster` :

- C'est un provider d'**infrastructure**. CRD unique **`VCluster`**, groupe
  `infrastructure.cluster.x-k8s.io/v1alpha1`. Un vcluster se déclare comme une paire
  `Cluster` (`cluster.x-k8s.io/v1beta1`) + `VCluster`.
- **Aucun objet `Machine`.** Le vcluster est géré comme une unité entière, pas comme un parc.
- Le plan de contrôle est déployé **par chart Helm** (`vcluster` depuis `charts.loft.sh` ; variantes
  `vcluster-k8s`, `vcluster-k0s`, `vcluster-eks`). `spec.helmRelease` porte values + chart.
- Installation : `clusterctl init --infrastructure vcluster`.
- ★ **Contrainte de topologie porteuse** : « at the moment, the provider is able to host vclusters
  only in the cluster where the vcluster provider is running (management cluster) ». Donc pour avoir
  des vclusters DANS `bioskop-wrkld`, le provider doit tourner DANS `bioskop-wrkld`.

## ★ La conséquence dans notre code — `ClusterRole` devient faux

`ClusterRole` (manifests-contract) affirme aujourd'hui, en javadoc ET en code :

> a workload cluster does not host CAPI at all — so the set follows the role

et `enabledDomainIds()` ne donne `catalog.clusterApi()` qu'à `MGMT`. Le modèle retenu rend cette
phrase fausse.

Le correctif n'est **pas** de basculer l'appartenance du domaine : `clusterApi` devient un domaine à
**contenu variable selon le rôle**.

- `MGMT` : CAPI core + **CAPN** + CAPRKE2 — le cycle de vie MACHINE, qui reste MGMT-only.
- `WRKLD` : CAPI core + **le provider vcluster** — aucune machine, donc ni CAPN ni CAPRKE2.

Autrement dit un rôle ne dit plus *si* on héberge CAPI, mais *quel* CAPI. C'est une décomposition du
domaine, pas un booléen — à traiter comme telle (le domaine `clusterApi` a des unités par rôle).

## Ce que ça ne change PAS — seed-incluster ne tourne pas dans la charge

L'invariant « un seul adopteur » de
`docs/architecture/cluster-api/cluster-seeding-controller.adoc` § *single-adopter topology* tient,
mais la bonne raison n'est pas « pas de machines donc pas de CAPI » (faux, cf. ci-dessus) : c'est que
le **primitif de seed-incluster n'a aucun référent** pour un vcluster. Son primitif est
*adopt = réconcilier avec un cluster DÉJÀ vivant, à machines* — pool, pets, providerID, quorum etcd,
init-vs-join. Un vcluster naît toujours neuf et n'a rien de tout ça.

⚠️ Et le risque s'aggrave dans l'autre sens : puisque les CRD CAPI **seront** présentes dans
`bioskop-wrkld`, un seed-incluster qu'on y ferait tourner « dans un mode » pourrait réellement devenir
un second adopteur de `bioskop-wrkld` lui-même. C'est l'argument qui a fait choisir un **contrôleur
dédié** plutôt qu'un mode, pour le mutateur de volumes. See [[single-owner-rule]].

Question ouverte pour plus tard : un vcluster a son propre Flux, donc potentiellement son propre
funnel — l'identité `(cluster, leaf)` de [[funnel-identity-is-per-cluster]] devrait pouvoir accueillir
un niveau de plus sans être réécrite.

See [[funnel-identity-is-per-cluster]] [[workload-bootstrap-chain-cilium-kubevip]]
[[single-owner-rule]].
