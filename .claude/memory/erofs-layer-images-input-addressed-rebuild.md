---
name: erofs-layer-images-input-addressed-rebuild
description: "Piste MESURÉE (2026-09-20) — les images EROFS de couche sont reconstruites et retransférées (~8 GiB) pour des octets identiques, parce que les dérivations sont adressées par ENTRÉE ; __contentAddressed les rendrait stables, et ca-derivations est déjà activé dans le flake ndh"
metadata:
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-20T18:48:29.206Z
---

**Le fait, mesuré.** Ajouter deux chemins à la closure de bringup (l'unité
`bringup-target-activate` et son script) a suffi à donner de **nouveaux chemins de store**
aux trois images EROFS de couche — alors que 002 et 003 sont **octet pour octet
identiques** :

| couche | taille avant | taille après | sha256 |
|---|---|---|---|
| 001 | 2 769 563 648 | 2 769 575 936 | changé (+12 Ko, les 2 chemins) |
| 002 | 8 025 645 056 | 8 025 645 056 | *non vérifié, taille identique* |
| 003 | 40 902 656 | 40 902 656 | **`1f69dc37…` identique, vérifié** |

**Pourquoi.** Les dérivations sont adressées par **entrée**, et les couches sont des **deltas
chaînés** : L1 = la closure de base, L2 = le delta contre la base, L3 = le delta contre base+L2.
Donc `baseClosureInfo` change ⇒ les trois entrées changent en **cascade** ⇒ nouveaux chemins ⇒
~8 GiB reconstruits sur le builder **et** retransférés par `nix copy` pour produire des octets qu'on
avait déjà.

⚠️ **CORRECTION (2026-09-20 soir)** — j'avais écrit ici que les couches « reçoivent toutes
`unionClosureInfo` ». **C'est faux**, et la mesure le tranche : à `develop` `ed4e4af5`, les
dérivations `erofs-001` (`k8fj66nk…`) et `erofs-002` (`xx7a3win…`) sont **identiques** entre les
bundles nikopol et bioskop, seule `erofs-003` diffère (`4711jpvi…` contre `br0br9sf…`). Si l'union
était une entrée des images, elles différeraient toutes — les unions par hôte diffèrent. Donc
l'union ne sert qu'à la **registration** dans la base nix du guest, pas à l'identité des images, et
les deux couches basses sont **fleet-shared au niveau dérivation**, pas seulement au niveau
contenu. Conséquence pratique : reconstruire un second hôte après le premier tombe sur le cache
pour L1 et L2, et ne paie que son L3 (~39 Mo).

**Le remède, à portée.** `__contentAddressed = true` sur `erofs-store-image.nix` : les
octets d'une couche ne dépendent que de la liste de chemins qu'elle packe (le packer est
déjà déterministe — `--force-uid/gid=0 -T 0 -U`, vérifié par l'horodatage 1980 dans les
superblocs). Une couche dont le contenu ne bouge pas garderait son chemin.
**`ca-derivations` est DÉJÀ dans `nixConfig.extra-experimental-features` du flake `ndh`** —
rien à activer.

**Ce que ça change au-delà du coût de build**, et c'est le point important : ça **corrige
une limite que je croyais structurelle**. J'avais conclu que la couche générique de flotte
ne dédupait qu'au sein d'une même révision de `ndh`, parce que les toplevels capturent
`self`. C'est vrai des *toplevels* mais pas des *images* : en adressage par contenu, la
couche générique de 7,47 GiB deviendrait un artefact de **flotte**, partagé entre révisions,
et pas un artefact de commit. Voir [[erofs-store-layer-stack-vision]].

**★ 2026-09-20 — `ca-derivations` SEULE NE SUFFIRAIT PAS, et le correctif manquant est plus simple.**
Le marqueur que tart écrit à côté de chaque image (`<disk>.img.source`) enregistre le chemin
**qualifié par le bundle** — constaté sur le vzhost :
`/nix/store/n4fiymrg…-io.seedmatic.ndh-nerd-bringup-zfs-disk-images/store-002.img`. Or dans le
bundle, `store-002.img` est un **symlink** vers un chemin de store séparé
(`…-io.seedmatic.ndh-nix-store-erofs-002`). Donc dès que le bundle change, **les quatre marqueurs
sont en désaccord, même si l'image de couche est identique octet pour octet** — la porte
re-matérialise, et le garde de remplacement compte la couche comme « remplacée », ce qui impose un
factory reset.

Correctif indépendant et bien moins cher que le content-addressing : **résoudre le symlink avant
d'écrire et de comparer le marqueur**. Une couche inchangée garderait alors son identité d'un bundle
à l'autre, la copie serait sautée, et un changement qui ne touche que la couche par-hôte deviendrait
matérialisable **sans factory reset** (sémantique d'ajout, pas de remplacement). Les deux correctifs
se composent : le marqueur résolu rend l'identité comparable, le content-addressing la rend stable.

⚠️ **Réserve.** `ca-derivations` reste expérimental et ses aspérités connues portent
justement sur les **builders distants** et les **substituters** — c'est-à-dire exactement
notre configuration (bioskop comme unique builder aarch64-linux, cachix). À éprouver en
mesurant, pas à basculer à l'aveugle. Test minimal : marquer *une* couche content-addressed,
provoquer un changement qui ne touche que la closure de bringup, vérifier que 002 et 003
gardent leur chemin et que `nix copy` ne transfère rien.
