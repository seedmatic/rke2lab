package io.seedmatic.rke2lab.manifests.units.cicd;

import io.seedmatic.rke2lab.dataplan.contract.DataplanLayout;
import io.seedmatic.rke2lab.dataplan.contract.DataplanLayout.ClusterDataplan;
import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRefs;
import io.seedmatic.rke2lab.manifests.units.runtime.SeedInclusterManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.storage.OpenebsZfsManifestsUnit;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * The in-cluster render pipeline — the upstream half of the GitOps loop rendered as Tekton
 * manifests (see {@code docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc}). Two Tasks
 * wired by one Pipeline ({@code fetch → render}), plus the persistent Maven-cache PVC:
 *
 * <ul>
 *   <li>{@code git-fetch} — clones the source repo at the pushed revision into the shared {@code
 *       source} workspace, authenticating with PaC's {@code basic-auth} workspace (the {@code
 *       git_auth_secret} App token PaC injects into the PipelineRun).
 *   <li>{@code render-publish} — nix-build-annotated (the flox NRI system gives the {@code
 *       step-render} container the nix runtime: nix on PATH, daemonless {@code NIX_CONFIG}, and the
 *       {@code /nix} store overlay hosted on a persistent PVC), then runs {@code nix run
 *       .#render-manifests}: the SINGLE render definition (shared with dev/release, no
 *       hand-scripted mvn+java that drifts) builds {@code manifests-cli} with the reactor
 *       discipline and seeds the {@code staging-extension} closure from a nix derivation (no
 *       separate bootstrap task), then runs the {@code update} verb — re-render following the
 *       branch HEAD facet + ff-push {@code manifests/<cluster>}.
 * </ul>
 *
 * <p><b>The nix-build capability lives on the PipelineRun stub, not here.</b> Pod annotations are
 * set by the PipelineRun (the {@code .tekton/} source stub), not by a Pipeline or Task. The stub
 * carries {@code flox.seedmatic.io/nix-build.step-render=<pvc-name>} — the value names the render's
 * persistent nix-store PVC (a warm store reused across renders). The flox-controller webhook
 * ensures that PVC (create-if-absent) + injects it as the {@code /nix} overlay upper backing +
 * {@code NIX_CONFIG}; the NRI plugin puts {@code nix} on PATH. The {@code step-render} container
 * ALSO carries {@code flox.seedmatic.io/environment.step-render=toolchains/git-sops} — the NRI
 * composes a flox env AND the nix-build runtime on ONE container (see {@code
 * flox-store-resolved-runtime-and-builder.adoc} § capability-vs-package-set): the nix-build runtime
 * builds + runs the closure, while the git-sops env brings git + sops + the git-sops filter so the
 * render commits branch Secrets ENCRYPTED (clean) and — with {@code SOPS_AGE_KEY} (the cluster age
 * key replicated into this namespace, {@link
 * io.seedmatic.rke2lab.manifests.units.gitops.SopsAgeSecretManifestsUnit}) — smudges {@code
 * .secrets} + the cellar asset, making the render secret-FULL. Only the {@code render-publish} pod
 * owns a {@code step-render} container, so the injection applies there and is ignored on the {@code
 * git-fetch} pod (no bare-key fallback — each container opts in BY NAME). See {@code
 * docs/architecture/patterns/flox-store-resolved-runtime-and-builder.adoc} + {@link
 * io.seedmatic.rke2lab.manifests.contract.FloxAnnotation}.
 *
 * <p><b>Workspaces vs caches — the distinction is load-bearing.</b> A WORKSPACE is scratch for ONE
 * run, shared between its tasks; a CACHE is state carried ACROSS runs. Only {@code source}
 * (per-run, bound by the stub to a {@code volumeClaimTemplate}) and {@code basic-auth} (PaC's
 * {@code git_auth_secret}) are workspaces. Neither cache is: this Task mounts the Maven cache as a
 * RAW VOLUME, on the three phases that bracket the build ({@code cache-prepare} → {@code render} →
 * {@code cache-publish}), and the nix store is not in the pod at all — it is the NODE's {@code
 * /nix} overlay, which the flox NRI plugin wires into the container and whose env provisioning runs
 * in the node's own namespace ({@code nsenter}).
 *
 * <p>So the two caches escape the workspace mechanism by DIFFERENT routes, and the Maven cache
 * cannot take the store's: a cache that must survive the NODE cannot live on the node — which is
 * exactly the failure this unit was fixed for. It stays a PVC; it simply stops being a workspace.
 *
 * <p>Because it WAS one, every symptom followed from that single misfiling: Tekton's affinity
 * assistant co-mounts every PVC-backed workspace beside the task pod, so a single-writer cache
 * needed the {@code shared: yes} bind-mount class, which lives on the EPHEMERAL tier — where a
 * dynamic {@code pvc-<uuid>} leaked a dataset per cold start (8 datasets for 1 live claim) and a
 * node-pinned PV stranded the cache on the first control-plane roll (both measured 2026-09-24). A
 * raw volume the assistant never sees takes the exclusive persist class and an adopted dataset of
 * stable name. The build is still serialised (concurrency 1, on the PaC {@code Repository}/stub) —
 * a Maven local repo, build-cache, and the nix store are not multi-writer safe.
 *
 * <p>The push token is wired: the {@code render-publish} step extracts PaC's App token from the
 * mounted {@code git_auth} secret into {@code RKE2LAB_PUSH_TOKEN}, which the in-cluster {@code
 * publish} reveals for the ff-push (container-aware {@code
 * ManifestSynthesisScenario.revealGithubToken} — on-demand App mint OPERATOR, env IN_CLUSTER).
 */
