---
name: netplan-projection-described-hosts
description: "Corrigé le 2026-09-22 — network-blueprint.json décrivait deux HÔTES là où il y a quatre clusters, donc ndh n'a jamais connu le réseau du cluster de charge. Et le roster est par RÔLE : mgmt est single-node, ce que la liste canonique contredisait en promettant six nœuds"
metadata:
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-23T05:26:43.993Z
---

`network-blueprint.json` — la projection que **ndh** consomme pour ses réservations dnsmasq LAN et son
attribution de segments — connaissait deux réseaux là où il en existe quatre. Corrigé par rke2lab
`237d4be65`.

## La cause

`NetplanBlueprintScenario` itérait `{bioskop, nikopol}` et passait chacun comme **nom de cluster**. Or
`ClusterRole.of()` sur un nom sans tiret retombe sur `MGMT` — son repli documenté, voulu pour les
surveys. Donc la projection décrivait deux pseudo-clusters, tous deux mgmt.

Chronologie vérifiée : la table de l'exportateur date du **2026-08-13**, la loi
`clusterId = (hostId << 1) | roleId` du **2026-08-29**. L'exportateur précède la dimension rôle de seize
jours et n'a jamais été mis à jour ; le repli l'a empêché d'échouer bruyamment.

⚠️ **Le repli n'est pas à retirer** (les surveys s'en servent) — c'est l'appelant qui ne doit pas en
dépendre.

## Ce que ça coûtait

`bioskop-wrkld` vit sur `10.80.8.0/21`, VIP `10.80.15.10` (mesuré sur `vmnet0` du nœud). La projection
étiquetait ce `/21` `nikopol-cluster-net`, et à la régénération il **disparaissait** — passerelle et plage
DHCP avec lui. C'est pourquoi le nœud de charge a un bail ordinaire du routeur (`192.168.1.13`) et une MAC
générée par CAPN : rien ne le décrivait, donc rien ne le réservait.

L'exportateur redisait aussi deux faits que le netplan possède, et les deux avaient dérivé : une table
hôte→id là où vont des ids de cluster, et `worker1=10 / worker2=11` alors que `nodeId()` dit **4** et **5**.

## ★ Le roster est par RÔLE — mgmt est single-node

Décision utilisateur, explicite : **le cluster de mgmt est single-node**, pas transitoirement.

`CANONICAL_NODE_NAMES` déclarait six nœuds « of every cluster » tandis que le découpage LAN dimensionne
mgmt à **un** (`/29` à `.128`, rempli à `host(3)`). Les deux se contredisaient, et le débordement était
invisible : avec les ids gonflés de l'exportateur (10, 11) les adresses tombaient à `.141`/`.142`, assez
loin pour ressembler à un `/27` plausible. Avec les **vrais** ids sur un `/29` :

- `worker1` (id 4) → `host(7)` = `.135` — adresse de **broadcast** du `/29`
- `worker2` (id 5) → `host(8)` = `.136` — hors du `/29`, et c'est l'adresse réseau de `bioskop-mgmt-lb`

Donc : `ClusterTopology.of(role)` répond par rôle (`MGMT` = 1,0,0 ; `WRKLD` = 1,3,2) et expose
`nodeNames()`. `CANONICAL_NODE_NAMES` est désormais documenté comme le **surensemble ordonné** dont un
roster se découpe — l'énumérer directement, c'est décrire un cluster de charge quel que soit le cluster
regardé.

⚠️ Deux consommateurs l'énumèrent encore en entier : `BlueprintRowEnumerator` (lignes de réservation
bbox, sur le LAN → **déborde** pour mgmt) et `GrowNetworkResolver.rawDnsmasq` (sur le vmnet `/21`, donc
inoffensif). Les deux unités cluster-api, elles, tranchent déjà avec `.limit(…_CONTROL_PLANE_REPLICAS)` —
un troisième endroit où « combien de nœuds par rôle » est dupliqué.

## Le résultat, vérifié

Quatre clusters, ids 0..3 : `bioskop-{mgmt,wrkld}`, `nikopol-{mgmt,wrkld}` → `/21` en
`10.80.{0,8,16,24}.0`. mgmt un nœud, wrkld six. Chaque adresse de nœud **dans** la tranche LAN de son
cluster, **aucun** recouvrement entre segments, et `bioskop-mgmt/master` toujours à `192.168.1.131` — donc
aucune réservation vivante ne bouge.

**Aucun changement côté ndh** : `catalog/default.nix` itère déjà `builtins.attrNames addressing` et nomme
la clé `cluster`. Il ne manquait que des entrées → un bump de flake suffit.

## À faire, laissé exprès pour son propre tour

`Cidr.host(offset)` **incrémente sans borner** — quitter le préfixe est silencieux, c'est ce qui a rendu
le débordement invisible. Ajouter la borne fera surgir la sur-énumération de bbox.

## ❌ Épingler la MAC des machines CAPN : porte FERMÉE (2026-09-22)

Tranché par l'utilisateur : « assigner les macaddr via capi/capn n'est pas prévu. » Le champ existe bien
(`LXCMachineSpec.Devices`, syntaxe `"eth0,hwaddr=…"`), mais ce n'est pas un usage conçu — et de toute
façon il vit aussi sur le `LXCMachineTemplate`, **un jeu pour toutes les répliques** : la même MAC partout
serait une collision, pas une identité. Troisième occurrence du même invariant après `spec.files` et le
label pet : *une réplique managée ne reçoit pas d'identité par nœud depuis un gabarit* ; la question n'est
jamais « quel champ » mais « qui crée l'objet ».

Conséquence définitive : un cluster managé n'a **pas** d'adresse de nœud dérivable. Son seul endpoint
stable est sa **VIP** — et c'est exactement ce à quoi elle sert.

## ⚠️ Le prérequis CAPN sur la VIP, non satisfait

`https://capn.linuxcontainers.org/reference/templates/kube-vip.html` exige « the management cluster can
connect to the VIP address ». C'est structurel : `Cluster.spec.controlPlaneEndpoint` EST la VIP, donc
CAPI/CAPRKE2 parlent au cluster managé par là. Or les deux `/21` sont disjoints et le nœud de mgmt
envoyait la VIP de wrkld à `192.168.1.254` (sa route par défaut sort par le LAN, pas par la passerelle
vmnet `10.80.0.1`). À corriger par une route pour l'autre `/21` via `10.80.0.1`, ou par le tailnet — c'est
ce qui sépare « wrkld existe » de « CAPI le gère ». Explique pourquoi `RemoteConnectionProbe` est la
condition qui compte.

NOTE: le template CAPN lui-même ne s'applique pas tel quel — il livre kube-vip en pod statique via la
spec **kubeadm** (`/etc/kubernetes/manifests/`), alors que nous sommes en CAPRKE2
(`/var/lib/rancher/rke2/agent/pod-manifests/`) et que nous avons choisi l'installation par Flux depuis la
branche (seed-incluster `31d5b15fb`).

See [[funnel-identity-is-per-cluster]] [[kubeconfig-context-per-cluster-intention]]
[[single-owner-rule]].
