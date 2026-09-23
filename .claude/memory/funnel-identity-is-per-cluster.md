---
name: funnel-identity-is-per-cluster
description: "ABOUTI le 2026-09-22 — les funnels sont par cluster, le volume persist est posé par un contrôleur in-cluster qui élit le nœud, et le dataplan a une racine par cluster. Vérifié vivant : VolumeIntention Placed, PVC funnel-cert Bound, restores Complete"
metadata:
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-22T21:24:41.970Z
---

Le blocage de départ (2026-09-21) : `FunnelCertRestoreManifestsUnit` portait
`NODE_NAME = "bioskop-mgmt-master"` en dur, donc le PVC `funnel-cert` ne liait jamais dans le cluster
de charge, donc toute la pile tailscale restait bloquée, donc pas de Connector et pas de VIP.

## ✅ Livré le 2026-09-22, et vérifié en vivant

```
volumeintention/funnel-cert   NODE=bioskop-mgmt-master   PHASE=Placed   READY=True
pvc/funnel-cert               Bound
funnel-cert-restore-{flux,pipelines}-webhook   Complete
tailnet-purge                                  Complete
tank/rke2lab/mgmt/ephemeral/nodes/master/containerd   ← créé par le nœud, bonne racine
```

rke2lab `fc444a5a9`→`237d4be65` (11 commits), seed-incluster `279b459ce`.

