# Plan — décomposer le CPU write-side du build d'image nerd-nixos

**Contexte / acquis (ne pas re-litiger)** : read-side CLOS. `virtiofsd --cache=always` gardé (×3.3, %system 44-74%→17-19%). DAX = cul-de-sac (jamais mergé mainline QEMU). Forme D (squashfs source read-only) RÉFUTÉE + revertée : reads éliminés (virtiofsd read=0, guest idle 54%→5%) mais **total ≈ baseline ~50 min**. → Le mur est la **production du store de sortie DANS la VM nested** : `%system` 86-92%, décomposé (à l'œil, non isolé) en **unpack NAR + hash sha256 + write ZFS checksum/compression**.

**Objectif** : isoler laquelle des 3 composantes domine, **en UN seul run** (chaque build coûte ~50 min → pas d'A/B à l'aveugle).

---

## ★ RÉSOLU PAR ARITHMÉTIQUE (2026-09-18) — aucun build nécessaire

**Mesuré live sur bioskop-nixos** (closure `/run/current-system`) : **1662 store paths, 9,3 GiB, 260 121 fichiers/symlinks**. `Features` du guest Tart : `aes pmull sha1 sha2 sha512 sha3` → **accélération crypto matérielle PRÉSENTE** (Check 0 = faux espoir), 10 cœurs.

Décomposition sur ~3000 s de build :

| Travail | Nature | Temps attendu |
|---|---|---|
| sha256 sur 9,3 GiB (HW accel) | octets | 5-10 s |
| zstd-1 sur 9,3 GiB | octets | ~30-60 s |
| écriture brute 9,3 GiB | octets | ~90 s |
| **création de 260 121 fichiers** | **opérations** | **tout le reste (~2800 s)** |

→ Tout le travail « octets » = **1 à 3 min sur 50**. Le reste = 260 k créations de fichiers à **~11 ms/fichier ≈ 87 fichiers/s**, contre des milliers/s en ZFS natif. **Le build est borné par les OPÉRATIONS par fichier, pas par les octets.** Cohérent avec la Forme D (lectures supprimées → total inchangé) et avec la note mémoire « latence/CPU par-opération de la VM nested ».

**Conséquences (ordre de priorité INVERSÉ par rapport au plan initial) :**
- ✗ `compression=off` → viserait les octets = **1-2 % de gain**. Abandonné.
- ✗ accélération sha256 → déjà présente, et négligeable de toute façon.
- ✗ **tous les leviers ZFS bon marché sont DÉJÀ appliqués** (`zfs-disko-config.nix`) : `atime=off`, `xattr=sa`, `recordsize=16K`, et **`sync=disabled` sur le pool pendant le bringup, restauré avant export** (l.187-189). Plus aucun fruit bas.
- ✅ **Store EROFS+overlay en SORTIE, packé sur l'hôte** = le seul levier restant, et il vise la bonne chose : `mkfs.erofs --tar=f` transforme 260 k créations de fichiers en **une écriture séquentielle d'un seul fichier image** → retombe dans la classe « octets » (minutes, pas heures).

**★ L'argument décisif (pourquoi EROFS et pas « créer le zpool sur l'hôte ») :** `mkfs.erofs` **ne requiert PAS root** — il écrit juste un fichier. Créer un zpool requiert root. **C'est précisément la raison d'être de la VM imbriquée : obtenir root dans un build nix.** Donc l'EROFS est la **seule** voie qui sorte la production du store de la VM nested **en restant une dérivation nix pure** — donc reproductible et dédupable fleet-wide, la contrainte gravée dans `outputs.nix` (« the image bytes stay bit-identical and nix dedups »).

**Caveats honnêtes** : (a) les 9,3 GiB / 260 k mesurés sont la closure *runtime* de bioskop-nixos, pas exactement celle de l'image bringup (~8 GiB uncompressed) — même ordre de grandeur, conclusion inchangée ; (b) les ~11 ms/fichier attribuent quasi tout le temps aux fichiers, alors que boot de la VM nested / `zpool create` / bootloader en prennent une part — même à moitié, les fichiers dominent massivement ; (c) **le vrai coût du changement est architectural** : le store ne vit plus sur ZFS (perte compression/snapshots ZFS *du store*, remplacées par EROFS + overlay), refonte `zfs-disko-config.nix`, upper sur disque et non tmpfs. Ce n'est pas une optimisation de build, c'est une **décision de design du runtime** (= Tier 2 de la vision).

**Reste à mesurer (facultatif, si on veut confirmer avant de coder)** : le protocole par-thread ci-dessous vaut toujours pour *vérifier* que les kthreads ZFS sont bien minoritaires face au userspace nix — mais l'arithmétique est déjà sans ambiguïté.

---

## Pourquoi mesurer avant de coder

Les 3 composantes n'ont pas les mêmes leviers, et traiter la mauvaise ne rapporte rien :

