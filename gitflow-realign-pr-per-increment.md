---
name: gitflow-realign-pr-per-increment
description: "Décision + exécution 2026-10-02 — rke2lab passe à git-flow (main → develop → feature/fix), un incrément = une branche = une PR dans develop ; le siège unique est rke2lab.d/develop et main n'a aucun worktree. ★ Corrigé le même jour : c'est le modèle PILE (gh stack, pile #11), le siège porte l'étage du haut, et le fond de pile exige un commit vide sur develop après chaque version coupée."
metadata:
  node_type: memory
  type: project
  originSessionId: 90ef886d-f66b-4b38-8d3d-ec4dc838147c
  modified: 2026-10-02T10:53:38.542Z
---

Décision utilisateur du **2026-10-02**, appliquée le même jour. rke2lab adopte le modèle qui
était déjà celui de ndh : `main` → `develop` → `feature/*` | `fix/*`. **Un incrément = une branche
= une PR** dans `develop` ; je délègue l'incrément, je relis au retour, je fusionne.

## L'état qu'on a trouvé, et qui justifiait le réalignement

- `develop` **existait et était strictement égal à `main`** (0/0, tous deux `73ac3b584`) — une
  branche qui existait sans servir.
- `main` ne portait que les correctifs **précurseurs** du grow (`70489ed9c`, `f83143299`,
  `1cd8837f1`, tous ≤ 09-27). Le travail qui fait réellement grandir un cluster — le train du
  09-28 au 10-01 (`45428c850`, `8526412a1`, `b11805cf3`, `9ccd89923`, `ca5e8c067`, `fb2a51de4`…)
  — vivait hors tronc, dans `feature/viewpoint-separation` (+161 / −1).
- **Aucune PR humaine n'avait jamais été fusionnée** dans ce dépôt (les 3 ouvertes étaient
  dependabot). Le passage aux PR est donc un changement de *pratique*, pas d'outillage.

## Ce qui a été fait

PR **#7** `feature/viewpoint-separation` → `develop` (merge-commit `62e3ffe7e`), puis PR **#8**
`develop` → `main` (`42b626bba`) = *le point de version « les clusters grandissent »*, puis
back-merge en fast-forward → `main` == `develop` == `42b626bba`.

Le siège a déménagé : `rke2lab.d/feature/viewpoint-separation` → **`rke2lab.d/develop`**
(profondeur 1, comme `ndh.d/develop` et les voisins). Voir [[seat-move-depth1-what-survives]].

## Les règles qui vont avec

- **Jamais de squash** — les messages de commit de ce dépôt sont des phrases travaillées, et un
  squash les détruit *et* force un restack. Merge-commit.
- **Il n'y a AUCUNE CI** : `.github/workflows/` n'existe pas et `main` n'est pas protégée (les
  trois méthodes de fusion sont ouvertes). Donc la revue **doit** inclure une vraie construction —
  et avec l'invocation canonique, cf. [[rke2lab-canonical-maven-invocation]], que j'ai justement
  manquée pour la PR #8 (profil absent → tests `live|spike` exclus et `target/` pollué).
- ★★★ **`gh stack` EST le modèle — corrigé le 2026-10-02, la ligne précédente était fausse.**
  Elle disait « en séquentiel strict elle ne sert à rien : PR plate par incrément » ; décision
  utilisateur : on travaille en **pile**. Exécuté le même jour : `gh stack init --base main
  develop feature/*` puis `gh stack submit --auto` → **pile #11** sur GitHub,
  `main ← develop ← feature/*`. Deux conséquences qui changent les gestes :
  **(1)** le siège se place sur l'**étage du haut**, pas sur `develop` — c'est le seul checkout
  qui a flox et sops, donc le seul qui puisse *construire* l'incrément ; un worktree ad hoc monté
  pour porter un commit ne peut rien vérifier. Avant de muter : lire
  `git rev-parse --abbrev-ref HEAD`.
  **(2) ★ Le fond de pile disparaît à chaque version coupée.** Juste après une fusion
  `develop → main`, GitHub répond `main...develop : identical, 0 commits` et **refuse** la PR de
  release. Remède : un **commit vide** sur `develop` (`git commit --allow-empty`, cf. `70698cefd`
  et la PR #10), dont le seul rôle est de rendre la PR de release constructible et de la tenir
  ouverte comme canal d'accumulation. Puis **rebaser l'étage du haut sur `develop`**, sinon
  l'incrément ne descend pas de son étage inférieur — vérifier par
  `git merge-base --is-ancestor develop HEAD`, ne pas le supposer.
- **`git-town` 24.0.0 est dans l'env flox et n'est pas configuré** (`main branch: (not set)`) —
  dette à retirer de l'env.
- ⚠️ **Un seul écrivain à la fois.** Je ne peux pas donner son propre worktree à un sous-agent :
  le harness le place sous `.claude/worktrees/`, où les `[include]` relatifs de flox ne résolvent
  plus, donc il ne pourrait ni activer l'env ni construire. Donc **pas d'incréments en
  parallèle** — le parallélisme exigera des worktrees **externes**, pas un worktree partagé.

## Branches mortes, mesurées le 2026-10-02

Zéro commit propre, supprimables sans perte : `feature/flox-hot-reload`,
`feature/two-tier-checksums`, `feature/nixos-node-substrate`.
`feature/medical-record-accumulator` en a **36** (dernier le 09-06) → à regarder avant de trancher.
