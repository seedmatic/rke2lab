---
name: tailnet-node-identity-ephemeral-ghosts
description: "RÉSOLU le 2026-09-26 (ndh 4fb6e112+3666cb3d) — un factory reset laisse un fantôme qui RETIENT le nom tailnet ; le matérialiseur Tart libère désormais le nom ENTRE l'arrêt du guest et l'effacement du disque, via manage-tailnet --reclaim-host (sans filtre d'âge). ephemeral=true était le mauvais levier."
metadata:
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-26T07:18:04.292Z
---

**Une seule cause, un seul levier.** `modules/.common.d/manage-tailnet.d/default.nix:62` construit le
corps de la requête `POST /keys` avec **`ephemeral = false`** (et `reusable = true`,
`preauthorized = true`). Un nœud non-éphémère n'est jamais retiré quand il disparaît ⇒ chaque
factory reset laisse un fantôme ⇒ le fantôme **retient le nom** ⇒ l'enregistrement suivant est
suffixé.

**Constaté sur la flotte le 2026-09-20** : 16 devices dont 13 fantômes, familles `nerd-nixos-1..7`
(nom du **bringup**, où `networking.hostName` vaut le littéral `nerd-nixos`),
`nikopol-nixos-1,2`, `bioskop-nixos-1,2`. Le nœud vivant `nikopol-nixos` s'affichait comme
**`nerd-nixos-7`**, et `bioskop-nixos` comme **`nerd-nixos-4`** — donc *toute* la flotte portait un
nom de bringup suffixé, pas seulement un nœud.

**Ce n'était PAS un second défaut.** J'avais diagnostiqué « la génération runtime ne réclame jamais
son nom » comme un défaut distinct. Faux : elle advertise bien le bon nom
(`--hostname=${cfg.hostname}` avec `defaultHostname = config.networking.hostName`,
`modules/nixos/headscale.nix:14,159`) ; elle ne pouvait pas l'**obtenir** tant qu'un fantôme le
détenait. Preuve : après la purge **+ un reboot**, le nœud s'est renommé tout seul (l'utilisateur a
précisé que le reboot était nécessaire — le renommage prend effet au ré-enregistrement, pas à chaud).

**Le bringup rejoint le tailnet DÉLIBÉRÉMENT** — `modules/nixos/bringup-minimal-system.nix:85-89` le
documente (accès opérateur pendant l'installation) et note que l'état vit dans `/var/lib/tailscale`
sur la racine ZFS persistante, donc l'enregistrement **survit** à la bascule. Ce n'est pas un bug
d'ordonnancement à corriger, c'est une fonctionnalité dont le nommage est l'effet de bord.

**La purge existe déjà** (découverte par l'utilisateur, pas par moi) :
`nix run .#manage-tailnet -- --prune-stale-devices --stale-after 1s --yes`. ⚠️ `1s` a un rayon
d'action large — elle a aussi emporté `tailscale-operator`, `flux-webhook`, `pipelines-webhook` et
`bioskop-mgmt-controlplane`, qui sont des devices créés côté Kubernetes et non des fantômes de
nœuds. Ils se ré-enregistrent, mais choisir `--stale-after` selon ce qu'on veut réellement élaguer.

**Remède automatique, NON appliqué (décision en attente)** : `ephemeral = true` sur la clé des
guests. Compromis à peser — une machine éteinte longtemps perdrait son identité de nœud ; sans
conséquence ici puisque les ACL passent par des **tags** et que
`modules/home-manager/ssh-tailnet-hosts.nix:161` documente déjà tolérer la rotation des clés d'hôte
des guests. Réserve non vérifiée : la fenêtre de grâce avant suppression d'un nœud éphémère (de
l'ordre de la demi-heure ?) — à mesurer, elle décide si un reboot normal provoque du churn.

**Dommage collatéral qui rend ça visible** : `ssh-tailnet-hosts.nix` **génère la config ssh de
l'opérateur depuis les hôtes du tailnet**, donc les fantômes la polluent.

**★ Question de fond ouverte, posée par l'utilisateur** (« ça laisse perplexe quant à la gestion des
identités dans le tailnet, on devrait pouvoir faire mieux ») : une identité de nœud a **trois
sources** — le nom vient du hostname de la *génération qui s'enregistre la première* (donc du
bringup), l'identité de nœud d'un état sur disque, et la *récupérabilité* du nom d'une purge
externe. Trois sources pour une seule identité, d'où l'imprévisibilité. Chantier à ouvrir.

