{
  description = "workspace — materialises a workspace from its manifest (the nix registry and the editor file), and verifies one";

  # Follow the seedmatic aggregator so the whole closure resolves to one nixpkgs (same
  # discipline as seed-incluster / flox-controller / flox-nri-plugin).
  #
  # ★ And COLLAPSE the aggregator's unused inputs onto nixpkgs, exactly as flox-controller and
  # flox-nri-plugin do. Measured 2026-10-02: following the aggregator without this block writes
  # a flake.lock of 320 983 lines / 8.5 MB — the full transitive closure of every tool the
  # aggregator carries — while the same input set collapsed this way weighs 164 lines. This
  # tool needs Go and nothing else, and the branch is meant to stay light.
  # Every SEEDMATIC-owned input below is an INDIRECT id (`url = "flake-commons"`), resolved through
  # nix's registry: the branch-less default target lives in the committed flake-registry.json, and
  # the operator re-aims it by dropping a flake-registry.local.json beside it (see the [include] in
  # .flox/env/manifest.toml). A branch named here could only be re-aimed by pushing an edit to this
  # file; naming none means naming nothing that can be deleted. The lock still records a revision,
  # so evaluating from it needs no registry at all.
  inputs = {
    flake-commons.url = "flake-commons";
    nixpkgs.follows = "flake-commons/nixpkgs";
    flake-utils.follows = "flake-commons/flake-utils";

    flake-commons.inputs.bird.follows = "nixpkgs";
    flake-commons.inputs.chromium-bin.follows = "nixpkgs";
    flake-commons.inputs.darwin.follows = "nixpkgs";
    flake-commons.inputs.determinate.follows = "nixpkgs";
    flake-commons.inputs.disko.follows = "nixpkgs";
    flake-commons.inputs.extra-container.follows = "nixpkgs";
    flake-commons.inputs.flake-compat.follows = "nixpkgs";
    flake-commons.inputs.flox.follows = "nixpkgs";
    flake-commons.inputs.home-manager.follows = "nixpkgs";
    flake-commons.inputs.impermanence.follows = "nixpkgs";
    flake-commons.inputs.lix-module.follows = "nixpkgs";
    flake-commons.inputs.maven-mvnd.follows = "nixpkgs";
    flake-commons.inputs.nix.follows = "nixpkgs";
    flake-commons.inputs.nix-snapshotter.follows = "nixpkgs";
    flake-commons.inputs.nixos-generators.follows = "nixpkgs";
    flake-commons.inputs.nixos-hardware.follows = "nixpkgs";
    flake-commons.inputs.nixpkgs-unstable.follows = "nixpkgs";
    flake-commons.inputs.nvfetcher.follows = "nixpkgs";
    flake-commons.inputs.sops-nix.follows = "nixpkgs";
    flake-commons.inputs.treefmt-nix.follows = "nixpkgs";
  };

  outputs = inputs@{ self, nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        version = pkgs.lib.fileContents ./VERSION;
      in
      {
        packages = rec {
          # Store-name prefix io.seedmatic.<asset> (org convention) so the artifact is findable
          # in /nix/store; meta.mainProgram keeps the bin at bin/workspace for `nix run`.
          workspace = pkgs.buildGoModule {
            pname = "io.seedmatic.workspace";
            inherit version;
            src = pkgs.lib.cleanSource ./.;

            # TWO runtime dependencies, and no more: `yaml.v3` for the manifest and
            # `BurntSushi/toml` for the flox environment manifests. The second was added
            # deliberately — a regex over raw TOML read a commented template as a declaration
            # and produced false positives that blocked generation, so parsing is the assertion
            # rather than a detail.
            #
            # NOTHING for the tests, and that part is not stylistic: the sibling controller's
            # flake documents at length how a single TEST-ONLY dependency slipped through a
            # green local build and broke delivery on the node, because nix generates a vendor
            # dir from this hash while `go test` locally runs in module mode.
            #
            # Regenerate after any go.mod change: set this to `pkgs.lib.fakeHash`, run
            # `nix build`, paste the hash nix reports.
            vendorHash = "sha256-2SXAu1fxiRbuMOKOoB8OVzTmtR3Os423j80En+SHnzU=";

            subPackages = [ "cmd/workspace" ];

            # ⚠️ `subPackages` restricts the CHECK phase as well as the build, so the default
            # check ran the 4 tests of `cmd/workspace` and silently skipped the 48 in
            # `internal/workspace` — where the assertions live. Measured 2026-10-03: the build
            # log held exactly one `ok` line. Override the phase so the gate covers the module
            # it is supposed to gate.
            checkPhase = ''
              runHook preCheck
              go test ./...
              runHook postCheck
            '';
            env.CGO_ENABLED = 0;
            ldflags = [ "-s" "-w" "-X main.version=${version}" ];
            meta.mainProgram = "workspace";
          };

          default = workspace;
        };

        apps = rec {
          # `nix run .#materialize` is the materialisation step: it produces the registry and
          # the editor file. It is NOT an activation hook — evaluating the principal's flake
          # needs the registry, and an environment builds before a hook runs, so a registry
          # generated by that hook would arrive too late for its own build.
          materialize = {
            type = "app";
            program = "${pkgs.writeShellScript "materialize" ''
              exec ${self.packages.${system}.workspace}/bin/workspace materialize "$@"
            ''}";
          };

          # `nix run .#verify` is what an activation hook calls: it asserts and fails hard.
          verify = {
            type = "app";
            program = "${pkgs.writeShellScript "verify" ''
              exec ${self.packages.${system}.workspace}/bin/workspace verify "$@"
            ''}";
          };

          # relock — THIS flake's locks, by the shared implementation in nix-flake-commons'
          # `lib.mkRelockApp`. This flake is the `feature/ssot-manifest` orphan branch of rke2lab,
          # so it names its `branch`: the slug alone would take any rke2lab checkout for this one.
          # No `consumers`: no seedmatic flake pins this branch.
          relock = {
            type = "app";
            program = "${
              inputs.flake-commons.lib.mkRelockApp {
                inherit pkgs;
                name = "ssot-manifest";
                slug = "seedmatic/rke2lab";
                url = "https://github.com/seedmatic/rke2lab.git";
                branch = "feature/ssot-manifest";
              }
            }/bin/relock";
            meta.description = "Reconcile THIS flake's locks: bump each input, DROP any bump that moves no exported derivation, push — impl: nix-flake-commons lib.mkRelockApp";
          };

          default = materialize;
        };

        devShells.default = pkgs.mkShell {
          packages = [ pkgs.go pkgs.gopls pkgs.gotools ];

          # ⚠️ A binary LINKED IN PLACE on this seat's $TMPDIR is SIGKILLed when exec'd, so
          # `go test` and `go run` — which link into $TMPDIR and exec immediately — die with a
          # bare `signal: killed` and no further output. That reads exactly like a crash in the
          # code under test, and it is not: setting GOTMPDIR to any normal filesystem fixes it,
          # which is what this shell does.
          #
          # Measured 2026-10-02, and the three cases are what pin the mechanism down:
          #
          #   linked directly on $TMPDIR, run there  -> exit 137 (SIGKILL)
          #   that SAME file copied off $TMPDIR      -> runs
          #   linked elsewhere, copied onto $TMPDIR  -> runs
          #
          # So it is neither the filesystem (executing FROM it is fine) nor the binary (the same
          # bytes run elsewhere). It is the combination: the Go linker applies the ad-hoc
          # signature IN PLACE, and on this filesystem the signature state the kernel sees at
          # exec does not match, so AMFI kills the process. `cp` writes a fresh file in one
          # pass, which is why a copy is unaffected.
          #
          # The fallback, if GOTMPDIR is ever not honoured: link the test binary into the tree
          # and run it yourself —
          #   go test -c -o ./ws.test ./internal/workspace && ./ws.test -test.v
          shellHook = ''
            export GOTMPDIR="$PWD/.gotmp"
            mkdir -p "$GOTMPDIR"
            echo "workspace ${version} — GOTMPDIR=$GOTMPDIR (a binary linked on \$TMPDIR is SIGKILLed here)"
          '';
        };

        formatter = pkgs.nixpkgs-fmt;
      });
}
