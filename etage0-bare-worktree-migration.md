---
name: etage0-bare-worktree-migration
description: "Étage 0 de bioskop vers bare/worktree sur deux volumes APFS : décidé et mesuré le 2026-10-02, AUCUN geste posé. Les quatre décisions, et les pièges qui cassent en silence."
metadata:
  type: project
---

**★★★ DÉCIDÉ ET ENTIÈREMENT MESURÉ le 2026-10-02 — rien d'exécuté.** Runbook complet dans
`.claude/etage0-migration-handoff.md` (gitignoré, `.claude/.gitignore:56`). Premier incrément du
chantier devpod-comme-atelier ; le *pourquoi* est dans `.claude/devpod-atelier-whiteboard.adoc`.

## Les quatre décisions utilisateur

1. **Layout** : converger sur les noms de nikopol — `/Volumes/git-bare-store` +
   `/Volumes/git-worktree-store`.
2. **Périmètre** : la fermeture du DAG rke2lab seule — 8 dépôts (rke2lab, ndh, nnh, claude-hub,
   flox-nri-plugin, flox-controller, nix-flake-commons, fleet). 6,4 Go sur les 298 du volume.
3. **Autorité** : le nouveau magasin immédiatement ; l'ancien gelé en lecture.
4. ★ **On DUPLIQUE, on ne déplace pas.** Ça annule `git worktree repair`, l'exigence « quand c'est
   calme », et tout risque d'atomicité — mais ça crée **deux copies vivantes**, qui est exactement
   la forme du fork de mémoire déjà mesuré (248 fichiers contre 325). D'où la règle : la bascule des
   consommateurs se fait dans le MÊME geste que la vérification.

## Ce qui casse en silence — les cinq pièges mesurés

1. ★ **Cloner depuis GitHub perdrait des commits.** 5 branches portent du non-poussé, dont
   `feature/controlplane-osgi-migration` et 3 rendus `manifests/*` **sans aucun upstream**. Donc
   `git clone --bare <chemin local>`, puis repointer `origin`.
2. ★ **L'état Pulumi embarque le chemin absolu du worktree** → export / `sed` / import, jamais un
   `cp -a`, et surtout pas « recréer + refresh ». Détail et recette dans
   [[pulumi-stack-per-worktree-backlog]].
3. ★ **`git clean -fdx` détruirait les planches** — 353 entrées dans `viewpoint-separation` seul,
   dont les whiteboards, les handoffs et `.local.d/` + `.pulumi-state/`. Elles sont gitignorées *par
   conception*. Et `git clean -fd` (sans `-x`) n'achète que 4 entrées sur toute la fermeture. Le
   vrai travail est le **triage inverse** : ce qui, étant ignoré, doit quand même voyager.
4. **`cp -a .local.d` emporterait `render/`**, c'est-à-dire 4 worktrees dont les `.git` pointent sur
   l'ANCIEN bare → worktrees orphelins. Exclure `render/`, le reconstruire par `git worktree add`.
5. **`.image.checksum.sha256` doit voyager avec `rootfs.squashfs`** (634 Mo) — séparés, l'image se
   rebâtit.

**Non-piège, contrairement à l'intuition** : les filtres sops viennent du store nix via
`~/.config/git/config`, **pas** du `.git/config`. Un clone neuf les hérite où qu'il soit.

## Les contraintes de FORME sur le layout cible

- `[include] dir = "../.flox.d/<nom>"` se résout depuis le **parent du worktree**, donc un symlink
  vers `fleet/flox` à **chaque niveau portant un worktree**. ★ `rke2lab.d/design/.flox.d` **manque
  aujourd'hui** → `flox activate` y échoue déjà, avant toute migration.
- Le `.code-workspace` de `viewpoint-separation` **n'a rien à changer** : ses chemins relatifs
  (`../seed-incluster`, `../../ndh.d/develop`) survivent, et ses libellés disent déjà
  `seedmatic/rke2lab.git/…` — ils décrivaient le layout cible avant qu'il existe.

## Trois corrections à des croyances de départ

- **Pas de ZFS sur bioskop** (`zpool: command not found`). `/private/var/lib/git` est déjà un volume
  « Git Clones », `disk3s8`, **`Case-sensitive APFS`**, owners activés. Les nouveaux se créent en
  **`APFSX`**, gratuits (891,6 Go libres dans `disk3`).
- ★ **nikopol n'est PAS case-sensitive** — ses deux magasins sont `APFS` simple. La divergence est
  l'inverse du souvenir. Et son modèle est **un conteneur dédié et dimensionné par magasin**
  (disk8 8 Go, disk10 9 Go), parce que c'est une VM dont les conteneurs sont des disques virtuels :
  **la topologie ne peut pas converger sur du bare-metal, seuls les noms le peuvent.**
- **Le motif `main`-comme-dépôt-primaire touche TROIS dépôts** — rke2lab, flox-controller,
  flox-nri-plugin. `ndh.git` est le seul vrai bare, donc **le gabarit** (noter son
  `tagOpt = --no-tags`).

## Deux chantiers greffés sur cet incrément

- **Le motif « mémoire sur branche orpheline » aux 7 autres dépôts** (commencé dans rke2lab seul).
  ★ Urgent : `flox-nri-plugin` et `flox-controller` ont un `autoMemoryDirectory` pointant sur des
  worktrees **supprimés**, donc chaque session y repart d'une mémoire vierge sans que rien ne le
  signale. `nnh` porte l'anti-motif (mémoire dans l'arbre d'une branche de code, 10 fichiers à
  migrer). Voir [[hub:worktree-per-conversation]].
- **Aucune stack `gh` déclarée** pour les dépôts seedmatic, et l'extension n'est pas installée sur
  ce siège. Le layout cible reste donc **un worktree par branche** ; la compression par stack est
  ultérieure et ne toucherait que les 5 branches de travail de rke2lab.

## Dette constatée au passage, hors périmètre

Trois défauts de ndh désignent `/var/lib/git/seedmatic/ndh`, **qui n'existe pas** (ndh vit en
`ndh.d/develop` + `ndh.git`), et aucun n'est surchargé : `etc-nixos-flake.nix:48`,
`openssh.nix:91`, `tart-config.nix:775`. Périmés avant cet incrément.

★ Et **rien dans ndh ne crée les volumes** — ni ici ni sur nikopol, où ils ont été faits à la main.
Le seul lien déclaratif est leur consommation (`sshfsMounts`,
`profile.user.home = /Volumes/user-home`). Rien ne les recréera après une reconstruction de disque.

Conséquence croisée : changer le layout de bioskop oblige à changer `hosts/bioskop/nixos.nix` et à
reconstruire la VM NixOS. Mesuré : **rien dans ndh ne consomme `/net/bioskop.local/…`** (un seul
commentaire dans `starship.nix`), donc l'ancien mount peut vraiment disparaître — c'est ce qui rend
la convergence avec `hosts/nikopol/nixos.nix` possible.
