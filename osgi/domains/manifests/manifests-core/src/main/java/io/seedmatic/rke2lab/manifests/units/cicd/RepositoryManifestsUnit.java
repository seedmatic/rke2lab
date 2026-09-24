package io.seedmatic.rke2lab.manifests.units.cicd;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
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
 * The Pipelines-as-Code {@code Repository} CR — the Tekton TWIN of the Flux {@code GitRepository}
 * ({@code FluxRootManifestsUnit}). Where the Flux {@code GitRepository} declares the OUTPUT branch
 * ({@code manifests/<cluster>}) it pulls, this {@code Repository} declares the SOURCE repo
 * (rke2lab) PaC watches for pushes and binds it to the execution namespace ({@code rke2lab-system},
 * where PaC runs the matched PipelineRuns — rke2lab owns the runs; the PaC controller stays in
 * {@code tekton-pipelines}).
 *
 * <p>The per-cluster attachment rides {@code spec.params}: the {@code .tekton/} PipelineRun stub on
 * the source branch reads {@code {{ cluster }}} / {@code {{ node }}} from here (PaC exposes a
 * {@code Repository}'s {@code spec.params} as standard PipelineRun params), so the generic stub
 * renders {@code manifests publish} for THIS cluster ({@code -Dcluster=…} → pushes {@code
 * manifests/<cluster>}). The identity comes from the synth-time {@link BootstrapIdentity} the
 * synthesis already holds — the same slice {@code FluxRootManifestsUnit} names the branch from.
 *
 * <p>Not a secret, not the bootstrap lane: the {@code Repository} carries no credential (the App is
 * configured globally in {@code pipelines-as-code-secret}), so it reconciles from the branch like
 * any other resource. {@code spec.url} matches the {@code GitRepository} source of truth minus the
 * {@code .git} suffix (PaC canonicalises the GitHub repo URL without it).
 */
public final class RepositoryManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CICD + "/repository";

  // rke2lab-system (runtime-system) is the EXECUTION namespace: PaC runs the matched PipelineRuns
  // in
  // the Repository's own namespace, and rke2lab owns those runs — not tekton-pipelines (the Tekton/
  // PaC controllers' system). The PaC controller (tekton-pipelines) watches Repository CRs and
  // creates the runs here.
  private static final String NAMESPACE = ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name();

  private static final String REPOSITORY_NAME = "rke2lab";

  private static final String SOURCE_REPO_URL = "https://github.com/seedmatic/rke2lab";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cicd", "repository");

  public RepositoryManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final BootstrapIdentity identity = ManifestSynthesisContext.current().bootstrapIdentity();

    final ApiObject repository =
        new ApiObject(
            scope,
            "repository-rke2lab",
            ApiObjectProps.builder()
                .apiVersion("pipelinesascode.tekton.dev/v1alpha1")
                .kind("Repository")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(REPOSITORY_NAME)
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "pipelinesascode.tekton.dev|Repository|"
                                    + NAMESPACE
                                    + "|"
                                    + REPOSITORY_NAME))
                        .build())
                .build());

    repository.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "url",
                SOURCE_REPO_URL,
                // Exposed to the .tekton/ PipelineRun as {{ cluster }} / {{ node }} — the generic
                // stub renders `manifests publish` for THIS cluster without threading identity
                // through the source repo.
                "params",
                new Object[] {
                  Map.of("name", "cluster", "value", identity.clusterName()),
                  Map.of("name", "node", "value", identity.nodeName())
                },
                // Serialise the render: PaC queues PipelineRuns and runs ONE at a time. It is not a
                // precaution but the OTHER HALF of choosing a shared mutable cache — the render
                // shares one RWO maven-cache PVC (local repo + maven-build-cache) and the node's
                // /nix store across runs, none of it multi-writer safe, and RWO is node-scoped, so
                // two concurrent runs on a single-node cluster WOULD co-mount and race the cache
                // into corruption. concurrency_limit=1 is what actually enforces the "one render at
                // a time" the pipeline assumes (previously only claimed in comments).
                //
                // ⚠️ It is also a THROUGHPUT CEILING, and the render is per-cluster: at N clusters
                // the renders queue behind each other. The way out is not a bigger limit but the
                // readers-many/writer-serialised shape — a read-only shared base plus a per-run
                // writable head, merged at the end (the union is sound because a released Maven
                // coordinate is immutable, so two runs adding one artifact write identical bytes).
                // Maven 3.9 expresses the local-repository half natively with a chained local repo
                // (maven.repo.local.tail), and the build-cache half wants the extension's REMOTE
                // cache with its opt-in save rather than a directory on a shared volume at all —
                // which is the same workspace/cache confusion as the one fixed in
                // RenderPipelineManifestsUnit, one level down.
                "concurrency_limit",
                1,
                // Read the PipelineRun definition from the branch that was pushed (the grow branch
                // is the source of truth); PaC's provenance is only `source` or `default_branch`.
                "settings",
                Map.of("pipelinerun_provenance", "source"))));
  }
}
