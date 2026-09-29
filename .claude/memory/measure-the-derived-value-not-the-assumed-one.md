---
name: measure-the-derived-value-not-the-assumed-one
description: "★★★ Quatre erreurs en une soirée (2026-09-27), toutes de la MÊME forme : un outil qui répond « moins » au lieu de « je ne peux pas », et une valeur supposée au lieu d'être dérivée. Le catalogue des quatre pièges, et le réflexe."
metadata:
  node_type: memory
  type: feedback
  originSessionId: c2c2a472-f982-468d-98de-1b23bbf4e274
  modified: 2026-09-27T19:20:47.974Z
---

Soirée du **2026-09-27**. Quatre conclusions fausses, dont trois corrigées par l'utilisateur. Elles
n'avaient qu'une cause, et c'est elle qu'il faut retenir.

## ★★★ La forme à redouter : une réponse plus PETITE au lieu d'un refus

| ce que j'ai lancé | ce qu'il a répondu | ce que ça voulait dire |
|---|---|---|
| `grep -E 'ERROR.*\.java'` sur un build maven | rien | échec de **résolution de dépendance**, invisible au filtre |
| `ls /var/lib/incus/images 2>/dev/null` | vide | **« Permission denied »** avalé par le `2>/dev/null` |
| `zfs list` en utilisateur non privilégié | moins de datasets | ZFS **omet** ce qu'on n'a pas le droit de voir |
| `incus image list` | rien (chez l'utilisateur) | **autre projet** que le mien (`rke2lab` vs `default`) |

À chaque fois j'ai lu l'absence comme un fait, et mes commandes paraissaient d'autant plus fiables
qu'elles « marchaient ». ⚠️ **Supprimer stderr fabrique exactement ce piège** : `2>/dev/null` change
un refus en vide.

## ★★ Le second visage : une valeur SUPPOSÉE au lieu de dérivée

- J'ai cherché le VIP d'un cluster à `10.80.8.10`, **déduit du bail DHCP du nœud**, alors qu'il fallait
  le lire dans le rendu : la convention est le `.10` du **dernier** `/24` de la tranche
  (`bioskop-mgmt` → `10.80.7.10`, `bioskop-wrkld` → `10.80.15.10`, `nikopol-mgmt` → `10.80.23.10`).
  L'adresse `10.80.0.10` est celle du **nœud** sur vmnet, pas le VIP. J'en ai conclu « kube-vip n'a pas
  revendiqué » alors que les deux VIP répondaient depuis le début.
- J'ai cherché une image incus dans un **dataset ZFS** (`<pool>/incus/images/<fp>`) : ce n'est pas
  l'image, c'est le **volume déplié**, créé sur le membre qui a fait grandir une instance. Les octets
  sont des fichiers `<fp>` + `<fp>.rootfs` dans `/var/lib/incus/images/`, **root seul**.
- Un `python replace` sans assertion, neutralisé par un reformatage entre deux éditions : le filtre
  n'était pas appliqué. Seul le **test que j'avais écrit** l'a dit. → préférer l'outil d'édition qui
  échoue si la chaîne ne matche pas, ou assortir chaque `replace` d'une assertion.

## Le réflexe

1. **Ne jamais suffixer un diagnostic de `2>/dev/null`.** L'erreur EST l'information.
2. **Filtrer large** sur une sortie de build : `BUILD SUCCESS|FAILURE` et le compte de tests, pas un
   motif étroit qui ne peut voir qu'une classe d'échec.
3. **Vérifier la PORTÉE avant le contenu** : projet incus courant, utilisateur, membre du cluster.
   Une même commande ne répond pas la même chose selon qui la pose et où.
4. **Dériver l'adresse, ne pas la déduire d'un voisin** : lire le rendu / le blueprint plutôt que
   d'inférer d'un bail ou d'une adresse proche.
5. ★ Quand l'utilisateur dit « va voir dans la codebase » ou « regarde en root », **c'est la réponse** :
   j'ai produit trois révisions successives d'un commentaire ndh à force de déduire au lieu de lire.

See [[nix-store-and-etc-can-both-lie]] [[cold-start-2026-09-27-nikopol-mgmt]]
