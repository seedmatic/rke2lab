package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.clusterpki.contract.VclusterDeclaration;
import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
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
 * enforceTolerations}) but the chart cannot select their nodes: {@link VclusterPinManifestsUnit}
 * adds the nodeSelector, and this unit renders only where that pin is served, after it.
 *
 * <p>ONE LoadBalancer, the render's, on the fabric pool at the cluster's {@code host(3)}: the
 * chart's own Service stays ClusterIP. {@code Local}, so the API pod sees the caller's address.
 */
public final class VclusterManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CLUSTER_API + "/vcluster";

  private static final String LABEL = "project";

  private static final String API_LABEL = ManifestAnnotation.VCLUSTER_API_LABEL.key();

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(ManifestDomainCatalog.CLUSTER_API, "vcluster");

  public VclusterManifestsUnit() {
    // After the pin: its Kustomization dependsOn the policy's, so no synced pod is ever admitted
    // before the nodeSelector is added to it.
    super(
        MANIFEST_UNIT_ID,
        List.of(
            VclusterCertsManifestsUnit.MANIFEST_UNIT_ID,
            VclusterPinManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final ManifestSynthesisContext synth = ManifestSynthesisContext.current();
    final String self = synth.bootstrapIdentity().clusterName();
    if (!VclusterPinManifestsUnit.servesThePin(synth)) {
      return;
    }
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
                .metadata(
                    clusterScoped(namespace)
                        .labels(
                            Map.of(
                                "pod-security.kubernetes.io/enforce", "restricted",
                                "pod-security.kubernetes.io/warn", "restricted"))
                        .build())
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
                        .labels(Map.of("io.cilium/lb-ipam-pool", "fabric", API_LABEL, name))
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

    // Announced from the pod's own pool: Local traffic needs the answering node to host it.
    final ApiObject announcement =
        new ApiObject(
            scope,
            "ciliuml2announcementpolicy-vcluster-" + name + "-api",
            ApiObjectProps.builder()
                .apiVersion("cilium.io/v2alpha1")
                .kind("CiliumL2AnnouncementPolicy")
                .metadata(clusterScoped("vcluster-" + name + "-api").build())
                .build());
    announcement.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "interfaces", List.of("fabric0"),
                "loadBalancerIPs", true,
                "nodeSelector",
                    Map.of(
                        "matchExpressions",
                        List.of(Map.of("key", LABEL, "operator", "In", "values", List.of(name)))),
                "serviceSelector", Map.of("matchLabels", Map.of(API_LABEL, name)))));

    // Fail closed: only the tenant's own pods reach the API. The operator's seat is added once the
    // address its flows carry is measured (behind the tailnet's SNAT); its refused flows, seen by
    // hubble, are that measure.
    final ApiObject apiPolicy =
        new ApiObject(
            scope,
            "ciliumnetworkpolicy-vcluster-" + name + "-api",
            ApiObjectProps.builder()
                .apiVersion("cilium.io/v2")
                .kind("CiliumNetworkPolicy")
                .metadata(clusterScoped(name + "-api").namespace(namespace).build())
                .build());
    apiPolicy.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "endpointSelector",
                    Map.of("matchLabels", Map.of("app", "vcluster", "release", name)),
                "ingress",
                    List.of(
                        Map.of(
                            "fromEndpoints",
                            List.of(
                                Map.of(
                                    "matchLabels",
                                    Map.of("k8s:io.kubernetes.pod.namespace", namespace))))))));
    apiPolicy.addDependency(ns);
  }

  private static String values(final String name, final String selector, final String apiAddress) {
    return """
        controlPlane:
          distro:
            k8s:
              securityContext:
                allowPrivilegeEscalation: false
                runAsNonRoot: true
                runAsUser: 12345
                runAsGroup: 12345
                capabilities:
                  drop: [ALL]
                seccompProfile:
                  type: RuntimeDefault
          statefulSet:
            # Rootless, for the namespace's PSA `restricted`: only at FIRST deployment, vcluster has
            # no migration from root.
            security:
              podSecurityContext:
                runAsNonRoot: true
                runAsUser: 12345
                runAsGroup: 12345
                fsGroup: 12345
                seccompProfile:
                  type: RuntimeDefault
              containerSecurityContext:
                allowPrivilegeEscalation: false
                runAsNonRoot: true
                runAsUser: 12345
                runAsGroup: 12345
                capabilities:
                  drop: [ALL]
                seccompProfile:
                  type: RuntimeDefault
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
        policies:
          resourceQuota:
            enabled: true
            quota:
              requests.cpu: 4
              requests.memory: 8Gi
              limits.cpu: 8
              limits.memory: 16Gi
              requests.storage: 50Gi
              services.nodeports: 0
              services.loadbalancers: 1
              count/pods: 40
          limitRange:
            enabled: true
          # The chart's own egress fence, whose defaults are the shape asked: the tenant's pods
          # reach their control plane and the internet minus every private range (all of the
          # fleet's addresses); no fleet Service, no host API, no other node's kubelet.
          networkPolicy:
            enabled: true
        """
        .formatted(LABEL, name, selector, apiAddress);
  }

  private ApiObjectMetadata.Builder clusterScoped(final String name) {
    return ApiObjectMetadata.builder().name(name).annotations(packageProfile.packageAnnotations());
  }
}
