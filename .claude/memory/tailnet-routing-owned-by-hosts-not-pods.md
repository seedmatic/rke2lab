---
name: tailnet-routing-owned-by-hosts-not-pods
description: "★★ Le routage tailnet appartient aux HÔTES, pas aux pods : les segments vmnet sont annoncés par le bare-metal (ndh 34605639) et la CR Connector est supprimée (rke2lab acd0e45d4). Plus : acl.hujson est la policy HEADSCALE, pas celle du SaaS ; un dst par tag ne couvre PAS les routes de sous-réseau ; et le nom tailnet est figé à l'inscription."
metadata:
  node_type: memory
  type: project
  originSessionId: c2c2a472-f982-468d-98de-1b23bbf4e274
  modified: 2026-09-27T13:26:27.449Z
---

Chantier du **2026-09-27**, livré dans ndh `a62b210c` → `07774b27` et rke2lab `acd0e45d4` → `5aa5065c7`
(tous poussés). Suite de [[rke2-rejects-a-loopback-resolv-conf]].

## ★ Le routage appartient à l'hôte, pas au pod

Chaque cluster rendait une CR `Connector` qui l'érigeait en routeur de sous-réseau, annonçant son VIP
kube-vip `/32` + le `/26` LB de cilium, ainsi que ceux des clusters qu'il gère. **Deux défauts dans ce
propriétaire** :

- Les adresses vivent sur un bridge `vmnet` que possède le **bare-metal**, tandis que l'annonceur était
  un pod **dans** le cluster. La route mourait donc avec le cluster — précisément quand la
  justification écrite dans le code la réclame (« the route is wanted while debugging a cluster that
  is half-born »). Mesuré : `bioskop-nixos` hors ligne a emporté les deux Connectors et toutes les
  routes de VIP.
- « il atteint un VIP frère **localement via l'hôte** » ne vaut que pour des bridges du **même** hôte.
  Un cluster sur l'autre bare-metal aurait été annoncé par un Connector incapable de l'atteindre ⇒
  **trou noir** derrière le routeur primaire élu par tailscale. `nikopol-mgmt` était le prochain cas.

**Correctif** : `cluster-vmnet.nix` annonce chaque segment vmnet que l'hôte déclare (le `/21` entier,
pas le `/32`+`/26` — l'hôte forwarde vers tout le `/21`, donc l'annonce étroite mentait par défaut).
Un annonceur par préfixe, pas d'élection, et la route survit au cluster. Un device d'hôte est
**persisté**, donc les routes cessent de churner à chaque cold start — le Job de purge n'a plus
d'orphelin. La CR n'avait aucun autre rôle (ni exit node ni app connector), donc elle part avec
`blueprintOf` et `tailnetReachRoutes()`.

Mesuré après coup : `bioskop-nixos` annonce `10.80.0.0/21 10.80.8.0/21 172.16.0.0/20 192.168.1.0/24`,
`nikopol-nixos` `10.80.16.0/21 10.80.24.0/21 172.16.16.0/20`.

★ **L'approbateur doit suivre l'annonceur** : `autoApprovers.routes` mappait le `/18` vmnet sur
`tag:k8s` *parce que* c'était un Connector qui l'annonçait. Laissé là, chaque segment restait PENDING
pour toujours. Les quatre familles pointent maintenant sur `tag:nixos`.

## ★★ Deux erreurs de lecture que j'ai faites sur l'ACL — ne pas les refaire

**1. `catalog/tailnet/acl.hujson` n'est PAS la policy appliquée.** C'est la policy du **serveur
headscale** (`catalog.tailnet.aclPolicyFile` → `modules/{nixos,darwin}/headscale-daemon.nix`), et
headscale est en **hibernation**. Ce que la flotte applique est `tailnetAclCanonical`, **généré en
Nix** dans `manage-tailnet.d/default.nix` et poussé par `--sync-acl`. Deux plans de contrôle, deux
policies — pas un doublon qui a dérivé. Son en-tête revendiquait « controller-AGNOSTIC », ce qui était
de l'aspiration et m'a fait lire le mauvais fichier deux fois de suite. Corrigé dans l'en-tête.
⚠️ Cause profonde de la confusion : `modules/nixos/headscale.nix` pilote en réalité le **client
tailscale du SaaS**. Le renommer serait un gain de lisibilité réel, non fait.

**2. Un `dst` par tag ne couvre QUE les adresses tailnet du nœud.** Une **route de sous-réseau** exige
son CIDR dans le `dst` — le code le dit : « a tag'd node's netmap only carries a subnet route it is
ACL-permitted to reach ». J'avais conclu l'inverse en voyant le Mac joindre `172.16.16.1` ; la vraie
raison est l'entrée `172.16.16.0/20`, **générée** depuis le catalogue. Les `172.16.6.0/24` /
`172.16.7.0/24` visibles dans la console ne sont pas du bricolage manuel : c'est une **génération
périmée**, d'avant le renumérotage. Un `--sync-acl --apply` les corrige.

