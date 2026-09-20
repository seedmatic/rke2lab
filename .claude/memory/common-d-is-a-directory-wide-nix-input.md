---
name: common-d-is-a-directory-wide-nix-input
description: "Règle (inférée, 2026-09-20) — ndh lit ses scripts partagés via worktreePath.of \"modules/.common.d\", un chemin de store de RÉPERTOIRE : ajouter n'importe quel fichier là-dedans change ce hash et reconstruit tout ce qui lit un script par ce chemin, sur les deux plateformes"
metadata:
  node_type: memory
  type: reference
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-20T18:48:43.753Z
---

Les modules ndh font `ndhCommon = worktreePath.of "modules/.common.d"` puis lisent leurs scripts par
`"${ndhCommon}/ssh/x.sh"`. `ndhCommon` est donc un chemin de store de **répertoire entier** : y
ajouter un fichier, même jamais lu, change son hash — et avec lui tout ce qui lit un script à travers
lui.

**Mesuré** (ndh `ed4e4af5`, extraction des authz-tools ssh en fonction partagée) : le commit était
inerte en comportement — vérifié par diff de dérivations, **aucune** unité openssh ne bouge — et
pourtant `nixosConfigurations.nikopol-nixos` passe de `rz4qd624…` à `2lgwd744…` et
`darwinConfigurations.nikopol` de `7sjfz9bm…` à `3mfwq5cn…`. Le seul delta : le chemin du script
`ExecStart` de `ssh-keys-enrichment.service`, **un consommateur que le commit ne touche pas** mais
qui lit d'autres scripts par le même `${ndhCommon}`.

**★ PROUVÉ le même soir, par l'usage.** L'utilisateur rebuild la config darwin `nikopol` depuis
bioskop et voit `nix` **recopier `erofs-001` depuis le builder**. Vérifié : le bundle à `develop`
(`ed4e4af5`) est `agzgy7rj…` contre `8k9329c1…` construit et copié deux heures plus tôt à
`8aa201c3`. Donc un commit qui ne change **aucun** comportement invalide les **trois** images de
couche et tout le bundle — la chaîne `fichier ajouté sous .common.d → hash du répertoire → closures
système → images de couche` est complète et mesurée de bout en bout.

**How to apply :** un ajout sous `modules/.common.d` n'est jamais gratuit — il implique une
reconstruction de flotte (et, depuis la pile EROFS, un renew avec factory reset, puisque les trois
couches changent d'octets). Le grouper avec un changement qui paie déjà ce coût.

**Nuance mesurée, qui adoucit la règle** : L1 et L2 sont **fleet-shared au niveau dérivation** (voir
[[erofs-layer-images-input-addressed-rebuild]]), donc le coût est payé **une fois** — ~10 GiB pour
le premier hôte reconstruit — et chaque hôte suivant ne paie que son L3 (~39 Mo). Ce n'est pas
~10 GiB par hôte, comme je l'avais d'abord annoncé. L'éviter
demanderait de faire lire des **fichiers individuels** aux consommateurs au lieu du répertoire :
refactor plus large, noté en dette, non entrepris.

**Méthode réutilisable (celle qui a servi tout ce jour-là)** : pour prouver qu'un refactor est inerte,
comparer les `drvPath` **rev contre rev** (`git+file:///path?ref=<branche>` des deux côtés, jamais
arbre sale contre arbre propre — `self` et le contenu source diffèrent alors pour une raison qui
n'est pas le diff). Puis, si ça bouge, descendre dans la dérivation :
`nix derivation show` → `.derivations.<name>.inputs.drvs` (schéma nix 2.34 ; les clés sont des
**basenames**, préfixer `/nix/store/`), comparer les ensembles niveau par niveau — toplevel → `etc`
→ `system-units` → l'unité — puis diffier `env.text`. Ça nomme la ligne exacte qui change.

See [[unmanaged-mac-ssh-material-chain]] [[erofs-layer-images-input-addressed-rebuild]].
