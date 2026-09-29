---
name: flake-commons-cluster-dedup-chantier
description: "Follow-up chantier — the ~12k-node flake.lock explosion: nix-flake-commons aggregates third-party flakes each dragging a recursive nix/devenv/cachix cluster; dedup at the source"
metadata:
  node_type: memory
  type: project
---

**FOLLOW-UP CHANTIER (known, deferred) — clean up the references pulled in via `nix-flake-commons`.** Surfaced 2026-09-03 while investigating a slow `nix run .#deploy`.

**Symptom:** rke2lab's `flake.lock` is **~12,100 nodes** — ~470 `devenv_N` + ~505 `nix_N` + ~366 `cachix_N` + ~254 duplicate `nixpkgs-23-11_N`. Bloats `nix flake update` + cold eval (the 9k-line haskell instantiation noise in a `-Lvv` run). **PRE-EXISTING** (12124 nodes before this branch's tailnet work — the ndh bump added exactly +1 node; NOT a tailnet regression). Steady-state eval is still ~5.7s (cached), and the deploy is NOT functionally broken — the perceived "deploy hang" was a cold run re-fetching git's inherently huge darwin closure (see below).

**ROOT:** `flake-commons` aggregates third-party flakes that EACH drag the mutually-recursive `nix`↔`devenv`↔`cachix` cluster, un-deduped (each `devenv_N` pulls a `nix_N` which pulls `devenv`/`cachix`…, combinatorial). The cluster-pulling flake-commons inputs (verified via lock BFS): **`determinate`, `incus-compose`, `ripvcs`, `socket-vmnet`, `zen-browser`** (+ flake-commons' own direct `cachix`/`devenv`/`nix`). rke2lab references NONE of the 5 (0 refs in flake.nix). BUT **ndh uses `socket-vmnet` + `ripvcs`** (`inputs.socket-vmnet.packages` / `inputs.ripvcs.packages`, ndh flake.nix ~L174/181) — so they can't be blanket-cut without breaking ndh.

**Why it's an UPSTREAM (flake-commons) fix, not rke2lab-local:** the recursion means a SINGLE uncut path keeps the whole cluster; and the pullers are third-party flakes flake-commons owns. The clean fix = in `nix-flake-commons`: make those inputs' `nix`/`devenv`/`cachix` follow one shared node (nested follows) OR stop aggregating the heavy dev-shell tooling — benefiting ALL seedmatic flakes (rke2lab, ndh, flox-runtime, flox-controller). User's framing: "c'est les devshells qui doivent être coupé" + "supprimer devenv des inputs et son utilisation des flakes" + "tous les flakes de seedmatic".

**DONE so far (rke2lab-local partial, `a08caedaf`):** cut flake-commons' DIRECT `cachix`/`devenv`/`nix` via `follows = ""` (rke2lab uses none of them). Removed only ~27 nodes (12125→12098) — the third-party pullers keep the cluster. Kept as a correct-but-partial down payment while the upstream chantier is pending.

**The 13GB git red herring (same investigation):** rke2lab's `pkgs.git` (= `flake-commons/nixpkgs` git) has a **12.8GB closure** on darwin (pulls apple-sdk-14.4 + clang-wrapper + cctools — a nixpkgs-darwin quirk where git references the full SDK). deployApp ALWAYS had `pkgs.git` in runtimeInputs, and manage-tailnet SHARES the same git (one git in the closure, not duplicated). So a cold `nix run .#deploy` re-fetches ~13GB → looks hung. A separate potential win: switch the deployApp's git to `gitMinimal` (lean closure) — orthogonal to the tailnet work, not yet done.

See [[tailnet-prune-on-incus-renewal]] [[manifests-publish-in-cluster-render]].
