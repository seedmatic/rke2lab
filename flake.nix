{
  description = "RKE2 lab infrastructure and network blueprints";

  # The seed-master build reuses the host `~/.m2` (resolved via getEnv "HOME")
  # to reach private GitHub Packages deps without plumbing a token into the
  # sandbox, so its eval is necessarily impure. Declare that here — like
  # extra-substituters, nixConfig is applied before outputs are evaluated, so
  # `nix build .#seed-master` works without a manual `--impure`. This only takes
  # effect when rke2lab is the TOP-LEVEL flake; when consumed as an input (e.g.
  # nix-darwin-home reading `lib.networkBlueprint`), it is ignored and that pure
  # path never forces the impure getEnv. First use prompts to trust the config
  # unless `accept-flake-config = true` is set.
  nixConfig.pure-eval = false;

  inputs = {
    # INVARIANT: nix-darwin-home must NEVER be an input of this flake.
    # The two repos relate in opposite scopes: nix-darwin-home depends on rke2lab
    # at BUILD/eval time (it imports this flake's networkBlueprint as the netplan
    # source of truth), while rke2lab depends on nix-darwin-home only at RUNTIME
    # (its incus instances run on the NixOS host nix-darwin-home provisions). That
    # runtime edge is invisible to nix eval, so there is no cycle. Adding
    # nix-darwin-home here would close the loop into a real flake-eval cycle.
    # Keep the dependency one-directional at the flake level: nix-darwin-home -> rke2lab.

    # Use flake-commons as aggregator to stay synchronized with nix-darwin-home
    flake-commons.url = "github:seedmatic/nix-flake-commons/develop";
    # Cut the devenv/cachix/nix cluster: we consume NONE of it (no devShell here uses devenv/cachix),
    # yet flake-commons pulls `cachix` → `devenv` → the `nix` flake, a MUTUALLY-RECURSIVE input tree
    # (~470 devenv_N + ~505 nix_N + cachix_N + duplicate nixpkgs-23-11) — ~11.5k lock nodes that bloat
    # eval + every `nix run` closure. `follows = ""` aliases each to this root (the cut idiom, like
    # ndh.inputs.rke2lab). ndh + flox-* follow THIS flake-commons, so one cut cascades to all.
    flake-commons.inputs.cachix.follows = "";
    flake-commons.inputs.devenv.follows = "";
    flake-commons.inputs.nix.follows = "";

    # Follow flake-commons versions
    nixpkgs.follows = "flake-commons/nixpkgs";
    flake-utils.follows = "flake-commons/flake-utils";
    # flox baked into the NixOS node substrate (nixosConfigurations.rke2-node-base) as a nix
    # derivation, replacing the Debian-era runtime installer. Same source the family already
    # locks (github:flox/flox), via the aggregator so it stays in sync with nix-darwin-home.
    flox.follows = "flake-commons/flox";

    # sops-nix for per-node secret decryption on the substrate: the age identity is delivered at
    # boot over devlxd (never baked), and sops-install-secrets decrypts the cluster-CA bundle +
    # tokens into /run. Followed through flake-commons so the version stays in lock-step with
    # nix-darwin-home. Only the flake input and its `nixosModules.sops` are consumed here — the
    # nix-darwin-home sops *module* is NOT imported (that would breach the invariant above).
    sops-nix.follows = "flake-commons/sops-nix";

    # The flox runtime flake owns the NRI plugin + per-workload package
    # definitions. We re-export its outputs here so the deployable artifacts
    # build through this top-level entry point (and the aarch64-linux NRI plugin
    # cross-builds via the configured linux-builder). The shim is now its own repo
    # (a fork), pinned to the `develop` integration branch (seedmatic convention —
    # releases abandoned for now, co-dev on develop alongside flox-controller); the
    # `follows` below dedup its aggregator + nixpkgs against ours so there is a
    # single resolved version set across the flakes.
    flox-runtime.url = "github:seedmatic/flox-nri-plugin/develop";
    flox-runtime.inputs.nixpkgs.follows = "nixpkgs";
    flox-runtime.inputs.flake-utils.follows = "flake-utils";
    flox-runtime.inputs.flake-commons.follows = "flake-commons";

    # The flox-controller flake owns the FloxEnv CRD + the node-agent controller
    # (runtime flox-env delivery — the companion to flox-runtime's NRI plugin). We
    # re-export its outputs so the controller binary + OCI image build through this
    # entry point (image cross-builds via the linux-builder, like the NRI plugin).
    # Referenced on the `develop` integration branch (seedmatic convention);
    # follows dedup its aggregator + nixpkgs against ours.
    flox-controller.url = "github:seedmatic/flox-controller/develop";
    flox-controller.inputs.nixpkgs.follows = "nixpkgs";
    flox-controller.inputs.flake-utils.follows = "flake-utils";
    flox-controller.inputs.flake-commons.follows = "flake-commons";

    # The rke2-adoption-controller flake owns the ClusterAdoption CRD + the in-cluster
    # controller that adopts running RKE2-on-Incus control planes into CAPI (Pulumi
    # bootstraps, this describes it in CAPI so cold-starts stop re-provisioning). It lives
    # on a DEDICATED orphan branch of THIS repo (a separate Go build artifact, not the Maven
    # reactor) — a same-repo branch input, pinned by rev in flake.lock. Re-exported so the
    # OCI image cross-builds via the linux-builder, like flox-controller. follows dedup.
    rke2-adoption-controller.url = "github:seedmatic/rke2lab/rke2-adoption-controller";
    rke2-adoption-controller.inputs.nixpkgs.follows = "nixpkgs";
    rke2-adoption-controller.inputs.flake-utils.follows = "flake-utils";
    rke2-adoption-controller.inputs.flake-commons.follows = "flake-commons";

    # Federation (Direction A): rke2lab consumes ndh's home-LAN facts
    # (catalog.netplan.lan). ndh already imports rke2lab's lib.networkBlueprint,
    # so this closes a mutual edge — cut with a reciprocal EMPTY follows: ndh's
    # back-reference to rke2lab follows THIS root, which breaks the lock cycle
    # while both flakes still build standalone. Consume ONLY `.lan` (a
    # self-contained constant); NEVER `.segments`/`.asns` (they union
    # networkBlueprint → value cycle). Pinned as a github rev now that the cut is
    # proven (a local path during prototyping).
    ndh.url = "github:seedmatic/ndh/develop";
    ndh.inputs.rke2lab.follows = "";
    ndh.inputs.flake-commons.follows = "flake-commons";
  };

  outputs = inputs@{ self, nixpkgs, flake-utils, flox-runtime, flox-controller, rke2-adoption-controller, flox, sops-nix, ... }:
    let
      # Enforce the INVARIANT above mechanically, not just by comment: fail eval
      # (any `nix build`/`nix eval` of this flake) with a printed diagnostic if
      # nix-darwin-home is ever wired in as an input. The `...` in the argument
      # set would otherwise swallow it silently. The dependency must stay
      # one-directional — nix-darwin-home -> rke2lab — so this edge never closes
      # into a flake-eval cycle.
      _invariantGuard =
        if inputs ? nix-darwin-home then
          throw ''
            rke2lab flake INVARIANT violated: `nix-darwin-home` is an input.
            The two repos relate in opposite scopes — nix-darwin-home depends on
            rke2lab at build/eval time (it imports lib.networkBlueprint), while
            rke2lab depends on nix-darwin-home only at runtime (incus instances
            run on the host it provisions). Adding nix-darwin-home as an input
            here closes that into a real flake-eval cycle. Remove the input;
            keep the dependency one-directional: nix-darwin-home -> rke2lab.
          ''
        else
          null;

      # The network blueprint is OS-independent data (cluster/node IDs, MAC
      # patterns, addressing): the netplan jar is a portable JDK/Maven build, so
      # the YAML — and the data parsed from it — is identical regardless of which
      # platform builds it. But IFD must still *realize* the generating
      # derivation on some concrete system, and a flat `lib` (no `lib.${system}`)
      # has no system in scope to pick. Pin generation to the single build host
      # (bioskop = aarch64-darwin): darwin eval builds it natively, and a NixOS
      # eval — which also runs on bioskop — builds the same darwin derivation
      # locally, baking in only the parsed data (pure MAC strings). No
      # linux-builder is involved on this path.
      blueprintSystem = "aarch64-darwin";

      # Single source of truth for the Maven-build toolchain. This one attrset
      # feeds two consumers: the build derivations below, and the re-exported
      # `packages` that the flox env pins against — so the dev loop and the store
      # build resolve the same versions from this
      # flake's nixpkgs. Spotless version-checks the shfmt binary it finds on
      # PATH against its configured `${shfmt.version}`, so the build passes
      # `-Dshfmt.version=${shfmt.version}` to keep binary and config identical,
      # both sourced from this `pkgs`.
      #
      # `which` is required: spotless locates shfmt by shelling out to
      # `which shfmt` (ForeignExe, when no <pathToExe> is set) and reports a
      # non-zero exit as "Unable to find shfmt on path". The nix stdenv has no
      # `which`, so without it the gate fails even though shfmt is on PATH; the
      # dev loop never hit this because flox/the system provides `which`.
      mavenToolchain = pkgs: { inherit (pkgs) jdk25 maven shfmt shellcheck which; };
      mavenBuildInputs = pkgs: builtins.attrValues (mavenToolchain pkgs);

      # Host ~/.m2 reuse for the Maven-in-nix builds. These read the host env at eval
      # time — they resolve ONLY when the eval is IMPURE. rke2lab as the top-level flake
      # has `nixConfig.pure-eval = false`, but when nix-darwin-home consumes it as an
      # input (building planJar via lib.networkBlueprint) that config is ignored and
      # the eval is PURE → getEnv returns "" → hostM2Repo becomes "/.m2/repository" and
      # hostGHToken "". So that path MUST be evaluated impurely: `darwin-rebuild … --impure`
      # (or the equivalent on the consuming flake). Without it the seed below copies
      # nothing and the build dies resolving staging-extension from central.
      hostM2Repo = "${builtins.getEnv "M2_REPO"}";
      hostGHToken = "${builtins.getEnv "GH_TOKEN"}";

      # Optional PERSISTENT maven primary repo + build-cache. When set — a dev cache dir (must
      # be writable by the nix BUILD user, i.e. nixbld on a multi-user daemon: use /tmp/…, NOT
      # $HOME) or the in-cluster PVC (single-user nix builds as the pod user) — the reactor build
      # writes its resolved deps AND its maven-build-cache there, so successive builds are
      # INCREMENTAL. The maven-build-cache hash is stable across nix builds ONLY once volatile
      # absolute paths are excluded from it (see .mvn/maven-build-cache-config.xml — the compiler
      # compilerArgs exclusion). Empty ⇒ a fresh tmpfs primary each build (the pure default: the
      # nix store already caches the jar per-src, so seed-master/CI need nothing more).
      buildCache = "${builtins.getEnv "MAVEN_BUILD_CACHE"}";

      # The io.seedmatic closure the `.mvn/extensions.xml` core extension needs at
      # bootstrap: staging-extension + its bnd-read dep + the parent-POM chain
      # (aggregator maven-embed-staging-ext → build-parent → root rke2lab) + the `bom`
      # build-parent imports in dependencyManagement. Model building resolves every link
      # (incl. reading staging-extension's descriptor → its parent chain → the bom import)
      # BEFORE any POM, by the BootstrapCoreExtensionManager — which does NOT consult
      # maven.repo.local.tail (the tail is wired into the main build's resolver only). So
      # the WHOLE closure must be seeded into the PRIMARY, self-sufficiently: a warm host
      # ~/.m2 masked the missing `bom` on dev, but a cold primary (the in-cluster render's
      # fresh PVC repo) then failed reading the staging-extension descriptor (bom absent).
      stagingExtensionClosure = [
        "io/seedmatic/rke2lab/staging-extension"
        "io/seedmatic/rke2lab/bnd-read"
        "io/seedmatic/rke2lab/maven-embed-staging-ext"
        "io/seedmatic/rke2lab/build-parent"
        "io/seedmatic/rke2lab/bom"
        "io/seedmatic/rke2lab/rke2lab"
      ];

      # Shared Maven-in-nix plumbing for both reactor derivations: a buildPhase prelude
      # that sets up a writable $M2_REPO, SEEDS the staging-extension closure into it
      # (mechanism 1 below), and defines `mvnHost` — an `mvn` wrapper pinning that primary
      # + the host ~/.m2 as a READ-ONLY tail (mechanism 2), with GH_TOKEN. Each derivation
      # opens its buildPhase with `${mavenHostPrelude}`, then calls `mvnHost <goals>`.
      #
      # (1) The CORE extension resolves at bootstrap from the seeded primary (the tail is
      #     invisible to that early resolver — see stagingExtensionClosure).
      # (2) The MAIN build's released private deps (java-bbox-api-client, java-systemd
      #     3.0.0-rc.2) come from the host ~/.m2 as a read-only tail — the build user can't
      #     WRITE the host repo (Maven tracking files → AccessDeniedException), so it's a
      #     tail, not the primary; public deps download into the temp primary.
      #
      # Both mechanisms read host state (hostM2Repo) → the eval must be impure (see above).
      # The seed guard turns an empty getEnv into a clear error instead of a cryptic
      # "staging-extension not found in central" 200 lines later.
      mavenHostPrelude = ''   
        : "Point M2_REPO at the writable build dir so the JVM's user.home-derived default"
        : "local repo ($HOME/.m2/repository\) IS the seeded primary — covers the bootstrap"
        : "resolver whether it honors -Dmaven.repo.local or falls back to user.home. The"
        : "tail path ($hostM2Repo) is baked at eval time, so this override doesn't touch it."
        export HOME="$TMPDIR"
        # Persistent primary local repo (MAVEN_BUILD_CACHE) when set, else a fresh tmpfs
        # primary. The maven-build-cache extension defaults its location to the PARENT of
        # maven.repo.local, so it lands at $cache/build-cache on its own → a persistent
        # MAVEN_BUILD_CACHE makes successive builds incremental.
        if [ -n "${buildCache}" ]; then
          M2_REPO="${buildCache}/repository"
        else
          M2_REPO="$HOME/.m2/repository"
        fi
        mkdir -p "$M2_REPO"

        # Seed the staging-extension closure from the NIX-built repo ($STAGING_EXTENSION_REPO,
        # set by each caller before this prelude) — the same store path for every mode, so the
        # operator standalone build and the in-cluster render replay the identical bootstrap.
        for a in ${builtins.concatStringsSep " " stagingExtensionClosure}; do
          src="$STAGING_EXTENSION_REPO/$a"
          # Idempotent when the primary persists across builds (skip if already seeded).
          if [ -d "$src" ] && [ ! -e "$M2_REPO/$a" ]; then
            mkdir -p "$M2_REPO/$(dirname "$a")"
            cp -r "$src" "$M2_REPO/$a"
            chmod -R u+w "$M2_REPO/$a"
          fi
        done

        if [ ! -d "$M2_REPO/io/seedmatic/rke2lab/staging-extension" ]; then
          echo "FATAL: staging-extension not seeded from '$STAGING_EXTENSION_REPO'" >&2
          exit 1
        fi

        mvnHost() {
          env GH_TOKEN="${hostGHToken}" mvn \
            -Dmaven.repo.local="$M2_REPO" \
            -Dmaven.repo.local.tail="${hostM2Repo}" \
            -Dmaven.repo.local.tail.ignoreAvailability=true \
            "$@"
        }
      '';

      # The staging-extension build closure, built by NIX (not seeded from a host ~/.m2 nor a
      # per-mode Tekton bootstrap task). The `.mvn/extensions.xml` core extension is a reactor
      # module needed at mvn STARTUP (before any reactor build) — a chicken-egg — so it is built
      # WITHOUT itself (rm .mvn/extensions.xml) and installed into $out, a maven-repo store path.
      # Every downstream reactor build (`mavenHostPrelude`) then SEEDS the closure from this ONE
      # store path — so the operator standalone (seed-master's build) and the in-cluster render
      # replay the identical bootstrap, zero per-mode duplication. Impure (host GH_TOKEN for any
      # GitHub Packages dep of the parent chain), like the other reactor builds.
      stagingExtensionRepoFor = pkgs: pkgs.stdenv.mkDerivation {
        name = "rke2lab-staging-extension-repo";
        src = ./.;
        nativeBuildInputs = mavenBuildInputs pkgs;
        buildPhase = ''
          export HOME="$TMPDIR"
          mkdir -p $out
          rm .mvn/extensions.xml
          env GH_TOKEN="${hostGHToken}" mvn -f bom/pom.xml install -Dmaven.repo.local="$out"
          env GH_TOKEN="${hostGHToken}" mvn -f build-parent/pom.xml install -Dmaven.repo.local="$out"
          env GH_TOKEN="${hostGHToken}" mvn -f maven-embed-staging-ext/pom.xml -pl :staging-extension -am \
            clean install -Dmaven.repo.local="$out"
        '';
        installPhase = "true";
        dontFixup = true;
      };

      # netplan JAR build, parameterized by pkgs so the per-system `packages`
      # output can build it locally while `lib` uses the pinned set.
      planJarFor = pkgs: pkgs.stdenv.mkDerivation {
        name = "rke2lab-plan";
        src = ./.;  # Need full repo for parent POM + BOM resolution

        nativeBuildInputs = mavenBuildInputs pkgs;

        buildPhase = ''
          STAGING_EXTENSION_REPO=${stagingExtensionRepoFor pkgs}
          ${mavenHostPrelude}

          # Install parent POM and BOM first, then build the plan CLI module. plan-cli
          # multiplexes both plan exports (network + dataset), driving the netplan +
          # dataplan scions; it builds through the reactor (`-pl :plan-cli -am`) so `-am`
          # pulls the netplan-core / dataplan-contract siblings from source.
          mvnHost install:install-file -Dfile=pom.xml -DpomFile=pom.xml
          mvnHost -f build-parent/pom.xml install
          mvnHost -f bom/pom.xml install
          mvnHost -pl :plan-cli -am \
            -Dshfmt.version=${pkgs.shfmt.version} clean package -DskipTests
        '';

        installPhase = ''
          mkdir -p $out/share/java
          cp exec/plan-cli/target/plan-cli-*-exec.jar $out/share/java/rke2lab-plan.jar
        '';
      };

      # Generate the network blueprint YAML from the Java source of truth.
      networkBlueprintYamlFor = pkgs:
        let planJar = planJarFor pkgs;
        in pkgs.stdenv.mkDerivation {
          name = "rke2lab-network-blueprint";

          buildInputs = [ pkgs.jdk25 ];

          dontUnpack = true;

          buildPhase = ''
            # PlanCli dispatcher: the `network` plane's export (the former yamlExport verb).
            java -jar ${planJar}/share/java/rke2lab-plan.jar network export > blueprint.yaml
          '';

          installPhase = ''
            mkdir -p $out
            cp blueprint.yaml $out/network-blueprint.yaml
          '';
        };

      # The dataplan (ZFS dataset layout) export — the `dataset` plane. The scion writes
      # dataplan.json (already JSON), so the CLI emits it raw; no yq conversion needed. The
      # REGEN artifact for the checked-in ./dataplan.json, the storage twin of
      # networkBlueprintJson; seed-master's DataplanLayout stays the source of truth.
      dataplanJsonFor = pkgs:
        let planJar = planJarFor pkgs;
        in pkgs.runCommand "dataplan.json" { buildInputs = [ pkgs.jdk25 ]; } ''
          # The OSGi FrameworkLauncher narrates its boot on stdout before the
          # export, so keep only the JSON document (from its top-level `{` on)
          # — otherwise a stray "HH:MM:SS.mmm INFO …" line lands at line 1 and
          # fromJSON chokes ("unexpected number literal").
          java -jar ${planJar}/share/java/rke2lab-plan.jar dataset export \
            | sed -n '/^{/,$p' > $out
        '';

      # The consumed blueprint is PURE committed data — read from the checked-in
      # network-blueprint.json, no IFD, no build. The reason is NOT "no builder": the
      # blueprint sits on the eval hot-path of *every* host config — the catalog's
      # netplan.lan.hosts `//`-merges the addressing map, so reading any host entry
      # forces the whole JSON. An IFD here would drag a Java/Maven jar build onto that
      # path and break routine ops that no remote builder fixes: with the darwin-pinned
      # jar, any aarch64-linux box evaluating locally has no darwin builder to realize
      # it; drop the pin to per-system instead and the companion's own first bringup
      # goes circular (its aarch64-linux jar offloads to the very <host>-nixos being
      # brought up). Committed data has zero infra dependency. The Java stays the
      # source of truth, materialized into the JSON at regen time on a jar-capable host:
      #   nix build .#networkBlueprintJson && cp result network-blueprint.json && commit
      networkBlueprintData = builtins.fromJSON (builtins.readFile ./network-blueprint.json);

      # The consumed dataplan is PURE committed data too — the ZFS dataset layout read from
      # the checked-in dataplan.json (regen: `nix build .#dataplanJson && cp result
      # dataplan.json && commit`). ndh pulls this via `lib.dataplan` and unions it into
      # catalog.datasets; seed-master's DataplanLayout is the source of truth.
      dataplanData = builtins.fromJSON (builtins.readFile ./dataplan.json);

      # Regeneration only (NOT on the consumed data path): the jar-built YAML on the
      # pinned blueprintSystem, surfaced via networkBlueprintYamlPath for inspection.
      # The blueprintSystem darwin pin now affects ONLY regeneration, never consumption.
      blueprintPkgs = nixpkgs.legacyPackages.${blueprintSystem};
      networkBlueprintYaml = networkBlueprintYamlFor blueprintPkgs;

      # Export network blueprint with Nix helpers. `deriveMacs` uses the
      # system-independent `nixpkgs.lib` (pure int/string functions) so the whole
      # export is platform-agnostic.
      networkBlueprint = networkBlueprintData // {
        # Helper to derive MACs for a cluster/node pair, using the cluster/node
        # ID mappings from the Java-generated YAML.
        deriveMacs = cluster: node:
          let
            clusterId = networkBlueprintData.clusters.${cluster};
            nodeId = networkBlueprintData.nodes.${node};
            toHex = n: if n < 16 then "0${nixpkgs.lib.toLower (nixpkgs.lib.toHexString n)}" else nixpkgs.lib.toLower (nixpkgs.lib.toHexString n);
          in {
            lan = "10:66:6a:4c:${toHex clusterId}:${toHex nodeId}";
            wan = "52:54:00:${toHex clusterId}:${toHex nodeId}:00";
          };
      };
    in
    # Force the invariant guard before returning any output, so a forbidden
    # nix-darwin-home input fails eval with the diagnostic rather than slipping by.
    builtins.seq _invariantGuard
    (flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

        planJar = planJarFor pkgs;

        # Build the seed-master bootstrap app (and the manifests jar it embeds)
        # as a single reactor build, so the deployable artifact Pulumi runs comes
        # from the immutable store rather than a mutable target/. seed-master
        # depends on manifests, netplan, systemd-contract and sdks/incus, so the
        # whole reactor is built once from the parent pom. Mirrors netplanJar's
        # Maven-in-nix pattern (shared `mavenHostPrelude` — full rationale there);
        # the build runs with sandbox=false (per the host nix.conf) so Maven can
        # resolve its dependency tree. java-systemd 3.0.0-rc.2 is a release now, so
        # the tail serves it — no seed needed beyond the staging-extension closure.
        # The flox-controller CRD staging snippet (single-sourced from the flake input,
        # never vendored) onto the manifest-synthesis classpath so
        # FloxControllerManifestsUnit emits it into the cluster's crds layer. Shared by
        # both reactor builds AND the dev-loop `stage-flox-controller-crd` app — one
        # definition, no duplication. Empty when the controller flake has no output for
        # this system (darwin-eval guard).
        # `install`, not `cp`: the store sources are read-only (nix-store mode), so a plain
        # `cp` into the working tree leaves read-only files that a SECOND stage cannot
        # overwrite ("Permission denied"). `install -m 644` unlinks + rewrites with a
        # writable mode, so re-staging in the dev loop / the render app is idempotent (the
        # crds/ dir is gitignored, so the mode is ours to set).
        stageFloxControllerCrds = nixpkgs.lib.optionalString (floxControllerCrds != null) ''
          mkdir -p ${floxControllerCrdResourceDir}
          install -m 644 ${floxControllerCrds}/*.yaml ${floxControllerCrdResourceDir}/
        '';

        # Same as stageFloxControllerCrds, for the ClusterAdoption CRD — the two share the
        # crds/ resource dir, so the DaemonSet + management units emit both into the cluster's
        # crds layer. Empty when the adoption flake has no output for this system.
        stageRke2AdoptionControllerCrds = nixpkgs.lib.optionalString (rke2AdoptionControllerCrds != null) ''
          mkdir -p ${floxControllerCrdResourceDir}
          install -m 644 ${rke2AdoptionControllerCrds}/*.yaml ${floxControllerCrdResourceDir}/
        '';

        # One reactor build, factored: the shared Maven-in-nix closure (repo src, the
        # mavenToolchain, the CRD staging, the `mvnHost` prelude, the spotless shfmt pin)
        # captured ONCE, parameterized by the mvn module selector and the exec jars to
        # install. Every store-built exe resolves deps + stages CRDs + gates spotless
        # identically — so seed-master (whole reactor) and manifests-cli (the render exe)
        # come from the SAME build logic, no drift.
        buildReactorExe = { pname, mvnArgs ? "", jars }: pkgs.stdenv.mkDerivation {
          name = "rke2lab-${pname}";
          src = ./.;

          nativeBuildInputs = mavenBuildInputs pkgs;

          buildPhase = ''
            STAGING_EXTENSION_REPO=${stagingExtensionRepoFor pkgs}
            ${mavenHostPrelude}
            ${stageFloxControllerCrds}
            ${stageRke2AdoptionControllerCrds}
            # The CRDs are already staged above; tell the staging-extension's lifecycle participants
            # to SKIP their `nix run .#stage-*-crd` (a nested nix run has no daemon/network in this
            # sandbox). A plain `./mvnw` build has no marker, so the participants stage there.
            export RKE2LAB_CRD_STAGED=1
            mvnHost -Dshfmt.version=${pkgs.shfmt.version} -DskipTests ${mvnArgs} clean package
          '';

          installPhase = ''
            mkdir -p $out/share/java
            ${nixpkgs.lib.concatMapStrings
              (jar: "cp ${jar.glob} $out/share/java/${jar.name}\n") jars}
          '';
        };

        # The seed-master bootstrap app + the manifests jar it embeds, as one whole-reactor
        # build (seed-master pulls manifests/netplan/systemd/incus, so the reactor builds
        # once from the parent pom) — the deployable artifact Pulumi runs from the immutable
        # store. mvnArgs defaults to "" (the exact whole-reactor `clean package` it always ran).
        seedMasterJar = buildReactorExe {
          pname = "seed-master";
          jars = [
            { glob = "exec/seed-master/target/seed-master-*-exec.jar"; name = "seed-master.jar"; }
            { glob = "exec/manifests-cli/target/manifests-cli-*-exec.jar"; name = "manifests.jar"; }
          ];
        };

        # The manifests-cli render exe alone — a lean `-pl :manifests-cli -am` build the
        # `render-manifests` app runs (`nix build .#manifests-cli` → run the jar), mirroring
        # deploy/seed-master. Same shared closure, so CRD staging + spotless gate are
        # identical; the resulting fat jar is self-contained (CRDs baked in) so running it
        # needs no maven cache, bootstrap or CRD staging at runtime.
        manifestsCliJar = buildReactorExe {
          pname = "manifests-cli";
          mvnArgs = "-pl :manifests-cli -am";
          jars = [
            { glob = "exec/manifests-cli/target/manifests-cli-*-exec.jar"; name = "manifests.jar"; }
          ];
        };

        # Per-system inspectable build of the blueprint YAML, plus its JSON projection.
        # networkBlueprintJson is the REGEN artifact for the checked-in data file — run
        # on a jar-capable host (darwin/bioskop): `nix build .#networkBlueprintJson`,
        # then `cp result network-blueprint.json` and commit. The consumed data reads
        # that committed JSON purely (see the flat `lib`), so this is off the eval path.
        networkBlueprintYaml = networkBlueprintYamlFor pkgs;
        networkBlueprintJson = pkgs.runCommand "network-blueprint.json" {
          nativeBuildInputs = [ pkgs.yq-go ];
        } ''
          yq -o=json '.' ${networkBlueprintYamlFor pkgs}/network-blueprint.yaml > $out
        '';

        # The dataplan REGEN artifact — the `dataset` plane emits JSON directly, so this
        # is the export as-is (no yq). `nix build .#dataplanJson`, then cp to ./dataplan.json.
        dataplanJson = dataplanJsonFor pkgs;

        # Darwin-buildable incus client. The full `incus` daemon is Linux-only
        # (requires lxc, libcap, cowsql, etc.), but nixpkgs ships a `client.nix`
        # variant exposed as `pkgs.incus.passthru.client` that builds on both
        # platforms because it only needs Go + the `cmd/incus` subpackage.
        # Surfacing it here gives us a stable `flake = ".#incus-client"`
        # reference for the rke2lab flox env to install on Darwin (the catalog
        # entry only ships Linux builds).
        incusClient = pkgs.incus.passthru.client;

        # Prebuilt pulumi CLI for the deploy wrapper (matches the flox env's
        # `pulumi.pkg-path = "pulumi-bin"`); the `-bin` variant avoids a Go
        # compile and tracks the same release line the dev loop uses.
        pulumiPkg = pkgs.pulumi-bin;

        # NRI plugin (+ debug) re-exported from the flox runtime flake, so the
        # aarch64-linux Go binary builds through this entry point at build time
        # (cross-built via the configured linux-builder) rather than on the node
        # at runtime. The node consumes the resulting store path via its gcroot.
        # Guarded so darwin-only eval of `packages` does not fail when the
        # runtime flake lacks an output for a given system.
        floxRuntimePackages = flox-runtime.packages.${system} or { };
        floxNriPluginPackages =
          (if floxRuntimePackages ? flox-nri-plugin
           then { inherit (floxRuntimePackages) flox-nri-plugin; }
           else { })
          // (if floxRuntimePackages ? flox-nri-plugin-debug
                then { inherit (floxRuntimePackages) flox-nri-plugin-debug; }
                else { });

        # flox-controller re-exported the same way: the node-agent binary + its OCI
        # image (the DaemonSet distribution artifact, cross-built via the
        # linux-builder). Same darwin-eval guard as the NRI plugin.
        floxControllerPackages =
          let ctlPkgs = flox-controller.packages.${system} or { };
          in (if ctlPkgs ? flox-controller
              then { inherit (ctlPkgs) flox-controller; }
              else { })
          // (if ctlPkgs ? flox-controller-image
                then { inherit (ctlPkgs) flox-controller-image; }
                else { })
          // (if ctlPkgs ? flox-controller-crds
                then { inherit (ctlPkgs) flox-controller-crds; }
                else { });

        # The flox-controller CRD as a store path (single-sourced from the flake —
        # controller-gen output, never vendored). Staged onto the manifest-synthesis
        # classpath (crds/ resource) by seedMasterJar for release and by
        # `nix run .#stage-flox-controller-crd` for the dev loop.
        floxControllerCrds = (flox-controller.packages.${system} or { }).flox-controller-crds or null;
        floxControllerCrdResourceDir =
          "osgi/domains/manifests/manifests-core/src/main/resources/crds";

        # rke2-adoption-controller re-exported the same way as flox-controller: the
        # controller binary + the ClusterAdoption CRD store path. The OCI image is NO
        # LONGER re-exported or baked — the controller rides the flox runtime (the
        # cluster-api/rke2-adoption-controller flox env installs the binary from the
        # flox-catalogue), so only the binary + CRD are needed here. Same darwin-eval guard.
        rke2AdoptionControllerPackages =
          let adoptPkgs = rke2-adoption-controller.packages.${system} or { };
          in (if adoptPkgs ? rke2-adoption-controller
              then { inherit (adoptPkgs) rke2-adoption-controller; }
              else { })
          // (if adoptPkgs ? rke2-adoption-controller-crds
                then { inherit (adoptPkgs) rke2-adoption-controller-crds; }
                else { });

        # The ClusterAdoption CRD as a store path (single-sourced from the flake —
        # controller-gen output, never vendored). Staged onto the same crds/ classpath
        # resource dir as the flox-controller CRD.
        rke2AdoptionControllerCrds =
          (rke2-adoption-controller.packages.${system} or { }).rke2-adoption-controller-crds or null;

        # Maven-build toolchain re-exported as individual packages, so the flox
        # env pins each tool to this flake's version
        # (e.g. `shfmt.flake = "github:seedmatic/rke2lab#shfmt"`) instead of the
        # overlapping fleet includes. This flake is the source of truth; flox
        # follows it, keeping the dev loop aligned with the Maven build's
        # spotless gate.
        toolchainPackages = mavenToolchain pkgs;

        # Cluster GROW: `nix run .#grow -- <stack>`. Runs `pulumi up` against
        # the STORE-built seed-master jar instead of the mutable Maven target the
        # dev loop uses. A Pulumi project is more than Pulumi.yaml — the stack
        # config (Pulumi.<stack>.yaml, committed) and stack state
        # (.pulumi-state/, the local backend) plus the flox PULUMI_* env all live
        # in the repo working dir — so we deploy IN PLACE and only swap the one
        # thing that differs between dev and release: Pulumi.yaml's `binary`,
        # pointed at the store path for the duration (restored on exit, even on
        # failure). Pulumi can't run from /nix/store (needs a writable cwd for
        # state + plugin cache), which is exactly why we don't copy a skeleton.
        #
        # Deterministic, self-contained: every dependency pulumi needs to run is
        # a nix build input (runtimeInputs) — NOT inherited from the flox env
        # (`nix run` doesn't propagate the caller's PATH anyway). pulumi itself,
        # a JRE to run the seed-master jar, and the incus client the provider
        # shells out to. The PULUMI_* vars are set here too, mirroring the flox
        # env's [vars]: local file:// backend under the repo, empty passphrase.
        # Mint a short-lived (~1h) GitHub App INSTALLATION token from the one org-owned App's
        # credentials in .secrets (github.app), so the maven build resolves private GitHub Packages
        # (java-systemd, java-bbox-api-client) AS THE APP — never a personal `gh auth token`. There
        # is no cluster/cellar at BUILD time, so this is the shell twin of the OSGi ghapp minter
        # (which is itself what the build produces — chicken-and-egg forbids reusing it here). Least
        # privilege: packages:read, the only scope maven needs. The grow / render wrappers export its
        # output as GH_TOKEN before the inner `nix build`; in-cluster there is no .secrets and the
        # caller sets GH_TOKEN from PaC's App token instead.
        mintGhAppTokenApp = pkgs.writeShellApplication {
          name = "mint-gh-app-token";
          runtimeInputs = [ pkgs.coreutils pkgs.openssl pkgs.curl pkgs.yq-go ];
          text = ''
            secrets="''${1:-.secrets}"
            if [ ! -f "$secrets" ]; then
              echo "mint-gh-app-token: no $secrets (github.app credentials)" >&2
              exit 1
            fi
            appId=$(yq -r '.github.app.appId' "$secrets")
            installationId=$(yq -r '.github.app.installationId' "$secrets")
            b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
            now=$(date +%s)
            header=$(printf '%s' '{"alg":"RS256","typ":"JWT"}' | b64url)
            payload=$(printf '%s' "{\"iat\":$((now - 60)),\"exp\":$((now + 540)),\"iss\":\"$appId\"}" | b64url)
            # Sign WITHOUT ever writing the private key to disk: the key is fed to openssl through a
            # process-substitution fd (an anonymous pipe, never a tmpfs file), and the data to sign
            # arrives on stdin. openssl reads the key sequentially so a pipe is fine.
            signature=$(printf '%s' "$header.$payload" \
              | openssl dgst -sha256 -sign <(yq -r '.github.app.privateKeyPem' "$secrets") -binary \
              | b64url)
            jwt="$header.$payload.$signature"
            curl -sS -X POST \
              -H "Authorization: Bearer $jwt" \
              -H "Accept: application/vnd.github+json" \
              -d '{"permissions":{"packages":"read"}}' \
              "https://api.github.com/app/installations/$installationId/access_tokens" \
              | yq -p json -r '.token'
          '';
        };

        growApp = pkgs.writeShellApplication {
          name = "rke2lab-grow";
          runtimeInputs = [
            pkgs.coreutils
            pkgs.nix
            pkgs.git
            pulumiPkg
            pkgs.jdk25
            incusClient
            mintGhAppTokenApp
          ]
          # ndh's manage-tailnet on PATH: InstanceGrow runs it as a local.Command to prune the old
          # node's stale tailscale devices when the instance is replaced (they orphan their MagicDNS
          # names → the new pac-webhook drifts to -1/-2 and the render webhook dies). The flox env
          # carries it too, so a plain `pulumi up` prunes; here it covers `nix run .#grow`.
          # darwin-only: ndh exposes the package only for aarch64-darwin (the operator host).
          ++ pkgs.lib.optional (system == "aarch64-darwin") inputs.ndh.packages.${system}.manage-tailnet;
          text = ''
            usage() {
              cat >&2 <<'USAGE'
usage: nix run .#grow -- <stack> [preview|up] [pulumi args...]

  <stack>    Pulumi stack name (required), e.g. dev
  preview    read-only diff, no apply (default)
  up         apply, non-interactive (pulumi up --yes)
  -h,--help  this message

Anything after the verb is passed through to pulumi (e.g. --diff, --target).
USAGE
            }

            # -h/--help works from anywhere (before the repo-root check).
            case "''${1:-}" in
              -h | --help) usage; exit 0 ;;
            esac

            if [ ! -f Pulumi.yaml ]; then
              echo "error: run from the rke2lab repo root (no Pulumi.yaml here)" >&2
              usage
              exit 1
            fi

            stack="''${1:-}"
            if [ -z "$stack" ]; then
              echo "error: missing <stack> argument" >&2
              usage
              exit 2
            fi
            # Guard the common slip: an ACTION verb given where the stack is expected
            # (`-- up` would silently make the stack "up", then preview a nonexistent
            # stack with no error). Catch it with a corrective hint.
            case "$stack" in
              preview | up)
                echo "error: '$stack' is an action, not a stack — did you mean: nix run .#grow -- <stack> $stack" >&2
                usage
                exit 2
                ;;
            esac
            shift

            # Action verb (default: preview — safe, read-only). `up` applies
            # non-interactively: preview is the inspection step, so an explicit
            # `up` means apply and the confirm prompt is redundant. Anything
            # after the verb is passed through to pulumi (e.g. --diff, --target).
            action="''${1:-preview}"
            case "$action" in
              preview | up) shift ;;
              -*) action="preview" ;;  # no verb given, first arg is a pulumi flag
              *) echo "error: unknown action '$action' (preview|up)" >&2; usage; exit 2 ;;
            esac

            # Pulumi project env (mirrors the flox env's [vars]); each
            # `''${VAR:-default}` so an active flox env's values win when present.
            # PULUMI_HOME defaults to the XDG location (NOT the repo) so the
            # plugin/cache dir never becomes a repo artifact, matching flox.
            export PULUMI_HOME="''${PULUMI_HOME:-''${XDG_STATE_HOME:-$HOME/.local/state}/pulumi}"
            export PULUMI_BACKEND_URL="''${PULUMI_BACKEND_URL:-file://$PWD/.pulumi-state}"
            export PULUMI_CONFIG_PASSPHRASE="''${PULUMI_CONFIG_PASSPHRASE:-}"
            mkdir -p "$PULUMI_HOME" .pulumi-state

            # Self-heal: a previous run killed before its restore trap fired can
            # leave Pulumi.yaml's binary pointing at a store path. Restore the
            # committed (Maven-target) file from git before swapping again.
            if grep -qE '^ *binary: /nix/store/' Pulumi.yaml; then
              echo "==> Pulumi.yaml left swapped by a prior run; restoring from git" >&2
              git checkout -- Pulumi.yaml
            fi

            # Maven-in-nix cache knob — SAME model as `.#render-manifests`: M2_REPO is the ONE knob
            # (the read-only tail with the released private deps), and the persistent maven-build-cache
            # is DERIVED beside it (its parent) unless MAVEN_BUILD_CACHE is set explicitly. Both are
            # read IMPURELY (getEnv) by the inner `nix build .#seed-master`, so exporting them here
            # makes that build INCREMENTAL instead of a cold tmpfs primary every `nix run .#grow`.
            : "''${M2_REPO:?set M2_REPO to your maven repository, e.g. \$HOME/.m2/repository}"
            export MAVEN_BUILD_CACHE="''${MAVEN_BUILD_CACHE:-$(dirname "$M2_REPO")}"

            # The inner `nix build .#seed-master` is a SEPARATE child nix process — flags on the
            # OUTER `nix run` (e.g. `nix run .#grow -L …`) go to building THIS wrapper, not it. So it
            # always gets `-L` (surface the build log), plus whatever the shared NIX_FLAGS env adds,
            # e.g. `NIX_FLAGS='--rebuild -Lvv' nix run .#grow -- dev up`. Same var across every app.
            nixFlags=( -L )
            read -r -a extraNixFlags <<< "''${NIX_FLAGS:-}"
            nixFlags+=( "''${extraNixFlags[@]}" )

            # GH_TOKEN for the inner maven build: mint from the one org-owned GitHub App
            # (.secrets github.app), never a personal `gh auth token`. The inner `nix build`
            # reads GH_TOKEN impurely (hostGHToken), so export it here first.
            # Mint ONLY when no token is already provided. In-cluster the Tekton step already set
            # GH_TOKEN from PaC's App git_auth (the App-provided k8s Secret) AND .secrets is present
            # but sops-ENCRYPTED at rest (no git smudge filter) — so keying off `[ -f .secrets ]`
            # would wrongly try to mint from an unreadable encrypted key. Key off GH_TOKEN instead:
            # set ⇒ in-cluster/explicit, use it; empty ⇒ the operator dropped `gh auth token`, mint.
            if [ -z "''${GH_TOKEN:-}" ] && [ -f .secrets ]; then
              # Non-fatal: if the mint fails (e.g. an encrypted key), do not abort the whole build.
              # The `if` condition swallows the non-zero exit (set -e does not fire on a tested cmd).
              if minted="$(mint-gh-app-token)"; then
                GH_TOKEN="$minted"; export GH_TOKEN
                echo "==> using a GitHub App token for the build (packages:read)" >&2
              else
                echo "==> mint-gh-app-token failed; falling back to the ambient GH_TOKEN" >&2
              fi
            fi

            echo "==> building seed-master from the store" >&2
            jar="$(nix build .#seed-master "''${nixFlags[@]}" --no-link --print-out-paths)/share/java/seed-master.jar"
            [ -f "$jar" ] || { echo "error: store jar not found at $jar" >&2; exit 1; }

            # Swap Pulumi.yaml's binary to the store jar for the duration; always
            # restore the committed file on exit so the dev loop is untouched.
            # Trap signals too (not just EXIT) so a Ctrl-C during `pulumi up`
            # still restores; the self-heal above covers a hard kill (-9).
            backup="$(mktemp)"
            cp Pulumi.yaml "$backup"
            trap 'cp "$backup" Pulumi.yaml; rm -f "$backup"' EXIT INT TERM HUP
            sed -E "s#^( *binary: ).*#\1$jar#" "$backup" > Pulumi.yaml

            case "$action" in
              preview)
                echo "==> pulumi preview --stack $stack (binary=$jar)" >&2
                pulumi preview --stack "$stack" "$@"
                ;;
              up)
                echo "==> pulumi up --yes --stack $stack (binary=$jar)" >&2
                pulumi up --yes --stack "$stack" "$@"
                ;;
            esac
          '';
        };

        # In-cluster / standalone render: `nix run .#render-manifests`. The ONE
        # definition of the render (build manifests-cli + `publish`), so the Tekton step
        # no longer hand-scripts an mvn+java block that drifts from the dev/release build
        # — it just calls this app. It is deliberately NOT a hermetic nix build: the mvn
        # runs against an EXTERNAL cache DIR ($MAVEN_BUILD_CACHE — a repo-local default
        # standalone, the maven-cache PVC mount in-cluster), so the build is INCREMENTAL
        # across renders (a nix sandbox would wall the cache off → a cold build each time).
        # Nix's role is the toolchain SSOT (jdk25/maven/shfmt/shellcheck/which via
        # runtimeInputs — no `flox activate` needed) + the single definition; it is
        # oblivious to what backs the cache dir (a host dir or a PVC — just a path).
        renderApp = pkgs.writeShellApplication {
          name = "rke2lab-render-manifests";
          # nodejs: the render's `publish` runs the cdk8s App, whose Java bindings (jsii) shell out
          # to `node` (`Cannot run program "node"` without it — the dev shell gets it via cdk8s-cli).
          # openssh: the publish signs the rendered commit with RKE2LAB_SIGNING_KEY via `ssh-keygen`
          # (SshCommitSigner → jgit), which openssh provides (`Cannot run program "ssh-keygen"`
          # without it). Both were implicit on dev / the deleted cicd/nix FloxEnv.
          runtimeInputs = [ pkgs.coreutils pkgs.nix pkgs.git pkgs.jdk25 pkgs.nodejs pkgs.openssh mintGhAppTokenApp ];
          text = ''
            if [ ! -f pom.xml ] || [ ! -f flake.nix ]; then
              echo "error: run from the rke2lab repo root" >&2
              exit 1
            fi

            # cluster + node are the branch coordinate (manifests/<cluster>) publish
            # delivers to — required (the jar rejects a publish without them). Positional
            # so the caller reads `nix run .#render-manifests -- <cluster> <node>`; anything
            # after is forwarded to the publish verb.
            cluster="''${1:?usage: nix run .#render-manifests -- <cluster> <node> [publish args...]}"
            node="''${2:?usage: nix run .#render-manifests -- <cluster> <node> [publish args...]}"
            shift 2

            # ONE knob: M2_REPO. The persistent build cache is DERIVED from it (its parent), so
            # maven.repo.local == M2_REPO and the maven-build-cache lands beside it
            # ($M2_REPO/../build-cache) → incremental across renders without a second variable.
            # M2_REPO must be writable by the nix build user (in-cluster: the PVC, single-user nix;
            # dev multi-user: a world-writable ~/.m2 or a /tmp repo). Override MAVEN_BUILD_CACHE
            # only for a deliberately split layout.
            : "''${M2_REPO:?set M2_REPO to your maven repository, e.g. \$HOME/.m2/repository}"
            export MAVEN_BUILD_CACHE="''${MAVEN_BUILD_CACHE:-$(dirname "$M2_REPO")}"

            # Build the manifests-cli exe from the store — the shared reactor derivation
            # stages the CRDs, resolves deps + gates spotless, so the fat jar is
            # self-contained (no runtime maven cache, bootstrap or CRD staging). Mirrors
            # `grow`'s `nix build .#seed-master`. The build is IMPURE (mvnHost reads
            # M2_REPO + GH_TOKEN) — the caller sets them (in-cluster: the maven-cache PVC +
            # the PaC App token). The inner build is a SEPARATE child nix process, so it always
            # gets `-L` (surface the build log) plus the shared NIX_FLAGS env (e.g.
            # `NIX_FLAGS='--rebuild -Lvv'`); OUTER `nix run` flags build THIS wrapper, not the child.
            nixFlags=( -L )
            read -r -a extraNixFlags <<< "''${NIX_FLAGS:-}"
            nixFlags+=( "''${extraNixFlags[@]}" )
            # GH_TOKEN for the inner maven build: on the operator (dev render), mint from the one
            # org-owned GitHub App (.secrets github.app), never a personal token. In-cluster the
            # Tekton step already set GH_TOKEN from PaC's App token, so the guard below skips.
            # Mint ONLY when no token is already provided. In-cluster the Tekton step already set
            # GH_TOKEN from PaC's App git_auth (the App-provided k8s Secret) AND .secrets is present
            # but sops-ENCRYPTED at rest (no git smudge filter) — so keying off `[ -f .secrets ]`
            # would wrongly try to mint from an unreadable encrypted key. Key off GH_TOKEN instead:
            # set ⇒ in-cluster/explicit, use it; empty ⇒ the operator dropped `gh auth token`, mint.
            if [ -z "''${GH_TOKEN:-}" ] && [ -f .secrets ]; then
              # Non-fatal: if the mint fails (e.g. an encrypted key), do not abort the whole build.
              # The `if` condition swallows the non-zero exit (set -e does not fire on a tested cmd).
              if minted="$(mint-gh-app-token)"; then
                GH_TOKEN="$minted"; export GH_TOKEN
                echo "==> using a GitHub App token for the build (packages:read)" >&2
              else
                echo "==> mint-gh-app-token failed; falling back to the ambient GH_TOKEN" >&2
              fi
            fi

            echo "==> building manifests-cli from the store" >&2
            jar="$(nix build .#manifests-cli "''${nixFlags[@]}" --no-link --print-out-paths)/share/java/manifests.jar"
            [ -f "$jar" ] || { echo "error: store jar not found at $jar" >&2; exit 1; }

            # publish prepares a linked worktree on manifests/<cluster> and FOLLOWS its HEAD facet, so
            # this render reproduces exactly what the grow recorded at the branch root (manifest.yaml)
            # rather than resetting to the operator posture (the mesh-drop footgun) — see
            # docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc § render-config. To do that
            # it first fast-forwards the local branch to origin's tip via `git fetch origin` from this
            # checkout; in-cluster that fetch must authenticate as the App, so persist PaC's token as
            # an http.extraheader on the LOCAL repo (no token in a URL, no global config) — the
            # domain's fetch AND the jgit push that follows both read it. Standalone the operator's
            # ambient git credentials cover it, so this is a no-op when GH_TOKEN is unset.
            export GIT_SSL_CAINFO="${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt"
            if [ -n "''${GH_TOKEN:-}" ]; then
              # GitHub git-over-HTTPS wants BASIC auth (x-access-token:<token>), NOT bearer. base64
              # -w0 so no newline breaks the header.
              authHeader="AUTHORIZATION: basic $(printf 'x-access-token:%s' "$GH_TOKEN" | base64 -w0)"
              git config --local "http.https://github.com/.extraheader" "$authHeader"
            fi

            # Steady-state re-render: `update` FOLLOWS the branch HEAD facet (the grow's recorded
            # posture), renders into the plot the exe LOCATES itself (.local.d/render/<cluster>) +
            # signed ff-push manifests/<cluster>. cluster/node are trailing key=value args
            # (discoverable in `update` help); RKE2LAB_SIGNING_KEY + RKE2LAB_PUSH_TOKEN come from the
            # caller's environment (the Tekton step / the operator). A first render of a cluster is
            # the grow's job (seed-master), not this wrapper.
            java -jar "$jar" update cluster="$cluster" node="$node" "$@"
          '';
        };

        # flux9s: K9s-style TUI for Flux. nixpkgs pins 0.7.2, which chokes on our
        # RKE2/headscale serving chain; the upstream v1.0.3 release is built from
        # source so the flox dev env tracks latest. kube-rs links OpenSSL, so the
        # same pkg-config + openssl inputs as the nixpkgs derivation. cargoHash
        # pins the vendored crate closure.
        flux9sPkg = pkgs.rustPlatform.buildRustPackage (finalAttrs: {
          pname = "flux9s";
          version = "1.0.3";

          src = pkgs.fetchFromGitHub {
            owner = "dgunzy";
            repo = "flux9s";
            rev = "v${finalAttrs.version}";
            hash = "sha256-9xk46wwQUegUJJWOLG3EkeTgHQ4qfhGISqcDUcsdBos=";
          };

          cargoHash = "sha256-VXWg6NrKNFRPwK6A3ttrwUSzLx3BjMDthtRwLX9Zrsg=";

          nativeBuildInputs = [ pkgs.pkg-config ];
          buildInputs = [ pkgs.openssl ];

          meta = {
            description = "K9s-inspired terminal UI for monitoring Flux GitOps resources";
            mainProgram = "flux9s";
            homepage = "https://flux9s.ca/";
            license = pkgs.lib.licenses.asl20;
          };
        });

      in {
        packages = {
          inherit planJar networkBlueprintYaml networkBlueprintJson dataplanJson seedMasterJar;
          seed-master = seedMasterJar;
          manifests-cli = manifestsCliJar;
          incus-client = incusClient;
          grow = growApp;
          mint-gh-app-token = mintGhAppTokenApp;
          flux9s = flux9sPkg;
        }
        // floxNriPluginPackages
        // floxControllerPackages
        // rke2AdoptionControllerPackages
        // toolchainPackages
        # manage-tailnet (darwin-only): rke2lab OVER-SEEDS ndh's bare package with its own context —
        # the OAuth client ndh user-mirrors at ~/.local/share/ndh/tailnet.tailscale.client
        # (TailscaleOauthClientGateway.NDH_CLIENT_PATH). The wrapper pre-supplies --client-secret-file
        # so a caller here just runs `manage-tailnet <action>` and it authenticates without a
        # .secrets/age key — the incus GROW's local.Command, or the operator by hand. Per-invocation
        # policy (--stale-after, --yes) stays the caller's. The flox env installs it as
        # `github:seedmatic/rke2lab#manage-tailnet`; flake.lock is the single ndh pin.
        // pkgs.lib.optionalAttrs (system == "aarch64-darwin") {
          manage-tailnet = pkgs.writeShellScriptBin "manage-tailnet" ''
            exec ${inputs.ndh.packages.${system}.manage-tailnet}/bin/manage-tailnet \
              --client-secret-file "$HOME/.local/share/ndh/tailnet.tailscale.client" "$@"
          '';
        };

        apps.grow = {
          type = "app";
          program = "${growApp}/bin/rke2lab-grow";
          meta.description = "Grow the cluster: build the seed-master jar and run pulumi preview/up against it";
        };

        apps.render-manifests = {
          type = "app";
          program = "${renderApp}/bin/rke2lab-render-manifests";
          meta.description = "Render manifests/<cluster> + signed ff-push (build manifests-cli against $MAVEN_BUILD_CACHE, then publish)";
        };

        # Standalone: `GH_TOKEN=$(nix run .#mint-gh-app-token) nix build .#seed-master` — mint a
        # packages:read GitHub App token from .secrets for a direct (non-grow) maven build.
        apps.mint-gh-app-token = {
          type = "app";
          program = "${mintGhAppTokenApp}/bin/mint-gh-app-token";
          meta.description = "Mint a short-lived packages:read GitHub App installation token from .secrets (github.app)";
        };

        # Regenerate the committed network-blueprint.json from the netplan jar. Run on a
        # jar-capable host (bioskop/darwin) from the repo root: `nix run .#regen-blueprint`.
        # The Java stays the source of truth; this materializes it into the checked-in file
        # that every system then reads purely (no darwin builder on the consume path).
        apps.regen-blueprint = {
          type = "app";
          program = toString (pkgs.writeShellScript "regen-blueprint" ''
            set -euo pipefail
            cat ${networkBlueprintJson} > network-blueprint.json
            echo "regenerated network-blueprint.json from ${networkBlueprintJson}"
          '');
          meta.description = "Regenerate the committed network-blueprint.json from the netplan jar";
        };

        # Regenerate the committed dataplan.json from the plan jar (`dataset` plane). The storage
        # twin of regen-blueprint: `nix run .#regen-dataplan` on a jar-capable host, then ndh pulls
        # the refreshed layout via `lib.dataplan`. seed-master's DataplanLayout stays the truth.
        apps.regen-dataplan = {
          type = "app";
          program = toString (pkgs.writeShellScript "regen-dataplan" ''
            set -euo pipefail
            cat ${dataplanJson} > dataplan.json
            echo "regenerated dataplan.json from ${dataplanJson}"
          '');
          meta.description = "Regenerate the committed dataplan.json from the plan jar (dataset plane)";
        };

        # Stage the flox-controller CRD (single-sourced from the flox-controller flake)
        # onto the manifest-synthesis classpath for the DEV loop (`./mvnw -pl :manifests`).
        # Release builds stage it inside seedMasterJar. The staged crds/ dir is
        # gitignored — controller-gen stays the single source, never a committed copy.
        apps.stage-flox-controller-crd = {
          type = "app";
          program = toString (pkgs.writeShellScript "stage-flox-controller-crd" ''
            set -euo pipefail
            ${stageFloxControllerCrds}
            echo "staged flox-controller CRD into ${floxControllerCrdResourceDir}/ from ${floxControllerCrds}"
          '');
          meta.description = "Stage the flox-controller CRD (from the flake) onto the manifest-synthesis classpath";
        };

        # Stage the ClusterAdoption CRD (single-sourced from the rke2-adoption-controller
        # flake) onto the manifest-synthesis classpath for the DEV loop. Release builds stage
        # it inside seedMasterJar. The staged crds/ dir is gitignored — controller-gen stays
        # the single source, never a committed copy.
        apps.stage-rke2-adoption-controller-crd = {
          type = "app";
          program = toString (pkgs.writeShellScript "stage-rke2-adoption-controller-crd" ''
            set -euo pipefail
            ${stageRke2AdoptionControllerCrds}
            echo "staged ClusterAdoption CRD into ${floxControllerCrdResourceDir}/ from ${rke2AdoptionControllerCrds}"
          '');
          meta.description = "Stage the ClusterAdoption CRD (from the flake) onto the manifest-synthesis classpath";
        };

        # Anti-drift gate: fail if the committed JSON diverges from the jar output
        # (compared canonically via jq -S, so formatting never trips it). Defined ONLY on
        # blueprintSystem — absent elsewhere, so `nix flake check` on an aarch64-linux node
        # never realizes the darwin-pinned jar.
        checks = {
          # Anti-drift gate for the cross-world node label (Java ↔ nix, which can't import each
          # other): extract NODE_FLOX_RUNTIME_LABEL from ManifestAnnotations.java (the single
          # source, used by the flox DaemonSet nodeSelectors) and fail unless the nixos oneshot
          # rke2lab-node-labels carries the same "<label>=true". Keeps the boot-time node label the
          # DaemonSets select on in lock-step with the Java constant.
          node-label-concord = pkgs.runCommand "node-label-concord" { } ''
            src=${./osgi/domains/manifests/manifests-contract/src/main/java/io/seedmatic/rke2lab/manifests/contract/ManifestAnnotations.java}
            label="$(sed -n 's/.*NODE_FLOX_RUNTIME_LABEL = "\([^"]*\)".*/\1/p' "$src")"
            if [ -z "$label" ]; then
              echo "could not extract NODE_FLOX_RUNTIME_LABEL from ManifestAnnotations.java" >&2
              exit 1
            fi
            if ! grep -qF "$label=true" ${./nixos/rke2.nix}; then
              echo "nixos/rke2.nix (rke2lab-node-labels oneshot) must carry '$label=true' —" >&2
              echo "it drifted from ManifestAnnotations.NODE_FLOX_RUNTIME_LABEL (DaemonSet nodeSelectors)." >&2
              exit 1
            fi
            touch $out
          '';
        } // pkgs.lib.optionalAttrs (system == blueprintSystem) {
          blueprint-fresh =
            pkgs.runCommand "blueprint-fresh" { nativeBuildInputs = [ pkgs.jq ]; } ''
              jq -S . ${./network-blueprint.json} > committed.json
              jq -S . ${networkBlueprintJson} > fresh.json
              if ! diff -u committed.json fresh.json; then
                echo "network-blueprint.json is STALE vs the netplan Java source." >&2
                echo "Regenerate on this jar-capable host: nix run .#regen-blueprint" >&2
                exit 1
              fi
              touch $out
            '';
          # The dataplan twin: fail if committed dataplan.json diverges from the plan jar output.
          dataplan-fresh =
            pkgs.runCommand "dataplan-fresh" { nativeBuildInputs = [ pkgs.jq ]; } ''
              jq -S . ${./dataplan.json} > committed.json
              jq -S . ${dataplanJson} > fresh.json
              if ! diff -u committed.json fresh.json; then
                echo "dataplan.json is STALE vs the dataplan Java source (DataplanLayout)." >&2
                echo "Regenerate on this jar-capable host: nix run .#regen-dataplan" >&2
                exit 1
              fi
              touch $out
            '';
        };
      }
    )) // {
      # The NixOS node substrate — the immutable Incus container image every RKE2 node
      # boots from (docs/architecture/nixos-substrate/substrate-model.adoc). System-pinned to
      # aarch64-linux (Incus containers on the Apple-Silicon hypervisor); build via bioskop-nixos.
      # Build the artifacts: `.config.system.build.{squashfs,metadata}`, then
      # `incus image import <metadata>/tarball/*.tar.xz <squashfs> --alias rke2lab/node-base`.
      # Per-node identity (node-ip, hostname, token) is injected at instance creation, not baked.
      nixosConfigurations.rke2-node-base = nixpkgs.lib.nixosSystem {
        system = "aarch64-linux";
        specialArgs = {
          inherit flox flox-runtime flox-controller;
          ndh = inputs.ndh;
        };
        modules = [
          "${nixpkgs}/nixos/modules/virtualisation/lxc-container.nix"
          sops-nix.nixosModules.sops
          ./nixos
        ];
      };

      # Flat, system-independent export consumed by nix-darwin-home as
      # `rke2lab.lib.networkBlueprint.deriveMacs` (no `lib.${system}` selector).
      # Built once on `blueprintSystem`; the data is the same everywhere.
      lib = {
        inherit networkBlueprint;
        # The ZFS dataset layout ndh pulls into catalog.datasets (the dataplan — storage twin
        # of networkBlueprint). Pure committed data, system-independent; seed-master's
        # DataplanLayout is the source of truth, materialised into ./dataplan.json at regen.
        dataplan = dataplanData;
        # Raw YAML store path for inspection (the pinned, canonical build).
        networkBlueprintYamlPath = "${networkBlueprintYaml}/network-blueprint.yaml";
      };

      # TEMP federation probe (Phase 1): proves rke2lab sees ndh's home-LAN facts
      # across the follows="" cut. Removed once real consumption is wired.
      _federationProbe = { ndhLan = inputs.ndh.catalog.netplan.lan; };
    };
}
