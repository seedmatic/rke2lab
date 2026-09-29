---
name: tart-sync-none-voids-zfs-crash-consistency
description: "Pourquoi une coupure de courant détruit irrécupérablement le tank de nerd-nixos — sync=none annule le flush ZFS, et le raidz1 sur 3 images d'un même FS hôte n'est qu'un seul domaine de panne"
metadata:
  node_type: memory
  type: project
  originSessionId: 0fe01e86-5aca-4049-bb7d-bd3975bb0a80
  modified: 2026-09-27T07:24:58.548Z
---

**Vécu le 2026-09-27 sur bioskop : coupure de courant → `tank` FAULTED, pool
définitivement perdu.** Ce n'est pas de la malchance, c'est structurel.

`tart-nerd-tart-run.sh` attache **tous** les `required_disks` avec
`--disk=<d>:sync=none,caching=cached` (fonction `tart:run-args:required-disks:add`).
L'aide de tart le dit sans détour : *« disable data synchronization with the
permanent storage … at the cost of a higher chance of data loss »*. Donc quand ZFS
demande un flush pour clore un groupe de transactions, **l'hôte l'ignore**. Toute la
cohérence-au-crash de ZFS repose sur ce flush : sans lui, elle est annulée à la
racine. Seuls les `required_prebuilt_disks` (couches EROFS, immuables) sont en `:ro`.

Et le raidz1 n'aide pas : `tank1/2/3.img` vivent **dans le même système de fichiers
hôte**, donc c'est **un seul domaine de panne**. La coupure ne perd pas un disque,
elle perd les mêmes derniers txg sur les trois simultanément — il ne reste aucune
redondance pour reconstruire.

**Signature à reconnaître** (pour ne pas re-perdre une heure) : labels ZFS
**intacts** sur les 3 vdev (même GUID de pool), `raidz1-0 ONLINE` et chaque vdev
`ONLINE`, devices lisibles à 500-700 Mo/s, 46 Go réellement alloués par image,
anneau de 32 uberblocks avec une plage de txg saine — **et pourtant** `tank FAULTED
corrupted data` + `cannot import: I/O error`. C'est un **MOS** détruit, pas des
périphériques perdus. Corollaire : `zpool import -F` ET `-FX` échouent tous deux
(le `-FX` renvoie le message trompeur *« one or more devices is currently
unavailable »* en 0,3 s — il n'y a aucun disque manquant, c'est juste qu'aucun jeu
de txg n'est assemblable). **Ne pas s'acharner : le rewind ne peut rien**, puisque
l'historique lui-même n'a jamais atteint le disque.

**★ CORRIGÉ le 2026-09-27, ndh `3b31fd0b`** : `sync=full,caching=cached` dans
`modules/darwin/tart-config.d/run.sh` (`tart:run-args:required-disks:add`) — un seul
site dans tous les dépôts. `caching=cached` est gardé : `sync=full` honore le flush
quel que soit le cache de page hôte, donc le gain en lecture ne coûte rien. Vérifié
**vivant** sur les arguments du processus tart, pas seulement dans le script. Cran
intermédiaire si la perf régresse : `sync=fsync`. Le disque racine (`disk.img`, rôle
`primary`) est hors de cette boucle — tart l'attache lui-même et ndh ne pose jamais
`--root-disk-opts`, donc il hérite du défaut `.full` du framework.

**Ne PAS ajouter `-F` à l'import au boot** (question posée puis tranchée le 09-27) :
le rewind a été tenté sur ce pool précis, `-F` **et** `-FX` ont refusé — on
automatiserait donc ce qu'on a prouvé inopérant. Raison de fond : `-F` revient à un
txg *committé*, ce qui suppose que l'historique a atteint le disque ; avec
`sync=none` il ne l'avait jamais fait, et avec `sync=full` un crash laisse un pool
importable, donc `-F` devient inutile. Et son coût est réel : `-F` détruit par
conception les dernières transactions committées — automatique au boot, ça veut dire
« à chaque crash, rembobiner en silence ». L'emergency mode était le BON
comportement : il a rendu le problème visible. `forceImportRoot`/`forceImportAll`
(`-f`, pas `-F`) restent nécessaires pour une raison sans rapport : le hostid change
au passage bringup→système complet et les labels doivent être ré-estampillés.

Garde-fou qui reste valable quoi qu'il arrive : **que rien d'irremplaçable ne vive
dans `/persist`** — en particulier le cert funnel, dont la ré-émission brûle du
budget Let's Encrypt (voir [[funnel-identity-is-per-cluster]]).

**★ Le runbook de rescue est daté sur un point** : il annonce Debian « ZFS ready ».
Faux sur trixie — `zfsutils-linux` est absent et il faut activer `contrib` puis
compiler `zfs-dkms` (2.3.9, ~5 min, kernel 6.12). Ce qui reste juste, et vaut le
détour : les disques sont désormais au format **ASIF** (`config.json` :
`"diskFormat": "asif"`, magic `shdw`), donc **illisibles en brut depuis macOS** — un
`dd` à l'offset 0 ne montre que l'en-tête du conteneur, pas de GPT, pas de label ZFS.
Tout diagnostic ZFS doit passer par un invité. Et piloter la VM rescue par
**`tart exec`**, pas par SSH. Choisir Debian (ZFS 2.3.x) plutôt qu'Ubuntu (module
préconstruit mais 2.2.x) : un pool NixOS 26.05 peut porter des features que 2.2
refuse.

See [[nerd-nixos-tart-vm-renew-procedure]] [[erofs-store-layer-stack-vision]].
