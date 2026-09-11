package io.seedmatic.rke2lab.manifests.units.cicd;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRefs;
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
 * <p><b>Workspaces:</b> the Pipeline DECLARES {@code source} (per-run, bound by the stub to a
 * {@code volumeClaimTemplate}), {@code maven-cache} (the persistent RWO PVC rendered here), and
 * {@code basic-auth} (PaC's {@code git_auth_secret}); the PipelineRun stub BINDS them. The nix
 * store is NOT a workspace — the webhook injects it as a raw volume from the annotation. The build
 * is serialised (concurrency 1, set on the PaC {@code Repository}/stub) — a Maven local repo,
 * build-cache, and the nix store are not multi-writer safe.
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
   * Container name the flox NRI plugin keys on: {@code flox.seedmatic.io/nix-build.step-render}.
   */
  private static final String RENDER_STEP = "render";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cicd", "render-pipeline");

  public RenderPipelineManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    createMavenCachePvc(scope);
    createGitFetchTask(scope);
    createRenderPublishTask(scope);
    createPipeline(scope);
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
    // RWO on the SHARED ZFS class (bind-mount): the ~/.m2 local repo + maven-build-cache are
    // single-writer and the pipeline runs concurrency 1, but under coschedule=pipelineruns the
    // affinity assistant and the task pod co-locate on one node and BOTH mount this PVC — the
    // exclusive (device-mount) default SC then fails "device already mounted", the same trap the
    // coalesced `source` PVC hit. The shared bind-mount lets the same-node pods co-mount the one
    // RWO
    // volume. Both ZFS classes bind Immediate, so this PVC Binds at deploy (no
    // Pending-until-consumer
    // wedge of the Flux Kustomization's wait).
    pvc.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "accessModes",
                List.of("ReadWriteOnce"),
                "storageClassName",
                "openebs-zfs-shared",
                "resources",
                Map.of("requests", Map.of("storage", "4Gi")))));
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
                  Map.of("name", "source"),
                  Map.of("name", "maven-cache"),
                  Map.of("name", "basic-auth", "optional", true)
                },
                "steps",
                new Object[] {
                  Map.of(
                      // Container name = step-render; the PipelineRun stub carries
                      // flox.seedmatic.io/nix-build.step-render=<pvc>, so the flox NRI system gives
                      // this container the nix runtime: nix on PATH, NIX_CONFIG, and the /nix store
                      // overlay hosted on the assigned (webhook-ensured) persistent PVC.
                      "name",
                      RENDER_STEP,
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
                      : "PaC minted an App token into the mounted git_auth secret; extract it into RKE2LAB_PUSH_TOKEN so the publish reveals it for the ff-push (the scion reads it in-container, ManifestSynthesisScenario.revealGithubToken). Backtick substitution, not the dollar-paren form, so Tekton does not claim it as one of its own vars"
                      : "The same App token authenticates .mvn/settings.xml to GitHub Packages (the env.GH_TOKEN placeholder) so the reactor resolves the private seedmatic releases java-systemd and java-bbox-api-client. Requires the App to carry packages:read"
                      : "nix must authenticate its flake-input fetches: the closure pulls a PRIVATE input, seedmatic/claude-hub transitively via ndh; the flox NRI sets NIX_CONFIG but no access-tokens, so an unauthenticated github fetch 404s on the private repo. Append the App token so nix reads it AS the App. Requires PaC to scope the git_auth token to include claude-hub via secret-github-app-scope-extra-repos"
                      : "xtrace is disabled across the next block so the App token is never echoed to the logs"
                      set +x
                      if [ -f "$GIT_AUTH_DIR/.git-credentials" ]; then
                        export RKE2LAB_PUSH_TOKEN=`sed -E 's#https://[^:]+:([^@]+)@.*#\\1#' "$GIT_AUTH_DIR/.git-credentials" | head -n1`
                        export GH_TOKEN="$RKE2LAB_PUSH_TOKEN"
                        export NIX_CONFIG="${NIX_CONFIG:-}"$'\\n'"access-tokens = github.com=$RKE2LAB_PUSH_TOKEN"
                      fi
                      set -x
                      : "The maven-cache PVC is the cache ROOT: repository/ AND build-cache/ side by side. M2_REPO points at repository; the render app derives MAVEN_BUILD_CACHE from its dirname (the PVC), so the build cache persists beside the repo, making renders incremental across pushes"
                      export M2_REPO="$(workspaces.maven-cache.path)/repository"
                      : "Secret-full render prerequisites — fail loud with a clear message, not a downstream decode error. SOPS_AGE_KEY arrives via the git-sops env spec.inject (flox-controller webhook); the sops-yaml filter is wired by the env on-activate hook; the clone step (also on git-sops) already smudged .secrets in this shared workspace at checkout"
                      set +x
                      [ -n "${SOPS_AGE_KEY:-}" ] || { echo >&2 "render: SOPS_AGE_KEY not set (git-sops inject / replicated sops-age missing)"; exit 1; }
                      set -x
                      git config --get filter.sops-yaml.smudge >/dev/null 2>&1 || { echo >&2 "render: sops-yaml git filter not registered (git-sops on-activate hook did not wire it)"; exit 1; }
                      : "The flox NRI plugin put nix on PATH, injected NIX_CONFIG (daemonless single-user) and hosts the /nix store overlay on the assigned persistent PVC, so there is no flox env and no flox activate. nix run .#render-manifests from the source checkout is the ONE render definition shared with dev and release: it builds manifests-cli, signs, and ff-pushes manifests/<cluster>; the exe locates its render worktree at .local.d/render/<cluster>"
                      nix run .#render-manifests -- "$(params.cluster)" "$(params.node)"
                      """)
                })));
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
                "workspaces",
                new Object[] {
                  Map.of("name", "source"),
                  Map.of("name", "maven-cache"),
                  Map.of("name", "basic-auth", "optional", true)
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
                        Map.of("name", "maven-cache", "workspace", "maven-cache"),
                        Map.of("name", "basic-auth", "workspace", "basic-auth")
                      })
                })));
  }
}
