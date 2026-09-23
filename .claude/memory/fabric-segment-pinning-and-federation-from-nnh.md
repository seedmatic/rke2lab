---
name: fabric-segment-pinning-and-federation-from-nnh
description: "nnh a défini le modèle du segment fabric : on épingle une adresse par `ipv4.address` sur le NIC du profil incus (PAS par un dhcp-host indexé MAC), et chaque locataire ne publie que SA portion dans lib.networkBlueprint"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-23T16:01:45.121Z
---

`nnh` (`/private/var/lib/git/seedmatic/nnh.d/main`) est le **premier locataire** du segment
fabric `bare-br`, et il en a défini le modèle avant rke2lab. À lire avant de coder le carve.

## ★★★ L'épinglage ne passe PAS par un MAC

```nix
lan0 = { type = "nic"; nictype = "bridged"; parent = "bare-br";
         "ipv4.address" = ipv4Address; };          # nnh flake.nix, mkProfile
```

> « Incus records the reservation in bare-br's dnsmasq, and `dns.mode=dynamic` still maps
> `nnh-*.nikopol` → the pin » — **validé** sur un NIC bridgé `parent: bare-br`, cross-project.

Donc une adresse stable **sans** schéma MAC. Et c'est structurellement meilleur que les
`dhcp-host=` que rke2lab écrit dans le `raw.dnsmasq` du **réseau** : la source de vérité devient
la config **de l'instance**, écrite à chaque grow, au lieu de la config du réseau — dont on a
mesuré le 2026-09-23 qu'elle est **write-once** tant que le réseau n'est pas dans l'état Pulumi
(le correctif `no-hosts` est resté inerte des jours pour cette raison). Voir
[[node-bootstrap-objects-need-instance-recreation]].

⚠️ **Mais rke2lab met `devices` dans `ignoreChanges`** sur l'instance ET le profil
(`InstanceGrow.java:272,420`), à cause d'un défaut de read-back du provider toujours présent en
1.2.0 (« modelled as an ordered list that churns »). Donc un `ipv4.address` serait honoré **à la
création et jamais réconcilié**. Acceptable — le re-grow est le geste de livraison — mais c'est la
**troisième** occurrence du même motif write-once dans la même journée, donc à poser en décision
consciente, pas en surprise.

⚠️ Et le profil rke2lab est **par cluster**, partagé par tous ses nœuds. nnh s'en sort avec
`ipv4.address` dans un profil parce qu'il a **un profil par instance** (`inletProfileYaml`,
`outletProfileYaml`). rke2lab doit donc soit un profil par pet, soit l'adresse sur le **device de
l'instance** — `InstanceDeviceArgs` est déjà importé, c'est la voie propre : le profil garde
`parent`/`nictype` partagés, l'instance surcharge l'adresse.

