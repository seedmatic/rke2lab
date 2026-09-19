---
name: store-name-prefix-ssot-flake-commons
description: Chantier — remonter la logique de préfixe store-name (io.seedmatic.<repo>) dans nix-flake-commons, partagée par ndh/rke2lab/flox-*
metadata:
  node_type: memory
  type: project
  originSessionId: a38c4453-c630-4e3d-afec-eca508d52df3
  modified: 2026-09-17T17:46:36.714Z
---

Chantier (2026-09-17, DESIGN tranché, code PAS commencé — séquencé APRÈS le DAX de [[nerd-nixos-image-build-slow-not-zfs-on-zfs]]).

**Constat :** le préfixe store-name (pour repérer nos derivations dans `/nix/store`) est **dupliqué par repo** :
- ndh : `mkNdhStoreApiFor` (préfixe `io.seedmatic.ndh`) — `let` INTERNE à `flake.nix` (~l.218-297), **non exporté** ; fournit `store.{writeShellScript,writeText,runCommand,writeShellScriptBin,installScript,installBinScript,mkLaunchdLabel,prefixedName}`. Seuls les packages qui passent par `ndh.store.*` sont préfixés ; ceux en `pkgs.*` brut, non.
- flox-controller : **sa propre** logique (`io.seedmatic.<asset>`, commentaire mentionne l'ancien `io.nxmatic`).
- rke2lab / flox-catalogue : pas de logique de préfixe.

**Le `io.nxmatic` que l'user voyait = store paths PÉRIMÉS** (38× `io.nxmatic.nix-darwin-home-*` sur le mac, builds d'avant le rename `99bf2000` io.nxmatic→io.seedmatic.ndh). Source déjà propre ; partiront au darwin-rebuild + GC. RIEN à corriger dans les sources.

**Design tranché :** remonter un helper **paramétré** `mkStoreApi { prefix, pkgs }` dans **`nix-flake-commons`** (`github:seedmatic/nix-flake-commons/develop`, déjà l'input SSOT des pins pour ndh & co). Chaque repo l'instancie avec son préfixe (`io.seedmatic.ndh`, `io.seedmatic.rke2lab`, …). ndh remplace `mkNdhStoreApiFor` inline par celui de commons ; flox-controller idem ; rke2lab/flox-catalogue l'adoptent. Tous ont déjà ndh en input, mais **commons est le bon foyer** (logique partagée, pas domaine ndh).

**Étapes :** (1) `lib.mkStoreApi` dans flake-commons ; (2) ndh : `flake-commons.lib.mkStoreApi { prefix="io.seedmatic.ndh"; }` ; (3) flox-controller + rke2lab + flox-catalogue adoptent avec leur préfixe. See [[nerd-nixos-image-build-slow-not-zfs-on-zfs]].
