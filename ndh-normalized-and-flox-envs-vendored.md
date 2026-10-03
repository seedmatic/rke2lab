---
name: ndh-normalized-and-flox-envs-vendored
description: "ndh normalisé le 2026-10-03 (PR #8) : `main` forcé sur develop après 20 mois de gel, pile montée, origine élaguée de 8 branches à 2 avec archivage, envs flox vendorés, et la révision ndh enfin lisible depuis un hôte."
metadata:
  node_type: memory
  type: project
  originSessionId: eef830de-9690-4fc8-ac3d-71fec81b1988
  modified: 2026-10-03T07:13:00.984Z
---

Livré le **2026-10-03** — ndh PR #8, branche `feature/flox-envs-vendoring`, sha gelé
`59b03c7c`. L'incrément décrit par `.claude/ndh-flox-envs-handoff.md` (scratch, dans le
siège rke2lab), dont le périmètre a été **élargi par l'utilisateur** : « ce qu'on veut
c'est normaliser le dépôt », pas seulement vendoriser.

## Volet 1 — la normalisation

`origin/main` était figé au **2025-01-23** pendant que `develop` courait jusqu'au
2026-10-01 : divergence **94 / 1691**, chacun avec des commits propres. Ce n'était pas
un git-flow, c'était deux lignes parallèles, et `main` ne décrivait plus rien
d'exploitable. **Forcé sur la HEAD de `develop`** (référence tranchée par l'utilisateur),
**sans ref de sauvegarde** — son choix explicite, les 94 commits de `main` étaient du
2023-2025 (travail initial + bumps dependabot).

⚠️ **Enchaînement contraint, et l'ordre compte** : pousser `develop` d'abord
(`7cd26b7d` y était non poussé), *puis* forcer `main` — sinon `main` pointerait un commit
absent d'origin.

Pile créée (ndh n'en avait aucune) : **`main -> develop -> feature/…`**, le modèle
rke2lab, via `gh stack init --base main develop` puis `gh stack add`.

### ★ La convention qui vaut d'être réutilisée : archiver, pas supprimer

Origine élaguée de **8 branches à 2**. L'utilisateur a dit « tue toutes les branches,
elles sont obsolètes en dehors de develop », mais les mesures ne disaient pas la même
chose pour toutes :

- **3 contenaient des commits propres** → poussées sous **`refs/archive/<nom>`** avant
  suppression (`bioskop`, `feature/netflow-monitoring`, `feature/network-catalog-segments`
  — cette dernière avec **45 commits** de vrai travail dont l'utilisateur ne se rappelait
  plus le contenu ; il a lui-même tranché « on l'archive ? »). Une ref hors `refs/heads`
  n'apparaît plus comme branche mais retient les objets.
- **3 étaient prouvées contenues** dans `develop` (`alcide`, les deux `split/*/hm-ssh.d`)
  → supprimées sèchement, rien à perdre.

⚠️ **Piège immédiat** : une fois `refs/archive/bioskop` créée, `git push origin --delete
bioskop` échoue sur « dst refspec matches more than one ». Il faut la forme pleinement
qualifiée `:refs/heads/bioskop`.

## Volet 2 — les envs flox

Les 4 includes du siège pointaient `/var/lib/git/seedmatic/fleet/flox/{lima,nix,shell,tart}`,
**tués par la migration étage-0** et inexistants sur tout hôte. Arbre de fleet vendoré à
`.flox-envs.d` (split `4f5dff7e`), includes réécrits en `./.flox-envs.d/<env>`.
Voir [[flox-envs-vendored-as-subtree]] pour la forme et la direction de la vérité.

★ **TROIS includes, pas quatre** : `lima` est **supprimé**, pas repointé — l'utilisateur
(« lima on utilise plus dans ndh ») et le code disent la même chose
(`vmConfigurations` : « Lima variant was retired — only Tart remains » ;
`modules/darwin/lima-config.nix` disparu). Le brief en comptait 4 : il avait compté les
`{ dir = }` **sans demander si chacun servait encore**. Compter n'est pas qualifier.

## ★★ La révision ndh est enfin lisible depuis un hôte

Un hôte n'exposait que les révisions de **nixpkgs** (`nixosVersion`) et de
**nix-darwin** (`darwinRevision`) — jamais celle de ndh. « Qu'est-ce qui tourne ? » se
reconstituait donc par hypothèse, et **l'hypothèse a été fausse au moins une fois** :
bioskop a switché *après* nikopol et pourtant avec l'ANCIENNE configuration, ce qu'aucune
lecture des hôtes ne pouvait montrer. `system.configurationRevision = self.rev or
self.dirtyRev or null` désormais, donc `nixos-version --json` / `darwin-version --json`
le **disent**.

★★★ **Il a fallu DEUX sites, pas un — et c'est la leçon transférable.** Posé dans
`mkBaseModulesFor` (partagé nixos+darwin), il manquait encore à
`minimalBringupSystemBase`, qui construit son **propre** `nixosSystem` avec une liste de
modules explicite qui contourne la partagée. Sans ce second site, **3 des 12**
configurations (`*-bringup` + l'alias `nerd-nixos`) renvoyaient `null` en silence pendant
que les runtime allaient bien. ⚠️ Discriminateur : l'erreur n'était pas « option
inconnue » mais `cannot coerce null to a string` — l'option existait, à sa valeur par
défaut. **« J'ai ajouté l'option » n'est pas la même mesure que « chaque configuration la
rapporte »** : boucler sur les 12 est la seule preuve. L'invariant du bringup
(bit-à-bit identique entre hôtes) survit — une révision du dépôt est une seule valeur
pour tous les hôtes.

## Ce qui reste, et à qui

- ⚠️ **Rebuild de `nixos.bioskop` — geste OPÉRATEUR, pas fait.** Débloqué par le push de
  `7cd26b7d` (« the git store is TWO volumes »), dont l'absence d'origin explique
  précisément pourquoi bioskop avait switché avec l'ancienne config. Tant qu'il n'est pas
  fait, bioskop ne monte que l'ancien chemin unique.
- Deux incréments **décidés, pas commencés** :
  [[flox-env-lock-logic-belongs-to-fleet]] et
  [[nerd-nixos-image-build-leaves-activation]].
- `README-bootstrap.md` a été corrigé de ce qui était **vérifiable contre le flake**
  (`<host>-nixos-bringup-install` → `<host>-bringup-install` ; `nixosDiskImages.<host>.full`
  n'a pas d'attribut `full`, l'image est `boot.img` ; la procédure Lima). La section
  `run.sh vm:reset` est **supprimée** — aucun `run.sh` à la racine, et `vm:reset` /
  `phase:bootstrap:all` / `vm:nixos:boot:*` n'existaient que dans ce fichier. Le flux
  étagé n'est **pas** réécrit : le reconstituer serait inventer une procédure opérateur,
  et ce qui remplacera `vm:reset` est la même frontière que
  [[nerd-nixos-image-build-leaves-activation]].
