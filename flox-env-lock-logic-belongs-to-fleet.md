---
name: flox-env-lock-logic-belongs-to-fleet
description: "DÉCIDÉ 2026-10-03, PAS COMMENCÉ : la logique de lock des envs flox va dans un flake DU SUBTREE possédé par fleet (`nix run ./.flox-envs.d#lock`) ; et la copie de rke2lab est DÉJÀ partiellement cassée."
metadata:
  node_type: memory
  type: project
  originSessionId: eef830de-9690-4fc8-ac3d-71fec81b1988
  modified: 2026-10-03T09:23:59.281Z
---

**Décidé par l'utilisateur le 2026-10-03, code pas commencé.** Arbitrage de la session
d'intégration : **incrément à part**, après l'atterrissage de ndh (PR #8).

## Le problème

`lock-flox-envs` existe maintenant **en deux copies** : dans le flake de rke2lab et dans
celui de ndh (ajoutée le 2026-10-03, voir
[[ndh-normalized-and-flox-envs-vendored]]). C'est exactement la forme qui a gelé les
**deux** subtrees de fleet : une procédure dupliquée sans propriétaire nommé.

## La destination tranchée

Un **`flake.nix` dans l'arbre flox de fleet**, vendoré avec lui, exposant une app `lock`
que chaque consommateur appelle par `nix run ./.flox-envs.d#lock`.

Pourquoi celle-là, et pas la lib de rke2lab :

- ce que la logique encode est la **convention d'includes relatifs de fleet** (`dir =
  '../keyhole'`) → le **producteur** doit la posséder, pas un consommateur ;
- elle **n'ajoute aucune arête d'input** entre dépôts, contrairement à
  `inputs.rke2lab.lib.mkLockFloxEnvsApp` ;
- un nouveau consommateur l'obtient **gratuitement** au pull, sans écrire de code de flake.

⚠️ Coût assumé : l'arbre flox de fleet **n'a aucun flake aujourd'hui** — c'est donc un
changement **structurel chez le producteur** (il gagne un `flake.nix` et un `flake.lock`
imbriqué), ce qui mérite sa propre revue.

★ **L'alternative écartée avait un précédent réel**, et il vaut d'être connu : ndh importe
déjà trois fois la lib de rke2lab (`lib.networkBlueprint`, `lib.dataplan`,
`lib.mkRelockApp`), et le commentaire de `mkRelockApp` dit mot pour mot « exported so
every repo in the chain applies the SAME rule instead of a copy of it ». C'est le même
problème, déjà résolu une fois — mais résolu *chez le consommateur*.

## ⛔ LA CONDITION DURE

**Les deux copies partent dans le MÊME changement** (fleet + rke2lab + ndh). Sinon deux
copies deviennent trois. En attendant, un commentaire à la copie de ndh **nomme la
destination décidée**, pour qu'elle ne devienne pas permanente en silence.

## ★★★ Le même défaut a frappé la copie de ndh, à l'autre niveau — et la leçon est la parade

Trouvé en revue de la PR #8 (2026-10-03), corrigé dans `d0abdb73`. `deps_of` lisait bien
les **deux** styles de quote, mais le lecteur de **premier niveau** (celui qui alimente la
boucle principale) n'en lisait qu'un. Mesuré : 3 includes en doubles quotes, **0** en
simples. Et zéro n'est pas une erreur, c'est un **silence** : la boucle `while read` ne
tourne jamais et la ligne de succès s'affiche quand même.

⛔ Donc l'app annonçait « ready » à l'opérateur, sur une étape déclarée **OBLIGATOIRE**
pour un clone frais, **après n'avoir rien verrouillé** — puis `flox activate` échouait sur
le « manifest and lockfile are out of sync » que l'app existe précisément pour éviter.

✅ **Deux correctifs, et le second compte plus que le premier :** symétriser les lecteurs,
**et REFUSER sur zéro include** (exit 1) au lieu de rapporter un succès. La symétrie règle
le cas du jour ; le refus règle la **classe** — il reste correct pour une forme d'include
qu'aucun des deux lecteurs n'anticipe. **Un verrouilleur qui ne sait pas lire le manifeste
doit le DIRE, pas répondre « rien ».**

★ C'est exactement la famille de
[[measure-the-derived-value-not-the-assumed-one]] : un outil qui répond « moins » au lieu
de « je ne peux pas ». Ici le « moins » était zéro, et zéro se déguisait en succès.

## ★★ Et l'urgence n'est pas cosmétique : rke2lab est déjà cassé

Mesuré le 2026-10-03 : le `sed` de rke2lab ne lit que les **simples** quotes
(`dir = '../x'`), alors que fleet écrit l'include du **siège** en **doubles**
(`{ dir = "./.flox-envs.d/x" }`). La version de ndh a été élargie aux deux styles ; celle
de rke2lab **ne relocke donc pas ce que celle de ndh relocke**. Ce n'est pas de la dette
de forme, c'est un défaut actif — et c'est l'argument pour que l'incrément vienne **tôt**
plutôt qu'un jour.

Voir [[flox-envs-vendored-as-subtree]] pour la direction de la vérité et la skill
`flox-envs-subtree-sync` qui porte la procédure.
