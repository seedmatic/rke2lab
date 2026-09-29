---
name: bridges-leave-incus-forced-not-chosen
description: "Tous les bridges adressés par hôte quittent la gestion d'incus — FORCÉ par le clustering, pas préféré ; fabric-br fait (2026-09-25), les vmnet restent à faire. Et le renommage est inutile."
metadata:
  node_type: memory
  type: project
  originSessionId: c2c2a472-f982-468d-98de-1b23bbf4e274
  modified: 2026-09-27T08:08:31.368Z
---

Décidé et entamé le **2026-09-25/26**, dans le chantier [[nikopol-mgmt-federation-clustermesh-first-case]].

## La décision, et pourquoi elle est forcée

Un cluster incus exige « all members of a cluster must have identical networks defined » : seules
`bridge.external_interfaces`, `parent`, `bgp.ipv4.nexthop`, `bgp.ipv6.nexthop` peuvent différer par
membre — **`ipv4.address` non**. Or nos sous-réseaux diffèrent par hôte/cluster par *conception*.

★ **La seconde raison, que j'avais manquée et qui est la pire** : un réseau géré cluster-wide est
matérialisé sur **CHAQUE** membre. Donc bioskop porterait un bridge local avec le `10.80.16.0/21` de
nikopol, s'installerait une **route locale** vers ce préfixe, et enverrait la portée cross-hôte dans
une interface morte. Ce n'est pas du gaspillage, c'est un **trou noir** sur exactement le chemin dont
le clustermesh a besoin.

★ **Le renommage est inutile** — c'était ma fausse piste. La collision n'existe *que* parce qu'un
objet incus doit être unique dans le cluster. Un **nom d'interface local peut se répéter** d'un hôte
à l'autre : `fabric-br` porte déjà `172.16.0.1/21` sur bioskop et `172.16.16.1/21` sur nikopol. Et
qualifier par cluster était de toute façon impossible : `vmnet-bioskop-mgmt` = 18 caractères contre
une limite de **15 qui est celle d'incus ET d'`IFNAMSIZ`**.

★ **Pas de perte de dynamisme** — mon autre objection, qui ne tient pas : l'ensemble des clusters est
**clos et dérivable** (`HOST_IDS` × 2 rôles ⇒ `clusterId` 0..5), aucun cluster imprévu ne peut
apparaître. Et un bridge déclaré sans membre est inoffensif : `fabric-br` siège DOWN avec son adresse
et dnsmasq s'y lie sans broncher — prouvé vivant.

État final visé : **incus ne gère AUCUN réseau**, chaque bridge appartient à son hôte, une seule
autorité dnsmasq par bare-metal.

## Fait (vérifié vivant sur les deux nœuds)

- `fabric-br` : networkd + `services.dnsmasq` (ndh `3776600c`). `ConfigureWithoutCarrier = true` est
  **porteur** — sans lui networkd retire l'adresse d'un bridge sans membre, et dnsmasq n'aurait rien
  où se lier. Le oneshot `incus-fabric-br` supprimé avec son script.
- `nixos.<host>` résout des deux côtés (`nixos.bioskop` → 172.16.0.1, `nixos.nikopol` → 172.16.16.1),
  par le dnsmasq de chaque hôte et via le split-DNS depuis le Mac. Les deux étaient NXDOMAIN.
  Côté rke2lab : `NamePlan.nixosFabricFqdn`, `LAN_DOMAIN`/`nixosLanFqdn` supprimés (`049adb9b5`).
- Export v6 des segments vmnet : `cidr6`/`gateway6` + `ip6` par nœud (rke2lab `e1ece2438`), et le
  **bump du lock ndh** qui en découle (`a7eb6c8a`) — premier changement de `network-blueprint.json`,
  donc premier bump nécessaire.
- ⚠️ Vérifier le PROPRIÉTAIRE, pas la résolution : le record `nixos.<host>` vient de
  `segmentHostRecords`, donc l'ancienne implémentation incus l'aurait émis aussi. C'est l'absence du
  oneshot + la présence des unités networkd qui tranchent.

## Reste à faire

