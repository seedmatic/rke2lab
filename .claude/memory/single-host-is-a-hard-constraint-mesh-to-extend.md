---
name: single-host-is-a-hard-constraint-mesh-to-extend
description: "Décision utilisateur du 2026-09-24 — un cluster ne s'étend JAMAIS sur plusieurs bare-metals ; la croissance passera par le cluster mesh, donc tout raisonnement multi-hôte est hors périmètre"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-24T16:47:51.840Z
---

Décision de l'utilisateur, 2026-09-24 : *« le single host on le garde pour une
contrainte dure, on fera du cluster mesh quand on aura besoin d'étendre »*.

Donc : **un cluster vit entièrement sur UN bare-metal**, et l'extension se fera en
fédérant des clusters (cilium clustermesh, déjà customisé par le `HelmChartConfig
rke2-cilium`), jamais en faisant enjamber un cluster sur deux hôtes.

## Ce que ça autorise à arrêter de défendre

Tous les « et si un cluster enjambait deux hôtes » deviennent hors sujet — et il y
en avait beaucoup, parce que le stockage local d'openebs raisonne par nœud :

- l'affinité de PV sur le **rôle** (`node-role.kubernetes.io/<role>`, Exists) n'est
  plus un compromis en attendant un futur multi-hôte : elle est **juste**, puisque
  tout nœud du rôle voit forcément le dataset. Voir
  [[volume-owner-is-who-mounted-not-where-data-is]] ;
- le dataplan clé par `ClusterRole` (un cluster par rôle et par hôte) est **cohérent**
  avec la contrainte, pas un raccourci ;
- la conception overlay + boîte de dépôt du cache Maven n'a besoin ni de
  `ReadOnlyMany`, ni d'un service HTTP, ni d'un `promote` que CSI n'offre pas. Voir
  [[workspace-is-not-a-cache]].

## La preuve du substrat, faite à la main

L'utilisateur a monté le MÊME dataset dans deux nœuds à la fois pour trancher :
`findmnt /mnt` dans `bioskop-wrkld-control-plane-*` **et** dans `bioskop-mgmt-master`
montrait `tank/rke2lab/wrkld/persist/funnel-cert`, `rw`, simultanément (puis démonté
— aucun résidu). Donc « un dataset ZFS ne se monte qu'une fois » est **faux** : le
noyau l'autorise, et un montage est de toute façon visible dans des centaines de
namespaces.

⚠️ Ce qui reste non mesuré est plus étroit : que le **plugin nœud** d'openebs veuille
bien publier un `ZFSVolume` dont l'`ownerNodeID` désigne un autre nœud. Le noyau
l'autorise n'est pas le driver le fait.

**La seule frontière réelle est donc l'HÔTE** — un autre bare-metal a un autre `tank`
— et elle est franchie par du mesh, pas par du stockage partagé.
