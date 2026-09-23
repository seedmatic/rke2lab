---
name: darwin-nic-service-not-device
description: "Sur les Macs de la flotte on nomme une NIC par son NOM DE SERVICE macOS, jamais par enX — mesuré sur bioskop : Wi-Fi=en1, en0=Ethernet intégré, Thunderbolt Ethernet Slot 1=en9"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-23T16:01:30.391Z
---

Fait d'environnement **mesuré le 2026-09-23 sur bioskop**, absent de tout dépôt (donc
invérifiable par lecture de code) :

```
(Hardware Port: Thunderbolt Ethernet Slot 1, Device: en9)   <- la NIC que la VM bridge
(Hardware Port: Ethernet,                    Device: en0)
(Hardware Port: USB 10/100/1000 LAN,         Device: en8)
(Hardware Port: Wi-Fi,                       Device: en1)
```

★ **`en0` n'est PAS le Wi-Fi sur bioskop** — c'est l'Ethernet intégré, et le Wi-Fi est `en1`.
Sur nikopol (MacBook) `en0` est bien le Wi-Fi. Donc une constante `en0` est fausse d'une machine
à l'autre **et** trompeuse sur une seule.

## La règle

On déclare un **nom de service** (`networksetup -listallnetworkservices`), jamais un device, et on
résout service → device **à l'exécution** :

```sh
networksetup -listnetworkserviceorder \
  | sed -n 's/^(Hardware Port: <service>, Device: \(.*\))$/\1/p' | head -1
```

Deux raisons, les deux vérifiées : la numérotation `enX` suit l'ordre d'énumération des
adaptateurs (elle bouge quand on branche/débranche un dock), et elle diffère entre machines.
Résoudre à chaque exécution rend le lien auto-réparateur — le LaunchDaemon `baremetal-link` se
déclenche justement sur `WatchPaths /Library/Preferences/SystemConfiguration`, donc pile sur
l'événement qui renumérote.

## La SSOT, et pourquoi c'est CELLE-LÀ

`hosts/<host>/hardware.nix` → `vmBridgeService` (ndh, commit `7ddd6cef`). Deux consommateurs qui
**doivent** s'accorder sous peine de casse silencieuse :

- `tart.configGenerator.vmRunBridgeInterface` — le mode bridgé de Tart ;
- l'alias `/30` de `baremetal-link` (`flake.nix`, `baremetalLinkVars`).

Ils doivent s'accorder parce que l'autre bout du `/30` est le `lan-br` **du guest** : aliaser le
mauvais adaptateur et les deux bouts ne partagent plus de L2. Le défaut trouvé était exactement
ça — `interface = "en0"` en dur, qui **redéclarait un fait déjà déclaré** (la classe « identifiant
redéclaré » du CLAUDE.md), juste par accident pour nikopol.

⚠️ La description de l'option `vmRunBridgeInterface` disait « e.g. `en0` » alors qu'elle prend un
nom de service — c'est ce commentaire qui a produit la constante. Corrigé.

Autres endroits de ndh qui nomment encore une interface en dur et qui n'ont PAS été audités :
`modules/darwin/socket_vmnet.nix` (`lanInterface = "en0"`, pour toute la flotte), le défaut `en0`
de `bird-daemon.nix`, et la liste de `network-bond.nix` (bond `enable = false` sur bioskop).

See [[fabric-segment-pinning-and-federation-from-nnh]].
