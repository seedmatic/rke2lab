---
name: nnh-second-collector-per-baremetal
description: "★ PARQUÉ le 2026-09-27 : un second collecteur nnh sur bioskop (forme retenue = deux collecteurs symétriques, un par bare-metal). État des lieux fait : la SONDE est déjà agnostique, c'est le COLLECTEUR qui est mono-hôte en 7 pièces. Et la coupure du cycle de flakes interdit de dériver le blueprint depuis le catalogue ndh."
metadata:
  node_type: memory
  type: project
  originSessionId: c2c2a472-f982-468d-98de-1b23bbf4e274
  modified: 2026-09-27T16:18:04.503Z
---

Demandé puis **parqué** le 2026-09-27 (décision utilisateur : « on met ça en attente pour dans le
futur »). Rien écrit, aucun worktree créé. L'état des lieux ci-dessous est le travail à ne pas
refaire.

## Le besoin, en deux points de vue DIFFÉRENTS (pas une duplication)

- **nikopol, en itinérance** : le bord **WAN** du vzhost sur le hotspot (entrée/sortie). C'est local
  au Mac corp, donc ça doit rester chez nikopol pour fonctionner hors ligne.
- **bioskop** : le trafic **entrant**, pour diagnostiquer les transferts qui **stallent** depuis le
  bare-metal vers les hôtes d'infra ndh/rke2lab — donc les bridges que possède `bioskop-nixos`.

★ Forme retenue : **deux collecteurs symétriques**, un par bare-metal, chacun voyant sa tranche.
J'avais proposé « collecteur primaire sur bioskop » — **à écarter** : le besoin nikopol est local et
doit survivre à l'absence de réseau. (Et exporter les flux de bioskop vers le collecteur de nikopol
est pire : ça rend l'observabilité de la machine toujours allumée dépendante de celle qui part.)

## Ce qui est DÉJÀ bon : la sonde

`netflow-probe` (pmacct `pmacctd` + `nfprobe`) est un bundle nix + LaunchDaemon root **pour
bare-metal macOS**, paramétré par `NETFLOW_COLLECTOR` / `NETFLOW_INTERFACE` / hôte cible. Or bioskop
*est* un Mac — et la validation d'origine s'est faite sur un transfert `vz → bioskop`. Donc
l'observation n'est pas le sujet.

⚠️ Deux détails durement acquis (mémoire nnh `pcap-capture-direction-macos`) qui voyagent avec, à ne
pas re-découvrir : la `interfaces.map` doit être **sans `direction`** (avec `direction=in` macOS perd
~95 % des paquets ; avec les deux entrées il ne capture que l'entrant), et Akvorado exige le patch
`outlet/core/enricher.go` parce qu'il jette tout flux à `ifindex=0` — ce que produit justement la map
sans direction.

## Ce qui est figé : le collecteur, en 7 pièces

Appareil Incus à **deux instances** (`nnh-inlet` + `nnh-outlet`) sur `fabric-br`. Tout le `let` de
`nnh/flake.nix` est mono-hôte :

1. `collector = rec { base = "172.16.16"; … }` — une seule base, en dur
2. `networkBlueprint.segments` — un segment, `nnh-collector` = `172.16.16.124/30`
3. `inletSystem` / `outletSystem` — configs NixOS avec adresses embarquées
4. `inletProfileYaml` / `outletProfileYaml` — profils Incus idem
5. `collectorDeploy` — pilote un seul remote Incus
6. `probeDeploy` — cible `vz.nikopol`, `en0`, `nnh-inlet.nikopol:2055`
7. les sorties `packages`/`apps` ne sont pas par hôte

L'emplacement symétrique est **libre** : chez bioskop seuls `172.16.0.1` (gateway) et `172.16.8.2`
(vzhost) sont pris, pool DHCP `172.16.0.0/27` ⇒ `172.16.0.124/30` disponible. Les noms d'instances
peuvent rester identiques (zones fabric distinctes : `nnh-inlet.bioskop` vs `.nikopol`) mais les
**noms de segments** doivent être désambiguïsés, sinon deux `nnh-collector` se télescopent dans
l'union côté ndh.

## ★★ Le point dur : la coupure du cycle de flakes

ndh lit `nnh.lib.networkBlueprint` avec `inputs.ndh.follows = ""`. **Dans cette direction nnh ne peut
pas lire le catalogue de ndh** — d'où la base en dur. Mais la coupure ne concerne QUE le blueprint :
le côté **déploiement**, en build autonome, a ndh comme input réel et peut dériver proprement.

D'où la suggestion qui transforme la duplication en invariant : que le build autonome de nnh
**assertionne** que ses bases auto-déclarées correspondent au carve de ndh. Même motif que la policy
auto-vérifiante livrée le même jour (voir [[tailscale-services-and-grants-unlock]]) — la duplication
subsiste mais ne peut plus dériver en silence. ⚠️ Et le catalogue de ndh se plaint DÉJÀ de cette
duplication en sens inverse (commentaire `staticHosts` : les host-records du collecteur « used to be
hand-copied here, duplicating values nnh ALREADY publishes »).

See [[tailscale-services-and-grants-unlock]] [[tailnet-routing-owned-by-hosts-not-pods]]