1. **ndh** — les deux bridges vmnet par hôte (networkd, dérivés des segments `<domain>-<role>-net`)
   + élargir le dnsmasq existant : un `interface=` de plus chacun, `dhcp-range` v4 **dynamique** (les
   nœuds CAPN cattle y prennent leur bail) et v6 **stateful**, plus les `dhcp-host` double-pile par
   nœud. Pas de zone DNS à reprendre (`dns.mode = none`).
   ⚠️ **Question ouverte** : les bornes du pool v6 dynamique. Les adresses de nœuds sont à v4
   embarqué, donc dispersées dans le /64 ; incus laissait dnsmasq auto-ranger. Inventer des bornes au
   hasard ferait échouer le v6 des cattle — défaut silencieux typique de ce projet.
2. **rke2lab** — supprimer `ensureNetwork` et `GrowNetworkResolver.vmnetBridgeConfig`, devenus morts.
3. Vérifier par un seed-master : le premier veth (le bridge passe-t-il UP en gardant son adresse) et
   le premier bail — `bioskop-mgmt-master.bioskop` est muet tant qu'aucune instance n'a pris de bail.

## Leçon de méthode, chère

Sur « bioskop est lent à matérialiser », j'ai poursuivi le tunnel tailscale, la MTU, la clé ssh et le
débit — **tout démonté par mesure** — alors que la cause était dans le `nix.conf` que j'avais lu dès
le début : un miroir Tsinghua **actif** dans `extra-substituters`, 1,00 s par narinfo contre 0,20 s,
et incontournable puisque nix parcourt les substituters dans l'ordre jusqu'à celui qui *a* le chemin
— donc chaque dérivation maison payait le tour complet jusqu'au 404. Corrigé (ndh `16993780`).
★ Deux mesures que j'ai moi-même faussées : `/dev/zero` sur un ssh à `Compression yes` (je ne mesurais
que gzip) et un wrapper d'horodatage forkant `date` **par ligne** sur 12 400 lignes de `-vv`. Lire la
config avant de sonder le réseau ; et se méfier de son propre instrument.

See [[nikopol-mgmt-federation-clustermesh-first-case]] [[rke2lab-canonical-maven-invocation]]
[[single-owner-rule]]

## État au 2026-09-26, 02 h (avant compactage)

**Vivant et vérifié** : incus ne gère **aucun** réseau sur les deux hôtes (`managed: false` partout) ;
`fabric-br` + `vmnet-mgmt` + `vmnet-wrkld` appartiennent à networkd, en double pile, adresses
présentes bien que DOWN ; un seul dnsmasq par hôte les sert tous (5 plages, réservations fabric
nommées et vmnet sans nom) ; `nixos.bioskop` et `nixos.nikopol` résolvent.

**Le cluster incus EXISTE sur bioskop** — un membre, un votant (`database-leader` + `database`),
`cluster.images_minimal_replica: "-1"`, `cluster.join_token_expiry: 10M`. Il ne manque que le second
membre.

**Trois défauts trouvés en EXÉCUTANT, pas en relisant** (ndh `01745554`, `44298662`) :
`cluster.images_minimal_replica` passé dans le `config:` du préseed était **silencieusement ignoré**
(portée globale ⇒ exige que le cluster existe ; l'adresse, locale, passait) — d'où l'unité devenue un
**réconciliateur** qui réaffirme les réglages à chaque activation ; `incus config set … -1` échouait
sur `unknown shorthand flag: '1'` (cobra ⇒ `--` avant les positionnels) ; et interpoler un snippet
multi-ligne dans `if …; then` met le `;` seul sur sa ligne (⇒ fonction shell).
★ **`nix build --dry-run` n'ÉVALUE que** : shellcheck, que `writeShellApplication` lance à la
construction, ne tourne jamais sous dry-run. Les deux défauts de script y étaient invisibles.
Construire pour de vrai quand le changement est un script.

**App de join : script écrit, pas câblé.** `ndh pkgs/incus-cluster-join.d/join.sh` (non commité) suit
l'idiome maison (trampoline bash, `installBinScript` + `replaceVars`, outils épinglés, `--quiet` pour
le token avec refus si la dernière ligne ne ressemble pas à un token). Il manque la fonction
`mkIncusClusterJoin` + les entrées `apps` dans `flake.nix`. ★ Il démote en `database-client`
**immédiatement** après le join : entre les deux, un cluster à deux membres a DEUX votants
(`max_voters` impair ≥ 3, non abaissable), donc le départ de l'itinérant coûterait son quorum au
sédentaire. Le réconciliateur déclaratif l'assure aussi, mais seulement à l'activation suivante — cette
fenêtre est ce que l'app ferme.

