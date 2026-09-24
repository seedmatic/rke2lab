# Handoff — forcer un VRAI rebuild de l'image nerd-nixos + le mesurer

> Notes d'opération (pas un design). Écrit le 2026-09-18 pour la session qui optimise le build
> image avec **EROFS**. Tout ce qui suit est **mesuré live** sur `bioskop-nixos`, pas supposé.
> Contexte/verdicts : mémoire `nerd-nixos-image-build-slow-not-zfs-on-zfs`,
> vision convergente : [gachix-nix-store-vision.md](gachix-nix-store-vision.md).

## Le piège : le build « ne tourne pas » (2 causes distinctes)

**(a) sortie déjà dans le store local** → `nix build` = no-op, aucun `qemu-system-aarch64` ne démarre.

**(b) sortie substituée depuis `nxmatic.cachix.org`** ← **LE piège, coûteux en temps perdu.**
L'image raw (**14,2 GiB**) y a été poussée par un build précédent : nix la **télécharge** au lieu de
la construire. Symptôme trompeur : « build fini » en quelques minutes, jamais de qemu, et le log
montre `copying path '…-nerd-bringup-zfs-disk-images-raw' from 'https://nxmatic.cachix.org'`.

## La recette qui marche

```bash
nix build "$DRV^out" -L --no-link --option substituters https://cache.nixos.org
```

`cache.nixos.org` n'a pas nos images → **build local forcé**, mais les dépendances (kmod,
nuke-refs, …) se substituent normalement. Bonus : supprime le bruit `cache.flakehub.com` 401
(bioskop-nixos tourne encore une config avec flakehub dans ses substituters ; il partira au
prochain rebuild du host).

## Ce qui NE marche PAS

`--no-substitute` → trop agressif, bloque **aussi** les deps de build :

```
error: Cannot build '/nix/store/…-nerd-bringup-zfs-disk-images-raw.drv'.
       Reason: 1 dependency failed.
```

## Si tu re-lances le MÊME drv (sortie déjà présente)

Pour EROFS ça ne devrait pas arriver (inputs changés → drv différent → sortie inexistante), mais
au cas où :

```bash
# 1. le wrapper manifest se supprime seul
nix-store --delete /nix/store/<hash>-io.seedmatic.ndh-nerd-bringup-zfs-disk-images

# 2. le RAW refuse ("still alive") : supprimer d'abord son RÉFÉRENT
nix-store --query --referrers /nix/store/<hash>-nerd-bringup-zfs-disk-images-raw
#   → …-io.seedmatic.ndh-manifest-base-….yaml
nix-store --delete <ce-yaml> && nix-store --delete <le-raw>     # libère ~14 GiB
```

⚠️ **Piège enchaîné** : la suppression peut emporter le `.drv` → `error: failed to obtain
derivation`. Re-pousser le drv depuis le Mac :

```bash
nix copy --derivation --to ssh://root@bioskop-nixos.local "$DRV"
```

## Gotchas d'opération (vécus)

- **`pgrep`/`pkill -f qemu-system-aarch64` par ssh s'auto-matche** (la chaîne est dans ta propre
  cmdline) → tu tues ton shell (`exit 255`). Filtrer sur le binaire
  (`qemu-host-cpu-only.*qemu-system`) ou tuer par PID relevé dans le log.
- **`/tmp` de bioskop-nixos est un tmpfs** → tout script d'orchestration y meurt au reboot de la
  VM Tart (`/tmp/ndh-measure.sh` inclus). Les `nix profile` de root, eux, survivent
  (`sysstat`/`iotop`/`socat` y sont installés).
- **Eval du flake côté Mac ≈ 100 s.** Si une éval part en IFD (import-from-derivation), elle
  déclenche un build **distant** pendant l'éval et devient interminable — éviter
  `callPackage "${derivation}/…"`, vendorer des fichiers statiques à la place.
- Lancer les évals longues **détachées** (`nohup … & disown`) : le harness interrompt les
  commandes de premier plan au bout de 2 min.

## Observabilité — la console guest ne remonte PAS au log nix

**Prouvé** : `echo MARK > /dev/ttyAMA0` (ou `/dev/console`) depuis le guest **n'apparaît jamais**
dans le log nix, alors que les logs virtiofsd (écrits côté hôte sur le même `/dev/pts/1`) oui.
Le log nix ne reçoit que la trace `preVM` (hôte) puis ~1500 **lignes vides** au boot, puis rien.
`-L` n'est pas en cause (déjà présent). Bug non corrigé, distinct.

Le canal fiable est le mirror commité (`4cdf9422`, ndh) :

```bash
tail -f /nix/var/nix/builds/nix-*/xchg/install.log
```

Second canal : shell debug guest sur `hvc0` — `socat - UNIX-CONNECT:/nix/var/nix/builds/nix-*/shell.sock`
(**`wait=off` = 1 seul client à la fois** : deux socat concurrents s'excluent).

## Chiffres de référence — à battre avec EROFS

- **Baseline `virtiofsd --cache=always`, 4 vCPU / 8 GiB : 3645 s (~61 min)** pour la phase qemu
  nested, build complet OK (`preVM … return 0`).
- Répartition observée : copie de closure finie vers **~35 min** (652 paths), puis **~26 min de
  post-copie**. ⚠️ **Attribution non mesurée finement** — ces 26 min mélangent queue de copie,
  `nixos-install`/bootloader, drain des TXG dirty (`sync=disabled`) + `zpool sync`/export. Si
  EROFS supprime l'écriture du store, une partie devrait tomber, **mais ce n'est pas prouvé**.
- **Plafond dur : le nested-KVM Apple thrashe au-delà de ~6 vCPUs.** À 8 : load guest ~21,
  threads en D-state, progression stallée, guest « unresponsive » (= CPU-saturé, pas hung).
  4 = défaut, 6 = max prouvé stable (nikopol). Donc **donner plus de cœurs à la VM Tart ne rend
  PAS le build plus rapide** (testé : Tart montée à 10c/46 GiB via `tart set`, sans effet).

## Rappel du verdict (ne pas refaire)

Le read-side est **clos** : `--cache=always` gardé (×3.3 sur la phase chaude, commit `67029777`) ;
**DAX virtio-fs = cul-de-sac** (jamais mergé dans QEMU mainline — vérifié 10.2.4, 11.1.0, master) ;
**squashfs read-only (Forme D) implémenté, mesuré, RÉFUTÉ et reverté** (round-trips virtiofs bien
éliminés — virtiofsd read = 0, idle guest 54 %→5 % — mais total ≈ baseline : la copie devient
CPU-bound sur unpack NAR + sha256 + write ZFS).

**Le mur est la PRODUCTION du store de sortie DANS la VM nested**, pas le transport d'entrée.
D'où EROFS : changer le **format de sortie** pour que le store se packe **sur l'hôte**
(débit hôte mesuré > 1 GB/s) — précédent in-tree `nixos/lib/erofs-store-image.nix` + le montage
`/nix/store` de `qemu-vm.nix`/`vz-vm.nix` (lower `/nix/.ro-store` erofs + overlay upper).
