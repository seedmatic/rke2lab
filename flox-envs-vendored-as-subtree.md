---
name: flox-envs-vendored-as-subtree
description: "Les envs flox du POSTE DE TRAVAIL sont vendorés dans rke2lab en subtree `.flox-envs.d` (livré 2026-10-02) ; le lock ne voyage pas, d'où une étape de bootstrap, et deux croyances du dossier de passation sont fausses."
metadata:
  node_type: memory
  type: project
  originSessionId: 369bb0b5-74a0-4db0-a22b-31ad4081c3be
  modified: 2026-10-03T07:14:00.237Z
---

Livré le **2026-10-02** sur `feature/flox-subtree` (étage de la pile #11, poussé) —
l'incrément décrit par `.claude/flox-subtree-handoff.md`, dont **deux affirmations
étaient fausses** (voir plus bas).

## Ce qui est en place

Les envs flox du **poste de travail** (`jdk`, `pulumi`, `shell`, `asciidoc`, `git`,
`k8s`, `keyhole`) ne sont plus atteints par un symlink dans le dossier **parent** du
worktree : ils sont **vendorés** dans rke2lab, subtree de `fleet:flox-subtree`, au
préfixe **`.flox-envs.d`**, et les 7 includes sont `./.flox-envs.d/<env>`.
Les symlinks `rke2lab.d/.flox.d` et `rke2lab.d/feature/.flox.d` sont **supprimés**
(`feature/` était un vestige vide, supprimé aussi).

★ **Ne pas confondre avec le catalogue d'envs RUNTIME** — la branche `flox-catalog`
+ le CRD `FloxCatalog`, livrés aux nœuds du cluster. Deux choses différentes ; c'est
pour ça que le préfixe n'est **pas** `.flox-catalog.d`. Le nom retenu est celui que
l'arbre se donne (`flox-envs.yaml`, entrées `kind: FloxEnvironmentDescriptor`).

## Le vrai blocage : le LOCK ne voyage pas

`fleet` ignore `*/.flox/env/manifest.lock`, et un env **sans lock n'est pas
incluable** : le siège refuse d'activer (`cannot include environment since its
manifest and lockfile are out of sync`). Décision utilisateur : **aucun lock en git**,
ni source ni livraison → `nix run .#lock-flox-envs` (app ajoutée) **parcourt le graphe
d'includes** et verrouille à la première activation. C'est désormais une étape
**obligatoire** du `Quick Start` de `CLAUDE.md`.
⚠️ Option écartée : committer les locks dans `fleet`. L'argument factuel était pourtant
bon (la règle d'exclusion vient de `1c6d03e` « activated flox env », un effet de bord,
et les deux consommateurs suivent déjà leur propre lock de siège) — mais `fleet` est le
**producteur**, et y mettre le lock recolle deux rôles que
[[references-resolve-at-build-not-runtime]] veut séparer.

## Les deux croyances fausses du dossier de passation

1. ⛔ **`common` n'est PAS un include fantôme.** Le dossier affirme que 8 envs le
   déclarent et que seul le lock masque la casse. **Faux** : dans `jdk`/`pulumi`/`shell`
   c'est une ligne **commentée** (`#     { dir = "../common" }`, boilerplate du gabarit
   flox) et dans `asciidoc` le mot est la clé **`[profile] common`** — un script, pas un
   include. Rien ne le déclare. Si l'assertion en échec dur de l'incrément SSOT était
   justifiée par lui, cette justification est à réécrire.
2. ✅ **Le `subtree split` passe par-dessus l'historique en `--squash`.** Le dossier le
   donnait comme risque non éprouvé. Mesuré : il réussit, et l'arbre produit est
   **bit-à-bit identique** à `main:flox` (`ac28805`). Les histoires étaient en revanche
   **disjointes** → force-push total, une seule fois.

## La direction de la vérité, et le target qui manquait

`fleet:main/flox` est la copie **vivante** ; `origin/flox-subtree` est un **split
dérivé**. Le `Makefile` de `fleet` n'avait **qu'un `pull`** → les éditions sont allées
dans la copie, la branche a gelé (2025-12-14 contre un arbre du 2026-09-29), et
`make flox-update` était devenu **destructeur** (il aurait repris la source périmée
par-dessus la vivante, perdant 8 envs dont 3 inclus par le siège).
Corrigé : targets `subtree-publish` / `flox-publish` / `rke2-publish` (fleet
`d3113a0`, poussé). La procédure complète vit dans la **skill
`flox-envs-subtree-sync`** — un subtree sans procédure nommée gèle, les deux de `fleet`
ont gelé la même semaine, le seul vivant du périmètre est celui qui a une skill.

## ★★ `git subtree add --squash` + `git rebase` = arbre corrompu

Mesuré le 2026-10-02, après que l'utilisateur ait tenté un rebase de la pile :
`C .gitignore`, HEAD détaché, et **tout l'arbre d'envs de fleet apparu à la RACINE**
du dépôt. Ce n'est **pas** un conflit de contenu :