★ `sync_acl` traite les champs **différemment** : `tagOwners` fusionne (canonical gagne par clé),
`acls` et `ssh` sont **remplacés intégralement**, `autoApprovers.routes` fusionne, `exitNode` s'unit,
le reste est préservé.

## ★ `tag:k8s` ne doit PAS être adopté dans le vocabulaire

`tag:rke2` et `tag:incus` étaient déclarés et **portés par personne** — chacun frappait une clé d'auth
inutilisée. `rke2` était le nom de l'époque headscale pour ce que l'opérateur appelle `k8s`. Tous deux
supprimés, et ajoutés à la liste d'élagage de `sync_acl` pour qu'ils quittent aussi la policy vivante.

⚠️ **Mais `tag:k8s` reste hors du vocabulaire, délibérément.** C'est le défaut du **chart** tailscale
(`proxyConfig.defaultTags`), possédé par l'OAuth client de l'opérateur. `ourTags` fait que `tagOwners`
**revendique** chaque kind ; la fusion étant `live * canonical` avec canonical gagnant, l'adopter
retirerait cette propriété et **l'opérateur ne pourrait plus enregistrer aucun device**. Il est donc
nommé en `dst` — ce qui n'exige aucune propriété — fermant une posture que personne n'avait choisie :
les quatre devices `*-{flux,pipelines}-webhook` répondaient à Internet par Funnel tout en étant absents
du netmap de chaque nœud, faute de tag d'axe de rôle.

## ★ Le nom tailnet est figé à l'INSCRIPTION

`tailscale up --hostname=` vient de `networking.headscale.hostname`, dont le défaut est
`networking.hostName` **de la génération qui s'inscrit**. Or le toplevel de bringup est une des couches
EROFS partagées par la flotte — mesuré : `nerd-nixos`, `bioskop-bringup` et `nikopol-bringup` produisent
la **même dérivation**, avec `hostName = nerd-nixos`. Donc toute VM s'inscrivait comme `nerd-nixos`, et
le commentaire qui le justifiait décrivait le défaut comme une fonctionnalité (« the full config reuses
the same registration on handoff »). Corrigé en **ne joignant plus le tailnet au bringup** (ndh
`f4d39c4d`) : l'unique inscription vient de la génération par-hôte. Graver le nom réel aurait forké une
couche de **661 Mio** par hôte.

★ Et `--reclaim-host` compare le `.hostname` **rapporté par le client**, pas le `.name` de machine. Un
renommage dans l'UI corrige le DNS mais **pas** la réclamabilité : le device restait réclamable sous
`nerd-nixos`. Accès de secours au bringup : `nerd-nixos.local` (mDNS, déclaré dans le même fichier) ou
la console du Mac — meilleur chemin, puisqu'il ne présuppose pas que la machine déboguée ait rejoint un
réseau.

## ★★★ 2026-09-27 (soir) — annoncer le LAN DOMESTIQUE a cassé le LAN, et la bonne voie est ailleurs

Un hôte LAN-fixe annonçait aussi `netplan.lan.cidr`, pour que les pairs hors site joignent les machines
de la maison. **Coût mesuré, inacceptable** : un pair qui accepte les routes ET siège sur ce LAN
installe `192.168.1.0/24 → utun0`, ce qui **déloge sa propre route connectée**. Le Mac bioskop a perdu
sa route `en9`, ne gardant que les routes d'hôte vers lui-même et la box ⇒ chemin **asymétrique**
(vzhost→bioskop direct en L2, TTL 64 ; bioskop→vzhost par le tunnel puis retour sur le LAN, TTL 63).
**Seul le SYN-ACK survivait** : écran partagé et ssh LAN bloqués sans bannière, alors qu'un `nc` sur le
même port DEPUIS l'hôte répondait — donc rien n'avait l'air cassé. ECN écarté (les retransmissions en
`tos 0x0` tombaient aussi). Retiré durablement (ndh `b333e8c7`).
★ **macOS n'a PAS restauré la route connectée** quand l'annonce a disparu : il a fallu
`route -n add -net 192.168.1.0/24 -interface en9`. « Cesser d'annoncer » ne suffit pas à réparer.

★ **Aucun cadrage ne rend ça sûr** : `--accept-routes` est tout-ou-rien par client ; le `via` des
**grants** *choisit* entre routeurs sans priver personne ; priver exige le préfixe hors du `dst` de ce
nœud, donc un tag distinguant FIXE d'ITINÉRANT alors que les tags sont frappés **par genre** ; et des
`/32` supprimeraient le délogement mais pas l'asymétrie, qui est ce qui tue TCP.

