// @codebase
package io.seedmatic.rke2lab.manifests.units.cicd;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.upstream.UpstreamYamlInclusion;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class TektonPipelinesManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CICD + "/tekton-pipelines";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cicd", "tekton-pipelines");

  public TektonPipelinesManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String operatorVersion =
        ManifestSynthesisContext.current().componentVersions().of(Component.TEKTON_OPERATOR);

    // Bundle the upstream operator release (Namespace, CRDs, RBAC, Service, Deployment,
    // ConfigMaps, webhooks). The bundle ships its own `tekton-operator` Namespace, so we no
    // longer create one separately. Version is resolved from ComponentVersions; the matching
    // release-<version>.yaml must exist under src/main/resources/upstream/cicd/tekton-operator/
    // — the build will fail fast if it doesn't.
    final String operatorReleaseResource =
        "/upstream/cicd/tekton-operator/release-" + operatorVersion + ".yaml";
    new UpstreamYamlInclusion(scope, operatorReleaseResource, packageProfile, context.yaml());

    // The operator CREATES targetNamespace (tekton-pipelines) only when it reconciles TektonConfig,
    // but PaC's secret (pipelines-as-code-secret) targets it in the same layer. Pre-create it here
    // so Flux — which applies Namespaces before namespaced resources — lands it; the operator then
    // adopts the existing namespace as its targetNamespace.
    createTargetNamespace(scope);
    createTektonConfig(scope);
  }

  private void createTargetNamespace(final Construct scope) {
    new ApiObject(
        scope,
        "namespace-tekton-pipelines",
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name("tekton-pipelines")
                    .annotations(packageProfile.packageAnnotations("|Namespace||tekton-pipelines"))
                    .build())
            .build());
  }

  private void createTektonConfig(final Construct scope) {
    ApiObject config =
        new ApiObject(
            scope,
            "tektonconfig-config",
            ApiObjectProps.builder()
                .apiVersion("operator.tekton.dev/v1alpha1")
                .kind("TektonConfig")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("config")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "operator.tekton.dev|TektonConfig|default|config"))
                        .build())
                .build());

    config.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "profile",
                "all",
                "targetNamespace",
                "tekton-pipelines",
                // coschedule=pipelineruns (default is `workspaces`): the affinity assistant pins a
                // whole PipelineRun's pods to one node instead of one-assistant-per-PVC-workspace.
                // The default caps a TaskRun at ONE PVC workspace ("more than one
                // PersistentVolumeClaim is bound"); our render pipeline's build task needs TWO (the
                // shared `source` PVC passed fetch->render + the persistent `maven-cache` PVC). On
                // a
                // single-node cluster this changes nothing scheduling-wise; the operator merges its
                // other pipeline defaults.
                "pipeline",
                Map.of("coschedule", "pipelineruns"),
                // The TektonConfig CRD requires result.{disabled,is_external_db,options} and
                // pruner.disabled (no schema defaults) — a bare result.disabled fails dry-run.
                // Results feature off (disabled) with an internal-DB posture + empty options;
                // pruner active (disabled=false) with our keep/schedule.
                "result",
                Map.of("disabled", true, "is_external_db", false, "options", Map.of()),
                "pruner",
                Map.of(
                    "disabled",
                    false,
                    "resources",
                    List.of("taskrun", "pipelinerun"),
                    "keep",
                    100,
                    "schedule",
                    "0 8 * * *"),
                // Extend the git_auth installation token PaC mints so it can read the one PRIVATE
                // flake input the render pulls: seedmatic/claude-hub (transitively via ndh). By
                // default secret-github-app-token-scoped=true scopes the token to the payload repo
                // (rke2lab) only → nix 404s on the private claude-hub; scope-extra-repos widens it
                // to
                // rke2lab + claude-hub (least-privilege vs token-scoped=false = the whole
                // installation). The render ALSO fetches flox-controller + flox-nri-plugin (the
                // packages attrset re-exports them) — but those repos are PUBLIC, so they need NO
                // scope; a valid token fetches them. (A 401 "Bad credentials" on a PUBLIC repo
                // means
                // an EMPTY token: nix sent `access-tokens = github.com=` and github rejects the
                // malformed header even anonymously — the fix is a real token, not the scope.) The
                // operator writes these settings into the operator-managed
                // pipelines-as-code ConfigMap (a direct edit would be reverted). This is the
                // KUBERNETES Tekton operator (not OpenShift): its validating webhook REQUIRES PaC
                // settings under platforms.kubernetes and REJECTS platforms.openshift — despite the
                // operand being named "openshift-pipeline-as-code".
                "platforms",
                Map.of(
                    "kubernetes",
                    Map.of(
                        "pipelinesAsCode",
                        // enable MUST be set: the operator's TektonConfig.SetDefaults only defaults
                        // Enable=true when the WHOLE pipelinesAsCode block is nil. Setting
                        // `settings`
                        // makes the block non-nil while Enable stays nil, and SetDefaults then
                        // dereferences `*PipelinesAsCode.Enable` (tektonconfig_defaults.go:105) →
                        // nil
                        // pointer panic in the defaulting webhook → every TektonConfig apply is
                        // rejected (EOF), wedging the whole Tekton install.
                        Map.of(
                            "enable",
                            true,
                            "settings",
                            Map.of(
                                "secret-github-app-scope-extra-repos",
                                "seedmatic/claude-hub")))))));
  }
}