- le commit `Squashed '.flox-envs.d/' content from …` porte le contenu **à la racine**,
  `.gitignore` de fleet compris ;
- il n'atterrit sous le préfixe que grâce au **commit de merge** qui suit ;
- `git rebase` **supprime les merges** et linéarise → il rejoue le contenu racine, où le
  `.gitignore` de fleet percute celui de rke2lab.

Remèdes : `git rebase --rebase-merges` (préserve la greffe), **ou** aplatir l'import en
**un commit ordinaire** en conservant les trailers `git-subtree-dir:` /
`git-subtree-split:` (c'est eux que `git subtree pull --squash` lit pour retrouver le
dernier point de synchro). ★ Vaut pour **tout** subtree du dépôt, `.claude/hub` inclus.

**Retenu : l'aplatissement** (décision utilisateur) — un étage qui ne survit qu'au
drapeau `-r` est un piège pour qui l'oublie. Recette : `git commit-tree <tree-du-merge>
-p <base>` pour fabriquer le commit à un seul parent, puis `git rebase --onto <neuf>
<ancien-merge> <branche>`. Vérifié après coup, et c'est la vérification qui compte :
l'arbre final **bit-à-bit identique** à l'ancien sommet, et `subtree pull` répond
« already at commit … ». rke2lab `748daa7d2`, étage force-poussé.

## Gotchas mesurés

- ★ `flox activate -- true` **ne prouve rien seul** : ndh y réussit aujourd'hui avec
  **4 chemins d'include absolus morts**, que son lock masque intégralement. La vraie
  barrière est `flox include upgrade` → doit répondre « No included environments have
  changes ». Le gel est une propriété **des includes**, pas des chemins.
- **6 envs n'ont aucun lock** (`darwin`, `dns-tools`, `docker`, `editor`, `gnumake`,
  `spacelift`) — personne ne les a activés ; aucun siège ne les inclut.
- **Ne jamais checkouter `flox-subtree` dans `fleet.d/main`** : elle porte les envs à la
  **racine**, sans répertoire `flox/` → le checkout supprimerait `fleet/flox` et
  casserait tous les consommateurs. Worktree dédié obligatoire.
- `gh stack push` **échoue en non-interactif** quand le dépôt a plusieurs remotes sans
  `remote.pushDefault` (rke2lab en a 4 : `origin`, `claude-hub`, `nix-darwin-home`,
  `fleet`). Contournement : `git push -u origin <branche>`.
- Le shell de session est **zsh** : une variable non quotée **ne se découpe pas en
  mots**. Une boucle `for f in $files` tourne **une seule fois** avec la liste entière
  en guise de nom de fichier — et le `sed` qui suit paraît « avoir marché ». Utiliser
  `while IFS= read -r`.

## ★★ Un formateur Nix RÉÉCRIT l'arbre vendoré (mesuré dans ndh, 2026-10-03)

Le `nix fmt` de pre-commit de ndh a réécrit
`.flox-envs.d/k8s/.flox/flakes/podman-wrapper/flake.nix` **dès la première mise en
index**, et a bloqué le commit. Formater une copie **dérivée** perd le changement au
prochain `subtree pull`, et d'ici là se lit comme une **dérive contre fleet**.
✅ Parade : `settings.global.excludes = [ ".flox-envs.d/**" ]` dans `treefmt.nix`.
★ **rke2lab n'a PAS de `treefmt.nix`** — il n'a donc pas pu rencontrer le défaut ; **tout
autre dépôt qui vendore l'arbre sous un formateur Nix le rencontrera**. L'arbre contient
du `.nix` (dont un flake imbriqué) et du `.sh`, donc nixfmt *et* shellcheck y mordent.

## ✅ La forme « un commit ordinaire » est PROUVÉE sûre au rebase

Mesuré dans ndh le 2026-10-03, et c'est la bonne forme de preuve — une absence de conflit
**mesurée**, pas déduite de « la forme est différente ». Rebase de la branche sur un
commit sonde qui **modifie le `.gitignore` racine** (la surface de collision exacte) :
l'arbre résultant ne différait de l'arbre d'avant rebase que des **2 lignes de la
sonde** — aucun conflit, aucun répertoire d'env à la racine, aucun motif de fleet dans le
`.gitignore` racine. C'est le **commit unique** qui rend l'opération sûre : il n'y a aucun
merge greffant qu'un rebase pourrait laisser tomber.

## Reste à faire

- ✅ **ndh : FAIT le 2026-10-03** (PR #8, sha `59b03c7c`) — et **3** includes, pas 4 :
  `lima` est supprimé, pas repointé. Voir [[ndh-normalized-and-flox-envs-vendored]].
- ⚠️ **La logique de lock est maintenant en DEUX copies** (rke2lab + ndh), et celle de
  rke2lab est déjà partiellement cassée (elle ne lit qu'un style de quote). Destination
  tranchée : un flake **du subtree** possédé par fleet →
  [[flox-env-lock-logic-belongs-to-fleet]].
- 8 chemins `/private/var/lib/git` morts dans 5 fichiers suivis de rke2lab.
