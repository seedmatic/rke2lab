---
name: orphaned-worktree-recovery
description: "Supprimer le clone principal orpheline ses worktrees (les objets git vivent dans le repo principal) — mais les FICHIERS survivent, et si l'arbre était propre ils SONT le contenu du commit. Recette de récupération vérifiée le 2026-09-23"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-23T16:54:27.705Z
---

Vécu le 2026-09-23 sur ndh : le clone principal `/private/var/lib/git/seedmatic/ndh` a été
supprimé par erreur, orphelinant le worktree `ndh.d/feature/bioskop-fabric-address` et son commit
non poussé.

## Ce qui meurt et ce qui survit

Le `.git` d'un worktree est un **fichier** (`gitdir: <main>/.git/worktrees/<nom>`). Tous les
objets vivent dans le repo principal → **l'historique du worktree est irrécupérable** si on le
supprime.

★ Mais les **fichiers de travail sont intacts**. Et si l'arbre était **propre** à `HEAD`, alors
ces fichiers **sont exactement** le contenu du commit perdu. Le commit est donc reconstituable ;
seuls son SHA et sa date changent.

## La recette

1. Trouver le **parent** du commit perdu dans un dépôt qui l'a encore (ici : `develop` du nouveau
   clone valait précisément ce parent, parce qu'il avait été poussé).
2. Mettre l'orphelin de côté (`mv …/x …/x.orphan`) — ne PAS le supprimer avant vérification.
3. Créer un worktree neuf sur ce parent.
4. `rsync -a --delete --exclude '.git' orphan/ neuf/`
5. **Vérifier** : `git diff --stat` doit rendre exactement le nombre de fichiers du commit perdu
   (on le connaît si on l'a vu passer). `--delete` sert de contrôle : rien ne doit disparaître.
6. Recommiter avec le même message.

⚠️ Deux pièges rencontrés :
- un hook `nix fmt --fail-on-change` a reformaté 2 fichiers et **bloqué** le commit — donc
  `git add -A` puis recommit ;
- `git commit -C ORIG_HEAD` réutilise le message d'`ORIG_HEAD`, **pas** celui du commit bloqué —
  il a produit un commit au mauvais message. Écrire le message dans un fichier et `-F`.

## La preuve que la récupération est exacte

Le meilleur contrôle est **adressé par contenu** : reconstruire un artefact nix et comparer son
chemin de store. Ici `bioskop-baremetal-link-install` est ressorti sur
`/nix/store/xswna9ld…-install.sh`, **identique** à avant la perte → récupération bit-à-bit, sans
avoir à relire un diff.

## La cause, et le layout qui l'évite

L'utilisateur travaille depuis **deux hôtes RDP dont les volumes macOS n'ont pas le même layout**,
donc les chemins absolus sous `/private/var/lib/git` ne se correspondent pas d'un hôte à l'autre —
c'est comme ça qu'il s'est perdu. Hazard récurrent, pas un accident isolé.

★ **ndh est désormais un dépôt BARE** : `/private/var/lib/git/seedmatic/ndh.git`, avec les
worktrees sous `ndh.d/<namespace>/<branche>`. C'est structurellement meilleur pour ce dépôt-ci :
il n'y a plus de checkout principal à supprimer, donc plus de worktree à orpheliner. Les autres
dépôts gardent le layout `<repo>.d/main` + worktrees frères.

See [[hub:worktree-layout-external]].
