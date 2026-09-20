---
name: erofs-store-layer-stack-vision
description: "Vision CONVERGÉE (2026-09-19) — /nix/store en PILE de couches EROFS, une par génération, déclarée par ndh ; justification = déterminisme. Whiteboard .claude/claude-preview.adoc. Expérience nikopol PRÊTE, non lancée"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-20T19:35:16.910Z
---

**La vision est SHIPPÉE EN SPEC** (rke2lab `a86a5a9f4`) :
`docs/architecture/nixos-substrate/erofs-store-layer-stack.adoc` — la *vision*, distincte de
`erofs-store-lower.adoc` qui décrit l'*acquis*. Renvois croisés bidirectionnels depuis le
lower, `substrate-model`, `node-bootstrap-delivery`, l'atlas et `docs/README`. Le whiteboard
d'origine est archivé sous
`.claude/claude-preview.archive-2026-09-19-erofs-layer-stack-vision.adoc` — **ne plus le
citer comme source vivante, lire la spec**.
Suite de [[nerd-nixos-image-build-slow-not-zfs-on-zfs]] (l'EROFS shippé) — **cette entrée
est la VISION, pas l'acquis**.

**Le modèle.** Le lower devient une **pile ordonnée** de couches EROFS (`lowerdir` est une
`nonEmptyListOf` chez NixOS, jointe par `:` — vérifié) : couche de base = **intersection
des closures runtime de la flotte** (identity-less), puis une petite couche de résidu par
hôte, puis un delta par génération. Frontière de propriété : **l'instance possède ses
DONNÉES** (pools, upper), **ndh possède le LAYOUT** ; un reboot peut changer le layout à
condition qu'il couvre les closures des générations retenues (« backward compatible »).

**Décisions (7 sur 12 sont de l'utilisateur)** : on EMPILE, on ne remplace pas (préserve le
rollback ; purger = supprimer un disque, pas des fichiers) · **pas de copy-up** (mesuré :
94 491 fichiers, la classe de coût qu'on venait d'éliminer) · couche de base =
intersection · `ndh` déclare la pile (aucun canal de remontée : source-vs-projection) · le
**merge est un choix de rendu, différable** (LSM : empiler puis compacter) · **chaque
génération = une couche** · **le switch devient `nixos-rebuild boot` + `systemctl reboot`**
(pas de contrainte de disponibilité) · **`ndh` possède l'ESP** (conséquence).

**★ La justification première est le DÉTERMINISME, pas l'économie** (recadrage de
l'utilisateur, plus juste que le mien) : aujourd'hui `nixos-rebuild switch` fait
matérialiser la closure PAR LE NŒUD — 165 591 créations de fichiers **hors de toute
dérivation** — pour produire ce que le builder aurait produit. Avec une couche par
génération, la matérialisation rentre DANS une dérivation : reproductible,
content-addressed, substituable, vérifiable (`nix-store --realise --check`), et le nœud ne
construit plus rien, il monte. Ancrage : le commit `8d6b6acb` (mai) l'écrivait déjà —
« bioskop is the authority and distributes closures to consumers ».

**Mesures qui fondent tout ça** (sur les 2 nœuds up) : intersection des runtimes
bioskop/nikopol = **1608 sur 1658/1662 = 96 %**, propre à chacun 50/54 · lower actuel 659
chemins, gen 1 à 100 % dedans, gen 2 à 33 % (554), **0 chemin dans les deux couches** ·
upper 1104 chemins / 5,3 GiB / 165 591 fichiers · un delta **n'est PAS clos seul** (142
références sortantes sur 60 chemins) donc la closure est vraie de la PILE, pas d'une couche
· 98,5 % du pool d'un nœud est l'upper du store.

**Le crochet existe déjà** : `bringup-zfs-disk-image.nix` porte `runtimeSystemPath`,
documenté « Production runtime system closure to include in the bringup image store »,
consommé par `systemd/zfs-nixos-install.{nix,sh}`. Il est à `null` parce qu'embarquer la
closure d'UN hôte tague l'image par hôte ; **la pile lève exactement cette tension**.

