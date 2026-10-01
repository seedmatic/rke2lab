---
name: outcluster-incluster-one-axis-chain
description: La chaîne est operator -> pulumi -> outcluster -> incluster ; le maillon 1 est une PERSONNE, et `standalone` sert fautivement sur deux axes orthogonaux
metadata:
  type: project
---

Décidé le 2026-10-01 par l'utilisateur, en renommant `seed-master` → `seed-outcluster`
(`17de9ca95`) : **« de fait on a operator -> pulumi -> outcluster -> incluster dans la
chaine »**, puis **« l'operateur c'est moi, celui qui lance la commande pulumi »**.

Donc le **maillon 1 est une personne et n'a aucun code**. C'est ce qui a tranché la question
que j'avais posée (« est-ce qu'`out-cluster` remplace `OPERATOR` ? ») : non — ce sont des
maillons distincts, pas deux mots pour un axe.

| maillon | code |
|---|---|
| 1. operator | **aucun** — l'humain |
| 2. pulumi | `RunMode` (`STANDALONE` / `PULUMI_PREVIEW` / `PULUMI_RUN`) |
| 3. outcluster | `ExecutionEnclosure.OPERATOR` |
| 4. incluster | `ExecutionEnclosure.IN_CLUSTER` |

`RunMode` et `ExecutionEnclosure` sont **orthogonaux** et le disent : *« a process can be
OPERATOR within a PULUMI_RUN, or IN_CLUSTER with no Pulumi at all »*.

**Conséquence pratique immédiate** : la prose « the operator's host » reste JUSTE (c'est
l'hôte que l'humain possède, maillon 1), donc le balayage des 67 docs n'a pas eu à toucher ce
vocabulaire et rien n'a été renommé deux fois.

## ★ La vraie dérive que la chaîne a révélée

**Le mot `standalone` sert sur les deux axes.** Dans `RunMode` il veut dire « hors Pulumi » ;
dans `ExecutionEnvironment:53` il est accepté comme synonyme d'`OPERATOR` —
`case "operator", "standalone", "local" -> ExecutionEnclosure.OPERATOR` — donc « hors
cluster ». Un seul mot pour deux axes orthogonaux, dans deux modules qui se lisent ensemble.
C'est la même forme que « un nom que personne ne déclare répond quand même » : ça ne casse
rien jusqu'au jour où quelqu'un règle le mauvais axe.

## Suites décidées, PAS encore faites

- `ExecutionEnclosure.OPERATOR` → **`OUT_CLUSTER`**, et retirer l'alias `standalone` au
  passage. Exact et symétrique d'`IN_CLUSTER` (« which container this JVM runs inside » : il
  y en a exactement deux), et ça nomme le CÔTÉ MACHINE au lieu du propriétaire de la machine.
- **Garder `Reach.OPERATOR_ONLY`.** Son complément n'est pas « out-cluster » : il couvre
  aussi la voie `NODE_BOOTSTRAP` (*« consumed operator-side, or delivered node-side »*). Un
  nœud n'est ni l'un ni l'autre, donc `OUT_CLUSTER_ONLY` mentirait. Son vrai sens est la
  négation fail-closed d'`IN_CLUSTER`.
- La prose de **ndh** et de la branche **`seed-incluster`** dit encore `seed-master` (ndh :
  `catalog/default.nix`, `docs/dataset-catalog.adoc`, les deux `hosts/*/darwin.nix` ;
  `seed-incluster` : 4 descriptions de CRD + la `description` du flake). Aucune n'est
  fonctionnelle — **aucun dépôt voisin ne consomme `.#seed-master`** (vérifié sur ndh,
  flox-controller, flox-nri-plugin), donc rien à relocker.

See [[runmode-livegate-pulumi-abstraction]] [[in-cluster-cellar-asset-reach]]
[[single-source-of-truth-before-logic]].
