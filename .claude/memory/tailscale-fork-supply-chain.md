---
name: tailscale-fork-supply-chain
description: "How the patched tailscale (CNAME + SSH-2222) reaches rke2lab — fork branch, ndh SSOT, two consumption paths, bump runbook"
metadata: 
  node_type: memory
  type: project
  originSessionId: 99cf515e-428e-4300-b5c1-688882542a04
  modified: 2026-09-09T11:04:13.589Z
---

rke2lab runs a **patched tailscale/tailscaled** — two patches: **CNAME in `DNSRecord.ExtraRecords`** (local resolver + tsdial chase CNAME extra-records the control plane pushes; the reason the fork must reach the **mesh** daemon) and **SSH interception on port 2222**.

**Fork** = `github:nxmatic/tailscale`, ONE branch **`nxmatic/integration/1.102`** = upstream `release-branch/1.102` + the 2 patches (+ headscale lab scaffold). Local checkout `/var/lib/git/tailscale/tailscale`. Consolidated 2026-09-09 from the two old branches `nxmatic/feature/extra-records-cname` + `nxmatic/labs/ssh-2222` (both DELETED). Upstream ships `flake.nix`+`flakehashes.json` (vendorHash+go SRI); our patches don't touch `go.mod` so vendorHash stays valid across an upstream rebase.

**ndh = single source of truth for the build** (`seedmatic/ndh`, branch develop): input `tailscale-fork` → `overlays/tailscale.nix` (`tailscaleOverlay`, version-stamps: `shortStamp`=upstream VERSION.txt for headscale skew check, `longStamp`=`<ver>-nxmatic-integration-<rev>`). Exposes `overlays.tailscaleOverlay`, `legacyPackages.<sys>.tailscale`, and `packages.<sys>.tailscale` (the indirection added 2026-09-09 = `inherit (systemPkgs) tailscale`, systemPkgs already overlaid).

**Two consumption paths** (both must be wired; easy to wire only one):
- **Path A — host**: rke2lab pins ndh; `services.tailscale.package` comes from ndh's overlaid `pkgsFor` (`legacyPackages`). bioskop (darwin) + bioskop-nixos both `services.tailscale.enable`. PROVEN live: `tailscaled --version` → `1.102.3-nxmatic-integration-7b96247`.
- **Path B — mesh**: `flox-catalogue` branch `tailscale-prod = ndh.packages.${system}.tailscale.overrideAttrs(doCheck=false)`; FloxEnv install ref `floxcatalog:catalogue#tailscale`; `FloxCatalog` CR + `GitRepository(flox-catalogue)` → flox-controller resolves the catalogue flake output at the reconciled commit (NOT `environment.d/*/manifest.lock` — that lock is local-activation hygiene only). **Was the trap**: flox-catalogue used `pkgs.tailscale` (nixpkgs STOCK) so the mesh daemon — the one the CNAME patch exists for — silently ran unpatched. Fixed 2026-09-09; mesh `out` == host `services.tailscale.package` store path (identical binary).

**Single ndh pin (topology).** Only rke2lab pins `ndh`. flox-catalogue (a branch of rke2lab) takes `rke2lab.url = github:seedmatic/rke2lab/feature/nixos-node-substrate` + `ndh.follows = "rke2lab/ndh"` → host and mesh can't drift onto different fork commits. No flake cycle (rke2lab references flox-catalogue only at runtime via FloxCatalog; `ndh.inputs.rke2lab.follows = ""` cuts the mutual edge). Consequence: flox-catalogue's ndh must be ≥ the commit that added `packages.<sys>.tailscale` (`fd404836`), so bump order = ndh → rke2lab(`nix flake update ndh`) → flox-catalogue(`nix flake update rke2lab`).

**Bump runbook** (manual until automated): (1) fork: rebase `nxmatic/integration/1.X` onto new `upstream/release-branch/1.X`, `push --force-with-lease`; (2) ndh: `nix flake update tailscale-fork` + `nix build .#tailscale` (validates vendorHash) + push develop; (3) rke2lab: `nix flake update ndh` + commit flake.lock + push (host path A); (4) flox-catalogue: `nix flake update ndh` + optional re-lock `./lock-envs.sh mesh/tailscale mesh/tailscale-debug` + push (mesh path B); (5) deploy: Flux reconciles flox-catalogue GitRepo → FloxCatalog → FloxEnv re-resolves → restart mesh pods.

**Graved**: `docs/architecture/nixos-substrate/tailscale-fork-supply-chain.adoc` (C4 context + build-flow figures, ❌/✅ patterns, runbook, troubleshooting). Commits (2026-09-09): fork `7b962478b`; ndh `d784979e` (repoint) + `91358b8f` (rke2lab dataplan bump) + `fd404836` (packages.<sys>.tailscale output); rke2lab `fdef9324b` (host lock) + `bba3e4d9a` (dataplan boot-log fix — see below) + `f79a61f83` (doc) + `95a1e3d77` (ndh→fd404836) + `d69ce6da5` (doc topology); flox-catalogue `dfd84dcea` (mesh consume) + `e64e05e63` (re-lock) + `3a20e6d38` (follows rke2lab/ndh).

Side-fix during this chantier: `dataplan.json` had an OSGi boot-log line leaked at line 1 (regen `java -jar … dataset export > $out` captured FrameworkLauncher stdout) → `fromJSON` broke every NixOS host's disko zfs datasets (bioskop-nixos rebuild). Stripped + hardened regen with `sed -n '/^{/,$p'` (rke2lab `bba3e4d9a`). See [[flox-env-migration-design]] [[flox-controller-build-deploy-state]].