**Composants réellement nouveaux** : seulement deux — le **calcul de l'intersection** et le
**calcul de péremption** (« une couche est supprimable ⟺ l'union des closures retenues
n'intersecte aucun de ses chemins »). Tout le reste devient une projection sur une liste
SSOT `erofs-store-layers.nix`, sur le patron de `zfs-pool-disk-map.nix`. **Darwin n'a RIEN
à changer** : `run.sh:225/449` et `activation.sh:876` sont des boucles sur N, le manifeste
émet `role: prebuilt` par image.

**★★ EXPÉRIENCE LANCÉE ET CONCLUANTE le 2026-09-19 (2 manches).** Les 3 branches qui
n'avaient jamais tourné ont tourné, chacune correctement. Preuve finale sur
`nikopol-nixos` : lower passé de l'UUID `…a62` à `…a63` et le nœud **boote dessus** ·
`tank` à **7,90 GiB ONLINE avant ET après** · `system-2-link` toujours courante (aucune
réinstallation, générations intactes) · hostname `nikopol-nixos` et non `nerd-nixos`. Le
log nomme les trois décisions : `[WARN] root disk holds materialized content from another
source; preserving it` · `tank1/2/3` + `recover` `ASIF data disk has live ZFS data;
preserving` · `store prebuilt image materializing from source: …`.

**Manche 1 a DÉTRUIT les pools**, et c'est ce qui a livré la vraie trouvaille : la branche
de préservation était **structurellement inatteignable**, donc *toute* matérialisation sans
`VM_FACTORY_RESET` détruisait les pools, sur n'importe quel hôte. D'où le « jamais
exercé ». Deux correctifs, tous deux dans `ndh` sur `develop` :

- `47943f0b` — la porte du disque root décide sur le **contenu** (marqueur `.source` + sonde
  de partition `EFI`) et non sur la taille ; toute l'introspection d'image passe par une
  porte unique `plutil → json` ; sondes à trois valeurs.
- `6213a553` — `tart:vm:exists` interroge **une** VM via `tart get --format=json` et ne crée
  que sur un `exit 2` formel ; plus une ceinture qui refuse de blanchir un disque portant du
  ZFS ou illisible.

Leçon générale et ses deux instances : [[destructive-gate-needs-three-valued-probe]].

**Historique du but initial** : éprouver les **3 branches jamais exécutées** (préservation
des pools · re-matérialisation d'une couche quand la source change · préservation/
remplacement du disque root). Mode d'échec de la première = **perte des pools**, d'où la
cible.

- **Cible `nikopol-nixos`** ; `bioskop-nixos` reste le builder (unique builder
  aarch64-linux : le perdre bloque la réparation).
- **Astuce du test** : on change l'**UUID** de l'image EROFS — paramètre *sémantiquement
  inerte*, le montage se fait par label — donc nouveau bundle et marqueur en désaccord,
  mais contenu de couche identique ⇒ le boot ne peut pas casser.
- ⚠️ **ÉDITION NON COMMITTÉE dans ndh** : `modules/nixos/erofs-store-image.nix`, uuid
  `…a61` → `…a62`. **À REVERTER après l'expérience.**
- Bundle neuf **déjà construit** : `/nix/store/b9f6dk7vxzqgfv62wx5xjm045p32dx9v-io.seedmatic.ndh-nerd-bringup-zfs-disk-images`
  (son `store.img` = `pbqwjqspi4qlf3phnf153ipg1mpz2s5b-…`, UUID `…a62` vérifié par `dump.erofs`).
- **État de nikopol AVANT** (pour comparer) : marqueur → `h01wjv82…/store.img` ; disk.img
  141217792 (100 Go virtuels, GPT = `GUID_partition_scheme` + `EFI`, **pas** de
  `disk.img.source`), recover.img 1446256640, store.img 2776629248, tank1/2 4031328256,
  tank3 4031324160 ; pools `tank` ONLINE **7,98 GiB alloués**, `recover` 644K.
- **Protocole** : matérialiser sur nikopol-vzhost **SANS** `VM_FACTORY_RESET`, puis
  vérifier : pools préservés · `store.img` remplacé · marqueur à jour · le nœud boote.
- **Filet** : en cas d'échec, renew (~11 min, chemin exercé 2× le 2026-09-18/19).

**2026-09-19 — la porte a été DURCIE avant de lancer l'expérience** (ndh `47943f0b`, à la
demande de l'utilisateur : « la taille du disque root n'est pas un marqueur fiable selon
moi, on devrait durcir cette porte non ? » — il avait raison, j'avais mesuré qu'elle
répondait « préserve » par **coïncidence** de tailles, 629 145 600 des deux côtés). Détail
et leçon réutilisable : [[destructive-gate-needs-three-valued-probe]].

