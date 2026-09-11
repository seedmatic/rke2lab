package io.seedmatic.rke2lab.manifests.units.runtime.flox;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRefs;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRuntimeNamespaceManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.gitops.SopsAgeSecretManifestsUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Emits the workload {@code FloxEnv} CRs the flox-controller realises on each node — the runtime
 * successor to the baked {@code environment.d} tree. Covers {@code kdns} (networking), {@code
 * headscale}/{@code tailscale}/{@code headplane} (mesh), and the cross-cutting {@code toolchains}
 * tier ({@code kube} + {@code git-sops}). The CI render's TOOLCHAIN is nix-build (not a flox env) —
 * nix CLI + config + persistent store via the {@code flox.seedmatic.io/nix-build} annotation; but
 * the render step ALSO carries the {@code toolchains/git-sops} flox env (git + sops + the git-sops
 * filter) composed with nix-build, so a checkout smudges the sops tree (see {@code
 * RenderPipelineManifestsUnit}).
 *
 * <p>Each env installs its workload package from the {@link FloxCatalogManifestsUnit} catalog via a
 * {@code floxcatalog:catalogue#<output>} ref (resolved same-namespace, both live in {@code
 * rke2lab-system}). The env's {@code folder} is the GC-root category the NRI plugin keys on ({@code
 * <base>/networking/kdns}) — a node-side path segment, not a k8s namespace.
 *
 * <p>The env NAME is the flavor: prod is {@code <name>} ({@code #<name>}), debug is {@code
 * <name>-debug} ({@code #<name>-debug}, delve-wrapped + an interactive toolchain), so the
 * provisioned GC-root ({@code <folder>/<name>}) is exactly what a pod waits on via its {@code
 * resolve*Environment} annotation. The PROD flavor is emitted ALWAYS; the debug flavor is emitted
 * ADDITIONALLY when the domain's debug toggle ({@code FloxDebugPolicy.networkingEnabled} / {@code
 * meshEnabled}) is on — both coexist in debug mode because a debug-flipped prod container
 * references {@code <name>-debug} while the always-prod bootstrap/sync Jobs still reference {@code
 * <name>}. On the {@code workloads} layer (after the catalog on {@code operators}).
 */
public final class FloxEnvManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.RUNTIME + "/flox-envs";

  /** Exploded package dir (relative to the runtime domain). */
  public static final String OUTPUT_DIR = "flox-envs";

  private static final String SCHEMA_VERSION = "1.10.0";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(ManifestDomainCatalog.RUNTIME, OUTPUT_DIR, false);

  public FloxEnvManifestsUnit() {
    super(
        MANIFEST_UNIT_ID,
        List.of(
            ClusterRuntimeNamespaceManifestsUnit.MANIFEST_UNIT_ID,
            FloxCatalogManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public String outputDir() {
    return OUTPUT_DIR;
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final var policy = ManifestSynthesisContext.current().floxDebugPolicy();
    final Cdk8sApiObjectResolver resolver = context.resolver();
    // The PROD flavor is emitted ALWAYS; the debug flavor ADDITIONALLY when the domain's debug
    // toggle is on. Both must coexist in debug mode: a debug-flipped prod CONTAINER references the
    // <name>-debug env (via resolve*Environment), but the ALWAYS-PROD Jobs keep referencing the
    // prod env — headscale's bootstrap Job annotates mesh/headscale (it needs yq-go, which the
    // headscale-debug env deliberately drops) and headplane's agent-sync Job annotates
    // mesh/headplane. Emitting only the selected flavor left those Jobs' flox-wait blocked forever
    // on a prod GC-root the controller never provisioned (the mesh-debug bootstrap wedge).
    // toolchains/kube — the cross-cutting kube-API scripting tier (kubectl + yq-go). Always prod:
    // helper
    // Jobs (e.g. the funnel-state mirror) activate it directly; domain envs will `[include]` it to
    // stop re-declaring the same tools. No debug flavor — it is a toolchain base, not a workload.
    createEnv(scope, resolver, "kube", FloxEnvFolder.TOOLCHAINS, kubeBaseManifest());
    // toolchains/git-sops — the git sops clean/smudge filter toolchain (git + sops + yq + the ndh
    // git-sops-filter). The in-cluster render pipeline's re-smudge step activates it so a Tekton
    // checkout smudges `.secrets` (and the branch's sops-encrypted Secrets) exactly as the operator
    // host does — the render is then secret-FULL, not blind. A cross-cutting toolchain tier (like
    // toolchains/kube), NOT cicd-bound: decoupled so the cicd need evolves independently and any
    // env
    // doing
    // git ops on the sops tree can `[include]` it. Always prod; a toolchain, not a workload.
    // git-sops CONTRIBUTES SOPS_AGE_KEY to its consumers (spec.inject): the flox-controller webhook
    // adds it (valueFrom the replicated sops-age Secret) to every container that annotates
    // environment.<c>=toolchains/git-sops, so a consumer (the render clone/step) only annotates the
    // env — it never wires the age key itself. optional: a consumer namespace without the
    // replicated
    // Secret does not wedge (the consumer's own fail-loud check reports the absence).
    createEnv(
        scope,
        resolver,
        "git-sops",
        FloxEnvFolder.TOOLCHAINS,
        gitSopsManifest(),
        List.of(
            Map.of(
                "name",
                "SOPS_AGE_KEY",
                "secretKeyRef",
                Map.of(
                    "name", SopsAgeSecretManifestsUnit.SECRET_NAME,
                    "key", SopsAgeSecretManifestsUnit.AGE_KEY,
                    "optional", true))));
    final boolean net = policy.networkingEnabled();
    createEnv(scope, resolver, "kdns", FloxEnvFolder.NETWORKING, kdnsManifest(false));
    if (net) {
      createEnv(scope, resolver, "kdns-debug", FloxEnvFolder.NETWORKING, kdnsManifest(true));
    }
    final boolean mesh = policy.meshEnabled();
    createEnv(scope, resolver, "headscale", FloxEnvFolder.MESH, headscaleManifest(false));
    createEnv(scope, resolver, "tailscale", FloxEnvFolder.MESH, tailscaleManifest(false));
    createEnv(scope, resolver, "headplane", FloxEnvFolder.MESH, headplaneManifest(false));
    // The tailnet-admin env for the stale-device prune Job (mesh-tailnet-purge): manage-tailnet +
    // yq-go (the retry loop parses its --format=json JSON Lines). Always prod — an ops tool, no
    // debug flavor.
    createEnv(scope, resolver, "tailnet", FloxEnvFolder.MESH, tailnetManifest());
    if (mesh) {
      createEnv(scope, resolver, "headscale-debug", FloxEnvFolder.MESH, headscaleManifest(true));
      createEnv(scope, resolver, "tailscale-debug", FloxEnvFolder.MESH, tailscaleManifest(true));
      createEnv(scope, resolver, "headplane-debug", FloxEnvFolder.MESH, headplaneManifest(true));
    }
  }

  private void createEnv(
      final Construct scope,
      final Cdk8sApiObjectResolver resolver,
      final String name,
      final FloxEnvFolder folder,
      final Map<String, Object> manifest) {
    createEnv(scope, resolver, name, folder, manifest, List.of());
  }

  /**
   * @param inject {@code spec.inject} entries the flox-controller webhook adds to every container
   *     that opts into this env — how the env contributes required runtime env/secrets to its
   *     consumers (e.g. git-sops → SOPS_AGE_KEY), so a consumer only annotates the env.
   */
  private void createEnv(
      final Construct scope,
      final Cdk8sApiObjectResolver resolver,
      final String name,
      final FloxEnvFolder folder,
      final Map<String, Object> manifest,
      final List<Object> inject) {
    final String namespace = ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name();
    final ApiObject env =
        new ApiObject(
            scope,
            "floxenv-" + name,
            ApiObjectProps.builder()
                .apiVersion("flox.seedmatic.io/v1alpha1")
                .kind("FloxEnv")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "flox.seedmatic.io|FloxEnv|" + namespace + "|" + name))
                        .build())
                .build());
    env.addDependency(resolver.require(ClusterRefs.RUNTIME_SYSTEM_NAMESPACE));

    final Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("folder", folder.value());
    spec.put("consumption", "overlay");
    spec.put("manifest", manifest);
    if (!inject.isEmpty()) {
      spec.put("inject", inject.toArray());
    }
    env.addJsonPatch(JsonPatch.add("/spec", spec));
  }

  /** The flox manifest (mirrors {@code manifest.toml}) for the kdns env, per flavor. */
  private Map<String, Object> kdnsManifest(final boolean debug) {
    final String flavor = debug ? "kdns-debug" : "kdns";
    final Map<String, Object> install = new LinkedHashMap<>();
    install.put("kdns", flakeRef(flavor));
    if (debug) {
      // Interactive debug shell alongside the delve-wrapped binary: attach a debugger / poke
      // around.
      install.put("go", Map.of("pkg-path", "go", "version", "^1.25"));
      install.put("delve", catalog("delve"));
      install.put("bash", catalogAll("bash"));
      install.put("coreutils", catalogAll("coreutils"));
      install.put("strace", catalog("strace"));
      install.put("curl", catalog("curl"));
    }
    return manifest(install);
  }

  /** Mirrors {@code environment.d/mesh/headscale[-debug]/manifest.toml}. */
  private Map<String, Object> headscaleManifest(final boolean debug) {
    final String flavor = debug ? "headscale-debug" : "headscale";
    final Map<String, Object> install = new LinkedHashMap<>();
    install.put("bash", catalogAll("bash"));
    install.put("coreutils", catalogAll("coreutils"));
    // The bootstrap/wait scripts drive the cluster via kubectl (both flavors carry it).
    install.put("kubectl", catalogAll("kubectl"));
    if (debug) {
      install.put("delve", catalog("delve"));
      install.put("strace", catalog("strace"));
      install.put("curl", catalog("curl"));
    } else {
      // bootstrap.sh parses `headscale ... -o yaml` with yq — prod only (the bootstrap Job always
      // activates the prod headscale env, never the delve-wrapped debug build).
      install.put("yq-go", catalogAll("yq-go"));
    }
    install.put(flavor, flakeRef(flavor));
    return manifest(install);
  }

  /** Mirrors {@code environment.d/mesh/tailscale[-debug]/manifest.toml}. */
  private Map<String, Object> tailscaleManifest(final boolean debug) {
    final String flavor = debug ? "tailscale-debug" : "tailscale";
    final Map<String, Object> install = new LinkedHashMap<>();
    install.put("bash", catalogAll("bash"));
    install.put("coreutils", catalogAll("coreutils"));
    if (debug) {
      install.put("delve", catalog("delve"));
      install.put("strace", catalog("strace"));
      install.put("curl", catalog("curl"));
    }
    install.put(flavor, flakeRef(flavor));
    return manifest(install);
  }

  /** Mirrors {@code environment.d/mesh/headplane[-debug]/manifest.toml}. */
  private Map<String, Object> headplaneManifest(final boolean debug) {
    final Map<String, Object> install = new LinkedHashMap<>();
    install.put("bash", catalogAll("bash"));
    install.put("coreutils", catalogAll("coreutils"));
    // The agent-sync script drives the cluster via kubectl + parses config with yq (both flavors).
    install.put("kubectl", catalogAll("kubectl"));
    install.put("yq-go", catalog("yq-go"));
    if (debug) {
      install.put("strace", catalog("strace"));
      install.put("curl", catalog("curl"));
      install.put("headplane-debug", flakeRef("headplane-debug"));
    } else {
      install.put("headplane", flakeRef("headplane"));
      // headplane reads the headscale config/CLI for its integration — prod env carries it.
      install.put("headscale", flakeRef("headscale"));
    }
    // hp_agent (the tailnet agent) + the ssh WASM helper are separate flake outputs both flavors
    // need — headplane symlinks /usr/libexec/headplane/agent to `command -v hp_agent`.
    install.put("headplane-agent", flakeRef("headplane-agent"));
    install.put("headplane-ssh-wasm", flakeRef("headplane-ssh-wasm"));
    return manifest(install);
  }

  /**
   * The tailnet-admin env for the stale-device prune Job: {@code manage-tailnet} (re-exported from
   * ndh through the catalog) + a shell + {@code yq-go} (the prune loop parses {@code manage-tailnet
   * --format=json} JSON Lines to know when the tailnet is clean). No {@code curl}: it is
   * closure-pinned inside the manage-tailnet package itself.
   */
  private Map<String, Object> tailnetManifest() {
    final Map<String, Object> install = new LinkedHashMap<>();
    install.put("bash", catalogAll("bash"));
    install.put("coreutils", catalogAll("coreutils"));
    install.put("yq-go", catalogAll("yq-go"));
    install.put("manage-tailnet", flakeRef("manage-tailnet"));
    return manifest(install);
  }

  /** The toolchains/kube env — the shared kube-API scripting toolchain, no workload flake. */
  private Map<String, Object> kubeBaseManifest() {
    final Map<String, Object> install = new LinkedHashMap<>();
    kubeApiScriptingInstall(install);
    return manifest(install);
  }

  /**
   * Mirrors {@code environment.d/cicd/git-sops/manifest.toml} — the git sops clean/smudge filter
   * toolchain. Carries git + sops + yq-go + a shell, plus the ndh {@code git-sops-filter} package
   * (the SSOT filter def re-exported by the catalog flake); on activation flox wires git to that
   * package's {@code sops} include, registering the {@code sops-yaml} clean/smudge commands. The
   * age key is NOT here — sops reads {@code SOPS_AGE_KEY} from the render pod's mounted {@code
   * sops-age} Secret.
   */
  private Map<String, Object> gitSopsManifest() {
    final Map<String, Object> install = new LinkedHashMap<>();
    install.put("git", catalog("git"));
    install.put("sops", catalog("sops"));
    install.put("yq-go", catalog("yq-go"));
    install.put("bash", catalogAll("bash"));
    install.put("coreutils", catalogAll("coreutils"));
    install.put("git-sops-filter", flakeRef("git-sops-filter"));
    return manifest(install, Optional.of(GIT_SOPS_ON_ACTIVATE));
  }

  /**
   * git-sops activation hook: register the {@code git-sops-filter} package's {@code sops} include
   * (shipped at {@code $FLOX_ENV/sops}) as a GLOBAL git include, so a {@code git checkout} in the
   * source checkout AND the render worktree (a {@code git worktree add} shares the config) runs the
   * {@code sops-yaml} clean/smudge filter. {@code SOPS_AGE_KEY} (per-pod, from the replicated
   * {@code sops-age} Secret) is what sops decrypts with. NOT fail-closed on a missing key: this
   * same hook runs during the flox-controller's {@code flox activate --mode dev -- true} realise,
   * which has no {@code SOPS_AGE_KEY} — a hard exit there would wedge the env's realisation. The
   * consumer (the render step) is where the key + filter are asserted fail-loud.
   */
  private static final String GIT_SOPS_ON_ACTIVATE =
      """
      if [ -f "$FLOX_ENV/sops" ]; then
        git config --global include.path "$FLOX_ENV/sops"
      else
        echo >&2 "git-sops: include $FLOX_ENV/sops not found — sops-yaml filter NOT wired"
      fi
      """;

  /**
   * The kube-API scripting fragment: a shell ({@code bash}/{@code coreutils}) plus {@code kubectl}
   * + {@code yq-go}. The single source for the tools a helper Job needs to read/patch cluster
   * objects — {@code toolchains/kube} installs exactly this, and domain envs will {@code [include]}
   * it rather than re-listing the same packages.
   */
  private void kubeApiScriptingInstall(final Map<String, Object> install) {
    install.put("bash", catalogAll("bash"));
    install.put("coreutils", catalogAll("coreutils"));
    install.put("kubectl", catalogAll("kubectl"));
    install.put("yq-go", catalogAll("yq-go"));
  }

  /**
   * A flake install resolved against the FloxCatalog artifact ({@code floxcatalog:catalogue#…}).
   */
  private Map<String, Object> flakeRef(final String output) {
    return Map.of("flake", "floxcatalog:catalogue#" + output);
  }

  /** A catalog install pulling a package's default output. */
  private Map<String, Object> catalog(final String pkg) {
    return Map.of("pkg-path", pkg);
  }

  /** A catalog install pulling all of a package's outputs (bin split across {@code out}/…). */
  private Map<String, Object> catalogAll(final String pkg) {
    return Map.of("pkg-path", pkg, "outputs", "all");
  }

  private Map<String, Object> manifest(final Map<String, Object> install) {
    return manifest(install, Optional.empty());
  }

  private Map<String, Object> manifest(
      final Map<String, Object> install, final Optional<String> hookOnActivate) {
    final Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("schema-version", SCHEMA_VERSION);
    manifest.put("install", install);
    // These envs activate inside Linux containers on the nodes — restrict resolution to Linux.
    manifest.put("options", Map.of("systems", new Object[] {"aarch64-linux"}));
    hookOnActivate.ifPresent(h -> manifest.put("hook", Map.of("on-activate", h)));
    return manifest;
  }
}
