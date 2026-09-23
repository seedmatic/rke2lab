---
name: fabric-migration-state-and-queue
description: "État de la migration fabric au 2026-09-23 soir (les deux bare-metals renumérotés et vérifiés vivants) + la file des quatre chantiers suivants, dont la circularité de bootstrap de headscale qui interdit de tout mettre dans la fabric"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-23T20:00:05.291Z
---

## ✅ FAIT et VÉRIFIÉ VIVANT — les tranches dérivées de `hostId`

Chaque bare-metal possède `172.16.<hostId*16>.0/20`. Le **lien est encodé dans l'adresse** :
offset du 3ᵉ octet < 8 ⇒ sur `bare-br`, ≥ 8 ⇒ un autre lien (le `/30` du vz-host est le slot 8).

| | bioskop (hostId 0) | nikopol (hostId 1) |
|---|---|---|
| `netCidr` | `172.16.0.0/21` | `172.16.16.0/21` |
| `linkCidr` | `172.16.8.0/30` | `172.16.24.0/30` |
| `vzHostAddress` | `172.16.8.2` | `172.16.24.2` |

Vérifié depuis le Mac corp : `ping 172.16.16.1` OK, `nnh-inlet.nikopol` → `172.16.16.126`,
`vzhost.nikopol` → `172.16.24.2`. Depuis bioskop : `ping 172.16.0.1` OK,
`vzhost.bioskop` → `172.16.8.2`.

★ **L'ordre était contraint** : nikopol d'abord. Le `/21` cible de bioskop (`172.16.0.0/21`)
**contient** l'ancien `/25` de nikopol, alors que la cible de nikopol ne contenait rien de
vivant. Ce n'était donc pas « ensemble » comme l'affirmait d'abord le carve.

Commits : rke2lab `966636636` (publie `hosts`, ajout **pur** au blueprint), nnh `d3fef78`
(son `/30` → `172.16.16.124/30`, déclaré une fois au lieu de cinq), ndh `32a5dd40` (les
host-records dnsmasq dérivés), `c34ac787` (les tranches dérivées), `ffd4ed6d` + `a9a6833d`
(les défauts du deploy), `5ad07a8f` (`vzHostKind` dérivé de `hostProfile.form`).

⚠️ **Reste à faire côté tailnet** : `nix run .#manage-tailnet -- --sync-acl` (dry-run par
défaut, puis `--yes`). Les annonces sont à jour des deux côtés, mais la netmap du Mac ne porte
que `172.16.0.0/20` — la `/20` de nikopol n'est pas approuvée.

★ Les autorisations de CIDR ne sont PAS dans `catalog/tailnet/acl.hujson` (qui n'a que les
règles par tag, à raison) : elles vivent dans `autoApprovers.routes`, **générés depuis le
catalogue** par `modules/.common.d/manage-tailnet.d/default.nix` (`routeApprovers`, dérivé des
`advertiseCidr`). Le fragment rendu porte déjà les deux `/20` → `tag:nixos`. Donc chercher les
CIDR dans le hujson et conclure à une dérive est une **fausse piste** (je l'ai suivie).

Sondes utiles : `tailscale status --json` → `.Peer[].AllowedIPs` (une route de sous-réseau
approuvée y apparaît ; `PrimaryRoutes` ne dit que « ce pair est primaire », pas « approuvée »)
et `tailscale debug prefs` → `AdvertiseRoutes` côté annonceur.

## La file, dans l'ordre (chaque étape débloque la suivante)

1. **`bare-br` → `fabric-br`.** Le vocabulaire a convergé sur « fabric ». Étendue mesurée :
   ndh (la définition) + **une** ligne dans nnh (`parent = "bare-br"`) ; rke2lab **zéro** (ses
   nœuds sont sur `vmnet-br`). ⚠️ Incus refuse de renommer un réseau auquel des instances sont
   attachées → gratuit sur bioskop (pont vide), coûteux sur nikopol. **Synergie qui expire** :
   les deux instances nnh doivent DÉJÀ être arrêtées pour prendre `.16.125`/`.126`, donc à
   faire dans la même fenêtre, sinon deux arrêts. Et ne PAS écrire le renommage avant la
   fenêtre : le preseed créerait le nouveau pont en laissant l'ancien orphelin.
2. **Les spans internes de cluster vers les slots 1-4.** Le carve les réserve (« slot = 1 +
   roleId »). C'est le gros morceau : rke2lab + les manifests cilium.
3. **Effondrer `lan-br` → `vmlan0`.** `lan-br` n'existe que parce que la NIC du guest a été
   mise dans un bridge Incus « for container LAN access ». Sans conteneurs sur le LAN, il n'a
   plus d'objet. ⚠️ Le guest **garde** son bail LAN — c'est son uplink vers l'internet et le
   tailnet, et le L2 que l'alias du Mac partage. Ce qui disparaît est le pont, pas
   l'attachement. Conséquence de (2), pas avant.

## ★★★ La circularité qui interdit de tout mettre dans la fabric

Le blueprint distingue **déjà** deux plans LB, et le préfixe porte le sens :

```
lbCidr    = 10.80.<x>.64/26   ← interne au cluster
lanLbCidr → lanHeadscale host(1), lanTailscale host(2)   ← sur le LAN domestique
```

**Headscale est ce qui permet de rejoindre le tailnet**, donc son point d'entrée ne peut pas
présupposer le tailnet. Aujourd'hui il passe par une redirection bbox
(`41841 → bioskop:41841`), et une bbox ne redirige que vers une adresse du LAN. Le déplacer
dans la fabric le rendrait injoignable de l'extérieur → plus aucun enrôlement possible.

Deux issues, à trancher : garder une adresse LAN pour ce seul service (et alors `lanHeadscale`
dit littéralement ce qu'il est), **ou** faire du funnel sa porte publique — ce qui casse bien
la circularité, puisque atteindre une URL de funnel ne demande pas d'être sur le tailnet, seul
le nœud qui sert doit l'être.

Donc l'étape 2 déplace `lbCidr`, **pas** `lanLbCidr`. Le préfixe `lan*` marque exactement
« doit être joignable sans le tailnet ».

## Un fait de topologie à ne pas reconfondre

- **bioskop** = le Mac Mini : à la fois vz-host ET rdp-host. **Deux** machines en tout
  (lui + `bioskop-nixos`). `hostProfile.form = "baremetal"`.
- **nikopol** = une **VM macOS** sur le Mac corp. **Trois** machines : le Mac corp
  (`nikopol-vzhost`, `192.168.1.65`, hostname `APL-g4xfl7qv06`, compte `stephane.lacoin`),
  la VM `nikopol` (`.33`), et `nikopol-nixos` (`.34`). `form = "vm"`.

⚠️ `nikopol.local` résout vers la **VM** (`.33`), pas vers le bare-metal. C'est ce qui a fait
viser la mauvaise machine à un déploiement. Le nom hors-bande du bare-metal est
`nikopol-vzhost.lan`.

See [[declared-once-read-once-defect-family]] [[darwin-nic-service-not-device]]
[[fabric-segment-pinning-and-federation-from-nnh]].