Conséquences pour le protocole :

- Les trois branches éprouvées sont désormais les branches **durcies** — pas celles qu'on
  s'apprêtait à remplacer. C'est pour ça qu'on a durci d'abord.
- Comportement attendu sur nikopol : disque root **préservé** avec un `[WARN] root disk
  holds materialized content from another source` (marqueur absent + partition `EFI`
  détectée) · 4 disques de pool **préservés** (`content-hint: ZFS` vérifié sur les 4 par
  clone APFS, sans arrêter la VM) · `store.img` **remplacé** (marqueur `store.img.source`
  en désaccord) et marqueur réécrit.
- **Le materializer est déployé par `nix copy`, pas par nix-darwin** — nikopol-vzhost ne
  fait PAS tourner nix-darwin. Build :
  `nix build .#packages.aarch64-darwin.nerd-tart-nikopol-materialize`
  → `/nix/store/ki3yy8zknv4sq1hv3dcx5qp3crhdjczy-nerd-tart-vm-materialize` (porte le script
  durci + le bundle `b9f6dk7v…` + `nixos-system-nikopol-nixos-26.05.20260911.21a67dc`).
  6 chemins manquants sur 1816, ~9,9 Go. Puis exécution **depuis le store path** sur le
  vzhost. Pas besoin de `NDH_IMAGE_MANIFEST_OVERRIDE` : le bundle neuf est baké dedans.
- Env vars utiles quand même : `NDH_IMAGE_MANIFEST_OVERRIDE` (gagne sur l'auto-résolution,
  qui sort tôt si le chemin est déjà lisible), `NDH_IMAGE_STORE_OVERRIDE`,
  `VM_FACTORY_RESET`, `TART_DISK_RELEASE_TIMEOUT_SECONDS` (nouveau).

**Plan de l'utilisateur après l'expérience** : si concluante → basculer le whiteboard dans
`docs/architecture/nixos-substrate/` + atlas → puis attaquer le code. **FAIT, et le code
est allé jusqu'à la phase 2 — voir ci-dessous.**

## 2026-09-19/20 — PHASES 0, 1 et 2 LIVRÉES ET VÉRIFIÉES SUR NIKOPOL

**Phase 0** (rke2lab `a86a5a9f4`) : la spec `docs/architecture/nixos-substrate/erofs-store-layer-stack.adoc`,
renvois bidirectionnels, atlas + `docs/README`, whiteboard archivé.

**Phase 1** (ndh `5383034d`) : `erofs-store-layout.nix` → `erofs-store-layers.nix`, une
LISTE ; les consommateurs deviennent des projections. À **contenu constant**, vérifié par
évaluation : image EROFS et les deux toplevels gardent leurs store paths.

**Phase 2** (ndh `6d25264c`, + `713826dd`) : une couche par closure + delta entre elles.

**★ RÉSULTAT MESURÉ après renew de nikopol-nixos** (facteur de réussite de toute la vision) :

| | avant | après |
|---|---|---|
| couches montées | 1 | **2**, par label, `vdf`/`vdg` |
| overlay | 1769 | 1769 = 659 + 1109 + 1 |
| **upper** | 1104 chemins / **5,3 GiB** | **1 chemin / 21 Ko** |
| **pool `tank`** | **7,90 GiB** | **26,1 Mo** |
| durée de la bascule de génération | — | **1 seconde**, upper inchangé |
| tailles de couche | — | base 2,58 GiB + delta 7,47 GiB (1,74 GiB partagés NON dupliqués) |