⚠️ **Et mon `acceptRoutes` est INERTE pour son objet** : le `dst` de la règle **`headless`** est
seulement `tag:headless:*` — ni les CIDR des segments, ni `tag:console`. Un nœud n'installe que les
routes qu'il a le droit d'atteindre, donc les hôtes reçoivent des routes inutilisables. Correctif
d'une ligne non fait : ajouter `baremetalCidrs ++ vmnetCidrs` au `dst` de `headless`.
★ **Le défaut est réel mais ma PREUVE était fausse** : j'avais cité « TCP 5900 refusé » depuis
`nikopol-nixos`. Un refus d'ACL est un **DROP donc un TIMEOUT** ; `Connection refused` est un **RST**,
qui prouve que le paquet est ARRIVÉ. Lire ce défaut dans la policy, jamais au port scan — détail dans
[[tailscale-services-and-grants-unlock]].

## ★★★ LA BONNE VOIE : Tailscale **Services** (GA janvier 2026)

Cherchée en écartant les fausses pistes, toutes mesurées : `serve` seul est **localhost-only** (« only
`http://127.0.0.1` is supported for proxies ») ; le SaaS **interdit** tout enregistrement personnalisé
(« It's not possible to add arbitrary records to MagicDNS ») ; le `cname` de dnsmasq refuse une cible
**upstream** ; et `apertures` est la plateforme de gouvernance d'agents IA, sans rapport.

Les **Services** répondent point par point :

- un service a **sa propre IP virtuelle et son nom** — un alias qui suit le service, pas la machine ;
- ⭐ « Tailscale Services virtual IPs are now **automatically accepted by clients regardless of the
  status of `--accept-routes`** » ⇒ **aucune route installée**, donc la classe de panne ci-dessus
  devient structurellement impossible ;
- **plusieurs nœuds** peuvent annoncer le même service (HA), contrairement au Connector retiré ;
- « configuration to use a **remote target** as a service destination » ⇒ peut viser une machine
  distante, ce que `serve` seul refuse — et **RDP est explicitement listé**.

★ Convergence : `catalog.netplan.tailnet.hosts.<host>.serviceNames` existe **déjà** et alimente
aujourd'hui des CNAME `dns.extra_records` **headscale** (avec un tailscaled **patché**,
`overlays/tailscale.nix`, pour chaîner le CNAME vers MagicDNS — `flake.nix:111`). Le vocabulaire est
bon ; seule la **livraison** doit passer à l'API des Services, que `manage-tailnet` sait déjà appeler.
Et le catalogue avait déjà tranché la répartition : « `vzhost.<host>` is intentionally absent from this
DNS section … never via tailnet DNS » ⇒ **`vzhost` dans la zone fabric, `rdp` dans le DNS du tailnet**.
Un `rdphost` dans la zone fabric a donc été écrit puis **retiré** : il doublait un nommage déjà conçu,
et n'était honnête que pour l'hôte qui ne bouge pas (pour l'itinérant, son adresse LAN serait un piège —
`192.168.1.33` désignerait la machine d'un inconnu dans un hôtel).

★ **Les trois inconnues sont TRANCHÉES** (même jour, sur la source du fork — la doc est fausse sur la
cible distante, qui est bien supportée) : voir [[tailscale-services-and-grants-unlock]], qui porte
aussi la décision `acls` → `grants` et son piège de réconciliation.

## Reste ouvert

- `acceptRoutes` est activé sur les deux hôtes (ndh `a62b210c`, avec le relais `server=/<pair>/…` du
  dnsmasq), mais **le cas `192.168.1.0/24` sur un hôte assis sur ce LAN n'est PAS mesuré** : tailscale
  installe dans la table 52, dont la règle passe avant `main`. Le Mac a gardé sa route locale, mais
  macOS n'est pas une preuve pour Linux. À mesurer avec `tailscale set --accept-routes` + `ip route get`
  avant de compter dessus hors site.
- Le fossile headscale dans rke2lab (`HeadscaleManifestsUnit`, qui annonce `tag:rke2,tag:nikopol` —
  deux tags que rien ne déclare) : dormant, non touché. Hibernation ≠ mort, donc décision utilisateur.
- Renommer `headscale.nix` en ce qu'il est vraiment.

See [[rke2-rejects-a-loopback-resolv-conf]] [[bridges-leave-incus-forced-not-chosen]]
[[incus-placement-must-be-stated-not-inferred]] [[tailnet-node-identity-ephemeral-ghosts]]
