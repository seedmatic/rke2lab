package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.clusterpki.contract.VclusterDeclaration;
import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * The pin of each hosted vcluster's pods to its pool: a MutatingAdmissionPolicy adding {@code
 * nodeSelector: {project: <name>}} to every pod created in its namespace, whoever creates it, and
 * failing closed. Its own cell, so {@link VclusterManifestsUnit}'s Kustomization waits on it.
 *
 * <p>MutatingAdmissionPolicy v1 is served from Kubernetes 1.36 (GA, on by default; beta and off in
 * 1.34). The render knows the RKE2 its cluster was GROWN on (the server image's version), not the
 * live apiserver's, so it gates on that: below 1.36, or with no image, neither the pin nor the
 * vcluster is rendered.
 */
public final class VclusterPinManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CLUSTER_API + "/vcluster-pin";

  private static final String LABEL = "project";

  private static final int FIRST_SERVING_MINOR = 36;

  private static final Pattern RKE2_VERSION = Pattern.compile("^v1\\.(\\d+)\\.");

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(ManifestDomainCatalog.CLUSTER_API, "vcluster-pin");

  public VclusterPinManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  /** Whether the cluster serves the pin: grown on RKE2 1.36 or later. */
  static boolean servesThePin(final ManifestSynthesisContext synth) {
    return synth
        .imageState()
        .map(image -> RKE2_VERSION.matcher(image.rke2Version()))
        .filter(Matcher::find)
        .map(m -> Integer.parseInt(m.group(1)) >= FIRST_SERVING_MINOR)
        .orElse(false);
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final ManifestSynthesisContext synth = ManifestSynthesisContext.current();
    if (!servesThePin(synth)) {
      return;
    }
    final String self = synth.bootstrapIdentity().clusterName();
    for (final VclusterDeclaration.Vcluster vcluster : VclusterDeclaration.fleet().vclusters()) {
      if (vcluster.hostCluster().equals(self)) {
        render(scope, vcluster.name(), vcluster.namespace());
      }
    }
  }

  private void render(final Construct scope, final String name, final String namespace) {
    final String policyName = "vcluster-" + name + "-pin-to-pool";
    final ApiObject policy =
        new ApiObject(
            scope,
            "mutatingadmissionpolicy-" + policyName,
            ApiObjectProps.builder()
                .apiVersion("admissionregistration.k8s.io/v1")
                .kind("MutatingAdmissionPolicy")
                .metadata(clusterScoped(policyName).build())
                .build());
    policy.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "failurePolicy",
                "Fail",
                "reinvocationPolicy",
                "IfNeeded",
                "matchConstraints",
                Map.of(
                    "resourceRules",
                    List.of(
                        Map.of(
                            "apiGroups", List.of(""),
                            "apiVersions", List.of("v1"),
                            "operations", List.of("CREATE"),
                            "resources", List.of("pods")))),
                "mutations",
                List.of(
                    Map.of(
                        "patchType",
                        "ApplyConfiguration",
                        "applyConfiguration",
                        Map.of(
                            "expression",
                            "Object{spec: Object.spec{nodeSelector: {\""
                                + LABEL
                                + "\": \""
                                + name
                                + "\"}}}"))))));

    final ApiObject binding =
        new ApiObject(
            scope,
            "mutatingadmissionpolicybinding-" + policyName,
            ApiObjectProps.builder()
                .apiVersion("admissionregistration.k8s.io/v1")
                .kind("MutatingAdmissionPolicyBinding")
                .metadata(clusterScoped(policyName).build())
                .build());
    binding.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "policyName",
                policyName,
                "matchResources",
                Map.of(
                    "namespaceSelector",
                    Map.of("matchLabels", Map.of("kubernetes.io/metadata.name", namespace))))));
    binding.addDependency(policy);
  }

  private ApiObjectMetadata.Builder clusterScoped(final String name) {
    return ApiObjectMetadata.builder().name(name).annotations(packageProfile.packageAnnotations());
  }
}