Le `lowerdir` observé est `…/.ro-store.002:…/.ro-store` — le plus récent à gauche, conforme
à la doc noyau, et l'ordre des slots virtio est *inverse* de l'ordre déclaré : sans
importance, le montage est par label. C'est ce qui justifie le label comme référence.

**Détails qui ne se lisent pas dans le diff** :

- `runtimeSystemPath` alimentait l'UNIQUE `closureInfo`, donc l'utiliser aplatissait la pile
  en une seule couche de 9,2 GiB. D'où trois closures séparées : base, runtime, et l'**union**
  pour la registration — une couche n'est pas close seule.
- **Labels déclarés, jamais dérivés du contenu** : impossible pour la couche d'une génération
  (point fixe — la config nommerait un label dérivé d'un contenu qui l'inclut). D'où l'index
  paddé, et la limite dure : `mkfs.erofs` refuse 16 caractères, **15 max** (mesuré).
- Le trampoline/logger ne va PAS dans `bringup-zfs-disk-images-install.sh` : il exige le
  profil bootstrap NDH que ce script installe lui-même (circulaire), et son FD 2 redirigé
  empêche QEMU de se terminer. Essayé, cassé, reverté, raison écrite en tête du fichier.
- `replaceVars` scanne les `@nom@` **jusque dans les commentaires** — un commentaire
  expliquant pourquoi ne pas utiliser un placeholder a suffi à casser le build.
- `comm` exige ses entrées triées dans SA collation et ne signale un désaccord que par un
  avertissement en produisant un résultat faux → `LC_ALL=C` explicite dans `delta.sh`.

## PHASE 3 — approche STRUCTURELLE validée par mesure, câblage à faire

**Tranché par l'utilisateur : approche structurelle, pas intersection ensembliste.** Motif :
« elle nous permettra de mieux maîtriser le contenu de chaque couche », et le layout `ndh`
essayait déjà de jouer cette logique.

**Le layout encodait bien l'intention** : `hosts/host-common.nix` est la part commune
*paramétrée* par `hostProfile`, et le spécifique d'un hôte tient en ~90 lignes —
`hosts/<h>/nixos.nix` (37-45 l.) plus la config de `hosts/<h>/profile.nix`. Concrètement :
`bringupObserve`, `services.sshfsMounts`, `profile.user.home`, les candidats de clés sops.
Le `hostProfile` lui-même ne fait que **6 champs**, dont un seul est de l'identité
(`hostName`) ; les `nixosDiskImageVm*` ne touchent même pas la closure runtime.

**`nixosConfigurations.nerd-nixos` est le système bringup partagé** — `nikopol-bringup` et
`bioskop-bringup` ont le **même toplevel** (`8yg13jjw…`), ce sont des alias. Donc pour le mode
minimal le partage existe déjà (= couche 001) ; pour le mode `full` il n'existe pas.
⚠️ Contrainte d'exploitation donnée par l'utilisateur : `nerd-nixos` étant le même système
partout, il ne peut être **vivant sur le réseau que sur un vzhost à la fois** → le bringup est
sérialisé sur la flotte.

⚠️ **`hosts/nerd-nixos/` est du CODE MORT** : aucune référence nix (le
`nixosConfigurations.nerd-nixos` exposé est un alias construit par `outputs.nix:584-588`). Son
`profile.nix` ne fixe que `profile.host.*` et n'importe **pas** `host-common.nix` — c'est le
squelette du *baseline bringup*, plus mince que ce que L2 demande.

**★ MESURE DÉCISIVE (faite, closures construites)** — L2 = closure d'une génération `full`
avec le `hostProfile` neutre de `hosts/nerd-nixos` + `host-common.nix` :

| | chemins | taille |
|---|---|---|
| L1 bringup | 659 | 2,59 GiB |
| **L2 générique full − L1** | **1098** | **7,49 GiB** — PARTAGÉE |
| **L3 résidu nikopol** | **77** | **39,6 Mo** |
| lest dans L2 (chemins qu'aucun hôte n'utilise) | 70 | 39,6 Mo |