See [[erofs-store-layer-stack-vision]] [[nerd-nixos-tart-vm-renew-procedure]].

## Récidive le 2026-09-26, et une CORRECTION du levier désigné ci-dessus

Mesuré après les cold starts de la nuit : `bioskop-nixos-1` (100.83.253.42) et `nikopol-nixos-1`
(100.97.69.67) vivants, tandis que `bioskop-nixos` (100.89.98.37, offline 8 h) et `nikopol-nixos`
(100.73.122.6, offline 7 h) retenaient les noms. Conséquence concrète : `host nikopol-nixos` résolvait
vers le **fantôme**, donc l'alias `nixos.nikopol` était mort et tout accès à nikopol demandait une
surcharge d'adresse. bioskop s'en sortait par un autre chemin — son nom nu tombait sur le domaine de
recherche `.lan` → 192.168.1.130 — et `nikopol-nixos.local` marchait parce que le mDNS voit le vivant
sur le LAN sans passer par le tailnet.

★ **`ephemeral = true` est le MAUVAIS levier pour l'hôte itinérant**, contrairement à ce que dit la
section précédente. Un nœud éphémère est supprimé **dès qu'il se déconnecte** — or se déconnecter est
la nature même de nikopol. Il serait donc recréé à chaque voyage, avec une **nouvelle adresse tailnet
à chaque retour**. Et l'adresse compte désormais : `cluster.https_address` du cluster incus est une
adresse tailnet, enregistrée dans l'état du cluster. On échangerait un problème de nom instable contre
un problème d'adresse instable, et c'est l'adresse qui casse le cluster. (Pour un hôte sédentaire
l'argument ne mord pas, mais un levier qui ne vaut que pour la moitié de la flotte n'est pas le levier.)

**Direction retenue (décision utilisateur, 2026-09-26)** : ne pas inventer un second mécanisme —
**répliquer la logique d'enregistrement des nœuds de CLUSTER pour les hôtes `nerd-nixos`**. Elle
existe déjà (`TailnetPurgeManifestsUnit` côté rke2lab purge les doublons `name-1` et les orphelins à
chaque grow) : on **supprime les hôtes périmés du tailnet AVANT d'enregistrer le nouveau nœud**. La
flotte est alors traitée uniformément, et le nom est stable par construction plutôt que réparé après
coup. À faire : lire cette unité, puis la porter côté hôte (matérialisation ou premier boot, avant
`tailscaled-autoconnect`).

⚠️ **Fausse piste, ne pas y retourner sans répondre à la question qu'elle soulève** : persister
`/var/lib/tailscale` sur « un dataset qui survit au renew ». Ça paraît juste — le commentaire de
`bringup-minimal-system.nix` dit déjà que la réutilisation de l'inscription est *voulue* (« so the
full config reuses the same registration on handoff ») et elle ne tient pas à travers une
rematérialisation. Mais il n'y a pas d'« extérieur du renew » là où je le croyais : `nerd/*` ne sont
pas un pool séparé, ce sont des **datasets de `tank`** (le nom du pool est interpolé dans
`zfs-disko-config.nix`, d'où un grep littéral qui ne trouve rien), et l'ensemble est construit par le
**tart image builder**. Donc la question préalable est **« qu'est-ce qui survit réellement à un
renew ? »** — le dataplan nomme une moitié `persist`, mais si le builder recrée le pool ce mot n'est
persistant qu'à l'intérieur de la vie d'une VM, exactement le même piège de vocabulaire que le
« persistent ZFS root ». Non tranché.

See [[bridges-leave-incus-forced-not-chosen]] [[nikopol-mgmt-federation-clustermesh-first-case]]

## RÉSOLU et VÉRIFIÉ VIVANT le 2026-09-26 — ndh `4fb6e112` + `3666cb3d` + `23458f6a`

**Le verbe** : `manage-tailnet --reclaim-host <nom>` (répétable) supprime les devices **taggés** nommés
`<nom>` ou `<nom>-<N>`, **sans filtre d'âge**, et c'est là toute la différence avec
`--prune-stale-devices`. Deux raisons, les deux mordent :

- au renew le device de l'hôte est encore **EN LIGNE** (la VM n'est pas encore arrêtée), donc un filtre
  d'âge épargne exactement le device dont on veut le nom. Tailscale ne marque un nœud hors ligne
  qu'après son keepalive (~50 s) — d'où la boucle de garde à 90 s du Job in-cluster, qui *attend* ce
  qu'on sait déjà. Affirmer « cette identité est détruite » supprime la course au lieu de la subir.
