---
name: nikopol-mgmt-federation-clustermesh-first-case
description: "Déclarer nikopol-mgmt — modèle B (deux mgmt coexistent puis auto-adoption) tranché le 2026-09-25 ; et c'est le premier cas d'usage du clustermesh MGMT, pré-armé mais jamais exercé."
metadata: 
  node_type: memory
  type: project
  originSessionId: c2c2a472-f982-468d-98de-1b23bbf4e274
  modified: 2026-09-25T16:27:41.728Z
---

Chantier ouvert le **2026-09-25** : déclarer le cluster de gestion de nikopol.
Whiteboard : `.claude/claude-preview.adoc` (7 figures).

**Tranché — modèle B avec auto-adoption.** `bioskop-mgmt` **enfante** `nikopol-mgmt` par intention
cross-hôte (`workloadTargets: - host: nikopol / role: mgmt` — `WorkloadTarget(host, role)` accepte
`mgmt` tel quel) ; `nikopol-mgmt` porte son propre CAPI (`ClusterRole.MGMT` ⇒ `+ clusterApi`,
structurel) et **s'auto-adopte** ensuite : bioskop est la sage-femme, pas le tuteur.
Écartés : le modèle A (un mgmt qui *voyage* — ce que
`docs/architecture/cluster-api/management-workload-topology.adoc` § *Naming convention* écrit
encore, « Target placement: management on bioskop ») et la fédération durable sans auto-adoption.
⚠ La spec est donc en retard sur ce point — à reporter quand la forme sera stable.

**Tranché aussi le 2026-09-25 — trois décisions de plus.**

1. **Connectivité complète PAR ÉTAPES, le débranchement comme juge.** Étape 1 = services globaux
   (`service.cilium.io/global`, affinité locale) ; étape 2 = **l'épreuve** — nikopol passe sur hotspot
   et on MESURE (le chemin `tailscale ping` direct→DERP ; cilium retire-t-il les endpoints distants ;
   le service global bascule-t-il sur son backend local ; **aucune** alarme de santé ne doit crier ;
   le re-appariement au retour est-il sans intervention ; le GitOps + le plan local de nikopol doivent
   être indifférents) ; étape 3 = pod-à-pod plein, seulement si la dégradation est propre.
   ★ **Construire sur le chemin DURABLE même quand le raccourci marche** — sinon l'épreuve casse au
   lieu de valider.
2. **CA du role-mesh : option B — frères + bundle de confiance croisée.** Aucune clé privée partagée.
   L'argument décisif n'est pas esthétique : `clustermesh-remote-users` est une liste d'utilisateurs
   **par cluster distant**, ce qui n'a de sens que si chaque pair a une identité DISTINCTE — un cert
   client partagé (option A) effondre tous les pairs en un utilisateur et **annule** cette
   autorisation. Donc B est la forme que cilium présuppose. Honnêteté : le sceau détient déjà toutes
   les clés de CA de la flotte, donc B borne le rayon d'un **membre**, pas celui du sceau.
3. ★ **La forme de nom cible est `nixos.<host>`, et elle UNIFIE** (choix utilisateur, vérifié vivant).
   Servie par le dnsmasq **de cet hôte** dans sa zone `.<host>`, portée par le split-DNS du tailnet →
   transport-indépendante, contrairement à `.lan` qui dépend de la box. Même dérivation pour une cible
   du même hôte et d'un autre ⇒ **le discriminant `target.host()` disparaît**. `LAN_DOMAIN` /
   `nixosLanFqdn` sont à SUPPRIMER (plus d'audience).

**Mesuré le 2026-09-25 — le piège du LAN partagé.** Les deux hôtes sont *actuellement* sur le même L2
domestique : `tailscale ping nikopol-nixos` → `via 192.168.1.34 direct in 5ms`, et
`nikopol-nixos.lan → 192.168.1.34` **résout**. Donc le code actuel (`nixosLanFqdn()`) marcherait
aujourd'hui et casserait SILENCIEUSEMENT au départ de nikopol. L'itinérance est une condition future
et intermittente, pas l'état du jour.
État vérifié de la zone : `domain nikopol → nameserver 172.16.16.1` (split-DNS en place),
`vzhost.nikopol → 172.16.24.2`, `vzhost.bioskop → 172.16.8.2` (symétrique), l'API incus répond sur
`https://172.16.16.1:8443` **et** `https://172.16.0.1:8443` (les passerelles `fabric-br`,
`172.16.<hostId×8>.1`). Mais `nixos.nikopol` **et** `nixos.bioskop` sont NXDOMAIN → il manque **une
ligne** dans `segmentHostRecords` de ndh `modules/nixos/baremetal-segment.nix`, à côté du
`vzhost.<host>` déjà là, uniformément pour les deux hôtes.
⚠ Le nom s'unifie mais la **joignabilité reste à deux étages** : les nœuds ne sont pas membres du
tailnet (pas de split-DNS, pas de route `172.16.16/20`), donc même-hôte = direct sur la fabric,
cross-hôte = encore l'egress (`tailnet-target-ip: 172.16.16.1` ; qu'une annotation accepte un nom de
split-DNS est à vérifier).

