{
  description = "RKE2 lab infrastructure and network blueprints";

  inputs = {
    # ndh (nix-darwin-home) IS an input of this flake — see `ndh` at the bottom of this block.
    # ndh does not pin this flake back: it pins its DELIVERY, the fabric/plan orphan, which pins
    # nothing — so the edge is one-directional and nothing is cut. What keeps it so is relock's
    # plan, which refuses any pin cycle among the heads of fabric/heads, read from their locks;
    # no guard here names an input (one did, `nix-darwin-home`, which no input was ever called).
    #
    # ★ Why it survived is the part worth keeping: the comment said `nix-darwin-home` while the
    # input says `ndh`, so NO search ever touched both. An incomplete rename does not leave a
    # visible inconsistency — it leaves two vocabularies that never meet. The repo's memory
    # records that trap for NOTES ("translate renamed words before searching"); it applies to
    # code just as hard, and every rotten pin found alongside this one was hiding behind a
    # renamed word.
    #
    # Every SEEDMATIC-owned input below is an INDIRECT id (`url = "ndh"`), resolved through
    # nix's registry: the default targets live in the committed flake-registry.json, and a
    # gitignored flake-registry.local.json re-aims one locally (see the [hook] in
    # .flox/env/manifest.toml). FOUR of the five used to name `/develop` — the sneakiest form
    # of this rot, because a branch that will never be deleted never errors: it just silently
    # supplies whatever `develop` holds instead of the line actually being worked on.
    #
    # Hermeticity is unaffected, measured: an indirect id is consulted only when RE-locking,
    # and flake.lock still records a revision, so anyone evaluating from the lock — with no
    # registry at all — resolves exactly what we resolved.

    # Use flake-commons as aggregator to stay synchronized with nix-darwin-home
    flake-commons.url = "flake-commons";
    # Follow flake-commons versions
    nixpkgs.follows = "flake-commons/nixpkgs";
    # The unstable nixpkgs flake-commons already locks, for the tools the stable line lags on: the
    # pulumi toolchain (26.05 stable has pulumi 3.192 and no pulumi-java).
    nixpkgs-unstable.follows = "flake-commons/nixpkgs-unstable";
    flake-utils.follows = "flake-commons/flake-utils";
    # flox baked into the NixOS node substrate (nixosConfigurations.rke2-node-base) as a nix
    # derivation, replacing the Debian-era runtime installer. Same source the family already
    # locks (github:flox/flox), via the aggregator so it stays in sync with nix-darwin-home.
    flox.follows = "flake-commons/flox";

    # sops-nix for per-node secret decryption on the substrate: the age identity is delivered at
    # boot over devlxd (never baked), and sops-install-secrets decrypts the cluster-CA bundle +
    # tokens into /run. Followed through flake-commons so the version stays in lock-step with
    # nix-darwin-home. Only the flake input and its `nixosModules.sops` are consumed here — the
    # nix-darwin-home sops *module* is NOT imported.
    sops-nix.follows = "flake-commons/sops-nix";

    # The flox runtime flake owns the NRI plugin + per-workload package
    # definitions. We re-export its outputs here so the deployable artifacts
    # build through this top-level entry point (and the aarch64-linux NRI plugin
    # cross-builds via the configured linux-builder). The shim is now its own repo
    # (a fork), pinned to the `develop` integration branch (seedmatic convention —
    # releases abandoned for now, co-dev on develop alongside flox-controller); the
    # `follows` below dedup its aggregator + nixpkgs against ours so there is a
    # single resolved version set across the flakes.
    flox-runtime.url = "flox-runtime";
    flox-runtime.inputs.nixpkgs.follows = "nixpkgs";
    flox-runtime.inputs.flake-utils.follows = "flake-utils";
    flox-runtime.inputs.flake-commons.follows = "flake-commons";

    # The flox-controller flake owns the FloxEnv CRD + the node-agent controller
    # (runtime flox-env delivery — the companion to flox-runtime's NRI plugin). We
    # re-export its outputs so the controller binary + OCI image build through this
    # entry point (image cross-builds via the linux-builder, like the NRI plugin).
    # Referenced on the `develop` integration branch (seedmatic convention);
    # follows dedup its aggregator + nixpkgs against ours.
    flox-controller.url = "flox-controller";
    flox-controller.inputs.nixpkgs.follows = "nixpkgs";
    flox-controller.inputs.flake-utils.follows = "flake-utils";
    flox-controller.inputs.flake-commons.follows = "flake-commons";

    # The seed-incluster flake owns the ClusterAdoption CRD + the in-cluster
    # controller that adopts running RKE2-on-Incus control planes into CAPI (Pulumi
    # bootstraps, this describes it in CAPI so cold-starts stop re-provisioning). It lives
    # on a DEDICATED orphan branch of THIS repo (a separate Go build artifact, not the Maven
    # reactor) — a same-repo branch input, pinned by rev in flake.lock. Re-exported so the
    # OCI image cross-builds via the linux-builder, like flox-controller. follows dedup.
    seed-incluster.url = "seed-incluster";
    seed-incluster.inputs.nixpkgs.follows = "nixpkgs";
    seed-incluster.inputs.flake-utils.follows = "flake-utils";
    seed-incluster.inputs.flake-commons.follows = "flake-commons";

    # Federation (Direction A): rke2lab consumes ndh's home-LAN facts
    # (catalog.netplan.lan). ndh pins rke2lab's DELIVERY — the fabric/plan orphan — not
    # this flake, so the pair is no cycle and nothing is cut. Consume ONLY `.lan` (a
    # self-contained constant); NEVER `.segments`/`.asns`: they union rke2lab's own plan,
    # so reading them back would feed the plan into itself.
    ndh.url = "ndh";

    # rke2lab's own delivery, pinned back like any head: netplan.json + dataplan.json on the
    # fabric/plan orphan, which `nix run .#publish-plan` writes. Data, not a flake.
    fabric-plan.url = "fabric-plan";
    fabric-plan.flake = false;
    ndh.inputs.flake-commons.follows = "flake-commons";
  };

  outputs =
    inputs@{
      self,
      nixpkgs,
      flake-utils,
      flox-runtime,
      flox-controller,
      seed-incluster,
      flox,
      sops-nix,
      ...
    }:
    let
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

      # The system the NODE runs — Incus containers on the Apple-Silicon hypervisor. ONE literal,
      # because two consumers must agree on it: nixosConfigurations.rke2-node-base (which BAKES the
      # images) and the image-ref staging (which PUBLISHES their RepoTags). A dockerTools tag is the
      # image's output hash and therefore per-system, so publishing a ref computed for the staging
      # HOST names an image no node holds — measured 2026-09-28, the render asked for the darwin tag
      # while containerd held the linux one, and there is no registry to pull the difference from.
      nodeSystem = "aarch64-linux";

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
      mavenToolchain = pkgs: {
        inherit (pkgs)
          jdk25
          maven
          shfmt
          shellcheck
          which
          ;
      };
      # The operator toolchain: what the flox seat installs as `github:seedmatic/rke2lab#…` and the
      # apps (the grow) run, so the two cannot diverge. Applied to the UNSTABLE nixpkgs below — the
      # stable line has pulumi 3.192 and no pulumi-java. Not `pulumi-bin`: Hydra never builds it
      # ("Output limit exceeded"), so every host assembled 4.6 GiB of providers this repo does not
      # use. Resource providers (`command`, `incus`) are fetched by pulumi at runtime.
      operatorToolchain = pkgs: {
        inherit (pkgs)
          pulumi
          pulumictl
          cdk8s-cli
          tektoncd-cli
          clusterctl
          k9s
          dasel
          graphviz
          ;
        pulumi-java = pkgs.pulumiPackages.pulumi-java;
      };
      mavenBuildInputs = pkgs: builtins.attrValues (mavenToolchain pkgs);

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

      # The Maven invocations of each build, written ONCE and replayed twice: online by mavenDepsFor,
      # which is how its repository holds exactly what the builds resolve (a dependency:go-offline
      # misses the plugins' lazily resolved deps), and offline by the builds themselves. `mvn` is the
      # name of the shell function the caller defined.
      stagingExtensionSteps = mvn: ''
        ${mvn} -f bom/pom.xml install
        ${mvn} -f build-parent/pom.xml install
        ${mvn} -f maven-embed-staging-ext/pom.xml -pl :staging-extension -am clean install
      '';
      # java-systemd is the one dependency no public Maven repository serves: Central stops at 2.1.0,
      # and GitHub Packages refuses an anonymous read even of a public package. So it is built from
      # its public tag, at the version the bom pins — the same jar, without a token anywhere.
      javaSystemdVersion = builtins.head (
        builtins.match ".*<java-systemd.version>([^<]*)</java-systemd.version>.*" (
          builtins.readFile ./bom/pom.xml
        )
      );
      javaSystemdSrcFor =
        pkgs:
        pkgs.fetchFromGitHub {
          owner = "seedmatic";
          repo = "java-systemd";
          rev = "v${javaSystemdVersion}";
          hash = "sha256-TtlrxZ1Nqg0/tcz1BIP/1ds2vshXI6vFBcTSikFX4Jw=";
        };
      javaSystemdSteps = pkgs: mvn: ''
        cp -r ${javaSystemdSrcFor pkgs} "$TMPDIR/java-systemd"
        chmod -R u+w "$TMPDIR/java-systemd"
        (cd "$TMPDIR/java-systemd" && ${mvn} -t "$NIX_BUILD_TOP/$sourceRoot/.mvn/toolchains.xml" -DskipTests install)
      '';
      planSteps = pkgs: mvn: ''
        ${mvn} install:install-file -Dfile=pom.xml -DpomFile=pom.xml
        ${mvn} -f build-parent/pom.xml install
        ${mvn} -f bom/pom.xml install
        ${mvn} -pl :plan-cli -am -Dshfmt.version=${pkgs.shfmt.version} clean package -DskipTests
      '';

      # Every third-party artifact the Maven builds resolve, as ONE fixed-output derivation: the
      # only derivation here allowed the network, which the sandbox grants a FOD because its content
      # is pinned by outputHash. It replays the builds' own steps online (the whole reactor covers
      # manifests-cli's `-pl` subset), then drops what is not a download — rke2lab's own artifacts and
      # java-systemd, which embed their build time and are why stagingExtensionRepoFor cannot be a
      # FOD — and the
      # resolver's bookkeeping, which records WHEN and FROM WHERE rather than WHAT. The CRD staging
      # is skipped: it puts files in the jars, not dependencies in the repository.
      #
      # A dependency change fails every Maven build with a hash mismatch until outputHash is
      # updated: set it to lib.fakeHash, build `maven-deps` by revision, take the hash it reports.
      mavenDepsFor =
        pkgs:
        pkgs.stdenv.mkDerivation {
          name = "rke2lab-maven-deps";
          src = mavenSource;
          nativeBuildInputs = mavenBuildInputs pkgs;
          buildPhase = ''
            export HOME="$TMPDIR"
            mvnDeps() { mvn -Dmaven.repo.local="$out" -Dmaven.build.cache.enabled=false "$@"; }
            ${javaSystemdSteps pkgs "mvnDeps"}
            cp -r . "$TMPDIR/bootstrap"
            (cd "$TMPDIR/bootstrap" && rm .mvn/extensions.xml && ${stagingExtensionSteps "mvnDeps"})
            ${planSteps pkgs "mvnDeps"}
            mvnDeps -Dshfmt.version=${pkgs.shfmt.version} -DskipTests -Dflox.crd-staging.skip=true clean package
            rm -rf "$out/io/seedmatic/rke2lab" "$out/com/github/thjomnx/java-systemd"
            find "$out" -type f \( -name _remote.repositories -o -name '*.lastUpdated' \
              -o -name resolver-status.properties -o -name 'maven-metadata-*.xml' \) -delete
          '';
          installPhase = "true";
          dontFixup = true;
          outputHashMode = "recursive";
          outputHashAlgo = "sha256";
          outputHash = "sha256-JXDU+SUpd8O5eGBKWRw8Z5EFLzCeh29clBOqA3Ixl3o=";
        };

      # Shared Maven-in-nix plumbing for both reactor derivations: a buildPhase prelude
      # that sets up a writable $M2_REPO over the fixed-output dependency repository, SEEDS
      # the staging-extension closure into it, and defines `mvnHost` — an OFFLINE `mvn`
      # pinned to that primary. Each derivation opens its buildPhase with
      # `${mavenHostPrelude pkgs}`, then calls `mvnHost <goals>`.
      #
      # The CORE extensions resolve at bootstrap from the primary itself (that early resolver
      # consults no tail — see stagingExtensionClosure), hence a symlinked copy of the
      # dependency repository rather than a read-only tail over it: links keep it cheap, and
      # the directories stay writable for what Maven records.
      mavenHostPrelude = pkgs: ''
        : "Point M2_REPO at the writable build dir so the JVM's user.home-derived default"
        : "local repo ($HOME/.m2/repository\) IS the seeded primary — covers the bootstrap"
        : "resolver whether it honors -Dmaven.repo.local or falls back to user.home."
        export HOME="$TMPDIR"
        M2_REPO="$HOME/.m2/repository"
        mkdir -p "$M2_REPO"
        cp -rs --no-preserve=mode ${mavenDepsFor pkgs}/. "$M2_REPO/"

        cp -r ${javaSystemdRepoFor pkgs}/. "$M2_REPO/"
        chmod -R u+w "$M2_REPO/com/github/thjomnx"

        # Seed the staging-extension closure from the NIX-built repo — the same store path for
        # every mode, so the operator standalone build and the in-cluster render replay the
        # identical bootstrap.
        for a in ${builtins.concatStringsSep " " stagingExtensionClosure}; do
          src="${stagingExtensionRepoFor pkgs}/$a"
          if [ -d "$src" ]; then
            mkdir -p "$M2_REPO/$(dirname "$a")"
            cp -r "$src" "$M2_REPO/$a"
            chmod -R u+w "$M2_REPO/$a"
          fi
        done

        if [ ! -d "$M2_REPO/io/seedmatic/rke2lab/staging-extension" ]; then
          echo "FATAL: staging-extension not seeded from '${stagingExtensionRepoFor pkgs}'" >&2
          exit 1
        fi

        # The maven-build-cache is off: the nix store already caches the jars per source, and a
        # build cache in a fresh $TMPDIR could only record entries no later build reads.
        #
        # NO GH_TOKEN, for a CACHING reason: interpolating it puts it in the derivation hash, and PaC
        # mints a fresh token per PipelineRun, so the reactor was rebuilt on every render. Nothing
        # resolved here is private (measured 2026-09-24: every dependency comes from a public source).
        # ⚠️ The GitHub Packages repos in .mvn/settings.xml are still declared; anonymous requests to
        # them fail and maven falls through. If an artifact ever becomes truly private, mavenDepsFor
        # fails LOUDLY on resolution — the moment to supply the token by a stable PATH whose CONTENT
        # is not hashed (the pattern nixos/rke2.nix uses with /run/rke2lab/nix-github.conf).
        mvnHost() {
          mvn -o -Dmaven.repo.local="$M2_REPO" -Dmaven.build.cache.enabled=false "$@"
        }
      '';

      # The jars' SOURCE: what their Maven builds read, not the whole repository. Under `src = ./.`
      # every commit — a doc, the flake.lock a relock writes — moved the jars' derivations (a
      # doc-only commit moved 8 of 27 packages), so none was ever reused and no impact guard could
      # set a change aside. The list is the Maven build cache's own: its glob and includes are what
      # a module's cache key hashes, so reading them here keeps the two from drifting. Outside that
      # key a build still needs the poms, the `.mvn/` directories and `mvnw`, and the bnd fragment
      # every bundle -includes from bundle-parent (a project input of its own there).
      mavenSource =
        let
          lib = nixpkgs.lib;
          root = ./.;
          config = builtins.readFile ./.mvn/maven-build-cache-config.xml;
          tagged =
            tag:
            map builtins.head (
              builtins.filter builtins.isList (builtins.split "<${tag}>([^<]*)</${tag}>" config)
            );
          globs = tagged "glob";
          patterns = lib.splitString "," (
            lib.removeSuffix "}" (lib.removePrefix "{" (lib.trim (builtins.head globs)))
          );
          includes = map lib.trim (tagged "include");
          matchesGlob =
            name:
            lib.any (
              pattern:
              let
                around = lib.splitString "*" pattern;
              in
              lib.hasPrefix (builtins.elemAt around 0) name
              && lib.hasSuffix (builtins.elemAt around 1) name
              && builtins.stringLength name >= builtins.stringLength pattern - 1
            ) patterns;
          isModule =
            dir: builtins.pathExists (if dir == "" then root + "/pom.xml" else root + "/${dir}/pom.xml");
          moduleInput =
            rel:
            let
              segments = lib.splitString "/" rel;
              name = lib.last segments;
              prefixOf = n: lib.concatStringsSep "/" (lib.take n segments);
              underInclude =
                include:
                if lib.hasSuffix "/" include then
                  let
                    dir = lib.removeSuffix "/" include;
                    at = lib.lists.findFirstIndex (segment: segment == dir) null (lib.init segments);
                  in
                  at != null && isModule (prefixOf at) && matchesGlob name
                else
                  name == include && isModule (prefixOf (builtins.length segments - 1));
            in
            lib.any underInclude includes;
          declared =
            rel:
            let
              segments = lib.splitString "/" rel;
            in
            builtins.elem (lib.last segments) [
              "pom.xml"
              "mvnw"
              "mvnw.cmd"
            ]
            || builtins.elem ".mvn" segments
            || rel == "osgi/bundle-parent/annotations.bnd";
        in
        assert lib.assertMsg (
          builtins.length globs == 1 && patterns != [ ] && includes != [ ]
        ) "mavenSource: no <glob> or <include> parsed from .mvn/maven-build-cache-config.xml";
        assert lib.assertMsg (lib.all (
          pattern: builtins.length (lib.splitString "*" pattern) == 2
        ) patterns) "mavenSource: every build-cache glob pattern must hold exactly one '*'";
        assert lib.assertMsg (builtins.pathExists (
          root + "/osgi/bundle-parent/annotations.bnd"
        )) "mavenSource: osgi/bundle-parent/annotations.bnd is declared but gone";
        lib.fileset.toSource {
          inherit root;
          fileset = lib.fileset.fromSource (
            lib.sources.cleanSourceWith {
              src = root;
              filter =
                path: type:
                let
                  rel = lib.removePrefix "${toString root}/" (toString path);
                in
                type == "directory" || declared rel || moduleInput rel;
            }
          );
        };

      # The staging-extension build closure, built by NIX (not seeded from a host ~/.m2 nor a
      # per-mode Tekton bootstrap task). The `.mvn/extensions.xml` core extension is a reactor
      # module needed at mvn STARTUP (before any reactor build) — a chicken-egg — so it is built
      # WITHOUT itself (rm .mvn/extensions.xml), offline over mavenDepsFor, and its rke2lab
      # artifacts alone go to $out, a maven-repo store path. Every downstream reactor build
      # (`mavenHostPrelude`) then SEEDS the closure from this ONE store path — so the operator
      # standalone (seed-outcluster's build) and the in-cluster render replay the identical
      # bootstrap, zero per-mode duplication. Not a FOD itself: the jars built here embed their
      # build time, so two builds never hash the same.
      stagingExtensionRepoFor =
        pkgs:
        pkgs.stdenv.mkDerivation {
          name = "rke2lab-staging-extension-repo";
          src = mavenSource;
          nativeBuildInputs = mavenBuildInputs pkgs;
          buildPhase = ''
            export HOME="$TMPDIR"
            repo="$TMPDIR/repository"
            mkdir -p "$repo"
            cp -rs --no-preserve=mode ${mavenDepsFor pkgs}/. "$repo/"
            mvnOffline() { mvn -o -Dmaven.repo.local="$repo" "$@"; }
            rm .mvn/extensions.xml
            ${stagingExtensionSteps "mvnOffline"}
          '';
          installPhase = ''
            mkdir -p $out/io/seedmatic
            cp -r "$repo/io/seedmatic/rke2lab" $out/io/seedmatic/
          '';
          dontFixup = true;
        };

      # java-systemd at the bom's version, built offline over mavenDepsFor; its artifacts alone go
      # to $out, seeded into every reactor build by `mavenHostPrelude`.
      javaSystemdRepoFor =
        pkgs:
        pkgs.stdenv.mkDerivation {
          name = "rke2lab-java-systemd-repo";
          src = mavenSource;
          nativeBuildInputs = mavenBuildInputs pkgs;
          buildPhase = ''
            export HOME="$TMPDIR"
            repo="$TMPDIR/repository"
            mkdir -p "$repo"
            cp -rs --no-preserve=mode ${mavenDepsFor pkgs}/. "$repo/"
            mvnOffline() { mvn -o -Dmaven.repo.local="$repo" "$@"; }
            ${javaSystemdSteps pkgs "mvnOffline"}
          '';
          installPhase = ''
            mkdir -p $out/com/github/thjomnx
            cp -r "$repo/com/github/thjomnx/java-systemd" $out/com/github/thjomnx/
            rm -f $out/com/github/thjomnx/java-systemd/maven-metadata-local.xml
          '';
          dontFixup = true;
        };

      # netplan JAR build, parameterized by pkgs so the per-system `packages`
      # output can build it locally while `lib` uses the pinned set.
      planJarFor =
        pkgs:
        pkgs.stdenv.mkDerivation {
          name = "rke2lab-plan";
          src = mavenSource;

          nativeBuildInputs = mavenBuildInputs pkgs;

          buildPhase = ''
            ${mavenHostPrelude pkgs}

            # Install parent POM and BOM first, then build the plan CLI module. plan-cli
            # multiplexes both plan exports (network + dataset), driving the netplan +
            # dataplan scions; it builds through the reactor (`-pl :plan-cli -am`) so `-am`
            # pulls the netplan-core / dataplan-contract siblings from source.
            ${planSteps pkgs "mvnHost"}
          '';

          installPhase = ''
            mkdir -p $out/share/java
            cp exec/plan-cli/target/plan-cli-*-exec.jar $out/share/java/rke2lab-plan.jar
          '';
        };

      # Generate the network blueprint YAML from the Java source of truth.
      networkBlueprintYamlFor =
        pkgs:
        let
          planJar = planJarFor pkgs;
        in
        pkgs.stdenv.mkDerivation {
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
      # dataplan.json (already JSON), so the CLI emits it raw; no yq conversion needed. What the
      # sources produce, which dataplan-fresh compares to the pinned delivery — the storage twin
      # of networkBlueprintJson; seed-outcluster's DataplanLayout stays the source of truth.
      dataplanJsonFor =
        pkgs:
        let
          planJar = planJarFor pkgs;
        in
        pkgs.runCommand "dataplan.json" { buildInputs = [ pkgs.jdk25 ]; } ''
          # The OSGi FrameworkLauncher narrates its boot on stdout before the
          # export, so keep only the JSON document (from its top-level `{` on)
          # — otherwise a stray "HH:MM:SS.mmm INFO …" line lands at line 1 and
          # fromJSON chokes ("unexpected number literal").
          java -jar ${planJar}/share/java/rke2lab-plan.jar dataset export \
            | sed -n '/^{/,$p' > $out
        '';

      # The consumed plan is rke2lab's own DELIVERY, pinned back: netplan.json and dataplan.json on
      # the fabric/plan orphan, read as pure data — no IFD, no build. The reason is NOT "no
      # builder": the blueprint sits on the eval hot-path of *every* host config — the catalog's
      # netplan.lan.hosts `//`-merges the addressing map, so reading any host entry forces the
      # whole JSON. An IFD here would drag a Java/Maven jar build onto that path and break routine
      # ops that no remote builder fixes. The Java stays the source of truth: publish-plan delivers
      # it, and blueprint-fresh / dataplan-fresh compare the pin to what the sources produce. ndh
      # pulls both through `lib`; seed-outcluster's DataplanLayout is the dataplan's truth.
      networkBlueprintData = builtins.fromJSON (builtins.readFile "${inputs.fabric-plan}/netplan.json");
      dataplanData = builtins.fromJSON (builtins.readFile "${inputs.fabric-plan}/dataplan.json");

      # Regeneration only (NOT on the consumed data path): the jar-built YAML on the
      # pinned blueprintSystem, surfaced via networkBlueprintYamlPath for inspection.
      # The blueprintSystem darwin pin now affects ONLY regeneration, never consumption.
      blueprintPkgs = nixpkgs.legacyPackages.${blueprintSystem};
      networkBlueprintYaml = networkBlueprintYamlFor blueprintPkgs;

      # The network blueprint, with what its nix consumers search for once here.
      networkBlueprint = networkBlueprintData // {
        # The vmnet SUPERNET — the span every cluster's /21 is carved from, so a node can name
        # "all my sibling clusters on this host" without knowing which cluster it is. Searched
        # once here rather than in each consumer: the segments are a list, and the node's
        # networkd config has no business walking it.
        vmnetSupernet =
          (nixpkgs.lib.findFirst (
            segment: segment.name == "vmnet"
          ) (throw "fabric-plan's netplan.json carries no 'vmnet' segment") networkBlueprintData.segments)
          .cidr;
      };
    in
    # The two systems anything here runs on: the operator seat (aarch64-darwin) and the Incus containers
    # of the Apple-Silicon NixOS guests (aarch64-linux) — the cluster nodes and the Tekton render pods
    # among them. Not eachDefaultSystem: its x86_64-darwin no longer evaluates (Nixpkgs 26.11 dropped
    # it), and both x86_64 systems only cost every relock pass an evaluation that measures nothing.
    (flake-utils.lib.eachSystem [ blueprintSystem nodeSystem ] (
      system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

        planJar = planJarFor pkgs;

        # Build the seed-outcluster bootstrap app (and the manifests jar it embeds)
        # as a single reactor build, so the deployable artifact Pulumi runs comes
        # from the immutable store rather than a mutable target/. seed-outcluster
        # depends on manifests, netplan, systemd-contract and sdks/incus, so the
        # whole reactor is built once from the parent pom. Mirrors netplanJar's
        # Maven-in-nix pattern (shared `mavenHostPrelude` — full rationale there):
        # offline, every dependency from mavenDepsFor.
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
        # Stages into the shell var "$dest" the CALLER sets — the profile-specific Maven build dir
        # (${project.build.directory}/generated-resources/crds, i.e. target~claude / target~nxmatic /
        # target), NOT a hardcoded path: build-helper embeds from ${project.build.directory}, so a
        # fixed `target/` would miss a `-Pnxmatic` build's target~nxmatic and ship a CRD-less jar.
        stageFloxControllerCrds = nixpkgs.lib.optionalString (floxControllerCrds != null) ''
          mkdir -p "$dest"
          install -m 644 ${floxControllerCrds}/*.yaml "$dest/"
        '';

        # Same as stageFloxControllerCrds, for the ClusterAdoption CRD — the two share the
        # crds/ resource dir, so the DaemonSet + management units emit both into the cluster's
        # crds layer. Empty when the adoption flake has no output for this system.
        stageSeedInclusterCrds = nixpkgs.lib.optionalString (seedInclusterCrds != null) ''
          mkdir -p "$dest"
          install -m 644 ${seedInclusterCrds}/*.yaml "$dest/"
        '';

        # The seed-incluster ClusterRole, staged into $destRbac/seed-incluster/ — a PER-CONTROLLER
        # subdir so multiple controllers' generic `role.yaml` never collide in the shared /rbac/
        # resource dir (SeedInclusterManifestsUnit includes /rbac/seed-incluster/role.yaml). Empty
        # when the flake has no rbac output.
        stageSeedInclusterRbac = nixpkgs.lib.optionalString (seedInclusterRbac != null) ''
          mkdir -p "$destRbac/seed-incluster"
          install -m 644 ${seedInclusterRbac}/*.yaml "$destRbac/seed-incluster/"
        '';

        # The flox-controller ClusterRole, staged into $destRbac/flox-controller/ (same per-controller
        # subdir convention). FloxControllerManifestsUnit includes /rbac/flox-controller/role.yaml.
        stageFloxControllerRbac = nixpkgs.lib.optionalString (floxControllerRbac != null) ''
          mkdir -p "$destRbac/flox-controller"
          install -m 644 ${floxControllerRbac}/*.yaml "$destRbac/flox-controller/"
        '';

        # One reactor build, factored: the shared Maven-in-nix closure (repo src, the
        # mavenToolchain, the CRD staging, the `mvnHost` prelude, the spotless shfmt pin)
        # captured ONCE, parameterized by the mvn module selector and the exec jars to
        # install. Every store-built exe resolves deps + stages CRDs + gates spotless
        # identically — so seed-outcluster (whole reactor) and manifests-cli (the render exe)
        # come from the SAME build logic, no drift.
        buildReactorExe =
          {
            pname,
            mvnArgs ? "",
            jars,
          }:
          pkgs.stdenv.mkDerivation {
            name = "rke2lab-${pname}";
            src = mavenSource;

            nativeBuildInputs = mavenBuildInputs pkgs;

            buildPhase = ''
              ${mavenHostPrelude pkgs}
              # CRDs stage into target/generated-resources/crds (crdStagingDir), which `clean` wipes —
              # so clean FIRST, then install the store CRDs, then package. `-Dflox.crd-staging.skip`
              # tells manifests-core's exec plugin to SKIP its own `nix run .#stage-*-crd` (a nested
              # nix run has no daemon/network in this sandbox); a plain `./mvnw` build omits the flag,
              # so that exec staging runs post-clean there.
              mvnHost -Dshfmt.version=${pkgs.shfmt.version} -DskipTests ${mvnArgs} clean
              # The store build runs the DEFAULT profile, so the module build dir is plain target/ —
              # crdStagingDir. (A -Pnxmatic/-Pclaude build would use target~*, but this sandbox does not.)
              dest=${crdStagingDir}
              ${stageFloxControllerCrds}
              ${stageSeedInclusterCrds}
              destRbac=${rbacStagingDir}
              ${stageSeedInclusterRbac}
              ${stageFloxControllerRbac}
              destRefs=${imageRefStagingDir}
              ${stageFloxControllerImageRef}
              ${stageFloxCarrierImageRef}
              mvnHost -Dshfmt.version=${pkgs.shfmt.version} -DskipTests -Dflox.crd-staging.skip=true ${mvnArgs} package
            '';

            installPhase = ''
              mkdir -p $out/share/java
              ${nixpkgs.lib.concatMapStrings (jar: "cp ${jar.glob} $out/share/java/${jar.name}\n") jars}
            '';
          };

        # The seed-outcluster bootstrap app + the manifests jar it embeds, as one whole-reactor
        # build (seed-outcluster pulls manifests/netplan/systemd/incus, so the reactor builds
        # once from the parent pom) — the deployable artifact Pulumi runs from the immutable
        # store. mvnArgs defaults to "" (the exact whole-reactor `clean package` it always ran).
        seedOutclusterJar = buildReactorExe {
          pname = "seed-outcluster";
          jars = [
            {
              glob = "exec/seed-outcluster/target/seed-outcluster-*-exec.jar";
              name = "seed-outcluster.jar";
            }
            {
              glob = "exec/manifests-cli/target/manifests-cli-*-exec.jar";
              name = "manifests.jar";
            }
          ];
        };

        # The manifests-cli render exe alone — a lean `-pl :manifests-cli -am` build the
        # `render-manifests` app runs (build manifests-cli from its own store source → run the jar), mirroring
        # deploy/seed-outcluster. Same shared closure, so CRD staging + spotless gate are
        # identical; the resulting fat jar is self-contained (CRDs baked in) so running it
        # needs no maven cache, bootstrap or CRD staging at runtime.
        manifestsCliJar = buildReactorExe {
          pname = "manifests-cli";
          mvnArgs = "-pl :manifests-cli -am";
          jars = [
            {
              glob = "exec/manifests-cli/target/manifests-cli-*-exec.jar";
              name = "manifests.jar";
            }
          ];
        };

        # Per-system inspectable build of the blueprint YAML, plus its JSON projection: what the
        # sources produce, which blueprint-fresh compares to the pinned delivery. Computed from
        # the sources alone — the generator never reads what it publishes: its drvPath does not
        # move with ndh or fabric-plan overridden by an empty input. Off the eval path.
        networkBlueprintYaml = networkBlueprintYamlFor pkgs;
        networkBlueprintJson =
          pkgs.runCommand "network-blueprint.json"
            {
              nativeBuildInputs = [ pkgs.yq-go ];
            }
            ''
              yq -o=json '.' ${networkBlueprintYamlFor pkgs}/network-blueprint.yaml > $out
            '';

        # The dataplan as the sources produce it — the `dataset` plane emits JSON directly, so
        # this is the export as-is (no yq); dataplan-fresh compares it to the pin.
        dataplanJson = dataplanJsonFor pkgs;

        # Darwin-buildable incus client, now OWNED BY ndh and re-exported here.  ndh owns the incus
        # daemon (its NixOS modules) and the operator's trust to it, so the client belongs beside
        # them; this end keeps the `.#incus-client` output name so the flox manifest's
        # `incus-client.flake = "github:seedmatic/rke2lab#incus-client"` and its lock stay valid.
        # Same direction as ndh's manage-tailnet below, which rke2lab also consumes.
        #
        # (Why a client-only variant at all: `pkgs.incus` is the full daemon and Linux-only, while
        # `incus.passthru.client` needs just Go + cmd/incus. That reasoning now lives once, in ndh.)
        incusClient = inputs.ndh.packages.${system}.incus-client;

        # The operator toolchain (see `operatorToolchain`), from the unstable nixpkgs flake-commons
        # locks: pulumi 3.263 and pulumi-java 1.37.2, the versions fleet's `pulumi` env takes from the
        # flox catalog. The grow takes pulumi and its Java language host from this same set.
        operatorToolchainPackages = operatorToolchain inputs.nixpkgs-unstable.legacyPackages.${system};
        pulumiPkg = operatorToolchainPackages.pulumi;
        pulumiJavaPkg = operatorToolchainPackages.pulumi-java;

        # NRI plugin (+ debug) re-exported from the flox runtime flake, so the
        # aarch64-linux Go binary builds through this entry point at build time
        # (cross-built via the configured linux-builder) rather than on the node
        # at runtime. The node consumes the resulting store path via its gcroot.
        # Guarded so darwin-only eval of `packages` does not fail when the
        # runtime flake lacks an output for a given system.
        floxRuntimePackages = flox-runtime.packages.${system} or { };
        floxNriPluginPackages =
          (
            if floxRuntimePackages ? flox-nri-plugin then
              { inherit (floxRuntimePackages) flox-nri-plugin; }
            else
              { }
          )
          // (
            if floxRuntimePackages ? flox-nri-plugin-debug then
              { inherit (floxRuntimePackages) flox-nri-plugin-debug; }
            else
              { }
          );

        # flox-controller re-exported the same way: the node-agent binary + its OCI
        # image (the DaemonSet distribution artifact, cross-built via the
        # linux-builder). Same darwin-eval guard as the NRI plugin.
        floxControllerPackages =
          let
            ctlPkgs = flox-controller.packages.${system} or { };
          in
          (if ctlPkgs ? flox-controller then { inherit (ctlPkgs) flox-controller; } else { })
          // (if ctlPkgs ? flox-controller-image then { inherit (ctlPkgs) flox-controller-image; } else { })
          // (if ctlPkgs ? flox-controller-crds then { inherit (ctlPkgs) flox-controller-crds; } else { })
          // (if ctlPkgs ? flox-controller-rbac then { inherit (ctlPkgs) flox-controller-rbac; } else { });

        # The flox-controller CRD as a store path (single-sourced from the flake —
        # controller-gen output, never vendored). Staged onto the manifest-synthesis
        # classpath (crds/ resource) by seedOutclusterJar for release and by
        # `nix run .#stage-flox-controller-crd` for the dev loop.
        floxControllerCrds = (flox-controller.packages.${system} or { }).flox-controller-crds or null;

        # The flox-controller ClusterRole (controller-gen output from its markers). Staged onto the
        # /rbac/flox-controller/ classpath resource dir — FloxControllerManifestsUnit includes it.
        floxControllerRbac = (flox-controller.packages.${system} or { }).flox-controller-rbac or null;

        # The two baked OCI images' RepoTags, staged onto the classpath at /image-refs/ for the
        # synthesis to READ.
        #
        # ★ They are published rather than written down because both tags are now derived from the
        # image's CONTENT (dockerTools' output hash, the explicit tag omitted). They used to be
        # static — `0.0.0-develop` and `0.1.0` — restated as Java literals in FloxDebugPolicy under a
        # javadoc pleading that they "MUST match the RepoTag". With the air-gap path (baked tar +
        # rke2 auto-import) and `imagePullPolicy: IfNotPresent`, a node already holding a tag NEVER
        # replaces it, so a rebuild shipped new content that no pod ever ran and nothing reported it.
        # A derived tag makes the reference unwritable by hand, which is exactly the point.
        # ⚠️ The NODE's system, never `${system}`. The tag is the image's OUTPUT HASH, so it differs
        # per system — and the ref is staged from a DARWIN host while the node runs the aarch64-linux
        # build. Taking `packages.${system}` published `…:b1zy8v3z…` (darwin) for an image the node
        # holds as `…:9r99n0zs…` (linux): a reference to something no node has, which is the very
        # failure this whole change removes. Measured 2026-09-28 on the first cold start after it.
        #
        # The carrier's ref avoids this by construction (it is built from
        # nixosConfigurations.rke2-node-base.pkgs); this is the same discipline, stated explicitly
        # because here the wrong system is the one in scope.
        floxControllerImageRef =
          (flox-controller.packages.${nodeSystem} or { }).flox-controller-image-ref or null;

        # The carrier's tag comes from the NODE's pkgs — the very ones
        # nixosConfigurations.rke2-node-base bakes the image with — so the tag the synthesis renders
        # and the image on the node cannot disagree. A separately imported nixpkgs would agree only
        # BY COINCIDENCE (no overlays on the node today), and a coincidence is the fragility this
        # whole change removes.
        floxCarrierImageRef =
          let
            carrier = import ./nixos/flox-carrier-image.nix {
              pkgs = self.nixosConfigurations.rke2-node-base.pkgs;
            };
          in
          pkgs.writeTextDir "flox-carrier" "${carrier.imageName}:${carrier.imageTag}";

        # Staged into target/ (generated) like the CRDs and the RBAC, so `mvn clean` wipes it and a
        # stale ref can never linger on the classpath — which for a content-derived tag would mean
        # rendering a reference to an image the node does not have.
        imageRefStagingDir = "osgi/domains/manifests/manifests-core/target/generated-resources/image-refs";

        stageFloxControllerImageRef = nixpkgs.lib.optionalString (floxControllerImageRef != null) ''
          mkdir -p "$destRefs"
          install -m 644 ${floxControllerImageRef}/* "$destRefs/"
        '';

        stageFloxCarrierImageRef = ''
          mkdir -p "$destRefs"
          install -m 644 ${floxCarrierImageRef}/* "$destRefs/"
        '';
        # Staged into the module's target/ (generated), NOT src/: `mvn clean` wipes it, so a
        # renamed/removed CRD never lingers as a stale checked-out source file (and no .gitignore
        # marker is buried in src to advertise a "resource" dir that is really generated output).
        # build-helper add-resource (manifests-core pom) puts it on the classpath at /crds/. Holds
        # BOTH CRD sets (flox-controller + seed-incluster).
        crdStagingDir = "osgi/domains/manifests/manifests-core/target/generated-resources/crds";

        # Same idea for the seed-incluster ClusterRole (single-sourced from its +kubebuilder:rbac
        # markers via the flake): staged onto the classpath at /rbac/ so SeedInclusterManifestsUnit
        # INCLUDES it instead of hand-listing the rules (which drift). build-helper add-resource
        # (manifests-core pom) puts it at /rbac/; the staged dir is gitignored.
        rbacStagingDir = "osgi/domains/manifests/manifests-core/target/generated-resources/rbac";

        # seed-incluster re-exported the same way as flox-controller: the
        # controller binary + the ClusterAdoption CRD store path. The OCI image is NO
        # LONGER re-exported or baked — the controller rides the flox runtime (the
        # cluster-api/seed-incluster flox env installs the binary from the
        # flox-catalog), so only the binary + CRD are needed here. Same darwin-eval guard.
        seedInclusterPackages =
          let
            seedPkgs = seed-incluster.packages.${system} or { };
          in
          (if seedPkgs ? seed-incluster then { inherit (seedPkgs) seed-incluster; } else { })
          // (if seedPkgs ? seed-incluster-crds then { inherit (seedPkgs) seed-incluster-crds; } else { })
          // (if seedPkgs ? seed-incluster-rbac then { inherit (seedPkgs) seed-incluster-rbac; } else { });

        # The ClusterAdoption CRD as a store path (single-sourced from the flake —
        # controller-gen output, never vendored). Staged onto the same crds/ classpath
        # resource dir as the flox-controller CRD.
        seedInclusterCrds = (seed-incluster.packages.${system} or { }).seed-incluster-crds or null;

        # The seed-incluster ClusterRole as a store path (controller-gen output from the markers,
        # never vendored). Staged onto the /rbac/ classpath resource dir.
        seedInclusterRbac = (seed-incluster.packages.${system} or { }).seed-incluster-rbac or null;

        # Maven-build toolchain re-exported as individual packages, so the flox
        # env pins each tool to this flake's version
        # (e.g. `shfmt.flake = "github:seedmatic/rke2lab#shfmt"`) instead of the
        # overlapping fleet includes. This flake is the source of truth; flox
        # follows it, keeping the dev loop aligned with the Maven build's
        # spotless gate.
        toolchainPackages = mavenToolchain pkgs;

        # Cluster GROW: `nix run .#grow -- <stack>`. Runs `pulumi up` against
        # the STORE-built seed-outcluster jar instead of the mutable Maven target the
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
        # a JRE to run the seed-outcluster jar, and the incus client the provider
        # shells out to. The PULUMI_* vars are set here too, mirroring the flox
        # env's [vars]: local file:// backend under the repo, empty passphrase.
        # The grow's profile, decided from the COMMITTED stack file (nix/grow-profile.d/profile.sh); the same
        # factory its bats suite tests.
        growProfile = import ./nix/grow-profile.d/package.nix { inherit pkgs; };

        growApp = pkgs.writeShellApplication {
          name = "rke2lab-grow";
          runtimeInputs = [
            pkgs.coreutils
            pkgs.nix
            pkgs.git
            growProfile
            pulumiPkg
            pulumiJavaPkg
            pkgs.jdk25
            incusClient
          ];
          text = ''
                        usage() {
                          cat >&2 <<'USAGE'
            usage: nix run .#grow -- <stack> [preview|up] [pulumi args...] 3< <(pass show seedmatic/cluster-age/<n>)

              <stack>    Pulumi stack name (required), e.g. dev
              preview    read-only diff, no apply (default)
              up         apply, non-interactive, after a preview you read (pulumi up --yes --skip-preview)
              -h,--help  this message

            Anything after the verb is passed through to pulumi (e.g. --diff, --target).
            The cluster's age identity is read from $RKE2LAB_CLUSTER_AGE, which your own shell sets
            (RKE2LAB_CLUSTER_AGE="$(pass show seedmatic/cluster-age/<n>)"); the grow refuses to run without it — except for a stack whose COMMITTED Pulumi.<stack>.yaml
            sets rke2lab:cluster:profile: throwaway, which is grown by the installed rke2lab-throwaway command.
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

                        # The profile first: a throwaway stack takes no age identity (rke2lab-grow-profile says why and
                        # refuses a stack and a marker that disagree), so the identity check below is the fleet's only.
                        profile="$(rke2lab-grow-profile "$stack")"
                        if [ "$profile" = throwaway ]; then
                          export RKE2LAB_GROW_PROFILE=throwaway
                        else
                          # The cluster's age identity (rke2lab-cluster-age-<n>) arrives in a variable the
                          # operator's OWN shell sets on this command line, never one this script or a session sets:
                          #   RKE2LAB_CLUSTER_AGE="$(pass show seedmatic/cluster-age/<n>)" nix run …#grow -- <stack> <action>
                          # Not a descriptor: a pipe is drained by the first process of Pulumi's tree that reads it.
                          # Every fleet grow renders the sops-age Secret from it, so a grow without it fails here, loud.
                          [ -n "''${RKE2LAB_CLUSTER_AGE:-}" ] || {
                            echo "error: no cluster age identity — prefix this command with: RKE2LAB_CLUSTER_AGE=\"\$(pass show seedmatic/cluster-age/<n>)\"" >&2
                            exit 1
                          }
                          export RKE2LAB_CLUSTER_AGE
                        fi

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

                        # The pass-through args reach pulumi, and pulumi's output reaches a session when operations runs
                        # the grow: a verbose, traced or log-flowing run writes the engine's internals, the program's
                        # inputs among them, where a session reads (--otel-traces and --profiling export the same). Refused here (security, S5 review, 2026-10-18); the
                        # operator who wants -v9 calls pulumi directly, and that log stays theirs.
                        refuseLogging() {
                          echo "error: $1 — the grow keeps pulumi's output plain; run pulumi yourself for that" >&2
                          exit 2
                        }
                        passed=( "$@" )
                        for ((i = 0; i < ''${#passed[@]}; i++)); do
                          arg="''${passed[i]}"
                          case "$arg" in
                            --logflow | --logflow=* | --logtostderr | --logtostderr=* | --tracing | --tracing=* | \
                              --otel-traces | --otel-traces=* | --profiling | --profiling=*)
                              refuseLogging "$arg is refused"
                              ;;
                            -v | --verbose) level="''${passed[i + 1]:-}" ;;
                            -v=* | --verbose=*) level="''${arg#*=}" ;;
                            -v[0-9]*) level="''${arg#-v}" ;;
                            *) continue ;;
                          esac
                          [[ $level =~ ^[0-3]$ ]] || refuseLogging "$arg ''${level}: a verbosity above 3 is refused"
                        done

                        # The debug switches pulumi and its providers read from the environment, scrubbed from the one
                        # pulumi runs in, for the same reason.
                        scrub=( -u PULUMI_DEBUG_GRPC -u PULUMI_DEBUG_COMMANDS -u TF_LOG -u TF_LOG_PATH )
                        for name in ''${!PULUMI_LOG@}; do
                          scrub+=( -u "$name" )
                        done

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

                        # The inner seed-outcluster build is a SEPARATE child nix process — flags on the
                        # OUTER `nix run` (e.g. `nix run .#grow -L …`) go to building THIS wrapper, not it. So it
                        # always gets `-L` (surface the build log), plus whatever the shared NIX_FLAGS env adds,
                        # e.g. `NIX_FLAGS='--rebuild -Lvv' nix run .#grow -- dev up`. Same var across every app.
                        nixFlags=( -L )
                        read -r -a extraNixFlags <<< "''${NIX_FLAGS:-}"
                        nixFlags+=( "''${extraNixFlags[@]}" )

                        # The inner build names THIS flake's own store source, never `.`: the checkout it runs in
                        # may hold decrypted sops files, and evaluating the working tree would copy them into the
                        # store in clear. So it builds what the outer `nix run` was given, not the working tree.
                        echo "==> building seed-outcluster from the store" >&2
                        jar="$(nix build "${self}#seed-outcluster" "''${nixFlags[@]}" --no-link --print-out-paths)/share/java/seed-outcluster.jar"
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
                            env "''${scrub[@]}" pulumi preview --stack "$stack" "$@"
                            ;;
                          up)
                            # --skip-preview (the operator's decision D6, 2026-10-18): the program then runs ONCE, and
                            # the descriptor is a pipe it reads once; `up` without it would run the program twice and
                            # the second run would find the pipe empty. The preview the operator reads is the separate
                            # `preview` action, before their go.
                            echo "==> pulumi up --yes --skip-preview --stack $stack (binary=$jar)" >&2
                            env "''${scrub[@]}" pulumi up --yes --skip-preview --stack "$stack" "$@"
                            ;;
                        esac
          '';
        };

        # The operator's two gestures on a throwaway cluster (nix/throwaway.d/throwaway.sh): `up` through #grow
        # itself, so its guards exist once; `reset` in its own order. The same factory its bats suite tests.
        throwawayApp = import ./nix/throwaway.d/package.nix {
          inherit pkgs;
          grow = "${growApp}/bin/rke2lab-grow";
          growProfile = "${growProfile}/bin/rke2lab-grow-profile";
          pulumi = "${pulumiPkg}/bin/pulumi";
          incus = "${incusClient}/bin/incus";
          kubectl = "${pkgs.kubectl}/bin/kubectl";
          # busybox 1.37's multi-arch index, as Docker Hub served it (🎛️, 2026-10-24: the body's sha256 recomputed); the
          # node pulls docker.io directly, no mirror.
          probeImage = "docker.io/library/busybox@sha256:bdf57e528e45e4433820e045b29b4597825a1c9e38353532d90a01445013f82e";
        };

        # In-cluster / standalone render: `nix run .#render-manifests`. The ONE
        # definition of the render (build manifests-cli + `publish`), so the Tekton step
        # no longer hand-scripts an mvn+java block that drifts from the dev/release build
        # — it just calls this app. The manifests-cli build is a sealed nix build (offline over
        # maven-deps), so the node's store is its only cache. Nix's role is also the toolchain
        # SSOT (jdk25/maven/shfmt/shellcheck/which via runtimeInputs — no `flox activate`
        # needed).
        renderApp = pkgs.writeShellApplication {
          name = "rke2lab-render-manifests";
          # nodejs: the render's `publish` runs the cdk8s App, whose Java bindings (jsii) shell out
          # to `node` (`Cannot run program "node"` without it — the dev shell gets it via cdk8s-cli).
          # openssh: the publish signs the rendered commit with RKE2LAB_SIGNING_KEY via `ssh-keygen`
          # (SshCommitSigner → jgit), which openssh provides (`Cannot run program "ssh-keygen"`
          # without it). Both were implicit on dev / the deleted cicd/nix FloxEnv.
          runtimeInputs = [
            pkgs.coreutils
            pkgs.nix
            pkgs.git
            pkgs.jdk25
            pkgs.nodejs
            pkgs.openssh
          ];
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

            # Build the manifests-cli exe from the store — the shared reactor derivation
            # stages the CRDs, resolves deps + gates spotless, so the fat jar is
            # self-contained (no runtime maven cache, bootstrap or CRD staging). Mirrors
            # `grow`'s inner build, and like it names this flake's store source, never `.` (a checkout
            # may hold decrypted sops files). GH_TOKEN is not read by the build, only by the
            # fetches and the push around it. The inner build is a SEPARATE child nix process, so it always
            # gets `-L` (surface the build log) plus the shared NIX_FLAGS env (e.g.
            # `NIX_FLAGS='--rebuild -Lvv'`); OUTER `nix run` flags build THIS wrapper, not the child.
            nixFlags=( -L )
            read -r -a extraNixFlags <<< "''${NIX_FLAGS:-}"
            nixFlags+=( "''${extraNixFlags[@]}" )
            echo "==> building manifests-cli from the store" >&2
            jar="$(nix build "${self}#manifests-cli" "''${nixFlags[@]}" --no-link --print-out-paths)/share/java/manifests.jar"
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
              # The header holds the token in the clear in .git/config, and the source workspace may
              # outlive the run: it lives exactly as long as the render that needs it. JGit reads the
              # repo config, not GIT_CONFIG_* env, so the env route would not reach the push.
              trap 'git config --local --unset-all "http.https://github.com/.extraheader" || true' EXIT
            fi

            # Steady-state re-render: `update` FOLLOWS the branch HEAD facet (the grow's recorded
            # posture), renders into an in-memory plot (manifests/<cluster>, nothing checked out) +
            # signed ff-push manifests/<cluster>. cluster/node are trailing key=value args
            # (discoverable in `update` help); RKE2LAB_SIGNING_KEY + RKE2LAB_PUSH_TOKEN come from the
            # caller's environment (the Tekton step / the operator). A first render of a cluster is
            # the grow's job (seed-outcluster), not this wrapper.
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

        # relock — nix-flake-commons' `lib.mkRelockApp`, the one implementation every seedmatic flake
        # shares, so every repo in the chain applies the same rule (docs/architecture/patterns/
        # flake-lock-propagation.adoc). rke2lab supplies its name; the heads, the order and what each
        # head pins are read from nix-flake-commons:fabric/heads.
        relockApp = inputs.flake-commons.lib.mkRelockApp {
          inherit pkgs;
          name = "rke2lab";
        };
      in
      pkgs.lib.recursiveUpdate
        {
          packages = {
            inherit
              planJar
              networkBlueprintYaml
              networkBlueprintJson
              dataplanJson
              seedOutclusterJar
              ;
            seed-outcluster = seedOutclusterJar;
            manifests-cli = manifestsCliJar;
            # Exposed so the bootstrap closure can be BUILT and INSPECTED on its own — it is otherwise
            # only interpolated into two other build phases, which makes it impossible to measure
            # (determinism, size, what it actually downloaded) without building something else first.
            staging-extension-repo = stagingExtensionRepoFor pkgs;
            maven-deps = mavenDepsFor pkgs;
            incus-client = incusClient;
            grow = growApp;
            throwaway = throwawayApp;
            flux9s = flux9sPkg;
          }
          // floxNriPluginPackages
          // floxControllerPackages
          // seedInclusterPackages
          // toolchainPackages
          // operatorToolchainPackages
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

          # Re-exported from ndh (which owns the incus HOSTS) so the operator finds it in the same
          # `nix run .#…` list as the grow, rather than having to remember which repo defines it —
          # joining a member is a step of bringing a cluster up, so this is where it is looked for.
          # Same shape as the `incus-client` / `manage-tailnet` re-exports above; flake.lock is the
          # single ndh pin, so there is one version of the tool, not a copy.
          #
          # ⚠️ It reaches each member at `nixos.<domain>`, the FABRIC name — deliberately not the
          # tailnet one, "which is what a renew changes". Measured 2026-09-27: a factory-reset
          # nikopol-nixos came back on a new tailnet address under a new device, and this app was
          # unaffected while `tailscale ssh` to the same host failed on a stale host key.
          # (the re-export itself is merged in at the end of this block, for the systems ndh serves)

          apps.grow = {
            type = "app";
            program = "${growApp}/bin/rke2lab-grow";
            meta.description = "Grow the cluster: build the seed-outcluster jar and run pulumi preview/up against it";
          };

          apps.throwaway = {
            type = "app";
            program = "${throwawayApp}/bin/rke2lab-throwaway";
            meta.description = "Grow or reset a throwaway cluster stack: each its own go, each checked by itself";
          };

          apps.render-manifests = {
            type = "app";
            program = "${renderApp}/bin/rke2lab-render-manifests";
            meta.description = "Render manifests/<cluster> + signed ff-push (build manifests-cli from the store, then publish)";
          };

          # Derive the plan and DELIVER it: `nix run .#publish-plan`, from the repo root. The plan jar's
          # `publish` verb sows and grafts ghapp → netplan → dataplan → fabric; the fabric scion commits
          # netplan.json + dataplan.json onto fabric/plan's head in memory (jgit, no checkout), signs the
          # commit and fast-forward pushes fabric/plan with a token minted from the App. openssh:
          # ssh-keygen signs the commit (SshCommitSigner → jgit); no git on the PATH.
          apps.publish-plan = {
            type = "app";
            program = toString (
              pkgs.writeShellApplication {
                name = "publish-plan";
                runtimeInputs = [
                  pkgs.coreutils
                  pkgs.jdk25
                  pkgs.openssh
                ];
                text = ''
                  if [ ! -f pom.xml ] || [ ! -f flake.nix ]; then
                    echo "error: run from the rke2lab repo root" >&2
                    exit 1
                  fi
                  exec java -jar ${planJar}/share/java/rke2lab-plan.jar publish
                '';
              }
              + "/bin/publish-plan"
            );
            meta.description = "Derive the plan and deliver it to fabric/plan (signed commit, fast-forward push)";
          };

          # The envs vendored under .flox-envs.d arrive WITHOUT their manifest.lock: fleet ignores
          # it, and the lock is what makes an env includable — so a fresh checkout cannot activate
          # the flox seat until this runs ("manifest and lockfile are out of sync"). The locks stay
          # gitignored on purpose: fleet publishes manifests, each checkout realises its own lock.
          # The include graph is WALKED, not listed — a composed env must be locked after the ones
          # it includes, and a hardcoded list rots the next time fleet adds an include.
          apps.lock-flox-envs = {
            type = "app";
            program = toString (
              pkgs.writeShellScript "lock-flox-envs" ''
                set -euo pipefail

                cd "$(git rev-parse --show-toplevel)"

                declare -A walked=()

                lock_env() {
                  local env="$1"
                  local dir=".flox-envs.d/$env"
                  [ -n "''${walked[$env]:-}" ] && return 0
                  walked[$env]=1
                  if [ ! -f "$dir/.flox/env/manifest.toml" ]; then
                    echo "lock-flox-envs: no vendored env '$env' — is the subtree imported?" >&2
                    exit 1
                  fi
                  while read -r dep; do
                    [ -n "$dep" ] && lock_env "$dep"
                  done < <(sed -n "s|^[[:space:]]*dir = '\.\./\([^']*\)'.*|\1|p" "$dir/.flox/env/manifest.toml")
                  if [ -f "$dir/.flox/env/manifest.lock" ]; then
                    echo "  $env already locked"
                  else
                    echo "  locking $env"
                    flox upgrade --dir "$dir" >/dev/null
                  fi
                }

                while read -r env; do
                  lock_env "$env"
                done < <(sed -n 's|^[[:space:]]*{ dir = "\./\.flox-envs\.d/\([^"]*\)".*|\1|p' .flox/env/manifest.toml)

                echo "vendored flox envs locked — 'flox activate' is ready"
              ''
            );
            meta.description = "Lock the flox envs vendored under .flox-envs.d so the flox seat can activate (fresh-checkout bootstrap)";
          };

          # Stage the flox-controller CRD (single-sourced from the flox-controller flake)
          # onto the manifest-synthesis classpath for the DEV loop (`./mvnw -pl :manifests`).
          # Release builds stage it inside seedOutclusterJar. The staged crds/ dir is
          # gitignored — controller-gen stays the single source, never a committed copy.
          apps.stage-flox-controller-crd = {
            type = "app";
            program = toString (
              pkgs.writeShellScript "stage-flox-controller-crd" ''
                set -euo pipefail
                # $1 = the Maven build dir to stage into (the exec-plugin passes the profile-specific
                # ${"$"}{project.build.directory}/generated-resources/crds); defaults to crdStagingDir
                # for a bare `nix run` in the dev loop.
                dest="''${1:-${crdStagingDir}}"
                ${stageFloxControllerCrds}
                echo "staged flox-controller CRD into $dest/ from ${floxControllerCrds}"
              ''
            );
            meta.description = "Stage the flox-controller CRD (from the flake) onto the manifest-synthesis classpath";
          };

          # Stage the ClusterAdoption CRD (single-sourced from the seed-incluster
          # flake) onto the manifest-synthesis classpath for the DEV loop. Release builds stage
          # it inside seedOutclusterJar. The staged crds/ dir is gitignored — controller-gen stays
          # the single source, never a committed copy.
          apps.stage-seed-incluster-crd = {
            type = "app";
            program = toString (
              pkgs.writeShellScript "stage-seed-incluster-crd" ''
                set -euo pipefail
                # $1 = the Maven build dir to stage into (see stage-flox-controller-crd); defaults to
                # crdStagingDir for a bare `nix run` in the dev loop.
                dest="''${1:-${crdStagingDir}}"
                ${stageSeedInclusterCrds}
                echo "staged ClusterAdoption CRD into $dest/ from ${seedInclusterCrds}"
              ''
            );
            meta.description = "Stage the ClusterAdoption CRD (from the flake) onto the manifest-synthesis classpath";
          };

          # Stage the two baked images' RepoTags onto the manifest-synthesis classpath at
          # /image-refs/ for the DEV loop (release builds stage them inside seedOutclusterJar). Both tags
          # are derived from image content, so the synthesis READS them here — there is no literal
          # anywhere that could still be right after a rebuild.
          apps.stage-image-refs = {
            type = "app";
            program = toString (
              pkgs.writeShellScript "stage-image-refs" ''
                set -euo pipefail
                # $1 = the Maven build dir to stage into; defaults to imageRefStagingDir for a bare
                # `nix run` in the dev loop.
                destRefs="''${1:-${imageRefStagingDir}}"
                ${stageFloxControllerImageRef}
                ${stageFloxCarrierImageRef}
                echo "staged image refs into $destRefs/:"
                for ref in "$destRefs"/*; do printf '  %s = %s\n' "$(basename "$ref")" "$(cat "$ref")"; done
              ''
            );
            meta.description = "Stage the flox-controller + flox-carrier RepoTags (content-derived) onto the manifest-synthesis classpath";
          };

          # Stage the seed-incluster ClusterRole (single-sourced from the flake's +kubebuilder:rbac
          # markers) onto the manifest-synthesis classpath at /rbac/ for the DEV loop. Release builds
          # stage it inside seedOutclusterJar. The staged rbac/ dir is gitignored — controller-gen stays
          # the single source, never a committed copy.
          apps.stage-seed-incluster-rbac = {
            type = "app";
            program = toString (
              pkgs.writeShellScript "stage-seed-incluster-rbac" ''
                set -euo pipefail
                # $1 = the Maven build dir to stage into; defaults to rbacStagingDir for a bare `nix run`.
                destRbac="''${1:-${rbacStagingDir}}"
                ${stageSeedInclusterRbac}
                echo "staged seed-incluster ClusterRole into $destRbac/seed-incluster/ from ${toString seedInclusterRbac}"
              ''
            );
            meta.description = "Stage the seed-incluster ClusterRole (from the flake) onto the manifest-synthesis classpath";
          };

          # Stage the flox-controller ClusterRole (single-sourced from the flake's +kubebuilder:rbac
          # markers) at /rbac/flox-controller/ for the DEV loop; release builds stage it in seedOutclusterJar.
          apps.stage-flox-controller-rbac = {
            type = "app";
            program = toString (
              pkgs.writeShellScript "stage-flox-controller-rbac" ''
                set -euo pipefail
                destRbac="''${1:-${rbacStagingDir}}"
                ${stageFloxControllerRbac}
                echo "staged flox-controller ClusterRole into $destRbac/flox-controller/ from ${toString floxControllerRbac}"
              ''
            );
            meta.description = "Stage the flox-controller ClusterRole (from the flake) onto the manifest-synthesis classpath";
          };

          apps.relock = {
            type = "app";
            program = "${relockApp}/bin/relock";
            meta.description = "Relock rke2lab through nix-flake-commons' shared relock: the heads, their order and what each pins come from fabric/heads";
          };

          # Anti-drift gate: fail if the pinned delivery diverges from the jar output
          # (compared with keys sorted by yq, so formatting never trips it). Defined ONLY on
          # blueprintSystem — absent elsewhere, so `nix flake check` on an aarch64-linux node
          # never realizes the darwin-pinned jar.
          checks = {
            # .sops.yaml names the operator's and the cluster's standalone identities only, no host of ndh's keys.yaml
            # (read at the locked ndh revision).
            sops-recipients = import ./nix/sops-recipients.nix {
              inherit pkgs;
              sopsYaml = ./.sops.yaml;
              keysJson = "${inputs.ndh}/modules/home-manager/ssh.d/keys.json";
            };
            # No nixConfig in this flake: no machine of ours takes one (nix-flake-commons' shared check).
            no-nix-config = inputs.flake-commons.lib.noNixConfig pkgs ./flake.nix;
            # A node takes no flake's nixConfig: accept-flake-config would let any flake it evaluates add substituters
            # and keys silently (the operator, 2026-10-18; ndh's nix-conf-no-accept-flake-config for the fleet). The
            # node's built nix.conf, and a mutant.
            node-nix-conf-no-accept-flake-config =
              let
                conf = c: c.config.environment.etc."nix/nix.conf".source;
                node = conf self.nixosConfigurations.rke2-node-base;
                mutant = conf (
                  self.nixosConfigurations.rke2-node-base.extendModules {
                    modules = [ { nix.settings.accept-flake-config = true; } ];
                  }
                );
              in
              pkgs.runCommand "node-nix-conf-no-accept-flake-config" { } ''
                accepts() { grep -Eq '^[[:space:]]*(extra-)?accept-flake-config[[:space:]]*=' "$1"; }
                if accepts ${node}; then echo "the node's nix.conf accepts flake configs: ${node}" >&2; exit 1; fi
                accepts ${mutant} || { echo "an accept-flake-config mutant passed: ${mutant}" >&2; exit 1; }
                touch $out
              '';
            # The cluster-builder delegation gate a node runs at boot (nixos/nix-builders.nix), tested as
            # built: a delivered description passes only if every builder is ssh-ng:// with its client
            # key, certificate and host key; anything else leaves no builders.conf behind.
            nix-builders-gate = import ./nixos/nix-builders.d/tests/package.nix { inherit pkgs; };
            # The grow's profile gate, as built: the profile from the COMMITTED stack file only, and only when the
            # installed command's marker agrees; every disagreement refused.
            grow-profile = import ./nix/grow-profile.d/tests/package.nix { inherit pkgs; };
            # The throwaway gestures, as built against mocked tools: the typed name, the reset's order, a failed step
            # never skipped, the kubeconfig's modes, no client key printed.
            throwaway = import ./nix/throwaway.d/tests/package.nix {
              inherit pkgs growProfile;
              realGrow = growApp;
            };
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
            # Every Jackson mapper is a codec profile (seed-broker-codec): none is constructed
            # anywhere else. A check, not a test: a test in one module is keyed on that module, so a
            # mapper added in another gets a cache hit on the checker and is never seen. This one is
            # built from mavenSource, every file it scans, so any such edit re-runs it. A bare
            # JsonFactory stays allowed — a streaming tokenizer, not a mapper (FlakeLock). The one
            # pattern serves the scan and its self-test over the codec's shape fixtures: every
            # refused shape caught, no allowed one.
            codec-only =
              let
                refused = ''new ([a-z0-9_]+\.)*(ObjectMapper|YAMLMapper|JsonMapper|XmlMapper|CsvMapper|YAMLFactory)\(|(JsonMapper|YAMLMapper|JsonFactory|YAMLFactory)\.builder\(|extends ([a-z0-9_]+\.)*(ObjectMapper|YAMLMapper|JsonMapper)\b|(MAPPER|[Mm]apper)\w*\.copy\(\)'';
                shapes = ./osgi/foundation/seed-broker-codec/codec-only-shapes;
              in
              pkgs.runCommand "codec-only" { nativeBuildInputs = [ pkgs.gnugrep ]; } ''
                missed=$(grep -cvE '${refused}' ${shapes}/refused.txt || true)
                wrongly=$(grep -cE '${refused}' ${shapes}/allowed.txt || true)
                if [ "$missed" != 0 ] || [ "$wrongly" != 0 ]; then
                  echo "codec-only pattern: $missed refused shape(s) not caught, $wrongly allowed one(s) caught" >&2
                  exit 1
                fi
                if grep -rnE '${refused}' --include='*.java' ${mavenSource} | grep -v '/seed-broker-codec/'; then
                  echo "a Jackson mapper is constructed outside seed-broker-codec. Use a profile of" >&2
                  echo "new SeedCodec(): canonical() for a file we write and read back, reading() for" >&2
                  echo "what we only parse, wire() for an external API's bodies." >&2
                  exit 1
                fi
                touch $out
              '';
          }
          // pkgs.lib.optionalAttrs (system == blueprintSystem) {
            blueprint-fresh = pkgs.runCommand "blueprint-fresh" { nativeBuildInputs = [ pkgs.yq-go ]; } ''
              yq -p=json -o=json 'sort_keys(..)' ${inputs.fabric-plan}/netplan.json > pinned.json
              yq -p=json -o=json 'sort_keys(..)' ${networkBlueprintJson} > fresh.json
              if ! diff -u pinned.json fresh.json; then
                echo "the pinned fabric/plan netplan.json is STALE vs the netplan Java source." >&2
                echo "Deliver it (nix run .#publish-plan), then relock rke2lab's fabric-plan pin." >&2
                exit 1
              fi
              touch $out
            '';
            # The dataplan twin: fail if the pinned dataplan.json diverges from the plan jar output.
            dataplan-fresh = pkgs.runCommand "dataplan-fresh" { nativeBuildInputs = [ pkgs.yq-go ]; } ''
              yq -p=json -o=json 'sort_keys(..)' ${inputs.fabric-plan}/dataplan.json > pinned.json
              yq -p=json -o=json 'sort_keys(..)' ${dataplanJson} > fresh.json
              if ! diff -u pinned.json fresh.json; then
                echo "the pinned fabric/plan dataplan.json is STALE vs the dataplan Java source." >&2
                echo "Deliver it (nix run .#publish-plan), then relock rke2lab's fabric-plan pin." >&2
                exit 1
              fi
              touch $out
            '';
          };
        }
        # Re-exported ONLY where ndh provides it: ndh declares its apps for aarch64-darwin alone
        # (its defaultSystems), and an unconditional `inputs.ndh.apps.${system}` broke every
        # evaluation of another system — `nix flake show` first among them.
        (
          pkgs.lib.optionalAttrs (inputs.ndh.apps ? ${system}) {
            apps.nikopol-incus-cluster-join = inputs.ndh.apps.${system}.nikopol-incus-cluster-join;
          }
        )
    ))
    // {
      # The NixOS node substrate — the immutable Incus container image every RKE2 node
      # boots from (docs/architecture/nixos-substrate/substrate-model.adoc). System-pinned to
      # aarch64-linux (Incus containers on the Apple-Silicon hypervisor); build via bioskop-nixos.
      # Build the artifacts: `.config.system.build.{squashfs,metadata}`, then
      # `incus image import <metadata>/tarball/*.tar.xz <squashfs> --alias rke2lab/node-base`.
      # Per-node identity (node-ip, hostname, token) is injected at instance creation, not baked.
      nixosConfigurations.rke2-node-base = nixpkgs.lib.nixosSystem {
        system = nodeSystem;
        specialArgs = {
          inherit flox flox-runtime flox-controller;
          ndh = inputs.ndh;
          # The upstream caches every seedmatic flake shares (nix-flake-commons), for the node's nix.conf.
          inherit (inputs.flake-commons.lib) upstreamCaches;
          # The netplan projection, so the node's networkd config names addressing facts from the
          # SSOT instead of restating them. Today: the vmnet supernet, for the sibling-cluster route.
          netplan = networkBlueprint;
        };
        modules = [
          "${nixpkgs}/nixos/modules/virtualisation/lxc-container.nix"
          sops-nix.nixosModules.sops
          ./nixos
        ];
      };

      # Flat, system-independent export (no `lib.${system}` selector): the data is the same
      # everywhere. ndh reads networkBlueprint and dataplan from here until it pins fabric/plan.
      lib = {
        inherit networkBlueprint;
        # The ZFS dataset layout ndh pulls into catalog.datasets (the dataplan — storage twin
        # of networkBlueprint), read from the pinned delivery like networkBlueprint.
        dataplan = dataplanData;
        # Raw YAML store path for inspection (the pinned, canonical build).
        networkBlueprintYamlPath = "${networkBlueprintYaml}/network-blueprint.yaml";
      };

      # TEMP federation probe (Phase 1): proves rke2lab sees ndh's home-LAN facts
      # across the follows="" cut. Removed once real consumption is wired.
      _federationProbe = {
        ndhLan = inputs.ndh.catalog.netplan.lan;
      };
    };
}
