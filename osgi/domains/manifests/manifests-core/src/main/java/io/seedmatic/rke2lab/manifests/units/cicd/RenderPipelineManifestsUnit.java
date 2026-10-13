package io.seedmatic.rke2lab.manifests.units.cicd;

import io.seedmatic.rke2lab.auth.contract.GithubAppTokens;
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
 * wired by one Pipeline ({@code fetch → render}):
 *
 * <ul>
 *   <li>{@code git-fetch} — clones the source repo at the pushed revision into the shared {@code
 *       source} workspace, authenticating with PaC's {@code basic-auth} workspace (the {@code
 *       git_auth_secret} App token PaC injects into the PipelineRun).
 *   <li>{@code render-publish} — nix-build-annotated (the flox NRI system gives the {@code
 *       step-render} container the nix runtime: nix on PATH, daemonless {@code NIX_CONFIG}, and the
 *       {@code /nix} store overlay hosted on a persistent PVC), then runs the flake's {@code
 *       render-manifests} app at the cloned revision (never the working tree): the SINGLE render
 *       definition (shared with dev/release, no hand-scripted mvn+java that drifts) builds {@code
 *       manifests-cli} offline from the nix store (its Maven dependencies are a fixed-output
 *       derivation, no cache volume), then runs the {@code update} verb — re-render following the
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
 * builds + runs the closure, while the git-sops env brings git + sops so the render seals the
 * branch Secrets it commits in memory and — with {@code SOPS_AGE_KEY} (the cluster age key
 * replicated into this namespace, {@link
 * io.seedmatic.rke2lab.manifests.units.gitops.SopsAgeSecretManifestsUnit}) — opens {@code .secrets}
 * and the cellar asset in memory, making the render secret-FULL. Only the {@code render-publish}
 * pod owns a {@code step-render} container, so the injection applies there and is ignored on the
 * {@code git-fetch} pod (no bare-key fallback — each container opts in BY NAME). See {@code
 * docs/architecture/patterns/flox-store-resolved-runtime-and-builder.adoc} + {@link
 * io.seedmatic.rke2lab.manifests.contract.FloxAnnotation}.
 *
 * <p><b>No Maven cache.</b> Only {@code source} (per-run, bound by the stub to a {@code
 * volumeClaimTemplate}) and {@code basic-auth} (PaC's {@code git_auth_secret}) are workspaces, and
 * the only state carried across runs is the NODE's {@code /nix} store, which the flox NRI plugin
 * wires into the container: it holds the fixed-output Maven dependency repository by hash and the
 * jars by source, so an unchanged source is a store hit and a dependency is downloaded once per
 * change. The render is still serialised (concurrency 1, on the PaC {@code Repository}/stub) — the
 * nix store is not multi-writer safe.
 *
 * <p>The push token is wired: the {@code render-publish} step extracts PaC's App token from the
 * mounted {@code git_auth} secret into {@link GithubAppTokens#PIPELINE_TOKEN_ENV}, which the
 * in-cluster {@code publish} reveals for the ff-push (container-aware {@code
 * ManifestSynthesisScenario.revealGithubToken} — on-demand App mint OPERATOR, env IN_CLUSTER).
 */
public final class RenderPipelineManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CICD + "/render-pipeline";

  // rke2lab OWNS the render pipeline (its Pipeline/Tasks/PVC + the PipelineRuns PaC creates against
  // the Repository CR), so it lives in rke2lab-system — the runtime-system namespace (which already
  // hosts flox-controller), NOT tekton-pipelines (that is the Tekton/PaC controllers' own system).
  private static final String NAMESPACE = ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name();

  private static final String PIPELINE_NAME = "render-manifests";

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
    createGitFetchTask(scope);
    createRenderPublishTask(scope);
    createPipeline(scope);
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
                        .annotations(packageProfile.packageAnnotations())
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
                      ManifestSynthesisContext.current().containerImages().carrier(),
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
                        .annotations(packageProfile.packageAnnotations())
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
                      ManifestSynthesisContext.current().containerImages().carrier(),
                      "workingDir",
                      "$(workspaces.source.path)",
                      "script",
                      """
                      #!/usr/bin/env bash
                      set -euxo pipefail
                      : "The publish narrates live to stdout: it runs STANDALONE (not seed-outcluster under Pulumi), so PaxLogbackConfigurer keeps its console appender on and the render/delivery logs land in this container's logs"
                      GIT_AUTH_DIR="$(workspaces.basic-auth.path)"
                      : "PaC mints the App token into the git_auth secret's git-provider-token key; read it RAW (not scraped from the .git-credentials URL) into @PUSH_TOKEN_ENV@ for the ff-push (the scion reveals it in-container, ManifestSynthesisScenario.revealGithubToken) + nix + maven. Backticks, not the dollar-paren form, so Tekton does not claim the substitution as one of its own vars"
                      : "nix must authenticate its flake-input fetches: the closure pulls a PRIVATE input, seedmatic/claude-hub transitively via ndh; the flox NRI sets NIX_CONFIG but no access-tokens, so an unauthenticated github fetch 404s on the private repo. Append the App token so nix reads it AS the App. Requires PaC to scope the git_auth token to include claude-hub via secret-github-app-scope-extra-repos"
                      : "xtrace is disabled across the next block so the App token is never echoed to the logs"
                      set +x
                      if [ -f "$GIT_AUTH_DIR/git-provider-token" ]; then
                        export @PUSH_TOKEN_ENV@=`cat "$GIT_AUTH_DIR/git-provider-token"`
                        export GH_TOKEN="$@PUSH_TOKEN_ENV@"
                        export NIX_CONFIG="${NIX_CONFIG:-}"$'\\n'"access-tokens = github.com=$@PUSH_TOKEN_ENV@"
                      fi
                      set -x
                      : "Secret-full render prerequisites — fail loud with a clear message, not a downstream decode error. SOPS_AGE_KEY arrives via the git-sops env spec.inject (flox-controller webhook): it opens the encrypted .secrets in memory and reads back the cellar carrier the previous render sealed; the render seals the Secrets it commits to manifests/<cluster> in memory, so no git filter is needed"
                      set +x
                      [ -n "${SOPS_AGE_KEY:-}" ] || { echo >&2 "render: SOPS_AGE_KEY not set (git-sops inject / replicated sops-age missing)"; exit 1; }
                      set -x
                      : "The flox NRI plugin put nix on PATH, injected NIX_CONFIG (daemonless single-user) and hosts the /nix store overlay on the assigned persistent PVC, so there is no flox env and no flox activate. The render-manifests app of the source checkout is the ONE render definition shared with dev and release: it builds manifests-cli, signs, and ff-pushes manifests/<cluster>; the render's plot manifests/<cluster> lives in memory, no worktree is checked out"
                      : "The flake is named by REVISION, never as the working tree: a checkout may hold decrypted sops files, and evaluating the tree would copy them into this node's store. shallow=1 because the clone is a depth-1 fetch"
                      nix run "git+file://$PWD?rev=`git rev-parse HEAD`&shallow=1#render-manifests" -- "$(params.cluster)" "$(params.node)"
                      """
                          .replace("@PUSH_TOKEN_ENV@", GithubAppTokens.PIPELINE_TOKEN_ENV))
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
                        .annotations(packageProfile.packageAnnotations())
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