public final class RenderPipelineManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CICD + "/render-pipeline";

  // rke2lab OWNS the render pipeline (its Pipeline/Tasks/PVC + the PipelineRuns PaC creates against
  // the Repository CR), so it lives in rke2lab-system — the runtime-system namespace (which already
  // hosts flox-controller), NOT tekton-pipelines (that is the Tekton/PaC controllers' own system).
  private static final String NAMESPACE = ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name();

  private static final String PIPELINE_NAME = "render-manifests";

  private static final String MAVEN_CACHE_PVC = "manifests-maven-cache";

  /**
   * Where the three cache phases mount the volume. NOT under {@code /workspace} — that prefix is
   * Tekton's, and borrowing it for a raw volume is how the two roles got confused in the first
   * place. Under it: {@code base/} (shared, read-through) and {@code incoming/<run>/} (this run's
   * writes).
   */
  private static final String MAVEN_CACHE_PATH = "/var/cache/rke2lab/maven";

  // The ~/.m2 local repo plus the maven-build-cache beside it; 4Gi has held both with headroom.
  private static final String MAVEN_CACHE_CAPACITY = "4Gi";

  /**
   * Container name the flox NRI plugin keys on: {@code flox.seedmatic.io/nix-build.step-render}.
   */
  private static final String RENDER_STEP = "render";

  /**
   * The cache phases that bracket the render. Both need a shell + coreutils, which the flox carrier
   * does NOT carry on its own — the stub annotates {@code environment.step-<name>=toolchains/kube}
   * for each, exactly as it does for step-render and step-clone. Renaming a step here means
   * renaming its annotation there, or the container silently starts without its toolchain.
   */
  private static final String CACHE_PREPARE_STEP = "cache-prepare";

  private static final String CACHE_PUBLISH_STEP = "cache-publish";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cicd", "render-pipeline");

  public RenderPipelineManifestsUnit() {
    // seed-incluster must be UP: it is what turns this unit's VolumeIntention into the ZFSVolume +
    // static PV the cache PVC binds against. Without the edge Flux would health-gate a PVC nobody
    // can
    // satisfy yet — the same reason FunnelCertRestoreManifestsUnit carries it.
    super(MANIFEST_UNIT_ID, List.of(SeedInclusterManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    createMavenCacheVolumeIntention(
        scope, context.nodeEnvContext().bootstrapIdentity().clusterName());
    createMavenCachePvc(scope);
    createGitFetchTask(scope);
    createRenderPublishTask(scope);
    createPipeline(scope);
  }

  /**
   * The cache's INTENT: the dataset to adopt, its size, the claim to satisfy, and the kind of node
   * eligible to serve it. It names NO node — a managed node's name is random and unknowable at
   * render time, and pinning one is precisely what stranded this cache when the control plane
   * rolled. The in-cluster volume controller elects, stamps the ZFSVolume's owner and creates the
   * static PV.
   *
   * <p>Static adoption rather than dynamic provisioning for the SAME reason the funnel cert needs
   * it: a cold start wipes the PVC object, so a dynamic class mints a fresh {@code pvc-<uuid>} and
   * leaks the previous dataset — eight of them had accumulated for one live claim. A stable dataset
   * name is the only handle that survives an etcd wipe.
   */
  private void createMavenCacheVolumeIntention(final Construct scope, final String cluster) {
    final ApiObject intention =
        new ApiObject(
            scope,
            "volumeintention-manifests-maven-cache",
            ApiObjectProps.builder()
                .apiVersion("cluster.seedmatic.io/v1alpha1")
                .kind("VolumeIntention")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(DataplanLayout.MAVEN_CACHE)
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());
    intention.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "pool",
                ClusterDataplan.of(cluster).persistPool(),
                "dataset",
                DataplanLayout.MAVEN_CACHE,
                "capacity",
                MAVEN_CACHE_CAPACITY,
                "storageClassName",
                OpenebsZfsManifestsUnit.PERSIST_CLASS,
                "claimRef",
                Map.of("namespace", NAMESPACE, "name", MAVEN_CACHE_PVC),
                "nodeRole",
                "control-plane")));
  }

  private void createMavenCachePvc(final Construct scope) {
    final ApiObject pvc =
        new ApiObject(
            scope,
            "persistentvolumeclaim-manifests-maven-cache",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("PersistentVolumeClaim")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(MAVEN_CACHE_PVC)
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|PersistentVolumeClaim|" + NAMESPACE + "|" + MAVEN_CACHE_PVC))
                        .build())
                .build());
    // RWO on the EXCLUSIVE persist class, and pre-bound by name to the static PV the
    // VolumeIntention
    // above produces. It rode the SHARED (bind-mount) class while it was a Tekton workspace,
    // because
    // under coschedule=pipelineruns the affinity assistant co-located with the task pod and BOTH
    // mounted it — the exclusive class then failed "device already mounted". As a raw volume on
    // step-render (createRenderPublishTask) there is exactly ONE mounter, so the bind-mount variant
    // is
    // no longer needed; `source`, a genuine shared workspace, keeps it.
    //
    // volumeName pins the claim to the adopted PV: without it the claim would match ANY sufficient
    // PV of the class, which on a re-grow is how a cache gets bound to a stranger's dataset.
    pvc.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "accessModes",
                List.of("ReadWriteOnce"),
                "storageClassName",
                OpenebsZfsManifestsUnit.PERSIST_CLASS,
                "volumeName",
                DataplanLayout.MAVEN_CACHE,
                "resources",
                Map.of("requests", Map.of("storage", MAVEN_CACHE_CAPACITY)))));
  }

  private void createGitFetchTask(final Construct scope) {
    final ApiObject task =
        new ApiObject(
            scope,
            "task-git-fetch",
            ApiObjectProps.builder()
                .apiVersion("tekton.dev/v1")
                .kind("Task")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("git-fetch")
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "tekton.dev|Task|" + NAMESPACE + "|git-fetch"))
                        .build())
                .build());
    task.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "params",
                new Object[] {
                  Map.of("name", "repo-url", "type", "string"),
                  Map.of("name", "revision", "type", "string")
                },
                "workspaces",
                new Object[] {
                  Map.of("name", "output"), Map.of("name", "basic-auth", "optional", true)
                },
                "steps",
                new Object[] {
                  Map.of(
                      "name",
                      "clone",
                      "image",
                      ManifestSynthesisContext.current().floxDebugPolicy().prodImage(),
                      "script",
                      """
                      #!/bin/sh
                      set -eux
                      : "PaC's git_auth_secret ships .gitconfig + .git-credentials; adopt them so the clone authenticates as the App without embedding a token in the URL"
                      if [ -f "$(workspaces.basic-auth.path)/.git-credentials" ]; then
                        cp "$(workspaces.basic-auth.path)/.git-credentials" "$HOME/.git-credentials"
                        cp "$(workspaces.basic-auth.path)/.gitconfig" "$HOME/.gitconfig"
                      fi
                      cd "$(workspaces.output.path)"
                      git init -q .
                      git remote add origin "$(params.repo-url)"
                      git fetch -q --depth 1 origin "$(params.revision)"
                      git checkout -q FETCH_HEAD
                      """)
                })));
  }

  private void createRenderPublishTask(final Construct scope) {
    final ApiObject task =
        new ApiObject(
            scope,
            "task-render-publish",
            ApiObjectProps.builder()
                .apiVersion("tekton.dev/v1")
                .kind("Task")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("render-publish")
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "tekton.dev|Task|" + NAMESPACE + "|render-publish"))
                        .build())
                .build());
    task.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "params",
                new Object[] {
                  Map.of("name", "cluster", "type", "string"),
                  Map.of("name", "node", "type", "string")
                },
                "workspaces",
                new Object[] {
                  Map.of("name", "source"), Map.of("name", "basic-auth", "optional", true)
                },
                // The Maven cache is a raw volume the TASK owns, not a workspace the PipelineRun
                // binds: a workspace would be co-mounted by Tekton's affinity assistant, which is
                // the
                // only reason a single-writer cache ever needed the shared bind-mount class.
                "volumes",
                new Object[] {
                  Map.of(
                      "name",
                      DataplanLayout.MAVEN_CACHE,
                      "persistentVolumeClaim",
                      Map.of("claimName", MAVEN_CACHE_PVC))
                },
                // Three phases, in order: prepare the cache topology, render, publish the delta.
                // The
                // preparation is a phase of its own rather than a line inside the render, because
                // the
                // primary has a precondition the tail cannot satisfy — see cachePrepareStep.
                "steps",
                new Object[] {
                  cachePrepareStep(),
                  Map.of(
                      // Container name = step-render; the PipelineRun stub carries
                      // flox.seedmatic.io/nix-build.step-render=<pvc>, so the flox NRI system gives
                      // this container the nix runtime: nix on PATH, NIX_CONFIG, and the /nix store
                      // overlay hosted on the assigned (webhook-ensured) persistent PVC.
                      "name",
                      RENDER_STEP,
                      "volumeMounts",
                      cacheMount(),
                      // The commit-signing key the operator's grow emitted as
                      // manifests-render-signing (RenderSigningSecretManifestsUnit), fed to the
                      // in-cluster publish's revealSigningKey() (RKE2LAB_SIGNING_KEY) so it signs
                      // the
                      // rendered commit — the enclosure twin of the PaC-provided push token.
                      // optional=true: on a cluster grown before this landed the env is unset and
                      // the
                      // delivery fails loud, rather than the pod failing to start on a missing
                      // secret.
                      "env",
                      new Object[] {
                        Map.of(
                            "name",
                            "RKE2LAB_SIGNING_KEY",
                            "valueFrom",
                            Map.of(
                                "secretKeyRef",
                                Map.of(
                                    "name",
                                    RenderSigningSecretManifestsUnit.SECRET_NAME,
                                    "key",
                                    RenderSigningSecretManifestsUnit.SSH_PRIVATE_KEY,
                                    "optional",
                                    true)))
                        // SOPS_AGE_KEY is no longer hand-wired here: the step-render container opts
                        // into the git-sops env (environment.step-render), which CONTRIBUTES it via
                        // spec.inject — the flox-controller webhook adds it from the replicated
                        // sops-age Secret. Same for the step-clone container in the git-fetch pod.
                      },
                      // The flox-carrier runtime (prodImage) like every other rke2lab workload — no
                      // stock base. The toolchain (nix + the git-sops env) arrives via flox NRI
                      // injection onto this container; no JDK/nix baked into the image.
                      "image",
                      ManifestSynthesisContext.current().floxDebugPolicy().prodImage(),
                      "workingDir",
                      "$(workspaces.source.path)",
                      "script",
                      """
                      #!/usr/bin/env bash
                      set -euxo pipefail
                      : "The publish narrates live to stdout: it runs STANDALONE (not seed-master under Pulumi), so PaxLogbackConfigurer keeps its console appender on and the render/delivery logs land in this container's logs"
                      GIT_AUTH_DIR="$(workspaces.basic-auth.path)"
                      : "PaC mints the App token into the git_auth secret's git-provider-token key; read it RAW (not scraped from the .git-credentials URL) into RKE2LAB_PUSH_TOKEN for the ff-push (the scion reveals it in-container, ManifestSynthesisScenario.revealGithubToken) + nix + maven. Backticks, not the dollar-paren form, so Tekton does not claim the substitution as one of its own vars"
                      : "The same App token authenticates .mvn/settings.xml to GitHub Packages (the env.GH_TOKEN placeholder) so the reactor resolves the private seedmatic release java-systemd. Requires the App to carry packages:read"
                      : "nix must authenticate its flake-input fetches: the closure pulls a PRIVATE input, seedmatic/claude-hub transitively via ndh; the flox NRI sets NIX_CONFIG but no access-tokens, so an unauthenticated github fetch 404s on the private repo. Append the App token so nix reads it AS the App. Requires PaC to scope the git_auth token to include claude-hub via secret-github-app-scope-extra-repos"
                      : "xtrace is disabled across the next block so the App token is never echoed to the logs"
                      set +x
                      if [ -f "$GIT_AUTH_DIR/git-provider-token" ]; then
                        export RKE2LAB_PUSH_TOKEN=`cat "$GIT_AUTH_DIR/git-provider-token"`
                        export GH_TOKEN="$RKE2LAB_PUSH_TOKEN"
                        export NIX_CONFIG="${NIX_CONFIG:-}"$'\\n'"access-tokens = github.com=$RKE2LAB_PUSH_TOKEN"
                      fi
                      set -x
                      : "OVERLAY over the cache: everyone READS the shared base, only the end of the run WRITES it. M2_REPO names the base because the flake bakes it as maven.repo.local.tail (a READ-THROUGH tail, ignoreAvailability=true) — Maven 3.9's chained local repository, verified present in maven-core-3.9.12. MAVEN_BUILD_CACHE names this run's own root, which the flake turns into maven.repo.local, so every write Maven makes lands in incoming/<run>/ and the base cannot be corrupted by a build — nor by one that is killed halfway. Both knobs already existed; in-cluster they pointed at the SAME directory, so the chained repo was wired to itself and bought nothing"
                      : "The volume is a RAW VOLUME, not a workspace, so the path is ours rather than Tekton's — hence the substitution rather than a workspaces.* reference"
                      export M2_REPO="@MAVEN_CACHE@/base/repository"
                      export MAVEN_BUILD_CACHE="@MAVEN_CACHE@/incoming/$(context.taskRun.name)"
                      : "The build-cache stays SHARED at base/build-cache rather than following the primary into incoming/: the maven-build-cache extension has no read-through tail, so a per-run location would start EMPTY and every render would rebuild from scratch. Shared is safe only because the render is serialised (concurrency 1 on the PaC Repository); the way out for the build-cache half is the extension's own REMOTE cache with its opt-in save, not a directory on a shared volume"
                      export MAVEN_ARGS="-Dmaven.build.cache.location=@MAVEN_CACHE@/base/build-cache"
                      : "Secret-full render prerequisites — fail loud with a clear message, not a downstream decode error. SOPS_AGE_KEY arrives via the git-sops env spec.inject (flox-controller webhook); the sops-yaml filter is wired by the env on-activate hook; the clone step (also on git-sops) already smudged .secrets in this shared workspace at checkout"
                      set +x
                      [ -n "${SOPS_AGE_KEY:-}" ] || { echo >&2 "render: SOPS_AGE_KEY not set (git-sops inject / replicated sops-age missing)"; exit 1; }
                      set -x
                      git config --get filter.sops-yaml.smudge >/dev/null 2>&1 || { echo >&2 "render: sops-yaml git filter not registered (git-sops on-activate hook did not wire it)"; exit 1; }
                      : "The flox NRI plugin put nix on PATH, injected NIX_CONFIG (daemonless single-user) and hosts the /nix store overlay on the assigned persistent PVC, so there is no flox env and no flox activate. nix run .#render-manifests from the source checkout is the ONE render definition shared with dev and release: it builds manifests-cli, signs, and ff-pushes manifests/<cluster>; the exe locates its render worktree at .local.d/render/<cluster>"
                      nix run .#render-manifests -- "$(params.cluster)" "$(params.node)"
                      """
                          .replace("@MAVEN_CACHE@", MAVEN_CACHE_PATH)),
                  cachePublishStep()
                })));
  }

  /** The cache volume, mounted identically by all three phases. */
  private Object[] cacheMount() {
    return new Object[] {Map.of("name", DataplanLayout.MAVEN_CACHE, "mountPath", MAVEN_CACHE_PATH)};
  }

  /**
   * Phase 1 — lay out the cache. A phase of its own, not a line inside the render, because the
   * PRIMARY local repository has a precondition the read-through tail cannot satisfy: Maven
   * resolves the {@code .mvn/extensions.xml} core extensions BEFORE it builds any project model, in
   * a session created before the chained local repository manager is installed. So the bootstrap
   * resolver sees only {@code maven.repo.local} — the tail is not ignored, it does not exist yet —
   * and the whole {@code staging-extension} closure must be self-sufficient in the primary. (That
   * seeding itself stays inside the nix derivation: it copies from a store path only the derivation
   * knows.)
   *
   * <p>It also PRUNES stale inboxes. A render that fails never reaches the publish phase (Tekton
   * stops the step sequence), so its inbox would linger — and that is the right trade: a failed
   * render may hold half-downloaded artifacts, which must never reach the base. Publishing only on
   * success comes for free from the step ordering; the pruning is what keeps the cost bounded.
   */
  private Map<String, Object> cachePrepareStep() {
    return Map.of(
        "name",
        CACHE_PREPARE_STEP,
        "volumeMounts",
        cacheMount(),
        "image",
        ManifestSynthesisContext.current().floxDebugPolicy().prodImage(),
        "script",
        """
        #!/usr/bin/env bash
        set -euxo pipefail
        mkdir -p "@MAVEN_CACHE@/base/repository" "@MAVEN_CACHE@/base/build-cache"
        mkdir -p "@MAVEN_CACHE@/incoming/$(context.taskRun.name)"
        : "Drop inboxes older than a day — the residue of renders that failed before publishing. No find(1): toolchains/kube carries coreutils, and find lives in findutils; a bash glob plus stat is the same job without widening a shared toolchain for one caller."
        now="$(date +%s)"
        pruned=0
        for stale in "@MAVEN_CACHE@"/incoming/*/; do
          [ -d "$stale" ] || continue
          if [ "$(( now - $(stat -c %Y "$stale") ))" -gt 86400 ]; then
            rm -rf "$stale"
            pruned=$((pruned + 1))
          fi
        done
        : "Say what this run reads through and what it cleaned, so cache-publish's numbers at the other end have a baseline to be read against."
        echo "[cache-prepare] base $(du -sk "@MAVEN_CACHE@/base/repository" | cut -f1)K read-through | ${pruned} stale inbox(es) pruned"
        """
            .replace("@MAVEN_CACHE@", MAVEN_CACHE_PATH));
  }

  /**
   * Phase 3 — publish this run's delta into the shared base. Add-only ({@code cp -rn}), which is
   * SOUND rather than merely convenient: a released Maven coordinate is immutable, so two runs that
   * resolved the same artifact wrote identical bytes, and an existing file is never overwritten.
   * Mutable resolver metadata ({@code _remote.repositories}, {@code *.lastUpdated}) is likewise
   * left alone — the base keeps its own.
   *
   * <p>⚠️ This step is the ONLY writer of the base, and it is serialised today only because the
   * render is ({@code concurrency_limit=1} on the PaC Repository). The day that limit is lifted,
   * this must move to a singleton merger — a CronJob with {@code concurrencyPolicy: Forbid} is the
   * native expression, and the inbox layout is already what it would consume.
   */
  private Map<String, Object> cachePublishStep() {
    return Map.of(
        "name",
        CACHE_PUBLISH_STEP,
        "volumeMounts",
        cacheMount(),
        "image",
        ManifestSynthesisContext.current().floxDebugPolicy().prodImage(),
        "script",
        """
        #!/usr/bin/env bash
        set -euxo pipefail
        inbox="@MAVEN_CACHE@/incoming/$(context.taskRun.name)"
        base="@MAVEN_CACHE@/base/repository"
        if [ ! -d "$inbox/repository" ]; then
          echo "[cache-publish] no repository in the inbox — nothing resolved, nothing to publish"
          rm -rf "$inbox"
          exit 0
        fi
        : "MEASURE, because xtrace shows one `cp` line and says nothing about a step whose entire purpose is a side effect on shared state. du/ls/wc/cut only — toolchains/kube carries coreutils, not findutils."
        delta_k="$(du -sk "$inbox/repository" | cut -f1)"
        delta_n="$(ls -RA1 "$inbox/repository" | wc -l)"
        before_k="$(du -sk "$base" | cut -f1)"
        cp -rn "$inbox/repository/." "$base/"
        after_k="$(du -sk "$base" | cut -f1)"
        : "added = what the base actually gained; overlap = the delta already present, i.e. what the read-through tail will serve next time instead of re-downloading. A shrinking overlap means the base is converging."
        echo "[cache-publish] delta ${delta_k}K (${delta_n} entries) | base ${before_k}K -> ${after_k}K | added $((after_k - before_k))K | overlap $((delta_k - after_k + before_k))K"
        rm -rf "$inbox"
        """
            .replace("@MAVEN_CACHE@", MAVEN_CACHE_PATH));
  }

  private void createPipeline(final Construct scope) {
    final ApiObject pipeline =
        new ApiObject(
            scope,
            "pipeline-render-manifests",
            ApiObjectProps.builder()
                .apiVersion("tekton.dev/v1")
                .kind("Pipeline")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(PIPELINE_NAME)
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "tekton.dev|Pipeline|" + NAMESPACE + "|" + PIPELINE_NAME))
                        .build())
                .build());
    pipeline.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "params",
                new Object[] {
                  Map.of("name", "repo-url", "type", "string"),
                  Map.of("name", "revision", "type", "string"),
                  Map.of("name", "cluster", "type", "string"),
                  Map.of("name", "node", "type", "string")
                },
                // `source` is the only PVC-backed workspace left, and the only one that WANTS the
                // affinity assistant: it is genuine per-run scratch passed fetch -> render.
                "workspaces",
                new Object[] {
                  Map.of("name", "source"), Map.of("name", "basic-auth", "optional", true)
                },
                "tasks",
                new Object[] {
                  Map.of(
                      "name",
                      "fetch",
                      "taskRef",
                      Map.of("name", "git-fetch"),
                      "params",
                      new Object[] {
                        Map.of("name", "repo-url", "value", "$(params.repo-url)"),
                        Map.of("name", "revision", "value", "$(params.revision)")
                      },
                      "workspaces",
                      new Object[] {
                        Map.of("name", "output", "workspace", "source"),
                        Map.of("name", "basic-auth", "workspace", "basic-auth")
                      }),
                  Map.of(
                      "name",
                      "render",
                      "runAfter",
                      new Object[] {"fetch"},
                      "taskRef",
                      Map.of("name", "render-publish"),
                      "params",
                      new Object[] {
                        Map.of("name", "cluster", "value", "$(params.cluster)"),
                        Map.of("name", "node", "value", "$(params.node)")
                      },
                      "workspaces",
                      new Object[] {
                        Map.of("name", "source", "workspace", "source"),
                        Map.of("name", "basic-auth", "workspace", "basic-auth")
                      })
                })));
  }
}