| Composante | Levier | Coût du levier |
|---|---|---|
| ZFS compression (dataset en `zstd-1`) | `compression=off` (ou `lz4`) pendant l'install, réactivé au 1er boot | petit diff, réversible |
| hash sha256 | **vérifier d'abord l'accélération matérielle** (cf. check 0) ; sinon quasi-irréductible | nul si c'est juste un flag CPU manquant |
| unpack NAR (userspace nix) | seul vrai levier = **sortir la production du store de la VM nested** (store EROFS packé sur l'hôte) | refonte `zfs-disko-config.nix`, perte features ZFS du store |

## Check 0 — le moins cher, à faire AVANT tout build (minutes)

**Le guest nested expose-t-il les extensions crypto ARMv8 (sha2) ?** Si non, sha256 est fait en logiciel → peut expliquer à lui seul une grosse part, et le fix est un flag CPU, pas une refonte.

- Dans le guest : `grep -o 'sha[0-9]*\|aes\|pmull' /proc/cpuinfo | sort -u` (chercher `sha2`).
- QEMU est lancé `-cpu max` (cf. `bringup-zfs-disk-image.nix`, wrapper `kvmDetectQemu`) → sous KVM nested `max` devrait passer les features de l'hôte, mais **à vérifier, pas à supposer** (c'est du nested sur Apple Virtualization).
- Benchmark direct dans le guest : `openssl speed -evp sha256` — comparer à l'hôte bioskop-nixos. Un écart >5× = accélération absente.

## Mesure principale — décomposition par-thread, un seul run

L'astuce : **ZFS fait sa compression + checksum dans des kthreads dédiés** (`z_wr_iss`, `z_wr_int`), séparés du process nix userspace. Donc un simple relevé **par thread** sépare déjà ZFS de nix, sans perf.

Pendant la phase de copie/install du build réel (`nerd-nixos-bringup-zfs-systemd-disk`), depuis le shell debug guest (`hvc0` / `shell.sock`, cf. `pauseAfterInstall`) :

1. **Par-thread, échantillonné** : `top -H -b -n 30 -d 5` (ou `ps -eLo pid,tid,comm,pcpu --sort=-pcpu | head -30` en boucle).
   - threads `z_wr_iss`/`z_wr_int` → **ZFS compression+checksum**
   - process `nix`/`nix-store`/`nix-daemon` en **%user** → unpack + sha256
   - `%system` hors kthreads ZFS → syscalls écriture / VFS
2. **Confirmer le ratio user/system global** : `vmstat 5` (colonnes `us`/`sy`) + `pidstat -u 5`.
3. **Débit ZFS réel** : `zpool iostat -v 5` dans le guest + `zfs get -r compressratio,compression,checksum <pool>` (confirme que le dataset cible est bien en `zstd-1` — défaut de `nixosZstdCompressionLevel`).
4. **Si le split user/system reste ambigu** : `perf record -a -g -- sleep 30` dans le guest pendant la copie, puis `perf report --sort=dso,symbol`. Cherche `sha256_*` / `zfs_compress` / `zstd_*` / `fletcher_4_*`. Nécessite `perf` + symboles dans l'image du builder (à ajouter au builder, pas à l'image produite).

## Critères de décision (écrits AVANT la mesure)

- **ZFS kthreads dominants (>40% du CPU total)** → tenter `compression=off` (ou `lz4`) pendant l'install + réactivation au 1er boot. Petit diff, on gagne vite.
- **sha256 dominant ET accélération matérielle absente** → fixer l'exposition CPU au guest. Gain potentiel énorme pour un flag.
- **unpack NAR userspace dominant** → aucun réglage ne sauve : passer au store **EROFS+overlay en SORTIE** packé sur l'hôte (précédent in-tree `nixos/lib/erofs-store-image.nix` + montage `qemu-vm.nix`/`vz-vm.nix`, boot-prouvé sur Apple Virtualization ; déjà reproductible `-T 0 --force-uid/gid=0 -U fixe`). Attention : upper sur **disque**, pas tmpfs (`builderMemSizeMiB` 8-16 GiB).
- **Réparti à peu près également** → la refonte EROFS-sortie est le seul levier qui déplace >1 composante à la fois.

## Gotchas déjà payés (mémoire) — ne pas re-découvrir

- `-device vhost-user-fs-pci` explicite **casse le boot** → utiliser `if=virtio`.
- **nested-KVM Apple thrashe au-delà de 6 vCPUs** (8 → guest load 21, D-state, stall). `builderVmCpuCores` : 4 par défaut, nikopol=6 OK.
- Guest « unresponsive » = **CPU-saturé**, pas hung.
- Compression du média source = pénalité de décompression → non compressé.
- **Bug séparé, gêne l'observation** : la console guest (`ttyAMA0`) n'atteint PAS le log nix → la trace `set -x` de l'install est invisible. Seul canal guest fiable = `hvc0`/`shell.sock` (1 client max). En tenir compte pour collecter les relevés.

## Ne PAS faire

- Re-tenter un fix **read-side** (transport de la closure source) sous quelque forme que ce soit — réfuté par mesure.
- Relancer la piste DAX.
