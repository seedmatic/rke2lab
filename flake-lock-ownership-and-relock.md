---
name: flake-lock-ownership-and-relock
description: "★★ Chaque repo est maître de SES locks ; franchir une frontière est une REQUÊTE (`relock`), pas une intrusion. Le cycle rke2lab⇄ndh ne se coupe pas : il termine dans l'espace des DÉRIVATIONS."
metadata:
  node_type: memory
  type: project
---

**Décidé le 2026-09-30.** `update-flox-envs` codait en dur trois hops sur un graphe qui n'est ni
trois hops ni une chaîne. Graphe mesuré (nos repos seulement) : `flake-commons`, `flox-nri-plugin`,
`flox-controller`, `rke2lab@seed-incluster` → `rke2lab` ; `rke2lab ⇄ ndh` (**cycle vivant** — le
`develop` de ndh épingle notre branche feature) ; et `rke2lab`, `ndh`, `flake-commons` →
`rke2lab@flox-catalogue`.

## La règle

**Bumper un input, c'est éditer SON lock ; faire que quelqu'un t'épingle, c'est SON acte.** Donc
franchir une frontière de repo est une **requête**, jamais une intrusion. ★ Ça VALIDE le périmètre de
l'app existante — `seed-incluster` et `flox-catalogue` sont des branches orphelines de rke2lab, pas
des repos tiers — et ne condamne que ce qu'on aurait voulu y ajouter : l'arête `ndh ← rke2lab plans`
appartient à une app de **ndh**.

## ★★ Le cycle ne se coupe pas, il termine

Fatal si on raisonne en **révisions** (chaque bump en frappe une nouvelle à épingler). Il y a un point
fixe dans l'espace des **dérivations** — la garde que `lock-envs` applique déjà : si la projection
signifiante est inchangée, on **reverte** (`git checkout -- manifest.lock`) et aucune révision n'est
émise. Un tour sans impact ne notifie personne et la chaîne meurt. Déclarer une arête « coupée » est
une rustine pour un problème que la garde supprime.

⚠️ **La projection ne se transpose PAS littéralement.** Dans un `manifest.lock` flox, `locked-url` est
volatile. Dans un `flake.lock`, `locked.rev` **EST** l'identité du contenu — le supprimer rendrait tout
bump sans impact. L'analogue correct est un cran plus loin : **les drv paths des outputs exportés**.
Mesuré : tout le package set s'évalue en **~9 s**, donc la garde tourne par input.

★ Et ça reformule ce qu'est un lock : **une déclaration sur des OUTPUTS, pas sur de la fraîcheur.**
Porter un input plus récent qui ne change rien n'ajoute aucune information et coûte une révision.

## L'app : `relock`, même nom partout

Uniforme, parce qu'un modèle par requête exige que l'appelant ne sache rien du destinataire au-delà
du nom. Cibles (le lock ET les artefacts générés sont la même chose — un fichier committé dérivé
d'une source qui peut périmer) : `inputs` / `<input>` / `plans` (`regen-dataplan`) / `netplan`
(`regen-blueprint`) / `envs[:<id>]` / `catalogue` ; aucune cible = tout. `--downstream` lance le
`relock` **du consommateur**. Éviter `propagate` (mot pris par une régression de flox-controller).

Deux skips silencieux fermés le même jour : rke2lab n'était poussé que si le pin seed-incluster
bougeait (le catalogue épinglait alors une révision plus VIEILLE en rapportant un bump propre), et le
landing était rapporté sans être **asserté**.

rke2lab `f19e225de` + `0f6bd5692`. Gravé dans
`docs/architecture/patterns/flake-lock-propagation.adoc`. ⏸️ Reste : la liste « qui me consomme »
(déclarée par repo, recommandé), l'export `lib.mkRelock` pour que ndh l'instancie, et l'exécution
in-cluster sur Tekton.
