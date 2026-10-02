---
name: seat-move-depth1-what-survives
description: "Mesuré 2026-10-02 — déplacer un worktree de siège (rke2lab.d/feature/<x> → rke2lab.d/develop) : ce qui survit seul (flox, .flox/run, .git des worktrees imbriqués) et le SEUL truc à réparer (les gitdir du dépôt bare)"
metadata:
  node_type: memory
  type: reference
  originSessionId: 90ef886d-f66b-4b38-8d3d-ec4dc838147c
  modified: 2026-10-02T09:44:06.692Z
---

Recette mesurée en déplaçant `rke2lab.d/feature/viewpoint-separation` → `rke2lab.d/develop`
(profondeur 2 → 1). **`mv` + `git worktree repair`**, pas un worktree neuf — voir le pourquoi en
bas.

## Ce qui survit tout seul

- **Le manifeste flox n'a besoin d'AUCUNE retouche.** Les 7 `[include] { dir = "../.flox.d/…" }`
  résolvent aux **deux profondeurs**, parce que `.flox.d` est un **symlink vers
  `fleet.d/main/flox` posé à `rke2lab.d/.flox.d` ET `rke2lab.d/feature/.flox.d`** (les deux créés
  par la migration de l'étage 0, cf. [[etage0-bare-worktree-migration]]). Vérifié après coup :
  `flox activate` fonctionne depuis le nouveau chemin.
- `.flox/run/*` sont des **symlinks vers `/nix/store`** → indépendants du chemin.
- Les fichiers `.git` des **worktrees imbriqués** (`.local.d/render/*`) pointent *vers le dépôt
  bare*, qui ne bouge pas → intacts après le `mv`.
- `.claude/settings.local.json` porte `autoMemoryDirectory` en **absolu** vers `rke2lab.d/memory`,
  hors du siège → non affecté.
- Aucun chemin absolu en dur dans `.flox`, `.claude/settings*.json`, `.vscode`, `.mvn` (grepé).

## Le seul à réparer : l'enregistrement INVERSE

Le dépôt bare garde les anciens chemins dans `worktrees/<nom>/gitdir`. Donc :

```bash
git -C <bare> worktree repair <nouveau-siege> <chaque-worktree-imbriqué>
```

Ici 5 entrées — le siège plus les 4 rendus. `repair` affiche `gitdir incorrect: …` par entrée
corrigée.

## Deux conséquences de layout

- ★ **Les 4 worktrees de rendu sont sous `.local.d/`, qui est IGNORÉ** (`.gitignore:25`). Donc ils
  **survivent aux changements de branche** du siège. Ça dissout la crainte « les cibles de rendu
  suivent leur worktree hôte » : elles cessent d'être attachées à un nom de branche.
- Le `.code-workspace` est posé **un niveau au-dessus** du siège, donc ses chemins relatifs
  perdent un `../` en passant de la profondeur 2 à 1. **Seule réécriture nécessaire** (7 dossiers).

## Pourquoi déplacer et non recréer

`.claude/` contient les **planches et handoffs ignorés par git** — ils portent toute la conception
et un worktree neuf les perdrait.

⚠️ **Le harness recrée l'état de session à l'ancien chemin** (≈52 K : `.claude/projects/<chemin
encodé>` + `session-env`) parce que le chemin projet de la session courante y est épinglé. Ne pas
le supprimer pendant la session. La **transcription suit le `mv`** (les FD ouverts suivent
l'inode) mais garde l'**ancien nom encodé** — pour la rendre résumable depuis le nouveau siège,
renommer le répertoire `.claude/projects/-Volumes-…-<ancien>` en `-Volumes-…-<nouveau>` **après**
la fermeture de la session.

See [[gitflow-realign-pr-per-increment]].