**1. Le dataplan a une racine par cluster.** `tank/rke2lab/<role>/{ephemeral/{nodes,volumes},persist}`.
Le niveau *nature* porte l'invariant (`zfs destroy -r <role>/ephemeral`, et la racine de cluster n'est
**pas** une cible de destruction — elle emporterait persist). Le niveau *propriétaire* donne un parent
par écrivain : `nodes/` au nœud, `volumes/` à openebs. Seuls les PARENTS sont déclarés ; openebs ne crée
pas son `poolname`, le nœud crée son `containerd` avec `zfs create -p`. `CONTROL_NODES` supprimé.
Clavé par **rôle** parce que la projection consommée par ndh est une liste unique matérialisée sur tous
les hôtes — un roster de flotte y serait un second propriétaire. Le nœud dérive son rôle du 2e champ du
hostname (uniforme host-grown et greenfield ; `node.env` n'a pas de scalaire de rôle).

**2. Le volume est posé par un contrôleur, pas par le rendu.** `VolumeIntention`
(`cluster.seedmatic.io/v1alpha1`) déclare dataset/taille/claim/rôle de nœud éligible et **aucun nœud** ;
le réconciliateur élit, puis crée le `ZFSVolume` (`ownerNodeID`) et le PV (`nodeAffinity`). Élection
**collante** — la donnée ne suit pas le choix. Rien n'est patché après pose : un objet existant nommant
un autre nœud est un fait à remonter, pas une dérive.

★ **Un seul binaire, un seul jeu de CRD, un seul ClusterRole** (décision utilisateur). Un second serait
à re-fusionner dès que le cluster de charge héberge des vclusters, puisque c'est la même logique
intention→CAPI. Ce qu'il faut n'est pas un mode mais **une** porte : `PoolReflection` est le seul
réconciliateur qui *surveille* un type CAPI (`Machine`), et un informer sur un kind non servi tue le
manager au démarrage — il s'enregistre donc si le RESTMapper connaît `Machine`. Les autres touchent CAPI
en unstructured, ce qui n'échoue que par réconciliation. Présence, pas configuration. See
[[seed-vcluster]].

L'unité de déploiement a quitté le domaine `clusterApi` (MGMT-only) pour **`runtime`** — le seul domaine
de base qui dépend déjà de `cluster` (namespace) et `platform` (token) et qui *contient* l'env flox.
`cluster` aurait été un cycle.

**3. L'identité funnel est `(cluster, leaf)`.** `FunnelLeaf` garde le vocabulaire du leaf ; un record
`Funnel` porte la paire et dérive `hostname = <cluster>-<leaf>` (cluster d'abord), `stateSecret`,
`proxyClass`. Le sous-répertoire de persistance et les noms d'objets gardent le leaf nu (`leafName()`) :
le dataset est déjà par cluster, un Job vit dans un namespace de cluster. `PacWebhookFunnel` cesse de
redéclarer `"pipelines-webhook"`.

## ★ Le cert tailscale ne se re-demande pas — vérifié à la source

`feature/acme/cert.go`, `shouldStartDomainRenewal` : la décision est **purement calendaire** (ARI, sinon
2/3 de la durée de vie, sinon `NotAfter − now < minValidity`). **Rien ne compare l'émetteur** ; l'URL du
directory ACME n'est que journalisée. Et aucune erreur ne remonte : GitHub refuse côté client, tailscaled
a servi son cert.

Conséquence : un cert staging valide serait servi ~90 jours après un retour en production. D'où le
marqueur — le backup écrit la posture dans `/persist/<leaf>/issuance`, le restore jette un cert qui ne
correspond pas. **Deux non-correspondances, une règle** : posture différente, ou hostname différent (le
renommage). Seules les clés `*.crt`/`*.key` partent, la clé de nœud reste → même device, une seule
émission. Testé contre le vrai `yq` dans les deux branches.

`tailscale cert --min-validity <durée>` existe comme levier de re-mint mais **n'est pas une garantie** :
son aide dit que le maximum autorisé dépend de la CA, donc un cert frais ne peut pas être forcé.

Le store est **clavé par domaine**, avec des clés séparées cert / identité — c'est ce qui permet de jeter
l'un en gardant l'autre.

## ⚠️ Fenêtre STAGING ouverte (2026-09-22)

`FunnelCertIssuance.current()` = `STAGING`, propriétaire **unique** des deux moitiés : le rendu tailscale
en projette `useLetsEncryptStagingEnvironment`, la réconciliation ghapp en projette `insecure_ssl` (qui
était une constante `0` **ré-affirmée à chaque grow** — donc le grow défaisait la bascule manuelle de
l'opérateur). Basculer sur `PRODUCTION` est désormais tout le geste.

Le cert de mgmt a été **perdu** au passage (dataset recréé au lieu d'être renommé — ma séquence était
fautive, la cible existait déjà). Coût quasi nul : il était lié à `flux-webhook`, un FQDN abandonné.

## ★ 5e occurrence — le `providerID` du Node, et sa résolution en amont (2026-09-23)

Le cluster de charge était **entièrement debout** — Node `Ready`, etcd, control-plane — et CAPI refusait
de le déclarer disponible : `RKE2ControlPlane` disait « Waiting for at least one machine to be ready »
d'une machine qui l'était. Cause : CAPI relie Machine↔Node par le **`providerID` seul**, et le Node n'en
portait aucun. Mesuré : Node `bioskop-wrkld-control-plane-899lr` `Ready` / `providerID <none>`, tandis que
sa Machine `…-8dvzv` portait `lxc:///…-899lr`.

Même forme que les quatre précédentes : la valeur est **par nœud**, la spec qui la porterait est **un jeu
pour toutes les répliques**. Le chemin host-grown le résout côté nœud (`nixos/rke2.nix`, oneshot
`rke2lab-provider-id`, conditionné à `node.env` — qu'un nœud CAPRKE2 n'a pas, donc il saute, comme son
commentaire l'annonçait déjà).

★ **Et l'amont avait déjà la réponse** : `LXCCluster.spec.cloudProviderNodePatch` — CAPN estampille
lui-même le Node. Sa doc de CRD nomme notre cas mot pour mot (« might not be easily doable when using
other ControlPlane and Bootstrap providers »). Posé dans seed-incluster `8716a97fe`. L'alternative des
templates CAPN par défaut est un arg kubelet templaté par cloud-init (`provider-id: {{ v1.local_hostname }}`),
écartée : elle suppose que jinja survit aux `write_files` de CAPRKE2, et son snippet omet le préfixe
`lxc:///`.

⚠️ Il fallait que CAPN **joigne** le cluster de charge, donc ça n'aurait pas pu marcher avant que
`RemoteConnectionProbe` passe True le même jour. La leçon générale : quand une identité par nœud manque,
chercher d'abord si le provider sait l'estampiller — avant d'inventer un canal.

⚠️ `ensure()` laisse un objet existant intact, donc ce champ n'atteint que les **nouvelles**
`LXCCluster` ; la vivante a pris un `kubectl patch`.

See [[single-owner-rule]] [[netplan-projection-described-hosts]]
[[kubeconfig-context-per-cluster-intention]] [[workload-bootstrap-chain-cilium-kubevip]].