★ **Verdict pour rke2lab** : oui pour les **pets** (le grow crée l'instance, donc il peut poser
l'adresse), non pour le **bétail** — CAPN crée N instances depuis UN `LXCMachineTemplate` à
profils partagés, il n'y a pas de prise par instance. C'est exactement la ligne pet/cattle, posée
sur un mécanisme au lieu d'un MAC.

## L'incident qui justifie d'épingler quoi que ce soit

2026-08-19 : un redéploiement ndh a recréé `bare-br` → les deux instances, alors simples
locataires DHCP, ont pris de **nouvelles** adresses. Deux casses distinctes :

- akvorado annonce Kafka **par nom** (`nnh-inlet.nikopol:9092`) et a coincé ses clients à la
  ré-résolution ;
- pmacct résout son `nfprobe_receiver` **une seule fois au démarrage**, donc la sonde a continué
  d'exporter vers l'IP morte.

★ Le test à appliquer à rke2lab : **a-t-on un consommateur résolu-une-fois ou annoncé-par-nom ?**
Les URLs de pairs etcd en sont — mais l'etcd est effacé à chaque cold start, donc le risque est
plus faible que chez nnh. À trancher explicitement, pas à supposer.

## ✅ FAIT le 2026-09-23 — bioskop a rejoint la fabric (ndh `e3e5c627`)

Worktree `ndh.d/feature/bioskop-fabric-address` (ndh est maintenant un dépôt **bare**,
`ndh.git` — voir [[orphaned-worktree-recovery]]), **fusionné en fast-forward dans `develop`**. ✅ **VÉRIFIÉ VIVANT** : en9 porte `172.16.7.253/30` à côté de `192.168.1.129`,
`172.16.7.0/25` route via `.254` sur en9, et `100.64/10` sort toujours par `utun4`.

bioskop a gagné `dynamicCidr` / `dhcpRange` / `linkCidr` / `hostAddress` et un
`vzHostAddress` de segment (`172.16.7.253`), **aux mêmes offsets que nikopol** — les deux entrées
partagent désormais UNE forme. Vérifié par évaluation : les 3 spans, le `/30` sur `lan-br`, la
config `bare-br` rendue, les deux configs darwin et le toplevel de `bioskop-nixos`.

★ Le module n'a **rien** eu besoin de gagner : `hasLink` signifiait déjà « un vz-host joint par un
`/30` », seuls ses commentaires disaient « corp Mac ». Déclarer les champs active les trois effets
d'un coup. Ce qui **ne** se généralisait pas par la donnée, c'est tout ce qui supposait le
vz-host **étranger** — trois faits redéclarés dans du code : l'interface
([[darwin-nic-service-not-device]]), le canal de livraison (ssh alors que bioskop **est** un hôte
nix-darwin, donc son propre `switch` doit le livrer — via `postActivation`, voir
[[nix-darwin-activation-keys-are-fixed]]), et les routes. Cette dernière était un **vrai danger** :
la paire codée en dur incluait `100.64.0.0/10 via <guest>`, qui existe parce que le Mac corp n'a
pas de tailnet — sur bioskop, membre du tailnet, elle aurait détourné son propre `utun`. Le
catalogue nomme désormais le fait une fois (`vzHostKind = foreign | nix-managed`) et **quatre**
choses en dérivent : la liste de routes, le canal, qui possède `/etc/resolver/<domain>`, et si le
nudge du guest est émis.

⚠️ **La numérotation reste l'ancienne**, et le passage aux `/20` dérivés doit bouger les DEUX hôtes
dans le même changement : le `netCidr` cible de bioskop (`172.16.0.0/21`) **contient** le `/25`
vivant de nikopol, donc une demi-migration rend `172.16.6.x` on-link sur bioskop et masque la route
tailnet vers le segment de nikopol (→ `nnh-inlet.nikopol` cassé depuis bioskop). Ce renumérotage
atteint aussi les **cinq littéraux épinglés de nnh** et exige un redéploiement du démon de chaque
vz-host. Consigné dans `docs/network-topology-c4.adoc#target-carve`.

## La convention de carve — ⚠️ elle entre en conflit avec le nouveau plan

ndh possède le `/25` **et son carve** : `dynamic-low` (`172.16.6.0/27`, `dhcp.ranges .2-.30`) /
`static-high`, rempli **du haut vers le bas**. nnh possède le `/30` du haut (`172.16.6.124/30`).

⚠️ Le plan d'adressage de l'atlas utilise un bit du troisième octet pour dire **quel lien**
(bas = `bare-br`, haut = un autre lien), là où la convention nnh utilise haut/bas pour dire
**dynamique ou statique**. **Les deux sémantiques se disputent la même frontière.** Il faut
choisir, et re-placer le `/30` de nnh en conséquence. Corollaire : « le pool de bioskop peut
prendre l'essentiel du `/25` » est FAUX pour nikopol, dont la moitié haute appartient à nnh —
et faire diverger les deux hôtes sur la sémantique du carve serait la classe de défaut habituelle.

## Le modèle de fédération, à copier tel quel

> « nnh publishes ONLY what it owns: this `/30` and its two hosts, NOT the enclosing `/25`
> (ndh's). […] Self-contained BY DESIGN (it declares only nnh's own `/30` and never reads ndh's
> catalog) — else nnh's contribution would depend on ndh's which depends on nnh's, a real value
> cycle. »

C'est la réponse à la question du cycle d'évaluation, déjà résolue une fois. Le cycle de flakes
est cassé par `inputs.<other>.inputs.<self>.follows = ""` — un follows vide résout vers le flake
ROOT, un point fixe qui termine la récursion.

## ★ Le joint `segments[].hosts` a DÉJÀ un producteur vivant

L'atlas dit « both ends of that contract exist but have never been joined ». Plus précis :
**nnh PRODUIT déjà** des `hosts = [{name, ip}]` sans MAC — exactement la forme
`SegmentHost(name, mac?, ip)` sans MAC. Et ndh **ré-écrit les deux mêmes valeurs à la main** dans
`baremetal.nikopol.staticHosts` (`nnh-inlet = .126`, `nnh-outlet = .125`), identiques à celles
publiées.

Donc le joint n'est pas « jamais fait » mais « le consommateur lit sa propre copie manuscrite au
lieu de la valeur publiée ». Dédupliquer ces deux déclarations est un **premier gain petit,
ndh-only et vérifiable** — et c'est le même joint dont le `host-record` de la VIP par cluster aura
besoin.

See [[netplan-per-node-projection-feeds-ndh-reservations]]
[[node-env-gated-oneshots-skip-capn-nodes]].