- `--prune-stale-devices` parcourt **tous** les devices taggés, donc l'hôte itinérant simplement
  *absent* est dans son rayon. ⚠️ **Ce défaut reste dans le Job in-cluster** (`--stale-after 1s`) :
  `nikopol-nixos` porte `tag:nixos` et est hors ligne dès que la machine voyage. Non corrigé.

**La POSITION est l'argument de correction**, pas un détail — dans `tart:vm:factory-reset:apply`,
**entre l'arrêt du guest et l'effacement du disque**. Plus tôt est *faux* et pas seulement prématuré :
supprimer le device pendant que le guest tourne fait voir à tailscaled sa clé invalidée, il se
**ré-enregistre depuis sa clé d'auth** et recrée le reliquat qu'on voulait éviter, quelques secondes
avant qu'on efface le disque. Plus tard court après l'enregistrement du guest renouvelé. (Point
soulevé par l'utilisateur ; mon placement initial, en tête du matérialiseur, avait ce défaut.)

**Découplage** : l'identité (`guest_host_name`, distinct du `vm_name` générique `nerd-nixos`) voyage
dans le run manifest comme tout fait par hôte ; l'outil est un jeton de bundle. `vm.guestHostName`
(`modules/.common.d/vm.nix`) expose la composition qui produisait déjà le `networking.hostName` du
guest, au lieu d'en écrire une seconde. Le bundle porte le **`.secrets` CHIFFRÉ** car un vz-host n'a
pas de checkout — sûr parce que `.gitattributes` désactive les filtres git sops exprès pour que
l'arbre tienne le chiffré ; une assertion refuse d'évaluer sinon (comme `sops.nix` pour `keys.yaml`).

**★ CORRECTION de mon hypothèse du jour** : j'avais désigné `guestHostName = "nerd-nixos"` codé en dur
(`bringup-minimal-system.nix:19`, identique pour les deux hôtes) comme la cause de fond, et proposé de
dériver le nom du MAC. **Mesuré faux** : le reset de nikopol a produit **un seul device** sur tout le
cycle — le bringup prend `nerd-nixos` puis le handoff le **renomme** en `nikopol-nixos`. Le reliquat
`nerd-nixos` qu'on observait était un **artefact de collision** (nom déjà tenu ⇒ `nerd-nixos-1` ⇒
débris), pas une fatalité du nom générique. Libérer le nom suffit. Reste vrai mais peu urgent : deux
bringups **simultanés** collisionneraient encore.

**Résultat mesuré** : `nikopol-nixos` revenu sous son nom, sans `-1`, DNS
`nikopol-nixos.mammoth-skate.ts.net`, adresse **neuve** `100.97.69.67` — attendu (nouvelle clé de
nœud) et c'est ce que `cluster.https_address` enregistre, d'où l'ordre **purge puis join, jamais
l'inverse**.

**Closure, mesurée** : `manage-tailnet` traînait **1602 Mo dont 1540 de `git`** (qui tire python3,
clang, apple-sdk) alors que `git` ne sert qu'à `--commit`. Paramètre `withCommit` ⇒ le bundle Tart
passe de **1608 Mo à 172 Mo**. Le même gain vaut pour l'env flox in-cluster, qui le traîne pour rien.

**★ Leçon sops, corrigée par l'utilisateur** : j'avais ajouté un destinataire age dédié au Mac corp
pour qu'il ne déchiffre pas `keys.yaml`. **Sans effet** — la clé opérateur est *déjà* dans ce home pour
signer les commits, donc quiconque accède à la machine déchiffre la CA SSH, que sops soit configuré
pour elle ou non. Retiré (`23458f6a`). L'exposition est dans la **liste de destinataires**, pas dans la
machine : `age10ey0l…` (= la clé ssh `github-signing-hyland`) ouvre **tous** les fichiers sops de ndh,
et la même clé signe les commits ⇒ la faire tourner révoque le déchiffrement dans ndh **et** rke2lab.
Le vrai correctif — une identité opérateur distincte pour les fichiers sensibles — **n'est pas fait**.

⚠️ **Piège voisin, non corrigé** : `manage-tailnet --retag-devices --apply`. Son heuristique « kind
d'après le hostname » retombe sur `darwin` par défaut, donc en dry-run elle propose de tagger le
**téléphone personnel** (`Google Pixel 6a`, aujourd'hui non taggé donc protégé de la purge) et de
retirer `tag:k8s` aux proxies de l'opérateur. Un téléphone taggé devient purgeable.
