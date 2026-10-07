{
  description = "Flox runtime env catalog: per-workload packages (kdns, tailscale) the flox-nri-plugin injects. The plugin itself now lives in github:seedmatic/flox-nri-plugin (consumed as the flox-runtime input of the rke2lab flake).";

  inputs = {
    flake-commons.url = "flake-commons";
    nixpkgs.follows = "flake-commons/nixpkgs";
    flake-utils.follows = "flake-commons/flake-utils";

    # flox CLI — bundled into the `lock-envs` app (nix run .#lock-envs) via
    # runtimeInputs, so re-locking env manifest.lock files needs no pre-activated
    # flox on PATH. Follows the aggregator's flox (same pin as rke2lab / ndh).
    flox.follows = "flake-commons/flox";

    # Per-workload sources. Adding a new workload package usually means a new
    # input + a new entry in `packages` below; the per-env manifest.toml then
    # references it via `flake = path:.../runtime/flox#<output>`.
    kdns-src = {
      url = "github:lab42/kdns?ref=v0.2.27";
      flake = false;
    };
    # The `headplane` and `headscale` inputs are GONE (2026-09-27): rke2lab removed the mesh
    # manifests domain, so nothing consumes their envs any more. They were the only reason this
    # flake carried an overlay at all — headplane needed a darwin pnpm-deps hash override — and
    # headplane pulled a pnpm dependency set into the lock for a workload nobody deployed.
    # rke2lab is the single owner of the `ndh` pin: this catalog is a branch of
    # rke2lab and part of its world, so it FOLLOWS rke2lab's ndh instead of
    # pinning ndh independently. That keeps the host (path A, rke2lab's
    # services.tailscale) and the mesh (path B, tailscale-prod below) on the
    # exact same ndh rev → the same tailscale fork build, no drift. rke2lab
    # doesn't take this catalog as an input (it references the flox-catalog
    # branch at runtime via the FloxCatalog), and the ndh<->rke2lab edge is
    # already cut in rke2lab (ndh.inputs.rke2lab.follows = ""), so no cycle.
    # This ref names the branch the PROPAGATION READS FROM — the active chantier's branch, not a
    # blessed integration line. `update-flox-envs` GUARDS on it equalling the branch it is run from,
    # so it is what decides whether a checkpoint can reach a cluster at all.
    #
    # ★ Pointing it at the active branch is deliberate, and it costs nothing: the app commits and
    # pushes this catalog on EVERY propagation anyway, so tracking `develop` instead would buy only
    # one thing — a merge into `develop` at every checkpoint. Determinism does not come from the
    # branch name: references resolve at BUILD, so the rev in flake.lock is what reaches a node.
    #
    # ⚠️ The one real hazard is lifecycle, not correctness: left on a RETIRED branch this strands
    # the propagation path. It had rotted onto `feature/nixos-node-substrate` that way. So repoint it
    # when a chantier ends — once per chantier, not per checkpoint.
    rke2lab.url = "rke2lab";
    rke2lab.inputs.flake-commons.follows = "flake-commons";

    # ndh (public) carries manage-tailnet + the tailscale fork
    # (packages.<sys>.tailscale). Follows rke2lab's pin — see above.
    ndh.follows = "rke2lab/ndh";

    # The seed-incluster flake (rke2lab orphan branch) owns the Go
    # controller + ClusterAdoption CRD. We re-export its BINARY below so the
    # cluster-api/seed-incluster FloxEnv installs it into the flox
    # carrier — the controller stops riding a baked node-base OCI image (see
    # rke2lab .claude/seed-incluster-floxenv-plan.md). rke2lab ALSO consumes
    # this branch (its binary + the ClusterAdoption CRD + the ClusterRole it
    # stages into manifest synthesis), so rke2lab is the single owner of the
    # seed-incluster pin — same rule as ndh above. FOLLOW it here instead of
    # re-pinning, or the catalog's binary would drift from the CRD/RBAC rke2lab
    # renders (binary-vs-schema skew). A bump lives in rke2lab's flake.lock; the
    # catalog picks it up when its `rke2lab` input refreshes.
    seed-incluster.follows = "rke2lab/seed-incluster";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
    kdns-src,
    ndh,
    seed-incluster,
    flox,
    ...
  } @ inputs:
    flake-utils.lib.eachSystem [
      "aarch64-darwin"
      "aarch64-linux"
    ] (system: let
      # No overlay: the only one this flake ever defined existed to fix headplane's pnpm deps on
      # darwin, and headplane is gone.
      pkgs = import nixpkgs {
        inherit system;
      };
      lib = pkgs.lib;

      # A workload's version is NOT a second literal: a github input's `?ref=vX`
      # is the ONE place the tag lives (a flake input's ref can't interpolate a
      # variable, so the ref itself is irreducible), and flake.lock mirrors it as
      # `nodes.<input>.original.ref`. Read it back here — strip the leading `v` —
      # so a bump touches only the input ref (the lock refresh follows). Used for
      # source-only inputs (kdns); an upstream-flake input carries its own version.
      lockedVersion = input:
        lib.removePrefix "v"
        (builtins.fromJSON (builtins.readFile ./flake.lock)).nodes.${input}.original.ref;

      # ---- NRI plugin: MOVED OUT ---------------------------------------
      # The flox-nri-plugin (+debug) is no longer built here — it lives in the
      # fork repo github:seedmatic/flox-nri-plugin and is consumed as the rke2lab
      # flake's `flox-runtime` input. This flake is now the env CATALOG only.
      # `doCheck = false` on the workloads below: lab-only build; upstream tests
      # add build time without catching anything the rke2lab use case cares about.

      # ---- kdns -------------------------------------------------------
      mkKdns = {
        packageName,
        debug,
      }:
        pkgs.buildGoModule rec {
          pname = packageName;
          version = lockedVersion "kdns-src";

          src = kdns-src;

          vendorHash = "sha256-2zPV+hatBEll8uMVaQ7WYGI1gBfugW8eJNwI04z2s7A=";

          env.CGO_ENABLED = "0";

          doCheck = false;

          nativeBuildInputs = lib.optionals debug [pkgs.makeWrapper];

          ldflags =
            lib.optionals (!debug) [
              "-s"
              "-w"
            ]
            ++ [
              "-extldflags=-static"
              "-X github.com/lab42/kdns/cmd.Version=${version}"
              "-X github.com/lab42/kdns/cmd.Commit=${src.rev or "dev"}"
              "-X github.com/lab42/kdns/cmd.Date=1970-01-01T00:00:00Z"
            ];

          gcflags = lib.optionals debug ["all=-N -l"];

          postFixup = lib.optionalString debug ''
            wrapProgram "$out/bin/kdns" \
              --prefix PATH : ${lib.makeBinPath [pkgs.delve]}
          '';

          meta = with lib; {
            description =
              if debug
              then "Kubernetes DNS controller with mDNS support (debug build)"
              else "Kubernetes DNS controller with mDNS support";
            homepage = "https://github.com/lab42/kdns";
            license = licenses.mit;
            platforms = platforms.unix;
          };
        };

      kdns = mkKdns {
        packageName = "kdns";
        debug = false;
      };

      kdns-debug = mkKdns {
        packageName = "kdns-debug";
        debug = true;
      };

      # ---- tailscale --------------------------------------------------
      # The fork tailscale/tailscaled (CNAME extra_records + SSH port-2222),
      # taken from ndh — ndh.packages.<system>.tailscale re-exports its
      # tailscaleOverlay build, so the in-cluster mesh tailscaled runs the
      # SAME patched binary as the operator host (path A). This is the point
      # that matters for CNAME: the control plane pushes DNSRecord CNAME
      # extra_records that only the patched resolver in this daemon honors.
      #
      # `doCheck = false`: lab-only build (same rationale as the others).
      # Tailscale's atomicfile_test specifically fails inside the nix sandbox
      # because the build tmpdir path can exceed the unix-socket name limit
      # (TestDoesNotOverwriteIrregularFiles).
      tailscale-prod = ndh.packages.${system}.tailscale.overrideAttrs (_: {
        doCheck = false;
      });

      # No delve-WRAPPING here — deliberately. In our nixpkgs, tailscale/tailscaled is ONE
      # combined binary dispatched by argv[0] basename, with $out/bin/tailscale a symlink to it
      # (verified on-node: `exec -a tailscale <bin> version` → CLI, `-a tailscaled` → daemon).
      # makeWrapper can't wrap that: its wrapper is a #! script, and for a script target the
      # kernel IGNORES exec -a's argv[0] and sets $0 to the script's own path → the binary sees
      # argv[0]="tailscaled" and `tailscale up` runs as the DAEMON ("does not take non-flag
      # arguments"). Wrapping added nothing anyway: the mesh/tailscale-debug flox env already
      # ships delve on PATH (manifest.toml [install.delve]) for `dlv attach $(pgrep tailscaled)`.
      # So keep the binaries PRISTINE — the debug value is the unstripped + `-N -l` build below.
      tailscale-debug = tailscale-prod.overrideAttrs (old: {
        pname = "tailscale-debug";
        dontStrip = true;
        ldflags = lib.filter (f: f != "-s" && f != "-w") (old.ldflags or []);
        gcflags = (old.gcflags or []) ++ ["all=-N -l"];
      });

      lockEnvsApp = pkgs.writeShellApplication {
        name = "lock-envs";
        runtimeInputs = [flox.packages.${system}.default pkgs.coreutils pkgs.git pkgs.jq];
        text = ''
          root="environment.d"
          if [[ ! -d "$root" ]]; then
            echo "run from the flox-catalog repo root (no ./$root here)" >&2
            exit 1
          fi
          if [[ "$#" -gt 0 ]]; then
            envs=("$@")
          else
            mapfile -t envs < <(cd "$root" && for m in */*/manifest.toml; do echo "''${m%/manifest.toml}"; done)
          fi
          # Meaningful projection = the lock minus the volatile per-package locked-url.
          proj='del(.packages[]."locked-url")'
          rc=0
          bumped=()
          paths=()
          for e in "''${envs[@]}"; do
            d="$root/$e"
            if [[ ! -f "$d/manifest.toml" ]]; then
              echo "SKIP $e (no manifest.toml)"
              continue
            fi
            printf 'lock %s ... ' "$e"
            if ! (cd "$d" && flox lock-manifest manifest.toml) >"$d/manifest.lock.tmp" 2>"$d/.lockerr"; then
              echo "FAILED"
              sed 's/^/    /' "$d/.lockerr"
              rm -f "$d/manifest.lock.tmp" "$d/.lockerr"
              rc=1
              continue
            fi
            rm -f "$d/.lockerr"
            new_proj=$(jq -S "$proj" "$d/manifest.lock.tmp")
            if old=$(git show "HEAD:$d/manifest.lock" 2>/dev/null); then
              old_proj=$(printf '%s' "$old" | jq -S "$proj")
            else
              old_proj=""   # untracked -> a new env, always a real bump
            fi
            if [[ -n "$old_proj" && "$new_proj" == "$old_proj" ]]; then
              # only locked-url churned -> drop it, restore the committed file
              rm -f "$d/manifest.lock.tmp"
              git checkout -q -- "$d/manifest.lock" 2>/dev/null || true
              echo "unchanged (churn dropped)"
            else
              mv "$d/manifest.lock.tmp" "$d/manifest.lock"
              git add -- "$d/manifest.lock"
              bumped+=("$e")
              paths+=("$d/manifest.lock")
              echo "BUMPED ($(wc -c <"$d/manifest.lock") bytes)"
            fi
          done
          if [[ "''${#paths[@]}" -gt 0 ]]; then
            git commit -q -m "chore(lock): re-lock envs (''${bumped[*]})" -- "''${paths[@]}"
            echo "committed ''${#paths[@]} env(s): ''${bumped[*]}"
          else
            echo "no real bumps — nothing to commit"
          fi
          exit "$rc"
        '';
      };
    in {
      apps.lock-envs = {
        type = "app";
        program = "${lockEnvsApp}/bin/lock-envs";
        meta.description = "Re-lock env manifest.lock files under environment.d/ via flox lock-manifest (run from the catalog repo root)";
      };

      # relock — THIS flake's locks, by the shared implementation in nix-flake-commons'
      # `lib.mkRelockApp`. This flake is the `flox-catalog` orphan branch of rke2lab, so it names
      # its `branch`: the slug alone would take any rke2lab checkout for this one. No `consumers`:
      # no flake pins this branch; rke2lab's own relock re-pins it here (its catalog hop).
      apps.relock = {
        type = "app";
        program = "${
          inputs.flake-commons.lib.mkRelockApp {
            inherit pkgs;
            name = "flox-catalog";
            slug = "seedmatic/rke2lab";
            url = "https://github.com/seedmatic/rke2lab.git";
            branch = "flox-catalog";
          }
        }/bin/relock";
        meta.description = "Reconcile THIS flake's locks: bump each input, DROP any bump that moves no exported derivation, push — impl: nix-flake-commons lib.mkRelockApp";
      };

      packages = {
        inherit kdns kdns-debug;

        # Prod = upstream stripped build; debug = unstripped + `-N -l` + delve
        # wrapper. The Java side (FloxDebugPolicy.resolveFloxEnvironment) flips
        # the prod container's flox env to the `*-debug` env when debug is on,
        # which causes the NRI plugin to mount the debug-package binary in
        # place of the prod one — so port mappings and pod identity stay
        # untouched.
        tailscale = tailscale-prod;
        inherit tailscale-debug;

        # The CI render toolchain the flox NRI plugin injects into the Tekton
        # render-publish step (the cicd/maven FloxEnv references these via
        # floxcatalog:catalog#jdk25 / #maven / #shfmt / #shellcheck). Straight
        # from nixpkgs — the SAME attributes the rke2lab build toolchain uses
        # (mavenToolchain in the root flake), and both flakes follow
        # flake-commons/nixpkgs, so the in-cluster `clean verify` runs the
        # spotless gates at the dev versions — spotless version-checks the shfmt
        # binary. `which` (which spotless shells out to, to locate shfmt) comes
        # from the catalog with `outputs: all`, not here: the default flake output
        # resolution locks its `-info` output, missing the binary.
        inherit (pkgs) jdk25 maven shfmt shellcheck;

        # Re-exported from ndh (public): the in-cluster stale-tailnet-device prune Job installs it
        # from this catalog via a FloxEnv and drives its --format=json JSON Lines output.
        # aarch64-linux for the node; darwin rides along for local parity.
        manage-tailnet = ndh.packages.${system}.manage-tailnet;

        # Re-exported from ndh (public): the git sops clean/smudge filter as a
        # self-contained package (the SSOT filter def, sops.sh dispatcher + sops.d
        # config). The toolchains/git-sops FloxEnv installs it so a checkout smudges
        # `.secrets` in the render pod exactly as on the operator host — that is what
        # makes the in-cluster render secret-FULL (the cellar fills from `.secrets`)
        # rather than secret-blind. aarch64-linux for the pod; darwin for parity.
        git-sops-filter = ndh.packages.${system}.git-sops-filter;

        # Re-exported from the seed-incluster flake (rke2lab orphan
        # branch): the in-cluster controller BINARY — NOT the `-image` OCI output
        # the node-base baking used. The cluster-api/seed-incluster
        # FloxEnv installs it via floxcatalog:catalog#seed-incluster so
        # the flox carrier runs it from PATH, replacing the baked image.
        # aarch64-linux for the node; darwin rides along for local parity.
        seed-incluster =
          seed-incluster.packages.${system}.seed-incluster;

        default = kdns;
      };

      defaultPackage = kdns;
    });
}