**★ La pièce que personne n'a posée : le mesh n'a AUCUN consommateur.** Vérifié, pas supposé — zéro
`service.cilium.io/global` dans tout l'arbre, et aucune occurrence de `remoteCluster`/`peerCluster`/
`remote-cluster`. Toute l'infrastructure est pré-armée (10 pièces sur 13 en place) et personne ne roule
dessus. Ce n'est pas un défaut : les `cluster.id` uniques et les CIDR disjoints sont nécessaires **de
toute façon** (la fabric L2 partagée l'impose entre le mgmt et le wrkld d'un même hôte, non maillés).

**L'idée qui structure tout : deux traversées cross-hôte de natures DIFFÉRENTES.**

- Le plan **CAPI** est un *échafaudage de naissance*. Après auto-adoption, le CAPN de nikopol est
  in-cluster et parle à l'incus **local** → `nikopol-nixos.lan` (ce que le code écrit déjà) redevient
  juste. L'échafaudage doit rester disponible (chaque re-naissance) mais ne porte aucun régime
  permanent → la tolérance à l'absence en continu sort du périmètre.
- Le **clustermesh MGMT** est *permanent*. Et c'est le vrai enjeu : `cluster-addressing-plan.adoc`
  § *Clustermesh — two role-partitioned meshes* le prévoit nommément — `bioskop-mgmt` (`cluster.id=1`,
  pods `10.44/16`) ↔ `nikopol-mgmt` (`cluster.id=3`, pods `10.46/16`), ids uniques et CIDR disjoints
  **par dérivation**. Deux mgmt vivants = le **premier cas d'usage réel** d'un clustermesh.

**Le mesh est pré-armé depuis toujours et n'a jamais eu de second membre.**
`CiliumConfigManifestsUnit` rend déjà `cluster.name`/`cluster.id` dérivés (`blueprint.meshClusterId()`
— le placeholder `id: 7` de la spec a disparu du code), `clustermesh.enabled: true`, `useAPIServer`,
l'apiserver en `Service type: LoadBalancer` annoncé par BGP dans le pool cilium `/26`.
★ **Mais l'appariement n'est PAS déclaratif** : `clustermesh-remote-users` est rendu **vide** avec le
commentaire « peer entries get appended **out-of-band**, by `cilium clustermesh users add` », et
**aucun** Secret `cilium-clustermesh` (le porteur des endpoints des pairs) n'est rendu. Un geste CLI
manuel dans un projet à rendu déclaratif + propriétaire unique — c'est ça le travail que le mesh
révèle, plus que la joignabilité. L'endpoint d'un pair étant un **champ texte** de ce Secret, le nom
de service d'egress est utilisable tel quel.

**Ce que la spec décidait DÉJÀ** (cinq de mes six « questions ouvertes » l'étaient) :
l'itinérance de `nikopol-nixos` est un fait assumé (hotspot, CGNAT ; le tailnet est le **seul**
transport commun, DERP puis direct) ; l'egress du pod CAPI est spécifié *et live-probé le
2026-09-12* — `ProxyGroup type: egress` + Service `ExternalName` annotée `tailnet-target-ip`,
en L4 pass-through (TLS bout-en-bout, contrairement à `apiServerProxy`) ; et « l'identité incus doit
devenir per-remote, `server`/`server-crt` par remote, le `client-crt`/`client-key` CAPN **partageable**
» est écrit dans § *Materialization consequences*.
★ **Propriété, pas Flux** : l'opérateur tailscale mute `spec.externalName`, donc un force-apply SSA de
Flux ferait battre la Service — c'est le `rke2-adoption-controller` qui possède celle du VIP, et la
nouvelle doit suivre. Piège déjà payé, ne pas le re-payer.

