---
name: network-blueprint-segments-plan
description: "Locked plan — rke2lab publishes network SEGMENTS it owns; ndh owns home hosts+segments; nnh (ex-flowlab) consumes. Ownership-clean split, Bbox ground truth."
metadata:
  node_type: memory
  type: project
---

# Goal
Make the network→ASN / segment mapping single-source, so **nnh** (ex-flowlab, the
netflow collector; GitHub repo renamed `seedmatic/flowlab`→`seedmatic/nnh`) stops
hardcoding `selfBase`/`gatewayNetworks`/`asns` in `hosts/collector.nix` and derives
everything from `ndh.catalog`.

# OWNERSHIP-CLEAN SPLIT (revised vs the original flowlab handoff)
rke2lab limits itself to what it OWNS. There are already TWO bbox reconcilers:
rke2lab (cluster nodes) + ndh (`bbox-reconcile.nix`, home hosts). So:

- **rke2lab** gains ONLY the `segments` it owns (asn 65010/65020). Does NOT declare
  home hosts. `BlueprintRowEnumerator`/reconciler unchanged (cluster only). Non-cluster
  devices are OBSERVED, never reconciled by rke2lab.
- **ndh** owns home hosts + home segments; UNIONs rke2lab's cluster segments into one
  `netplan.segments` output (exactly like it already unions hosts).
- **nnh** consumes `ndh.catalog.netplan.{hosts,segments}` + resolves the live Bouygues
  v6 /64 itself.

# LOCKED DECISIONS
- Host schema gains `ownership ∈ {corporate, personal}` (default personal). corporate =
  `nikopol-vzhost`/`nikopol-vz` (the corp Mac + its vz layer). personal = everything else.
- Mobiles (iPad/iPhone/Pixel) NOT declared — randomized MACs can't be pinned; they fall
  in the `home-dynamic` segment.
- Segment naming (family `home-*` for ndh; `<cluster>-*` for rke2lab):
  - `192.168.1.0/27`  home-dynamic  65000   (the DHCP pool .1-.30; roaming/transient)
  - `192.168.1.32/27` home-hosts    65000
  - `192.168.1.64/27` home-media    65000
  - `192.168.1.96/27` nikopol-cluster-lan 65010
  - `192.168.1.128/27` bioskop-cluster-lan 65010
  - `192.168.1.160/27` nikopol-lb   65010
  - `192.168.1.192/27` bioskop-lb   65010
  - `10.42.0.0/16` pod 65010 ; service-cidr 65010 ; `10.80.0.1/32` gateway 65020
  - `100.64.0.0/10` tailnet 65000 ; `fd96:6924:3693::/48` ULA (65010) ; fe80::/10 65000
- AS names: 65000 home, 65010 rke2-cluster, 65020 gateway.
- IPv6 Bouygues PD `2001:861:3243:75e0::/64` = VARIABLE, NOT committed; resolved LIVE by nnh.

# WHERE TO CODE
- rke2lab blueprint export: `osgi/domains/netplan/netplan-bdd/.../NetplanBlueprintScenario.java`
  — record `NetworkBlueprintMetadata(clusters,nodes,macPatterns,nodeTypes,addressing)`
  (SeedCodec serialises by field name → JSON keys). ADD a `List<Segment> segments` field
  (+ `Segment(cidr,name,asn)` record + optional `asns` map). Additive → existing keys
  byte-identical, flake/ndh unaffected. Slices derivable from `ClusterNetworkBlueprint`
  (`bp.lan().nodeCidr()`, `bp.lan().lbCidr()`, superNetworkCidr, ULA); pod/service/gateway
  are constants (pod 10.42.0.0/16, gateway 10.80.0.1 — see CiliumAdvancedManifestsUnit).
- Regen: `nix build .#networkBlueprintJson && cp result network-blueprint.json` (or
  `nix run .#regen-blueprint`). nix is system-provided; flox NOT needed for regen.
- ndh: `catalog/default.nix` — (a) enrich `netplan.lan.hosts` (add `ownership`; promote
  audio/TV/cast from `lan-ignored-reservations.yaml` to first-class `hosts` with real IPs;
  add vz `ownership=corporate`); fix the wrong "nikopol-vz has no reservation" comment
  (l.93-99 — it DOES, see below). (b) add `netplan.segments` = home segments ∪ projected
  `networkBlueprint.segments`. Keep catalog pure-data / aarch64-safe.
- step 6: clean stale headscale tags (`service`/`gateway`/`server`) — ndh lot, non-blocking
  (headscale is dormant; fleet is on Tailscale SaaS today).

# BBOX GROUND TRUTH (live, 2026-08-16, via /api/v1/dhcp/clients + /hosts; creds lan.bbox.* in .secrets)
- 24 static reservations. Cluster nodes match blueprint 1:1 (MAC 10:66:6a:4c:<cluster>:<node>).
- Non-cluster reserved: nikopol-vzhost .1 (mac 4a:04:df:ff:a8:de, RANDOMIZED, lives at .33),
  nikopol-vz .65 (84:2f:57:d4:36:be), zecoute .5, huematic .6, pop-screen .7, vertdegris .8,
  pop-cast .12, bioskop .129, bioskop-nixos .130, bioskop-wifi .158, nikopol .33 (86:b7:...),
  nikopol-nixos .34.
- DHCP dynamic pool = 192.168.1.1–.30 (240min). Statics overlap it (.1,.5-.12) = smell but
  decoupled from the segments work. /24 carved into 8 /27 slices; slices 1,2,7 mostly free.
