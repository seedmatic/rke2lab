---
name: kubeconfig-context-per-cluster-intention
description: "PROCHAINE TÂCHE (convenue 2026-09-21, après le checkpoint) : .local.d/kubeconfig.yaml ne porte qu'un contexte (mgmt) ; en vouloir un par cluster déclaré par une intention — toute la matière existe déjà dans le cellier"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-21T15:22:21.107Z
---

Demandé par l'utilisateur, à faire **juste après le cold start du 2026-09-21**. Motivation donnée :
pouvoir requêter chaque cluster directement depuis bioskop.

## L'état

`.local.d/kubeconfig.yaml` ne porte **qu'un** contexte :

```yaml
clusters:  [{ server: https://bioskop-mgmt-master.local:6443 }]
contexts:  [bioskop-mgmt]
current-context: bioskop-mgmt
```

Écrit host-side après le grow : `AdminCredentials` est révélé du cellier
(`ClusterPkiCoordinate.ADMIN_CREDENTIALS`, SEALED) et `AdminCredentials.kubeconfig(endpoint, …)`
enveloppe ses trois blocs PEM autour d'un endpoint. Le `ClusterKubeconfigManifestsUnit` rend la même
matière deux fois : l'opérateur (endpoint mDNS) et le Secret CAPI `<cluster>-kubeconfig` (endpoint VIP).

## Reformulation à retenir

Les intentions sont la **source d'énumération**, pas ce qu'on interroge — elles vivent toutes dans le
cluster de gestion, dont le contexte existe déjà. La cible est **un contexte par cluster qu'une
intention déclare**.

⚠️ Corollaire de conception : calculer la liste **host-side depuis `workloadTargets`** (gravé au grow
dans le `manifest.yaml` racine, rejoué du HEAD par les UPDATE), PAS en lisant les `ClusterIntention`
vivantes — sinon la kubeconfig ne se régénère que quand le cluster répond, c'est-à-dire exactement
quand on n'en a pas besoin.

## Toute la matière existe

`WORKLOAD_CLUSTER_CAS` porte, **par cluster de charge**, quatre paires de CA avec leur
`keyPem` — dont **`clientCa`**, précisément ce qui signe un certificat client admin — et
`serverCa.certChainPem`, la chaîne qui vérifie l'apiserver (enracinée `mammoth-skate-tls`, donc
nativement approuvée). Ce sont les trois blocs d'`AdminCredentials`.

## Ce qui manque

. **Un `AdminCredentials` par cluster de charge.** Aujourd'hui `ADMIN_CREDENTIALS` est une coordonnée
  de cellier **unique** (le cluster de soi) alors que `WORKLOAD_CLUSTER_CAS` est déjà une liste. C'est
  l'asymétrie à lever — modification du sceau PKI.
. **Une écriture qui FUSIONNE.** Le writer produit un kubeconfig complet à un contexte ; il faut
  itérer et assembler `clusters`/`users`/`contexts` dans un seul fichier, `current-context` restant
  `bioskop-mgmt`.

## L'endpoint, seule vraie subtilité

mgmt marche avec `bioskop-mgmt-master.local` parce qu'il **porte l'identité semée** (il a été adopté).
Un workload greenfieldé, non : `bioskop-wrkld-master.local` est dans les SANs du certificat mais ne
résout rien, aucun nœud ne portant ce nom. Ce qui résout est `…-control-plane-<hash>.local`, un nom
tiré au hasard par CAPI.

Donc le contexte du workload doit viser la **VIP** (`10.80.15.10`) : elle est dans les SANs, elle est
déterministe, elle survit au re-provisionnement. Il sera donc **écrit juste dès maintenant et
commencera à fonctionner le jour où le Connector l'annonce** — voir
[[workload-bootstrap-chain-cilium-kubevip]]. (`192.168.1.11` fonctionne aujourd'hui et est dans les
SANs, mais c'est une adresse DHCP sur un nœud au nom aléatoire : ne pas la graver.)

See [[workload-grow-foundations-resume]] [[zfs-dataset-gc-missing]].