Couverture vérifiée : **0** chemin de la closure nikopol absent de la pile. Le lest est bien
ce qu'on prédisait — des dérivations engendrées par la config (`unit-home-manager-…`,
`X-Restart-Triggers-*`, `sshd.conf-final`, `nftables-save-deletions`).

Contre l'intersection ensembliste (L2 1056 / ~7,45 GiB, L3 53 / ~34 Mo, lest 0) : la
structurelle coûte **40 Mo de lest + 6 Mo par hôte**, soit 0,5 % de la couche partagée, et
achète la **suppression du couplage fleet-wide**. Bruit contre bénéfice → structurelle.

**Effet attendu** : la couche par hôte passe de **7,47 GiB à 39,6 Mo**, facteur ~190.

**★ PATCH NON COMMITTÉ dans `ndh/flake.nix`** — à reprendre : il ajoute
`nixosConfigurations.nerd-runtime` (bindings `fleetRuntimeHostProfile` /
`fleetRuntimeProfileModule` / `fleetRuntime` insérés dans le `let` de `nixosConfigurations`,
à côté de l'alias `nerd-nixos`). Il **évalue ET construit** :
`0yxxi9glxgm3327nw32rggn9x2bvwjy3-nixos-system-nerd-nixos-nixos-…`. Délibérément non committé :
rien ne le consomme encore, donc il partira dans un seul commit avec le câblage des 3 couches.
`mkNixosConfig` est exporté et déjà dans la portée de `flake.nix` — pas besoin de toucher à
`outputs.nix` pour ça.

## ★★★ 2026-09-20 — TOUT EST LIVRÉ, JUSQU'À L'ACTIVATION AU BOOT

**La spec porte désormais le détail** :
`docs/architecture/nixos-substrate/erofs-store-layer-stack.adoc` (rke2lab `69370f20e` +
`9f9444f37`) — figures C3a/C3b refaites sur le livré, flux de bootstrap, les deux invariants,
les décisions. **Ne pas dupliquer ici ce que la spec dit** ; cette note garde les chiffres,
les commits et ce qui reste.

**État final vérifié sur `nikopol-nixos`** (deux factory resets successifs) : `current` =
`booted` = `llkgfl08…-nixos-system-nikopol-nixos` · couches **661/1098/77** · overlay 1837 ·
**upper 1 chemin / 21 K** · `tank` **23,6 Mo** (contre 7,90 GiB avant le chantier) · couche
par-hôte **39 Mo** (contre 7,47 GiB) · 0 chemin manquant.

**Commits `ndh`** (chaîne `4c6ec85e` → `7c00be7b`) : `4c6ec85e` treefmt isolé (dette
préexistante) · `5be06617` la couche partagée de flotte + `mkFleetRuntimeConfig` (le patch
`flake.nix` en attente y est fondu) · `3757f8ba` PATH de tart + fuite `socat` · `e3451f13` la
porte de remplacement · `008bb497` un bundle par hôte · `0dde86de` runbook · `7c00be7b`
l'activation au boot. **rke2lab** : `69370f20e`, `9f9444f37`.

**L'activation au boot, livrée** : `modules/nixos/systemd/bringup-target-activate.nix`. Le
toplevel cible arrive comme **donnée** (`/var/lib/ndh/bringup-target-system`, écrit par
l'installateur de la VM imbriquée) et **jamais** par `ndh.context.runtimeSystemPath` — sinon
le toplevel de bringup référencerait le runtime et la closure de base avalerait la pile.
Vérifié après coup : closure de bringup à 661 chemins, aucun `nixos-system` étranger.
Preuve du fonctionnement : générations 1 (13:32:49) et 2 (13:39:22) créées sans intervention,
plus le couple `bringup-target-system` / `.attempted` sur le nœud.

⚠️ **Sept minutes entre les deux générations.** La bascule elle-même coûte **0,804 s**
(mesurée à la main) ; le reste est le boot de bringup qui doit atteindre la cible contribuée
avant que l'unité passe. Candidat d'amélioration : ordonner l'unité plus tôt.

**Pièges rencontrés, à ne pas re-découvrir** :

- `zfs-nixos-install.{nix,sh}` **ressemble** au véhicule de l'activation au boot et n'en est
  pas un : il installe dans un `--root` séparé (topologie d'avant l'EROFS) et n'est importé
  que par une config à la fois `bringupMode` **et** important `modules/nixos/default.nix` —
  combinaison qu'aucune config livrée ne satisfait. **Il n'est dans aucun toplevel.** J'ai
  affirmé deux fois qu'il était « armé mais vide » ; c'était faux, il est orphelin. Le
  `assertions = lib.mkForce [ ]` de `bringup-minimal-system.nix` neutralise TOUTES les
  assertions du bringup pour se garder d'une assertion qui ne peut pas l'atteindre — à
  supprimer, avec le paramètre mort `runtimeSystemPath` de `mkNixosConfig`.
- **tart appelle `diskutil` via PATH**, et le PATH de connexion du Mac corp ne porte pas
  `/usr/sbin` (interactif comme non interactif) → `tart run` échouait. Le manifeste de run
  portait un champ `diskutil_bin` que **personne ne lisait**.
- **`exec` interdit tout trap** : `run.sh` remplaçait son shell par tart, donc le relais
  `socat` survivait à chaque lancement (4 orphelins trouvés).
- La clé ssh autorisée sur le guest est `rdp-host`, dans `~/.local/share/ndh/ssh-keys/`, et
  le bloc `~/.ssh/config` matche `Host nerd-nixos` — **pas** `nerd-nixos.local`. Cibler le
  `.local` contourne le bloc et fait tomber sur un prompt de mot de passe. `sudo` casse tout
  (HOME devient `/var/root`). Il n'y a **pas** de bloc pour `nikopol-nixos`.
- Un factory reset régénère les clés d'hôte du guest → purger `known_hosts`.

**Restes**, par ordre de valeur : la piste content-addressed
[[erofs-layer-images-input-addressed-rebuild]] · la **péremption** d'une couche (le dernier
composant orange de la figure C3a) · ~~supprimer l'orphelin `zfs-nixos-install` et le
`mkForce [ ]`~~ **FAIT, ndh `8aa201c3`, voir ci-dessous** · ordonner l'unité de bascule plus tôt ·
restructurer le manifeste (18
références, 3 fichiers) · dé-masquer `modules/.common.d` (113 occurrences de code, **20 dans
`docs/sessions/` à NE PAS réécrire**) · `README-bootstrap.md` cite
`.#nixosDiskImages.<host>.full`, un attribut mort (c'était l'image disque du runtime complet,
supprimée ; origine confirmée dans `62838ec8`).

**Questions encore ouvertes** : quel **K** (profondeur de pile) · limite de disques
attachables (on est à **8** et ça passe) · part des *contenus de fichiers* partagés entre
closures, qui déciderait de l'intérêt d'un backend git · faut-il baker le runtime dans l'ESP
pour un boot unique (coûterait 0 de plus, le bundle étant déjà per-hôte, et lèverait la
contrainte « `nerd-nixos` vivant sur un seul vzhost à la fois »).

## 2026-09-20 soir — 3e checkpoint nikopol : le nettoyage de l'orphelin, mesuré inerte

**ndh `8aa201c3`** supprime `zfs-nixos-install.{nix,sh}`, son import dans `systemd/default.nix`, le
champ mort `ndh.context.runtimeSystemPath` (+ le paramètre de `mkNixosConfig`), le
`before = [ zfsNixosInstallServiceName ]` de `zpool-init` dans `zfs.nix`, et le
`assertions = lib.mkForce [ ]` de `bringup-minimal-system.nix`. Précédé de `4032ae37` (treefmt isolé)
et suivi de `ed4e4af5` (authz-tools ssh partagés).

**Ce que le nettoyage a révélé** : le `mkForce [ ]` neutralisait **1437 assertions** NixOS du bringup
pour se garder d'UNE assertion inatteignable ; toutes les 1437 évaluent maintenant, **0 échoue**. Et
le champ `ndh.context.runtimeSystemPath` était vraiment illisible par personne : le diff de
dérivations ne montre **aucun** effet de sa suppression. Le seul delta de comportement sur toute la
flotte était la ligne `Before=io-seedmatic-ndh-zfs-nixos-install.service` de `zpool-init.service` —
un ordonnancement contre une unité inexistante, donc un no-op pour systemd. Vérifié ensuite **sur le
nœud vivant** : l'unité n'a plus que ses `Before=` implicites, `active`/`success`/status 0, et zéro
fichier d'unité correspondant à `zfs-nixos-install`.

⚠️ **Inerte en comportement ≠ inerte en octets** : les trois couches changent (l'unité modifiée est
dans les trois closures), donc ce nettoyage seul coûte un renew complet avec factory reset. Leçon de
cadence : le faire **voyager avec un changement qui paie déjà ce coût**, jamais le déclencher seul.

**État vérifié après renew** (`sr0mndcq…` = current = booted) : couches **661/1098/77**, overlay
**1837**, upper **1 chemin / 21 Ko**, `tank` **23,3 Mo**, générations 1 à 17:25:54 → 2 à 17:33:12 sans
intervention. Identique au checkpoint précédent, ce qui était le résultat attendu.

**★ QUAND A-T-ON VRAIMENT BESOIN D'UN FACTORY RESET ? Mesuré le 2026-09-20 au soir.** Réponse :
**pour remplacer des couches, jamais pour mettre un nœud à jour.** Un reset produit un *triplet
cohérent* (entrée ESP + profil nix du pool + contenu des couches nommant les mêmes toplevels) ; ce
qui casse, c'est de remplacer le contenu d'une couche sous un ESP et un pool préservés. La topologie
overlay, elle, est déjà là et serait préservée — et c'est précisément **parce que l'upper est
inscriptible que le chemin pas cher reste ouvert**.

Éprouvé sur nikopol-nixos pour passer de `8aa201c3` à `ed4e4af5` :
`nixos-rebuild boot --flake .#nikopol-nixos --target-host root@nikopol-nixos.local` + reboot, au lieu
d'un renew. Résultat : génération 3 = `ih26zj7w…` (vérifié identique à la sortie du drv de `develop`,
donc aucune dérive), couches **intactes** (661/1098/77), et le prix total = **34 chemins / 9,2 Mo**
dans l'upper (contre 1 chemin / 21 Ko), pool à 133 Mo. Contre : 17 Gio de transfert à 66 MB/s, pools
détruits, et un fantôme tailnet de plus à purger. **Rapport ~1 pour 1800 en octets.**

