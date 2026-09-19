---
name: erofs-store-layer-stack-vision
description: "Vision CONVERGÉE (2026-09-19) — /nix/store en PILE de couches EROFS, une par génération, déclarée par ndh ; justification = déterminisme. Whiteboard .claude/claude-preview.adoc. Expérience nikopol PRÊTE, non lancée"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-19T14:33:05.753Z
---

Brainstorm **convergé** le 2026-09-19, whiteboard dans
`.claude/claude-preview.adoc` du worktree `feature/nixos-node-substrate` (484 lignes,
figures C2 / C3a / C3b / C3c + un flow). Le précédent whiteboard est archivé sous
`.claude/claude-preview.archive-2026-09-19-cluster-intention-capi-decomposition.adoc`.
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
`docs/architecture/nixos-substrate/` + atlas → puis attaquer le code.

**Questions ouvertes** (4) : quel **K** (profondeur de pile) · le **couplage fleet-wide**
de l'intersection (changer un hôte change le lower de tous) est-il acceptable · 3 mesures
manquantes (limite de disques attachables · taux de décroissance de la couverture, 33 %
aujourd'hui · part des *contenus de fichiers* partagés entre closures, qui déciderait de
l'intérêt de git) · les 3 branches ci-dessus.

See [[nerd-nixos-image-build-slow-not-zfs-on-zfs]] [[materializer-corp-mac-identity-gcroots]]
[[nerd-nixos-tart-vm-renew-procedure]].
