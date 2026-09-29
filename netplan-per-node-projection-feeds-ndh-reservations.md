---
name: netplan-per-node-projection-feeds-ndh-reservations
description: "Les réservations d'IP tuées côté bbox sont TOUJOURS régénérées par ndh, qui aplatit addressing.<cluster>.<node> en entrées d'hôtes — la source est la projection netplan, pas ses consommateurs (mesuré 2026-09-23)"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-23T14:00:02.041Z
---

Le 2026-09-23, `21bdd8f4b` a coupé l'énumérateur bbox de 12 lignes à 2 (« reserve LAN addresses
only where a reservation can be claimed »). **Ce n'était que la moitié du chemin** — et
l'utilisateur l'a dit : « on avait tué les réservations d'IPs mais on n'était pas allé jusqu'au
bout ».

★ **ndh les régénère.** `ndh/catalog/default.nix` (~248-264) aplatit **chaque**
`addressing.<cluster>.<node>` en entrée d'hôte du catalogue :

```nix
mac = addressing.${cluster}.${node}.macs.lan;
ip  = addressing.${cluster}.${node}.ips.lanHost;
kind = "rke2";
```

Son propre commentaire l'assume : « the bbox static reservations stay in lockstep with rke2lab ».
Donc les 12 réservations fictives des clusters de charge sont toujours déclarées, depuis la même
projection. **La source est la projection netplan, pas ses consommateurs** — c'est là qu'il faut
couper, et alors ndh se corrige sans être touché.

## La fuite jumelle, dans un certificat

`RuntimeRke2ConfigManifestsUnit.tlsSanSuperset` ajoute `blueprint.lan().hostInetaddr()` **et** les
six FQDN mDNS de la roster canonique. Mesuré dans la chaîne servie par l'apiserver de
`bioskop-wrkld` : `IP Address:192.168.1.147` + `bioskop-wrkld-{master,peer1,…,worker2}.local`,
alors que le nœud réel est `bioskop-wrkld-control-plane-5dcd9` sur `192.168.1.14` (bail ordinaire
du routeur). Pas une faille — un SAN de trop n'autorise rien — mais un mensonge dans un document
signé, et l'entretien de la croyance qui a produit les 12 réservations.

## ⚠️ Les contraintes mesurées (survivent à tout plan)

- **ndh ne lit que TROIS sections** : `addressing`, `segments`, `asns`. Et il itère
  `builtins.attrNames addressing.<cluster>` **comme des noms de nœuds** → on ne peut PAS ajouter
  une clé cluster-scopée à côté des nœuds, elle passerait pour un nœud. Une nouvelle section
  **top-level** est sûre (ndh ignore ce qu'il ne lit pas).
- **Le flake lit `networkBlueprintData.clusters.<cluster>` comme un ENTIER** (`flake.nix` ~404,
  pour dériver le `clusterId` du segment vmnet). Donc `clusters` doit rester **complet pour les
  quatre clusters**, même ceux qui n'ont plus d'entrées par nœud — attention, il était peuplé
  DANS la boucle par nœud.
- **`<cluster>-lb` (`192.168.1.160/29`) est VIVANT** : c'est le pool cilium `lan`
  (`CiliumAdvancedManifestsUnit`), qui porte `lanHeadscale` = host(1) et `lanTailscale` = host(2).
  Abandonner les adresses de NŒUDS ne veut pas dire abandonner le carve LAN — le cluster de charge
  a besoin d'un morceau du LAN domestique pour ses **services** LoadBalancer annoncés en L2, pas
  pour ses nœuds.
- `clusters`, `nodes`, `macPatterns`, `nodeTypes` : je n'ai trouvé **aucun** lecteur de `nodes` /
  `macPatterns` / `nodeTypes` dans rke2lab ni ndh — mais `nix-darwin-home` n'était pas dans mon
  périmètre, donc ne pas les retirer sans l'avoir vérifié.

## Le discriminateur à nommer une fois

`ClusterRole.hasPredictableNodeIdentity()` — MGMT = pets host-grown (nom + MAC posés par le grow,
donc adresse réservable), WRKLD = bétail CAPI (nom et MAC mintés par CAPN). C'est ce prédicat qui
manquait et dont l'absence a coûté deux fois. Une prédiction que personne ne peut réaliser est
pire qu'aucune : elle consomme un carve ET elle invite le consommateur suivant à s'y fier.

⚠️ Chantier **ABANDONNÉ en cours de route le 2026-09-23** (l'utilisateur : « j'ai un autre plan
qui va simplifier les choses »), code jeté sans commit. Ce fichier garde les mesures, pas le code.

See [[netplan-projection-described-hosts]] [[node-env-gated-oneshots-skip-capn-nodes]].
