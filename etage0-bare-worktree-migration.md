---
name: etage0-bare-worktree-migration
description: "Étage 0 de bioskop migré vers bare/worktree sur deux volumes APFS case-sensitive : EXÉCUTÉ, vérifié, et l'ancien magasin SUPPRIMÉ le 2026-10-02. Reste le rebuild NixOS. Porte les 3 trous que seul un triage inverse révèle."
metadata:
  node_type: memory
  type: project
  originSessionId: 56347839-f6c3-41aa-82ca-50aeb242564e
  modified: 2026-10-02T09:09:11.349Z
---

**★★★ EXÉCUTÉ, VÉRIFIÉ, ET L'ANCIEN SUPPRIMÉ le 2026-10-02.** Le siège travaille depuis
`/Volumes/git-bare-store` + `/Volumes/git-worktree-store`. L'ancien
`/private/var/lib/git/seedmatic` **n'existe plus** : les 8 dépôts de la fermeture (6,4 Go) ont été
supprimés après la vérification ci-dessous ; seuls les 3 hors fermeture restent sur place
(`java-bbox-api-client`, `java-systemd`, `macos-screen-sharing-addons`, 17 Mo). Le gel en lecture
n'a donc jamais eu lieu — on est passé directement à la suppression, décision utilisateur, la
vérification étant passée. Runbook + passation complète dans
`.claude/etage0-migration-handoff.md` du worktree (gitignoré, `.claude/.gitignore:56`) — sa section
« COMMENCE ICI » porte l'état d'exécution et les 7 pièges. Premier incrément du chantier
devpod-comme-atelier ; le *pourquoi* est dans `.claude/devpod-atelier-whiteboard.adoc`.

## Preuves d'exécution, en une ligne chacune

* 8 clones **bare locaux** (jamais depuis GitHub : 5 branches non poussées, dont une sans upstream)
  → **47 branches vérifiées identiques**.
* ★ `pulumi preview` : `~ 1 to update · - 1 to delete · 20 unchanged`, **et l'ancien worktree donne
  le plan IDENTIQUE** → migration **neutre en comportement, aucun cold start**. L'empreinte d'image
  `077320b4…` correspond à l'image vivante dans incus, parce qu'elle vient du **contenu**
  (`SplitImageFingerprint.of(metadata, rootfs)`), pas du chemin.
* `main` ramené sur `develop` (`ed531af2e`) + force-push, avec 2 refs de filet **publiées**
  (`backup/main-before-stack-realign`, `backup/origin-main-before-stack-realign`).
* Mémoire : **346 fichiers des deux côtés**, par commit sur la branche `memory` puis fast-forward.

## ⚠️ Le fork n'est pas théorique — il s'est produit

Jouer `pulumi preview` dans les **deux** worktrees (pour prouver la neutralité) a fait commiter un
render de chaque côté : les 4 branches `manifests/*` ont divergé en quelques minutes.
★ **`pulumi preview` n'est donc PAS en lecture seule côté git** — il commite
`render <cluster> manifests @ <sha>`. D'où l'urgence du gel.

Les 4 tips anciens sont conservés en `refs/heads/backup/old-render-*` dans le nouveau bare. Leur
seul écart sémantique était `dirty: false` → `true` dans `manifest.yaml` ; le reste n'était que du
bruit de ré-chiffrement sops (±même nombre de lignes). Des artefacts dérivés, donc.

## ★★★ Le triage inverse n'est pas optionnel — 3 trous, tous gitignorés

Avant de supprimer l'ancien magasin (2026-10-02), la vérification a trouvé **trois choses vivantes
qui n'existaient que là**. Toutes les trois étaient **gitignorées**, et c'est exactement pourquoi
elles sont passées : un clone local porte les objets git, une copie d'artefacts porte ce qu'on a
pensé à nommer, et **aucun des deux ne porte l'ignoré-mais-vivant**.

1. **Les 16 `manifest.lock` de `fleet`** — absents du nouveau magasin (seuls les `manifest.toml`
   étaient trackés). Sans eux, chaque env flox que les `[include]` de rke2lab consomment relockait
   sur une closure potentiellement différente. Cf. [[references-resolve-at-build-not-runtime]] :
   le lock DOIT voyager. Les locks de ndh, nnh et flox-controller, eux, sont trackés — donc arrivés.
2. **Le certificat du listener incus de bioskop** — `listener.crt` + clé EC dans
   `ndh.d/develop/.local.d/incus-listener-cert/bioskop/`, générés la veille. Pas régénérable sans
   re-trust.
