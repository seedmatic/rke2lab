---
name: declared-once-read-once-defect-family
description: "Quatre défauts de la même famille, tous rencontrés le 2026-09-23 : un nom traité comme convention, un échec avalé, un ajout sans retrait, une valeur déclarée lue une seule fois. Le symptôme commun : le rendu est juste, nix eval le confirme, et le vivant diverge sans rien dire"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-23T19:56:41.881Z
---

Journée du 2026-09-23, migration de la fabric (ndh + nnh + rke2lab). **Sept** défauts trouvés,
qui se rangent en quatre formes. Ils ont tous la même signature : le rendu est correct,
`nix eval` le confirme, et le système vivant diverge **sans rien signaler**.

## Forme 1 — un nom traité comme une CONVENTION au lieu d'être lu

- `interface = "en0"` en dur : juste par accident pour nikopol (Wi-Fi), faux pour bioskop
  (Thunderbolt = en9), où en0 est l'Ethernet intégré. Voir
  [[darwin-nic-service-not-device]].
- `bootstrapHost = "${bm.domain}.local"` : suppose que le bare-metal porte le nom de son
  segment. Vrai pour bioskop (qui EST son bare-metal), faux pour nikopol où `nikopol` nomme
  la **VM invitée** — le déploiement a visé la mauvaise machine.
- `vzHostKind` déclaré alors que `hostProfile.form` (`baremetal` | `vm`) dit **la même
  chose** ; `stephane.lacoin` écrit en trois endroits ; le `/30` de nnh en **sept** copies.

★ Le test : si deux consommateurs doivent s'accorder sous peine de casse silencieuse, c'est
**une** déclaration, lue deux fois. Une convention qui « tient » ne tient que jusqu'au
premier hôte qui ne la respecte pas, et elle ne prévient pas.

## Forme 2 — un échec AVALÉ

`deploy.sh` a vu `Permission denied`, puis a imprimé
`ndh::logger:command:run completed successfully`. Cause : il est invoqué **par** ce runner,
qui fait `local rc=0; main`, ce qui **neutralise `set -e` à l'intérieur**. Le shebang
`#!/usr/bin/env -S bash -euo pipefail` n'y change rien.

⚠️ Corollaire général : **dans une fonction appelée par un wrapper, errexit ne protège
rien.** Tester explicitement, et dire « NOTHING was applied ».

Deuxième instance : `route add || route change || true` — le `|| true` a mangé l'échec du
`change`, laissant la route tailnet du Mac corp sur une passerelle morte.

## Forme 3 — AJOUTER sans RETIRER

`link-up.sh` ne faisait qu'ajouter (`grep -q || ifconfig alias`). Après renumérotage, le Mac
portait les **deux** alias, une route périmée, et — le seul vraiment nuisible — sa route
tailnet vers l'ancienne passerelle. Correctif : l'applicateur **enregistre** ce qu'il a posé
(`$conf_dir/.applied`), l'installeur retire exactement ça avant d'appliquer.

★ Pourquoi un fichier d'état et pas un balayage par préfixe : c'est un Mac géré par
l'entreprise, où `172.16/12` n'est pas forcément à nous — un VPN corp peut y vivre. On retire
ce qu'on sait avoir posé, rien d'autre.

⚠️ La protection ne prend effet qu'au run **suivant** : au premier, le fichier n'existe pas.

## Forme 4 — une valeur DÉCLARÉE que le runtime ne lit QU'UNE FOIS

`networking.headscale.advertiseRoutes` est déclaratif en nix, mais tailscaled ne l'applique
**qu'à l'enregistrement** : après le rebuild, `tailscale debug prefs` montrait encore les
anciens `/24`. Aucun `nix eval` ne pouvait l'attraper.

★ **Troisième occurrence du même motif sur ce projet** :
- `raw.dnsmasq` du réseau incus — **write-once** tant que le réseau n'est pas dans l'état
  Pulumi ([[node-bootstrap-objects-need-instance-recreation]]) ;
- `ensure` de seed-incluster — **create-only** sur les CR CAPI
  ([[node-env-gated-oneshots-skip-capn-nodes]]) ;
- `advertiseRoutes` — appliqué à l'enregistrement seulement.

Donc la question à poser devant toute valeur déclarée : **le système la rééchantillonne-t-il,
ou seulement à la naissance ?** Si c'est à la naissance, `nix eval` ne prouve rien sur le
vivant et il faut une sonde runtime (`tailscale debug prefs`, `incus network show`,
`kubectl get -o yaml`).

## La leçon de méthode

Toutes mes vérifications par évaluation étaient vertes pendant que trois de ces défauts
étaient actifs. Ce qui les a trouvés : lire le **log unifié ligne par ligne**, et les
remarques de l'utilisateur (« en9 pas en0 », « stephane.lacoin existe pas sur nikopol »,
« nikopol c'est le vz guest »). Une évaluation verte ne dit rien de la livraison.

See [[darwin-nic-service-not-device]] [[nix-darwin-activation-keys-are-fixed]]
[[fabric-segment-pinning-and-federation-from-nnh]].
