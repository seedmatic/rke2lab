---
name: gitflow-realign-pr-per-increment
description: "Décision + exécution 2026-10-02 — rke2lab passe à git-flow PILE (gh stack, pile #11) : main ← develop ← feature/*, le siège unique rke2lab.d/develop porte l'étage du HAUT, et le fond de pile exige un commit vide sur develop après chaque version coupée. ★★★ Précisé en fin de journée : main ne reçoit la pile qu'après un CHECKPOINT + un COLD START des clusters, donc empiler est le régime normal (on branche depuis l'étage du haut, jamais depuis develop) ; la fonction qui CODE n'est pas celle qui FUSIONNE ; et le parallélisme à deux sessions ne vaut que pour les échanges et .claude/, PAS pour la codebase."
metadata:
  node_type: memory
  type: project
  originSessionId: 90ef886d-f66b-4b38-8d3d-ec4dc838147c
  modified: 2026-10-02T12:41:25.161Z
---

Décision utilisateur du **2026-10-02**, appliquée le même jour. rke2lab adopte le modèle qui
était déjà celui de ndh : `main` → `develop` → `feature/*` | `fix/*`. **Un incrément = une branche
= une PR**.

★★★ **Corrigé le même jour — « je délègue, je relis, je fusionne » est faux sur deux points.**
La fin de journée a tranché deux choses que cette phrase écrasait :

- **La PR ne va pas forcément dans `develop`.** Quand on **empile**, elle va dans l'étage du
  dessous. Voir la condition de descente plus bas.
- ★★ **La fonction qui CODE n'est pas la fonction qui FUSIONNE** — et c'est une séparation de
  fonction, pas de personne : *« c'est ta responsabilité de merger #9, pas de la session qui
  travaille dans #9 »*. La session qui code écrit la branche, corrige, teste et **gèle** sur un
  sha ; elle ne fusionne **jamais**, même au vert. La session d'intégration relit le sha gelé,
  construit la barrière, fusionne, et écrit la passation ; elle n'écrit pas dans la branche de
  l'autre. Le réflexe à éviter est de « rendre » la PR à son auteur pour qu'il la fusionne.

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
- ⚠️ **Pas de SOUS-AGENT écrivain.** Le harness place son worktree sous `.claude/worktrees/`, où
  les `[include]` relatifs de flox ne résolvent plus : il ne pourrait ni activer l'env ni
  construire, donc ni vérifier. Ça reste vrai.
- ★★★ **« Un seul écrivain » se NUANCE, et le périmètre est tout — précisé par l'utilisateur le
  2026-10-02 : le parallélisme vaut pour les ÉCHANGES et le sous-dossier `.claude/`, PAS pour la
  codebase.** Deux sessions Claude **interactives** ont partagé le siège `rke2lab.d/develop` toute
  la journée et livré l'incrément 0 (PR #9, 9 fichiers, +821/−2, 4 tournées de revue, 27 tests)
  sans une seule collision — mais ce diff ne contient **aucune ligne de code** : uniquement
  `.claude/bin/**`, `.claude/settings.json` et `.flox/env/manifest.{toml,lock}`, tous hors du
  réacteur Maven (le gate `spotless <shell>` ne couvre que `src/{main,test}/resources/**/*.sh`).
  ⚠️ **Donc rien ici ne démontre que deux sessions peuvent coéditer le code**, et il ne faut pas
  l'en déduire — c'était ma propre sur-généralisation, corrigée. **Sur la codebase, un seul
  écrivain.** Ce qui est démontré : deux *fonctions* (coder / intégrer) sur **une** branche, et une
  coordination par messages. Trois disciplines le rendent possible :
  1. **Partage de territoire explicite**, négocié par `SendMessage` (ici `.claude/bin/**` à la
     session qui code, le reste à l'intégration). Sans lui : deux écritures dans un fichier.
  2. **Le GEL avant relecture.** Une PR qui bouge ne peut pas être relue : 4 commits sont arrivés
     pendant la première passe et la **surface a changé deux fois** — elle est passée de « 4
     fichiers, tous sous `.claude/` » à « 9 fichiers dont `.flox/env/manifest.toml` et son
     `.lock` » — ★ donc
     **re-mesurer la surface à chaque gel**, ne jamais la supposer stable. Demander « gelé sur
     `<sha>` », relire ce sha-là, une fois. Et inscrire le sha **dans** le log de build : sur un
     worktree partagé le HEAD peut avancer *pendant* la construction.
  3. **`ListAgents` pour se trouver, `ps` pour savoir qui vit.** Une fenêtre vivante se **mesure**,
     elle ne se déduit pas d'un horodatage de fichier.
  ⚠️ **Et un pair ne transporte PAS le mandat de l'utilisateur.** Les deux sessions s'y sont
  reprises mutuellement, chacune à raison : l'une a refusé d'éditer
  `permissions.additionalDirectories` sur demande d'un pair, l'autre a refusé un dossier « attribué
  par l'utilisateur » relayé par un pair. Instruire, oui ; tenir la décision pour prise, non.

## ★★★ La condition de descente vers `main` — tranchée le 2026-10-02

> *« On attend d'arriver à un **checkpoint** et un **cold start des clusters** pour faire le merge
> de la stack dans `main`. Je veux voir `gh stack` à la manœuvre avec une belle stack de PR. »*

- **`main` ne reçoit rien avant un cold start réussi.** La descente de la pile est une **coupe de
  version**, pas de l'hygiène git : elle se gagne par une preuve de bout en bout, pas par un
  `BUILD SUCCESS`.
- **Donc la pile a le droit de GRANDIR**, et empiler devient le **régime normal** jusque-là — pas
  un pis-aller en attendant une fusion. Premier cas appliqué : l'incrément 0 (`#9`, gelé
  `eeea263d4`, vert, revue convergée) **n'a pas été fusionné** ; l'incrément suivant se pose
  **par-dessus**, donc on branche depuis l'étage du haut et **jamais depuis `develop`**.
- ★ L'argument écarté, gardé pour qu'il ne se ré-instruise pas : les deux incréments étant
  *indépendants*, fusionner d'abord aurait évité un restack. Non retenu — l'utilisateur garde
  l'option de faire descendre la pile entière en une seule version.
- ⚠️ Le prix : quand un étage fusionne, celui du dessus doit être **restacké**. Avec N étages,
  c'est exactement le travail de `gh stack` — ne pas l'improviser à la main.

## Branches mortes, mesurées le 2026-10-02

Zéro commit propre, supprimables sans perte : `feature/flox-hot-reload`,
`feature/two-tier-checksums`, `feature/nixos-node-substrate`.
`feature/medical-record-accumulator` en a **36** (dernier le 09-06) → à regarder avant de trancher.