3. **La mémoire de `flox-nri-plugin`** — `MEMORY.md` + `extraction-task.md` dans l'arbre de code
   (l'anti-motif), la base de connaissance de ce dépôt.

★ **Et une quatrième catégorie qu'aucune comparaison de refs ne peut voir : les `git stash`.**
L'ancien rke2lab en portait **8**, le nouveau bare 0. Une entrée de stash n'est ni une branche ni un
tag — elle n'est atteignable que par le **reflog de `refs/stash`**. Six étaient des `autostash`
(résidus de rebase, abandonnés), mais deux étaient délibérés, dont un étiqueté par l'utilisateur
lui-même « SAFETY NET, do NOT inspire alignment; align from specs » (2026-07-11). Conservés en
`refs/archive/stash/*` dans le nouveau bare, messages d'origine intacts.

**La recette, pour la prochaine fois :** comparer les refs ne suffit pas. Il faut (a) `git stash
list` sur chaque dépôt source, (b) `git status --porcelain --ignored=matching` par worktree, puis
comparer **par contenu** (`cmp`) ce qui reste après avoir écarté le régénérable — et le régénérable
se filtre par motif **non ancré** (`.flox/log` vit aussi sous `flox/<env>/.flox/log`, 96 faux
positifs sinon).

## ★★ Deux mémoires concurrentes dans le dépôt — RÉPARÉ

**248 fichiers de mémoire sont commités sur la branche `main`** (`main/.claude/memory`, tracké),
concurrents de la branche orpheline `memory` (346 fichiers). C'est le côté **perdant** du fork de
2026-09-29 (248 contre 325 → réconciliés en 346), resté commité. L'orphelin est un **sur-ensemble
strict** : 192 identiques, 56 divergents, **aucun** fichier présent seulement côté tracké ; son
`MEMORY.md` fait 18 Ko (index restructuré) contre 132 Ko (ancien index plat).

✅ **RÉPARÉ le 2026-10-02** — `73ac3b584`, poussé sur `develop` **et** `main` (en fast-forward, pas
une réécriture). Les 248 fichiers sont supprimés du tronc + règle **ancrée** `/memory/` dans
`.claude/.gitignore:52`. Ancrée exprès : un `memory/` nu attraperait aussi `.claude/hub/memory/`,
la mémoire du subtree hub, qui DOIT rester trackée. Vérifié — un checkout frais de `main` n'a plus
de `.claude/memory`. Les checkouts `main`/`develop` restent **hors** du magasin : dans le modèle de
pile, le tronc se visite par `gh stack trunk`.

## ★★★ La leçon qui généralise — reculer une branche ressuscite ce qu'elle ne contenait plus

Cette mémoire concurrente **n'était pas un vieux résidu** : `origin/main` ne la portait plus
(`60b04c5db`, 0 fichier). C'est le **réalignement de pile qui l'a ressuscitée** — `main` ramené de
`7b34a5354` à `ed531af2e`, c'est-à-dire **sous** le commit
`27687987f refactor(claude)!: memory becomes an orphan branch` qui l'avait supprimée. Et ce commit
portait aussi `memory-guard.sh`, `memory-commit.sh` et leur hook `settings.json` : le recul a donc
retiré **le garde-fou contre précisément l'accident qu'il a causé**, puis le force-push l'a publié.

> **Règle : avant de reculer une branche, lire ce que contiennent les commits abandonnés.** Un
> `reset --hard` vers un commit ancien ne « remet pas en ordre », il **défait tous les refactors
> intermédiaires** — y compris ceux qui supprimaient des pièges. Aucune erreur de configuration
> n'est nécessaire pour que ça morde.

★ **Contrôle d'ouverture de session** : si `MEMORY.md` fait 132 Ko / 250 lignes, tu lis la mémoire
**périmée** — le dire immédiatement.

## Les quatre décisions utilisateur

1. **Layout** : converger sur les noms de nikopol — `/Volumes/git-bare-store` +
   `/Volumes/git-worktree-store`.
2. **Périmètre** : la fermeture du DAG rke2lab seule — 8 dépôts (rke2lab, ndh, nnh, claude-hub,
   flox-nri-plugin, flox-controller, nix-flake-commons, fleet). 6,4 Go sur les 298 du volume.
3. **Autorité** : le nouveau magasin immédiatement ; l'ancien gelé en lecture — puis, le même jour,
   **supprimé** sans passer par le gel, la vérification étant passée (décision utilisateur).
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
- Le `.code-workspace` de `viewpoint-separation` **n'a rien eu à changer pour migrer** : ses chemins
  relatifs (`../seed-incluster`, `../../ndh.d/develop`) survivent, et ses libellés disaient déjà
  `seedmatic/rke2lab.git/…` — ils décrivaient le layout cible avant qu'il existe. Un 7ᵉ dossier y a
  été ajouté depuis, à la demande de l'utilisateur : `../memory`, pour suivre la mémoire dans
  l'explorateur VSCode. Le fichier vit **hors** de tout worktree, donc il n'est pas versionné.

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
  migrer), et `flox-nri-plugin` aussi — ses 2 fichiers, rescapés de l'ancien magasin, attendent la
  branche orpheline. Voir [[hub:worktree-per-conversation]].
- **La pile de PR.** L'extension `github/gh-stack` est installée, mais avec `main == develop` une
  seule PR est ouvrable (`viewpoint-separation → develop`, 160 commits) : ce n'est pas une pile. Une
  vraie pile demande de **découper ces 160 commits** — incrément de conception à part entière.

## Où a atterri ce que l'ancien magasin portait seul

`rke2lab.d/.etage0-archive/` (16 Mo, 227 fichiers, **hors de tout worktree git** exprès, pour qu'il
ne soit jamais commité par accident) : les planches, checkpoints, plans et transcripts des 5
worktrees que le nouveau magasin ne matérialise plus, la mémoire de `flox-nri-plugin`,
`opencode.json`, et les `settings.local.json` des 3 dépôts dont l'`autoMemoryDirectory` est cassé —
gardés comme **référence** et non réinstallés, précisément parce qu'ils portent le bug. Son
`README.md` liste la vérification. Les transcripts y sont **archivés, pas reprenables** : le slug
est le chemin absolu, donc `--resume` ne les verra pas sans le worktree d'origine.

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