## ★★★ COLD START bioskop-mgmt RÉUSSI — 2026-09-26, 11 h 46 : les 5 inconnues sont levées

`bioskop-mgmt-master` **RUNNING** sur `bioskop-nixos` (LOCATION vérifié), avec
`172.16.1.3 (fabric0)`, `10.80.0.10 (vmnet0)` et `10.44.0.116 (cilium_host)` — donc rke2 a démarré et
cilium s'est initialisé. Ce qui n'avait JAMAIS été exercé et qui passe :

- **`fabric-br` et `vmnet-mgmt` sont UP.** Ils vivaient DOWN avec leur adresse, ce qui était prouvé
  inoffensif mais jamais fonctionnel. Le choix « les bridges quittent incus » est maintenant validé.
- **Trois baux**, dont le PREMIER bail **IPv6** du projet : `fd96:6924:3693:20::a50:a`, exactement le v4
  embarqué de `10.80.0.10`. Le DHCPv6 stateful et la fenêtre de pool tiennent.
- **Le placement a tenu** grâce à `scheduler.instance = manual`, pas grâce à un `target` — voir
  [[incus-placement-must-be-stated-not-inferred]].

★ **`images_minimal_replica = -1` est INERTE — mesuré, question tranchée.** Après le grow : bioskop
porte **1,32 Go** sous `tank/nerd/incus/images`, nikopol **128 Ko** (le dataset parent vide). L'image
n'a pas voyagé. Donc la justification que j'avais écrite dans `incus-cluster.nix` (« sinon l'itinérant
n'a rien pour provisionner ») n'était pas seulement non mesurée : le réglage ne produit **aucun**
préchargement observable. À retirer ou à comprendre, plus à invoquer.

★ **`dhcp-host` ≠ `host-record` pour le DNS.** Un nom de `dhcp-host` ne devient résoluble qu'avec un
**bail actif** ; un `host-record` répond toujours. Mesuré : `nixos.bioskop` et `vzhost.bioskop`
répondent, `bioskop-mgmt-master.bioskop` est muet tant que le nœud n'existe pas. C'est voulu, mais ça
rend tout probe DNS sur un nœud pré-création structurellement impossible.

⚠️ **Le PREMIER essai a échoué, et la cause est une classe de défaut** : `GrowthCondition` lit le
snapshot Pulumi **à l'entrée du grow**, donc AVANT que le refresh du même `pulumi up` ne le corrige. Le
factory reset ayant détruit l'instance hors-bande, l'état disait encore `Running` ⇒ **WARM** ⇒ budget
`WARM_FAILFAST` (connect 5 s / total 10 s, au lieu du patient `PT2M`) ⇒ la sonde systemd a tiré sur un
nœud inexistant, le programme a levé, et Pulumi a avorté AVANT de créer l'instance (aucune ligne
`creating` — incus n'a jamais été sollicité). **Auto-correcteur** : le run suivant lit COLD et réussit.
Même famille que le device tailnet fantôme et le membre de cluster périmé — **une destruction
hors-bande fait mentir un enregistrement**. Non corrigé.

## ★★★ LE CLUSTER À DEUX MEMBRES EST VIVANT — 2026-09-26, 10 h 45

```text
bioskop-nixos   database-leader, database   database: true    Online  Fully operational
nikopol-nixos   database-client             database: false   Online  Fully operational
```

`database: false` sur l'itinérant est la pièce load-bearing : la majorité reste de **1**, donc nikopol
peut voyager sans emporter la base de bioskop. Et `incus-cluster-roles` tourne avec un code de sortie
**0** — son assertion « aucun autre membre n'est votant » passe désormais contre un vrai second membre,
plus contre le vide. Pool `default` cluster-wide, `images_minimal_replica: -1`, `join_token_expiry: 10M`.
App : `nix run .#<host>-incus-cluster-join -- --clear-joining-config` (ndh `c3415293`, `77b23284`,
`575418ba`, `26df5b73`).

**Ce que la jointure a coûté à découvrir**, tout par exécution et jamais par relecture :

- ★ **Un membre qui rejoint doit être VIDE.** `Config key "source" is cluster member specific` ne nomme
  pas sa cause : le démon joignant avait déjà créé `default` depuis son préseed, donc la jointure
  tentait de le *mettre à jour* avec la source du `member_config` — interdit sur un pool existant. D'où
  `--clear-joining-config`, qui refuse par DÉFAUT parce que `--preseed` escamote le « All existing data
  is lost, continue? » de la version interactive.
- ★ **Le préseed nixpkgs est un `incus admin init --preseed` NU**, sans garde de clustering. Oneshot +
  RemainAfterExit donc il ne rejoue pas seul, mais tout rebuild qui le change le relance — sur un membre
  en cluster désormais. Réglé par un `ExecCondition` qui fait SAUTER l'unité (sortie 1, jamais 255).
- ★ Trois défauts de mes propres scripts, même famille : voir
  [[ndh-logger-wrapper-disables-errexit]].
- ⚠️ **À MESURER, non tranché** : la justification de `images_minimal_replica = -1` reposait sur une
  supposition — qu'un membre sans l'image « n'a rien pour provisionner ». Jamais vérifié. Si incus copie
  à la demande vers le membre cible, `-1` fait voyager des gibioctets pour rien. Le vrai arbitrage est le
  MOMENT du transfert (préchargement à la maison contre à la demande depuis un hotspot). Mesurable
  maintenant : zéro image des deux côtés, donc regarder où atterrit la première, puis si
  `incus launch --target nikopol-nixos` va la chercher seul.
- ⚠️ **Reste ouvert** : retirer le membre périmé avant un re-join (un renew détruit l'appartenance mais
  pas l'enregistrement chez bioskop, donc la 2ᵉ jointure échouera sur un nom déjà pris) ; et
  `acceptRoutes` pour adresser par le segment au lieu du tailnet — **mesuré non viable en l'état**, les
  deux hôtes annoncent leur /20 mais aucun ne les accepte (`RouteAll: false`), la route part au routeur
  de la maison.

## ★★★ 2026-09-27 — un membre `database-client` NE SURVIT PAS à la mort de son votant unique

Le bare-metal bioskop a crashé et sa VM a été refaite (factory reset), donc `bioskop-nixos` —
`database-leader` + seul votant — a disparu avec le raft. État mesuré sur nikopol :

```text
server_clustered: true    cluster.https_address: 100.97.69.67:8443
incus cluster list → Failed to begin transaction: failed to create cowsql connection:
                     no available cowsql leader server found
```

**L'incus de nikopol est BRIQUÉ, pas seulement désynchronisé** : `database: false` signifie aucune copie
du raft, donc plus aucune écriture ne passe — il ne peut ni lire sa config, ni quitter le cluster, ni se
promouvoir. Incus n'offre **aucune** opération « quitter un cluster dont le votant est mort ».

★ C'est le revers, jamais écrit, du choix dont on n'avait noté que la moitié : `database: false`
permettait à l'itinérant de **voyager sans emporter la base**. La dépendance est **à sens unique** —
bioskop perd nikopol sans dommage, nikopol ne survit pas à la perte de bioskop. Avec `max_voters`
impair ≥ 3 et deux machines, **aucune** configuration ne protège les deux : le vrai livrable est un
runbook, ou l'acceptation que l'itinérant soit reconstruit à chaque perte du sédentaire.

Récupération (mesurée sans risque : tous les datasets vides, 128 K chacun, zéro instance) —
`systemctl stop incus.service incus.socket`, `rm -rf /var/lib/incus/{database,cluster.crt,cluster.key}`,
restart. Ça **compose** avec l'`ExecCondition` du préseed : la base partie, `server_clustered` redevient
faux et le préseed réinitialise en autonome. Puis re-pin du certificat côté Mac (le factory reset le
régénère — `certificate signed by unknown authority`, la dette « réconciliation de confiance câblée
seulement sur l'activation darwin » qui a mordu) et `nix run .#nikopol-incus-cluster-join --
--clear-joining-config`.

**DÉBLOQUÉ le 2026-09-26** : l'identité tailnet des hôtes est réglée et vérifiée vivante — le
matérialiseur Tart libère le nom entre l'arrêt du guest et l'effacement du disque
(`manage-tailnet --reclaim-host`, ndh `4fb6e112`+`3666cb3d`). Détail et mesures dans
[[tailnet-node-identity-ephemeral-ghosts]]. ★ Ordre imposé par ce réglage : **purge, puis join** — un
renew donne une adresse tailnet neuve, et c'est elle que `cluster.https_address` enregistre.