- Bouygues v6 /64 confirmed: 2001:861:3243:75e0::/64.

# TAILSCALE (mammoth-skate) — DIAGNOSED
NO headscale server up; fleet on Tailscale SaaS (tailnet stephane.lacoin@gmail.com,
MagicDNS mammoth-skate.ts.net). nikopol re-auth was NODE-KEY EXPIRY (180d default;
new expiry 2027-02-12 = today+180). No node is tagged on SaaS (all user-owned) → no tag
loss. Fix (user, admin console): disable key expiry for the fleet nodes.

# WORKTREES
- rke2lab: `/Volumes/git-worktree-store/nxmatic/rke2lab.d/feature/network-blueprint-segments`
  (branch feature/network-blueprint-segments off feature/nixos-node-substrate).
- ndh: `/Volumes/git-worktree-store/seedmatic/ndh.d/feature/network-catalog-segments`
  (branch feature/network-catalog-segments off develop).
- Workspace: `.../rke2lab.d/feature/network-blueprint-segments.code-workspace` (ndh = 2nd folder).
- flox `[include]` is BROKEN on nikopol for both (bioskop absolute paths / missing .flox.d) —
  use `nix` directly for builds/evals.
- HUB CHANGE PENDING: generalised worktree skill step 3 (sops re-smudge now scans
  .gitattributes via `git check-attr`) — publish to claude-hub at session end.

# AS-BUILT (supersedes the plan bits above where they differ)
- Single source is an ENUM (codebase convention; contract-pure), not a class/interface:
  `ClusterAsn { RKE2_CLUSTER(65010,"rke2-cluster"), GATEWAY(65020,"gateway") }` with
  number()/asName(), in netplan-contract. pod/service CIDR + GATEWAY_ADDRESS are
  `public static final` constants on `ClusterNetworkBlueprint` (record, beside ULA_PREFIX)
  — no new exported type, so no extra spec-coverage. `ClusterAsn` documented in
  docs/architecture/patterns/netplan-blueprint-single-source.adoc (spec-coverage).
- Cilium/NodeEnv migrated to read those; CiliumConfig valuesContent → Java text block.
- Blueprint export gains `segments` (List<Segment(cidr,name,asn)>) + `asns` (Map, derived
  from ClusterAsn.values()). network-blueprint.json REGENERATED (nix build via `path:`
  flakeref — the git+file fetcher fails on the relativeworktrees extension; `path:` bypasses
  it). Verified: 9 segments + asns present, additive (+51/-0).
- ndh: NO "observed" list — ndh reconciles ALL its LAN hosts. media (kind="media") + vz×2
  (kind="vz-host", ownership="corporate") added to lan.hosts; `ownership` on every host
  (personal default); media removed from lan-ignored-reservations.yaml (now []). Added
  `netplan.segments` (home spans ∪ `networkBlueprint.segments or []`) + `netplan.asns`
  (`networkBlueprint.asns or {}` // {"65000"="home"}). Parse+stub-eval OK (segCount 9 home + N).
- Home segment names: home (/24 + broad private fallbacks = the old flowlab selfBase),
  home-dynamic (192.168.1.0/27, the DHCP pool), tailnet (100.64/10). v4/v6 fallbacks → home.

# REMAINING / FOLLOW-UPS
- Verify manifests-core compiles + CiliumConfig text block synthesises byte-identical YAML
  (needs a reactor/seedMasterJar build; netplan build alone doesn't cover manifests-core).
- Bump ndh's rke2lab flake input to feature/network-blueprint-segments so
  networkBlueprint.segments/asns actually populate (until then `or []`/`or {}` → empty).
- Commit both branches. Publish the hub worktree-skill change to claude-hub.
- nnh (ex-flowlab, other session): consume ndh.catalog.netplan.{hosts,segments,asns}; drop
  hardcoded selfBase/gatewayNetworks/asns; resolve the live Bouygues /64 itself.
- CHORE (separate worktree): java-bbox-api-client should ship a CLI via its nix flake, and
  ndh's bbox-reconcile should CALL that CLI (one impl of the Bbox contract, shared by
  seed-master + ndh) instead of the hand-rolled curl/yq shell script.
- CHORE (separate worktree): migrate ndh flox `[include]` from absolute bioskop paths to
  relative (like rke2lab); refactor the remaining YAML-string builders
  (Headscale/Headplane/Envoy manifests) to snakeyaml/Jackson or cdk8s typed constructs.
- DONE: `nix-repo-setup` skill authored in the hub (.claude/hub/skills/nix-repo-setup) +
  the worktree skill re-smudge generalisation — both committed on this branch (hub commits
  68b2e4a1, 94ad4fcc) to publish up together at sync.
- BLOCKED / DEFER TO A NIX-CAPABLE CHECKOUT (bioskop): apply `nix-repo-setup` to rke2lab
  (treefmt.nix + flake `formatter` + treefmt-nix input + .githooks/pre-commit, then
  `nix flake lock` + `nix fmt` normalize commit). Can't do it in THIS worktree: rke2lab's
  bare carries the `extensions.relativeworktrees` git extension, so nix can't open the
  worktree (`nix flake lock`/`nix fmt` fail; `path:` is read-only so can't write flake.lock).
  ndh is unaffected (its bare lacks the extension) — nix fmt already gates ndh. NB: the
  global `~/.config/git/hooks` dispatcher execs the on-disk `.githooks/pre-commit`, so do NOT
  drop that file into rke2lab until the `formatter` is locked & verified — an unlocked hook
  blocks every commit.
