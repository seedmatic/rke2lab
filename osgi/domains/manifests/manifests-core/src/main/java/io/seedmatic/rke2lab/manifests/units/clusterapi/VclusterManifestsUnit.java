package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.clusterpki.contract.VclusterDeclaration;
import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Each vcluster its cluster hosts ({@link VclusterDeclaration}), on that cluster's own branch: its
 * namespace, loft's chart, the API on the fabric, and the policy that pins the tenant's pods to its
 * pool. vcluster takes the rendered {@code <name>-certs} ({@link VclusterCertsManifestsUnit}) as
 * its PKI rather than minting one.
 *
 * <p>Placement rests on the pool's taint and label ({@code project=<name>}). The control plane is
 * pinned by the chart's own scheduling; the synced pods carry the toleration ({@code
 * enforceTolerations}) but the chart cannot select their nodes, so a MutatingAdmissionPolicy adds
 * the nodeSelector to every pod created in the namespace, whoever creates it.
 *
 * <p>ONE LoadBalancer, the render's, on the fabric pool at the cluster's {@code host(3)}: the
 * chart's own Service stays ClusterIP. {@code Local}, so the API pod sees the caller's address.
 */
public final class VclusterManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CLUSTER_API + "/vcluster";

  private static final String LABEL = "project";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(ManifestDomainCatalog.CLUSTER_API, "vcluster");

  public VclusterManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of(VclusterCertsManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final ManifestSynthesisContext synth = ManifestSynthesisContext.current();
    final String self = synth.bootstrapIdentity().clusterName();
    final String chartVersion = synth.componentVersions().of(Component.VCLUSTER_CHART);
    for (final VclusterDeclaration.Vcluster vcluster : VclusterDeclaration.fleet().vclusters()) {
      if (vcluster.hostCluster().equals(self)) {
        render(scope, vcluster, chartVersion, apiAddress(self));
      }
    }
  }

  private static String apiAddress(final String cluster) {
    return ClusterNetworkBlueprint.builder()
        .cluster(cluster)
        .node("master")
        .deriveRecipeModel()
        .build()
        .fabric()
        .lbCidr()
        .host(3)
        .getHostAddress();
  }

  private void render(
      final Construct scope,
      final VclusterDeclaration.Vcluster vcluster,
      final String chartVersion,
      final String apiAddress) {
    final String name = vcluster.name();
    final String namespace = vcluster.namespace();
    final String selector = LABEL + "=" + name;

    final ApiObject ns =
        new ApiObject(
            scope,
            "namespace-" + namespace,
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Namespace")
                .metadata(clusterScoped(namespace).build())
                .build());

    final ApiObject chart =
        new ApiObject(
            scope,
            "helmchart-vcluster-" + name,
            ApiObjectProps.builder()
                .apiVersion("helm.cattle.io/v1")
                .kind("HelmChart")
                .metadata(clusterScoped(name).namespace("kube-system").build())
                .build());
    chart.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "repo",
                "https://charts.loft.sh",
                "chart",
                "vcluster",
                "version",
                chartVersion,
                "targetNamespace",
                namespace,
                "valuesContent",
                values(name, selector, apiAddress))));
    chart.addDependency(ns);

    final ApiObject service =
        new ApiObject(
            scope,
            "service-vcluster-" + name + "-api",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Service")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name + "-api")
                        .namespace(namespace)
                        .labels(Map.of("io.cilium/lb-ipam-pool", "fabric", "app", "vcluster-api"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                Map.of("lbipam.cilium.io/ips", apiAddress)))
                        .build())
                .build());
    service.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "type",
                "LoadBalancer",
                "externalTrafficPolicy",
                "Local",
                "selector",
                Map.of("app", "vcluster", "release", name),
                "ports",
                List.of(
                    Map.of("name", "https", "port", 443, "targetPort", 8443, "protocol", "TCP")))));
    service.addDependency(ns);

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
    binding.addDependency(ns);
  }

  private static String values(final String name, final String selector, final String apiAddress) {
    return """
        controlPlane:
          statefulSet:
            scheduling:
              nodeSelector:
                %1$s: %2$s
              tolerations:
                - key: %1$s
                  operator: Equal
                  value: %2$s
                  effect: NoSchedule
          service:
            spec:
              type: ClusterIP
        sync:
          toHost:
            pods:
              enforceTolerations:
                - %3$s:NoSchedule
        exportKubeConfig:
          server: https://%4$s:443
        """
        .formatted(LABEL, name, selector, apiAddress);
  }

  private ApiObjectMetadata.Builder clusterScoped(final String name) {
    return ApiObjectMetadata.builder().name(name).annotations(packageProfile.packageAnnotations());
  }
}
