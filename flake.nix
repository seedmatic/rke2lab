{
  description = "Flox runtime env catalog: per-workload packages (kdns, headplane, headscale, tailscale) the flox-nri-plugin injects. The plugin itself now lives in github:seedmatic/flox-nri-plugin (consumed as the flox-runtime input of the rke2lab flake).";

  inputs = {
    flake-commons.url = "github:seedmatic/nix-flake-commons/develop";
    nixpkgs.follows = "flake-commons/nixpkgs";
    flake-utils.follows = "flake-commons/flake-utils";

    # Per-workload sources. Adding a new workload package usually means a new
    # input + a new entry in `packages` below; the per-env manifest.toml then
    # references it via `flake = path:.../runtime/flox#<output>`.
    kdns-src = {
      url = "github:lab42/kdns?ref=v0.2.27";
      flake = false;
    };
    # Upstream headplane flake — its overlay carries the darwin pnpm-deps hash
    # override that prod (`pkgs.headplane`) needs. The debug re-derivation below
    # reuses THIS input as its `src` (a flake input is also a source tree), and
    # reads its `version` from package.json — so the v0.7.0 tag lives in ONE
    # place (this ref), not duplicated across a second `-src` input + a literal.
    headplane = {
      url = "github:tale/headplane?ref=v0.7.0";
      inputs.flake-utils.follows = "flake-utils";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    # Upstream headscale flake, v0.29.3 (v0.28.0 had a startup memory runaway —
    # ~600Mi/s → OOM; v0.29.3 plateaus ~28MB).
    headscale = {
      url = "github:juanfont/headscale?ref=v0.29.3";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    # rke2lab is the single owner of the `ndh` pin: this catalog is a branch of
    # rke2lab and part of its world, so it FOLLOWS rke2lab's ndh instead of
    # pinning ndh independently. That keeps the host (path A, rke2lab's
    # services.tailscale) and the mesh (path B, tailscale-prod below) on the
    # exact same ndh rev → the same tailscale fork build, no drift. rke2lab
    # doesn't take this catalog as an input (it references the flox-catalogue
    # branch at runtime via the FloxCatalog), and the ndh<->rke2lab edge is
    # already cut in rke2lab (ndh.inputs.rke2lab.follows = ""), so no cycle.
    rke2lab.url = "github:seedmatic/rke2lab/feature/nixos-node-substrate";
    rke2lab.inputs.flake-commons.follows = "flake-commons";

    # ndh (public) carries manage-tailnet + the tailscale fork
    # (packages.<sys>.tailscale). Follows rke2lab's pin — see above.
    ndh.follows = "rke2lab/ndh";

    # The rke2-adoption-controller flake (rke2lab orphan branch) owns the Go
    # controller + ClusterAdoption CRD. We re-export its BINARY below so the
    # cluster-api/rke2-adoption-controller FloxEnv installs it into the flox
    # carrier — the controller stops riding a baked node-base OCI image (see
    # rke2lab .claude/rke2-adoption-controller-floxenv-plan.md). Follows our
    # nixpkgs/utils so it dedups with the rest of the catalog.
    rke2-adoption-controller.url = "github:seedmatic/rke2lab/rke2-adoption-controller";
    rke2-adoption-controller.inputs.nixpkgs.follows = "nixpkgs";
    rke2-adoption-controller.inputs.flake-utils.follows = "flake-utils";
    rke2-adoption-controller.inputs.flake-commons.follows = "flake-commons";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
    kdns-src,
    headplane,
    headscale,
    ndh,
    rke2-adoption-controller,
    ...
  }:
    flake-utils.lib.eachSystem [
      "aarch64-darwin"
      "aarch64-linux"
    ] (system: let
      pkgs = import nixpkgs {
        inherit system;
        overlays = [self.overlays.default];
      };
      lib = pkgs.lib;

      # A workload's version is NOT a second literal: a github input's `?ref=vX`
      # is the ONE place the tag lives (a flake input's ref can't interpolate a
      # variable, so the ref itself is irreducible), and flake.lock mirrors it as
      # `nodes.<input>.original.ref`. Read it back here — strip the leading `v` —
      # so a bump touches only the input ref (the lock refresh follows). Used for
      # source-only inputs (kdns); upstream-flake inputs (headscale) carry their
      # own version, and headplane derives it from package.json.
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

      # ---- headscale --------------------------------------------------
      # Sourced from the upstream juanfont/headscale flake — they pin the Go
      # toolchain themselves (main is on Go 1.26 which nixpkgs 1.25.9 can't
      # satisfy). Two outputs:
      #   - prod: upstream as-is (stripped, smallest binary, normal latency)
      #   - debug: overrideAttrs to drop `-s -w`, disable strip, build with
      #     `-N -l` (no inlining/optimization), wrap with delve in PATH so the
      #     shell sidecar can `dlv attach $(pgrep headscale)` with full
      #     source-level visibility through the shared PID namespace.
      headscale-prod =
        (headscale.packages.${system}.headscale or headscale.packages.${system}.default)
        .overrideAttrs (_: {
          doCheck = false;
        });

      headscale-debug = headscale-prod.overrideAttrs (old: {
        pname = "headscale-debug";
        dontStrip = true;
        ldflags = lib.filter (f: f != "-s" && f != "-w") (old.ldflags or []);
        gcflags = (old.gcflags or []) ++ ["all=-N -l"];
        nativeBuildInputs = (old.nativeBuildInputs or []) ++ [pkgs.makeWrapper];
        postFixup =
          (old.postFixup or "")
          + ''
            wrapProgram "$out/bin/headscale" \
              --prefix PATH : ${lib.makeBinPath [pkgs.delve]}
          '';
      });

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

      # ---- headplane (debug only) -------------------------------------
      # Prod headplane stays on `pkgs.headplane` via the cross-system overlay
      # below — that overlay carries the darwin pnpm-deps hash override the
      # operator runs into when `flox lock` evaluates the env on darwin. For
      # debug we re-derive from the `headplane` input with sourcemaps preserved so
      # `node --inspect` resolves to TS lines, accepting that we manage the
      # pnpm-deps hash ourselves for this single derivation.
      headplane-debug = pkgs.stdenv.mkDerivation rec {
        pname = "headplane-debug";
        # Single-sourced from the headplane input: its ref pins the tag, its
        # package.json carries the version, and the input tree IS the src.
        version = (builtins.fromJSON (builtins.readFile "${headplane}/package.json")).version;
        src = headplane;

        nativeBuildInputs = [pkgs.nodejs_22 pkgs.pnpm_10 pkgs.pnpm_10.configHook];
        buildInputs = [pkgs.nodejs_22];

        # First-build placeholder (same workflow as the vendorHash pattern):
        # run `nix build .#headplane-debug` and copy the printed SRI hash here.
        pnpmDeps = pkgs.pnpm_10.fetchDeps {
          inherit pname version src;
          hash = "sha256-QjfnE3rvk1NNON9JJfVIDuVf/zU7bveyTYYNc34SPMA="; # re-run nix build .#headplane-debug on bump to refresh
          fetcherVersion = 1;
        };

        # Skip minification + keep sourcemaps so node --inspect lands on
        # readable TS lines instead of mangled output.
        env.NODE_ENV = "development";

        buildPhase = ''
          runHook preBuild
          pnpm run build
          runHook postBuild
        '';

        installPhase = ''
          runHook preInstall
          mkdir -p $out/share/headplane
          cp -r build node_modules package.json $out/share/headplane/
          # headplane runs drizzle-orm migrations from a CWD-relative ./drizzle
          # (app/server/db/client.server.ts). Ship the migrations dir and launch
          # from the app root, or startup dies "ENOENT scandir './drizzle'".
          [ -d drizzle ] && cp -r drizzle $out/share/headplane/ || true
          mkdir -p $out/bin
          cat > $out/bin/headplane <<EOF
          #!${pkgs.runtimeShell}
          cd $out/share/headplane
          exec ${pkgs.nodejs_22}/bin/node --enable-source-maps --inspect=0.0.0.0:9229 \
            build/server/index.js "\$@"
          EOF
          chmod +x $out/bin/headplane
          runHook postInstall
        '';

        meta = with lib; {
          description = "Headplane web UI (debug build with sourcemaps + node --inspect)";
          homepage = "https://github.com/tale/headplane";
          license = licenses.agpl3Only;
          platforms = platforms.unix;
        };
      };
    in {
      packages = {
        inherit kdns kdns-debug;
        inherit headplane-debug;

        # Prod = upstream stripped build; debug = unstripped + `-N -l` + delve
        # wrapper. The Java side (FloxDebugPolicy.resolveFloxEnvironment) flips
        # the prod container's flox env to the `*-debug` env when debug is on,
        # which causes the NRI plugin to mount the debug-package binary in
        # place of the prod one — so port mappings and pod identity stay
        # untouched.
        headscale = headscale-prod;
        tailscale = tailscale-prod;
        inherit headscale-debug tailscale-debug;

        # Prod headplane outputs flow through the overlay defined below so the
        # darwin pnpm-deps override is in scope. The overlay is shared with
        # any consumer that imports the runtime flake's overlays.default. Debug
        # is re-derived above from the `headplane` input so we can preserve sourcemaps
        # for `node --inspect`.
        inherit (pkgs) headplane headplane-agent headplane-nixos-docs headplane-ssh-wasm;

        # The CI render toolchain the flox NRI plugin injects into the Tekton
        # render-publish step (the cicd/maven FloxEnv references these via
        # floxcatalog:catalogue#jdk25 / #maven / #shfmt / #shellcheck). Straight
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

        # Re-exported from the rke2-adoption-controller flake (rke2lab orphan
        # branch): the in-cluster controller BINARY — NOT the `-image` OCI output
        # the node-base baking used. The cluster-api/rke2-adoption-controller
        # FloxEnv installs it via floxcatalog:catalogue#rke2-adoption-controller so
        # the flox carrier runs it from PATH, replacing the baked image.
        # aarch64-linux for the node; darwin rides along for local parity.
        rke2-adoption-controller =
          rke2-adoption-controller.packages.${system}.rke2-adoption-controller;

        default = kdns;
      };

      defaultPackage = kdns;
    })
    // {
      # Cross-system overlay so headplane builds on darwin (pnpm hash override)
      # and aarch64-linux alike. Mirrors the upstream headplane overlay with a
      # single pnpm-deps fix-up for darwin.
      overlays.default = final: prev: let
        upstream = headplane.overlays.default final prev;
      in
        upstream
        // {
          headplane =
            if final.stdenv.hostPlatform.isDarwin
            then
              upstream.headplane.overrideAttrs (old: {
                pnpmDeps = final.pnpm_10.fetchDeps {
                  inherit (old) pname version src;
                  hash = "sha256-oSlxe//0AUA9oIFA6piULkHcDnbc+MMVvfMcah9IoxM=";
                  fetcherVersion = 1;
                };
              })
            else upstream.headplane;
        };
    };
}
