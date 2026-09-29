---
name: tailscale-services-and-grants-unlock
description: "★★ Tailscale Services : les 3 inconnues tranchées SUR LA SOURCE du fork (svc:<nom>, cible distante SUPPORTÉE contre ce que dit la doc, autoApprovers.services sinon PENDING à vie). Plus : les `grants` sont débloqués par l'hibernation de headscale, mais la bascule doit `del(.acls)` dans le MÊME changement. Et un discriminateur : refus ACL = TIMEOUT, jamais `Connection refused`."
metadata:
  node_type: memory
  type: project
  originSessionId: c2c2a472-f982-468d-98de-1b23bbf4e274
  modified: 2026-09-27T13:26:05.828Z
---

Établi le **2026-09-27**, suite de [[tailnet-routing-owned-by-hosts-not-pods]]. Specs mises à jour
dans ndh `docs/network-topology-c4.adoc` (nouvelle section `[[authorisation]]`) + le commentaire de
`manage-tailnet.d/default.nix`. **Rien d'implémenté.**

## ★★★ Le discriminateur qui m'a fait corriger ma propre conclusion

**Un refus par le filtre de paquets tailnet est un DROP, donc il TIMEOUT. `Connection refused` est un
RST : le paquet est ARRIVÉ et rien n'écoutait.** C'est une preuve de joignabilité, pas de refus.
J'avais cité « TCP 5900 refusé depuis nikopol-nixos » comme preuve mesurée du trou `headless` — la
mesure disait l'inverse. Le défaut est réel, mais il se lit **dans la policy**, pas au port scan.

Mesure de référence prise depuis bioskop (`tag:console`) vers nikopol (`tag:console`, le miroir du
cas itinérant) : `nc -vz 100.100.216.24 5900` **réussit**. ★ Et c'est **indépendant du lieu** : l'ACL
est appliquée par le filtre du nœud **destinataire** depuis son netmap, donc être sur le même LAN ne
flatte pas le résultat. Ce qui exige vraiment l'itinérance, c'est la latence et le comportement des
routes locales — jamais le droit d'accès. (Question posée par l'utilisateur, à raison.)

## ★★ Les trois inconnues des Services, tranchées SUR LA SOURCE

Lues dans le clone du fork qu'on build (`overlays/tailscale.nix`, `7b962478b` = pile le commit du
binaire 1.102.3 installé), **parce que la doc est fausse sur le point central**.

1. **Nom** : `svc:<nom>`. Donné par `tailscale serve drain --help`.
2. **Cible distante : SUPPORTÉE.** La page du format de fichier prétend que les cibles sont locales ;
   `isRemote()` (`cmd/tailscale/cli/serve_v2.go:1176`) existe précisément pour isoler ce qui n'est
   **pas** `localhost`/`127.0.0.1`/`::1`. ⚠️ Le garde-fou plateforme est **FATAL** (`return err`,
   l. 525), pas un avertissement malgré son nom : un tailscaled **Mac App Store ou sysext** refuse
   net, et **Linux sans `SO_MARK`** aussi. Nos Macs tournent le build standalone de l'overlay, donc
   éligibles — mais passer au client App Store casserait ça silencieusement.
3. **ACL, trois exigences** : (a) l'hôte de service **doit être taggé** (« You cannot use a device
   authenticated with a user account as a Service host ») — nos Macs ont déjà `tag:console` ;
   (b) ★ `autoApprovers.services` doit mapper le service sur le tag de l'annonceur, **sinon PENDING
   à vie** — c'est *exactement* le défaut « l'approbateur suit l'annonceur » déjà payé le matin même
   sur `autoApprovers.routes` ; (c) l'accès se donne en nommant le service en `dst` avec son préfixe.

★ **Livraison déclarative par FICHIER** : il n'existe **pas** de `--advertise-services` (la préférence
est posée par `serve` lui-même), mais `tailscale serve set-config <fichier>` / `get-config` servent à
« declaratively set configuration for a service host ». C'est ce qui rend le chantier compatible avec
le dépôt.

## ★★ `acls` → `grants` : la raison de rester en legacy a EXPIRÉ

Décision utilisateur du 2026-09-27. La justification était écrite mot pour mot dans
`manage-tailnet.d/default.nix` : « so the same tag vocabulary stays usable by the headscale
controller too » — headscale ne comprend pas `grants`. Headscale hibernant, et sa policy étant un
artefact **séparé** que rien ne synchronise, le couplage tombe.

Deux choses que ça **n'achète pas**, pour ne pas les re-dériver : ce n'est **pas** un prérequis des
Services (un `dst` en `svc:` est accepté dans les **deux** syntaxes, la doc donne l'exemple en
`acls`) ; et ça ne rend **pas** sûre l'annonce du LAN domestique (le `via` des grants *choisit* entre
routeurs, il ne prive personne).

⚠️ **Le piège est dans le réconciliateur, pas dans la policy.** `sync_acl` fait `.acls =
canonical.acls` (un REMPLACEMENT) et ne touche **que** les clés qu'il nomme, préservant le reste.
Donc un `.grants` canonique serait **silencieusement ignoré** pendant que l'`acls` périmé resterait
vivant — et la policy effective étant l'**union permissive** des deux, l'ancien bloc continuerait
d'autoriser. La bascule doit écrire `.grants` **et** `del(.acls)` dans le **même** changement. Idem
pour `autoApprovers.services`, absent du jq (seuls `routes` et `exitNode` y sont fusionnés).

## ★ Le défaut `headless` (réel, lu dans le code)

Le `dst` de la règle `headless` est **seulement** `tag:headless:*` : ni les CIDR de segments, ni
`tag:console`. Un nœud n'installe que les routes qu'il a le droit d'atteindre, donc l'`acceptRoutes`
posé le même jour est **inerte pour l'hôte-à-hôte**. Correctif : ajouter
`baremetalCidrs ++ vmnetCidrs` au `dst` de cette règle.

## ★ Pourquoi les Services malgré un chemin direct qui marche

Argument de l'utilisateur, retenu et c'est le bon : **une vue opérateur uniforme sur les deux
bare-metals**. Aujourd'hui les deux écrans se joignent par deux **mécanismes** différents — `bioskop`
est un nom MagicDNS, `vzhost.nikopol` un enregistrement de zone fabric derrière une route de
sous-réseau. L'asymétrie supprimée est dans le mécanisme, pas dans l'orthographe. S'y ajoutent :
aucune route installée (la panne de LAN devient impossible), un alias qui suit le service et non la
machine, et plusieurs annonceurs possibles.

Table proposée : `svc:rdp-bioskop` (bioskop, `localhost:5900`), `svc:rdp-nikopol` (nikopol,
`localhost:5900`), `svc:rdp-vzhost-nikopol` (**nikopol-nixos**, cible **distante**
`vzhost.nikopol:5900`). ★ `vzhost.bioskop` **n'a pas de ligne** : c'est bioskop lui-même à son
adresse fabric. `vzhost.nikopol` est un Mac corp distinct qui ne rejoint jamais le tailnet — d'où la
seule ligne qui dépend de la cible distante.

Le vocabulaire existe déjà : `catalog.netplan.tailnet.hosts.<host>.serviceNames`, qui alimente
aujourd'hui des CNAME `dns.extra_records` **headscale** (la raison du patch tailscaled). Seule la
**livraison** bascule.

See [[tailnet-routing-owned-by-hosts-not-pods]] [[rke2-rejects-a-loopback-resolv-conf]]
[[nix-store-and-etc-can-both-lie]]