**Dettes de code relevées** (aucune n'est une décision — la spec les réclame) :
`IncusIdentityMaterial` est un singleton alors que le Secret est déjà nommé par hôte
(`nikopol-incus-identity` porterait le cert de *bioskop*) ; `WORKLOAD_CONTROL_PLANE_REPLICAS = 3` est
une constante → une cible **mgmt** recevrait 3 pets là où son blueprint n'en déclare qu'un (débordement
du `/29`), **et** `bioskop-wrkld` est vivant sur 3 donc le correctif doit être par rôle, pas
`topology().nodeNames()` nu ; l'endpoint doit suivre l'hôte ; et le Connector annonce le reach-set de
chaque cible gérée alors que la spec dit « advertises only what its OWN node can forward » — cross-hôte
il trou-noirerait le `/21` de nikopol en gagnant l'élection de routeur primaire.

**★ REJET RETIRÉ le 2026-09-25 — les serveurs incus EN CLUSTER redeviennent la piste forte.** J'avais
écarté ça sur un raisonnement en partie faux ; l'utilisateur a renvoyé à
https://linuxcontainers.org/incus/docs/main/explanation/clustering/ et la doc démonte deux objections
sur trois :

- « à deux membres les deux votent, donc nikopol absent fait perdre le quorum à bioskop » → **FAUX car
  évitable** : le rôle **`database-client`** (non automatique) « prevents the affected cluster member
  from being elected as a voter or stand-by ». Nikopol épinglé dessus n'entre jamais dans le raft.
  Mon calcul n'était vrai que par DÉFAUT (`cluster.max_voters`=3, `cluster.max_standby`=2).
- « le stockage ne se met pas en cluster ici » → **TROP FORT** : la doc prévoit des options
  **member-specific** exactement pour ça — « the source device and size for a storage pool », « the
  name for a ZFS zpool ». Un pool ZFS local par membre sous un nom commun est le motif normal.
- le battement est **confirmé mais étroit** : `cluster.offline_threshold` = 20 s (min 10) ; un membre
  offline bloque « operations on this member » et celles « that require a state change across all
  members » — donc l'exploitation courante de bioskop continue, seuls les changements cluster-wide
  (créer un réseau, un pool) attendent le retour de nikopol.

Et le clustering **dissoudrait deux dettes** : les images sont répliquées par le cluster
(`cluster.images_minimal_replica = -1` pour tous) → le point « l'image doit exister côté nikopol »
disparaît ; et un seul remote = un seul couple (adresse, cert serveur) → la dette de l'identité incus
**par-remote** disparaît aussi.

★ **Le point qui décide, PAS ENCORE VÉRIFIÉ** : un membre peut-il servir l'API pour une ressource
détenue par un autre membre (forwarding) ? Si oui, un pod de bioskop-mgmt parle à `nixos.bioskop`,
local sur sa propre fabric, et **la traversée cross-hôte de l'API incus disparaît entièrement** — plus
d'egress sur ce chemin. Cette page-là ne le couvre pas.

Coûts restants s'il faut trancher : le préseed ndh devient cluster-aware (`fabric-br`/`vmnet-br`
deviennent des objets cluster-wide à valeurs par membre + un flux de *join*) — c'est le gros du
travail et il tombe dans **ndh** ; joindre un cluster = un seul domaine de confiance entre les deux
daemons ; et le rejoin répété d'un membre itinérant n'est pas un flux conçu pour ça (à vérifier).

⚠️ Conséquence sur le livré : `nixos.<host>` et la mort de `.lan` restent justes dans les deux
scénarios. Seule l'ENTRÉE de la dérivation changerait — en cluster, l'endpoint d'une intention est le
point d'entrée du CLUSTER (`nixos.bioskop`), pas l'hôte de la cible. Une ligne dans
`ClusterApiWorkloadManifestsUnit`.

See [[funnel-identity-is-per-cluster]] [[kubeconfig-context-per-cluster-intention]]
[[netplan-projection-described-hosts]] [[single-owner-rule]]
[[node-env-gated-oneshots-skip-capn-nodes]] [[destructive-gate-needs-three-valued-probe]]
