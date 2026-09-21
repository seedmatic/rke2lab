---
name: funnel-identity-is-per-cluster
description: "Design convergé le 2026-09-21 : l'identité d'un funnel est (FQDN, device tailnet, cert), donc par CLUSTER — un persist plat ne serait pas négligé mais détruirait le budget Let's Encrypt des deux clusters à la fois, et le purge de mgmt supprimerait le device suffixé de wrkld à chaque passage"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-21T21:32:32.765Z
---

Brainstorm du 2026-09-21, déclenché par le blocage réel de `bioskop-wrkld` : le PVC `funnel-cert`
ne liait pas, donc `tailscale-funnel-cert-restore-operators` restait `Unknown`, et **toute** la pile
tailscale en dépend (`tailscale-operators`, `funnel-state`, `tailnet-purge` tous
`False dependency … is not ready`) — donc pas de Connector, donc la VIP jamais routable.

Cause immédiate : `FunnelCertRestoreManifestsUnit` porte
`private static final String NODE_NAME = "bioskop-mgmt-master"` — un littéral, utilisé pour le
`nodeAffinity` du PV **et** l'`ownerNodeID` du `ZFSVolume`.

## Pourquoi le per-cluster est FORCÉ, pas souhaitable

Toute la persistance des funnels n'existe que pour **le budget LE : 5 certs / FQDN exact / 168 h**
(`pac-in-cluster-render-spec.adoc#funnel-durability`). L'identité protégée est le triple
**(FQDN, device tailnet, cert)** — les trois par cluster.

Donc un `persist/funnel-cert` plat partagé ne serait pas juste désordonné : les deux proxies
écriraient le même `/persist/<leaf>/state.yaml`, les deux reviendraient comme de nouveaux devices,
les deux brûleraient leur budget → le `429` que le design existe pour empêcher.

⚠️ **Et pire, de façon déterministe** : le second cluster ne peut pas réclamer le nom nu, le tailnet
le suffixe (`flux-webhook-1`) — et la règle du purge dit qu'un doublon dérivé *ne matche jamais* le
`--keep-host` nu, donc **est toujours purgé**. Le Job de purge de mgmt supprimerait le device de
wrkld à chaque passage, en boucle.

## Décisions prises (utilisateur)

1. Dataset : **`tank/rke2lab/persist/<cluster>/funnel-cert`** — le palier `persist` reste UN frère de
   `control-nodes`, donc l'invariant « l'effacement éphémère ne l'atteint jamais » s'exprime à un seul
   niveau. Les sous-répertoires `<leaf>/state.yaml` restent dedans.
2. **UN PV par CLUSTER, pas par leaf.** Le graphe de dépendances sérialise déjà restore et backup, donc
   ils ne se disputent jamais l'unique claim RWO (« no shared-mount storage class needed »). Un PV par
   leaf jetterait cet argument.
3. **Le volume appartient au CONTRÔLEUR.** Le rendu déclare l'intention et ne nomme aucun nœud ;
   seed-incluster élit le nœud depuis le roster qu'il détient déjà et estampille l'affinité +
   `ownerNodeID`.

Le changement de contrat est **`FunnelLeaf`** (enum dual-realm dont le leaf est aujourd'hui l'identité
entière) qui devient **(cluster, leaf)**. Touche aussi le FQDN, le Secret `ts-<leaf>-state`, le
`ProxyClass` par funnel, et le `--keep-host` du purge.

Effet de bord heureux : la spec dit que renommer un FQDN ouvre un budget de 5 certs **neuf**. La
migration EST ce renommage, donc aussi la sortie de secours si celui de `flux-webhook` est entamé.
Coût : mettre à jour l'URL de webhook de l'App GitHub.

## Faits vérifiés qui ferment des fausses pistes

- **Le provisionnement dynamique ne remplace PAS l'adoption statique** : un cold start efface l'objet
  PVC, donc openebs provisionne un `pvc-<uuid>` neuf, l'ancien dataset fuit et le cert est perdu. Le PV
  pré-déclaré qui adopte un dataset au nom stable est la SEULE chose qui traverse un effacement d'etcd.
- **Un label de nœud ne libère pas** : `ownerNodeID` doit égaler le nom de l'objet `ZFSNode`, qu'openebs
  nomme d'après le nœud (vérifié : `ZFSNode/bioskop-wrkld-control-plane-v9fhz`, labels
  `openebs.io/nodename` et `openebs.io/nodeid` tous deux valués au nom du nœud). Le label ne vaut que
  pour la moitié scheduling (`spec.nodeAffinity` est un `NodeSelector` standard).
- **Sortir openebs du chemin serait une régression** : il est là délibérément, et c'est lui qui
  *applique* l'unicité d'accès (un seul `ZFSNode` sert le volume) au lieu de laisser `ReadWriteOnce`
  comme un contrat que personne ne police.
- **Pas de cercle d'amorçage** : le contrôleur n'a pas besoin de la VIP. Un nœud de charge répond sur
  son adresse LAN — mesuré, `https://192.168.1.21:6443` répond `Unauthorized` pendant que la VIP time
  out. Le cluster gestionnaire l'atteint avant que le Connector existe.