Et la pureté n'est pas perdue mais **mise en pause** : les couches n'ayant pas bougé, le prochain
factory reset — quel qu'en soit le motif — ramène l'upper à ~1 chemin.

**La branche de REFUS du garde reste non exercée** — `VM_FACTORY_RESET` la contourne par
construction. Prédiction lue dans le code pour le jour où on voudra l'éprouver : marqueur
`disk.img.source` présent mais en désaccord → sonde EFI positive → `preserve` → le garde trouve les
trois couches à remplacer et **refuse**. Coût du test : un arrêt de VM (la porte stoppe avant de
décider), aucune perte.

**Deux unités rouges, ni l'une ni l'autre imputable** : `systemd-boot-random-seed` échoue
structurellement (`/boot` monté `ro` → `Operation not permitted`) et c'est **elle seule** qui met
`systemctl is-system-running` à `degraded` sur tous les nœuds — donc `degraded` ne signale plus rien
d'utile tant qu'elle n'est pas réconciliée. `incus-preseed` a échoué une fois
(`Network "bare-br" already exists`) puis a disparu des échecs au boot suivant : défaut
d'idempotence sur ré-exécution, pas une lacune de configuration.

See [[nerd-nixos-image-build-slow-not-zfs-on-zfs]] [[materializer-corp-mac-identity-gcroots]]
[[nerd-nixos-tart-vm-renew-procedure]] [[common-d-is-a-directory-wide-nix-input]]
[[unmanaged-mac-ssh-material-chain]] [[tailnet-node-identity-ephemeral-ghosts]].