- Le Connector, lui, est **déjà correct et par cluster** (`createConnector(…, clusterName())`, routes
  depuis le blueprint du cluster ; et l'ACL tailnet auto-approuve tout le /18 vmnet pour `tag:k8s` —
  « so any cluster's routes »). Le côté tailnet a été conçu pour N clusters ; seul le trio funnel était
  mal attribué.

## Le label PET — rendre au nœud managé son identité stable

Décidé avec l'utilisateur : le contrôleur joue une logique basique — trier les Machines du control
plane par `creationTimestamp` (déterministe, monotone, possédé par CAPI) et assigner `master`,
`peer1`, `peer2` dans l'ordre, en sautant celles qui portent déjà un pet. Collant et idempotent par
construction.

Écrit comme **label de Machine** dans un domaine que CAPI propage au Node :
`rke2lab.io.node.cluster.x-k8s.io/pet: master`. Règle **vérifiée à la source**
(`util/labels/helpers.go`, `GetManagedLabels`) — la propagation Machine→Node couvre
`node-role.kubernetes.io`, `node-restriction.kubernetes.io` (+ `*.`), **`node.cluster.x-k8s.io`
(+ `*.`)**, plus les regex `--additional-sync-machine-labels`.

### Deux voies écartées, et pourquoi

⚠️ **NE PAS reprendre la création des Machines.** Les pré-créer avec des noms de pets donnerait des
noms déterministes et dissoudrait la cause racine — mais `RKE2ControlPlane` nomme parce qu'il
**possède le cycle de vie** : init-vs-join, appartenance etcd + quorum, scale, rollouts, remédiation.
Prendre le nommage prend une tranche non bornée de tout ça. Le chemin d'adoption prouve que CAPRKE2
compte une Machine pré-créée comme sa réplique, mais explicitement **sans l'amorcer**
(`dataSecretName` sans `configRef` — la sentinelle) : ce n'est donc PAS une preuve pour le greenfield.

⚠️ **NE PAS compter sur la cloud config côté managé.** Le canal par machine existe bien (mesuré :
`Machine …-rmz8z` → `configRef: RKE2Config/…-566v4`), mais **CAPRKE2 l'écrit** depuis l'unique spec du
`RKE2ControlPlane` et régénérerait tout patch. `spec.files` est un jeu unique pour toutes les
répliques — c'est exactement pourquoi les 7 fragments RKE2 sont invariants par nœud. Côté **hôte**
l'inverse est vrai : `InstanceGrow` écrit un cloud-init par instance, donc `node-label=` y serait
trivial. `NodeRestriction` n'est pas l'obstacle : il protège `kubernetes.io/` et `k8s.io/`, un domaine
tiers est permis depuis un kubelet.

### Ce que le label ne règle PAS

`ZFSVolume.ownerNodeID` exige toujours le **nom** du nœud (il doit égaler le nom de l'objet `ZFSNode`).
Le label ne sert que la moitié scheduling. Et il ne règle pas la fuite de datasets.

## ★ Le pool openebs est partagé entre clusters — BUG VIVANT

`OpenebsZfsManifestsUnit` : `final String ephemeralPool = layout.controlNodePool("master")` — un pet
**codé en dur**, et `poolname` est un paramètre unique de StorageClass. Donc tous les nœuds de tous les
clusters résolvent le même chemin hôte.

**Mesuré le 2026-09-21** : le PV `pvc-e13b83da-59d7-4250-9288-c3cb7558af0f` lié **dans le cluster de
charge** est listé sur l'hôte sous `tank/rke2lab/control-nodes/master/`. Les données PVC de wrkld
vivent dans le dataset du nœud de **gestion**. Le dataset propre du nœud existe pourtant
(`control-nodes/bioskop-wrkld-control-plane-v9fhz/`, créé par le chemin profil/containerd) — openebs
l'ignore.

Correctif : **pool par cluster**, pas par pet (une StorageClass par pet + `allowedTopologies`
n'achèterait rien — les nœuds d'un cluster cohabitent sous un parent comme des `pvc-*` distincts). Ça
donne aussi une racine bien définie au GC. Spec :
`docs/architecture/patterns/dataplan-single-source.adoc#per-cluster-pools`.

Et `CONTROL_NODES` (`master`, `peer1..3`) n'a de sens que pour un cluster **grown par l'hôte** : pour un
nœud CAPI openebs crée son dataset à la demande, hors layout déclaré.

## ★ L'invariant à retenir

**Rien de ce que le rendu produit ne peut être clavé sur le nom d'un nœud managé.** C'est la QUATRIÈME
fois que « un nœud CAPI porte un nom aléatoire » coûte quelque chose : la divergence de nommage de
l'infraRef, l'absence de GC des datasets ZFS, ce PV de funnel, et la liste déclarée `CONTROL_NODES`
(plate, `master/peer1..3`, alors que le réel porte déjà
`control-nodes/bioskop-wrkld-control-plane-v9fhz` qu'openebs s'est créé hors layout).

Note de cohérence : `DataplanLayout` projette la forme inverse pour le cache maven
(`tank/rke2lab/<cluster>/persist/maven-cache`, « a foundation-3 item »). Les deux ne doivent pas
diverger.

Spec : `docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc#funnel-per-cluster` et
`#funnel-node-affinity`.

See [[workload-bootstrap-chain-cilium-kubevip]] [[zfs-dataset-gc-missing]]
[[tailnet-node-identity-ephemeral-ghosts]] [[rke2-peer-join-config-gap]].
