package io.seedmatic.rke2lab.manifests.units.cluster;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * The sessions' read-only access: a ServiceAccount in every cluster, a ClusterRole that READS it,
 * and a long-lived token Secret the token controller fills in-cluster (so no credential rides the
 * branch; revoked by deleting it). An ndh-ops tool the operator arms collects the tokens into the
 * sessions' kubeconfig.
 *
 * <p>Resources NAMED, group by group, never {@code *}: RBAC cannot subtract, and a wildcard would
 * grant Secrets, every read-through subresource ({@code pods/log}, {@code exec}, {@code attach},
 * {@code portforward}, {@code proxy}: what a container or a node holds) and any aggregated API. A
 * group the fleet adds needs its line here.
 */
public final class SessionViewerManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CLUSTER + "/session-viewer";

  public static final String ACCOUNT = "rke2lab-session-viewer";

  private static final String NAMESPACE = "kube-system";

  private static final Map<String, List<String>> READABLE = readable();

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(ManifestDomainCatalog.CLUSTER, "session-viewer");

  public SessionViewerManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  private static Map<String, List<String>> readable() {
    final Map<String, List<String>> byGroup = new LinkedHashMap<>();
    byGroup.put(
        "",
        List.of(
            "pods",
            "pods/status",
            "configmaps",
            "services",
            "endpoints",
            "events",
            "namespaces",
            "nodes",
            "nodes/status",
            "persistentvolumes",
            "persistentvolumeclaims",
            "serviceaccounts",
            "limitranges",
            "resourcequotas",
            "replicationcontrollers"));
    byGroup.put(
        "apps",
        List.of("daemonsets", "deployments", "replicasets", "statefulsets", "controllerrevisions"));
    byGroup.put("batch", List.of("jobs", "cronjobs"));
    byGroup.put("events.k8s.io", List.of("events"));
    byGroup.put("discovery.k8s.io", List.of("endpointslices"));
    byGroup.put("policy", List.of("poddisruptionbudgets"));
    byGroup.put("networking.k8s.io", List.of("networkpolicies", "ingresses", "ingressclasses"));
    byGroup.put("storage.k8s.io", List.of("storageclasses", "csidrivers", "csinodes"));
    byGroup.put("coordination.k8s.io", List.of("leases"));
    byGroup.put(
        "rbac.authorization.k8s.io",
        List.of("roles", "rolebindings", "clusterroles", "clusterrolebindings"));
    byGroup.put(
        "admissionregistration.k8s.io",
        List.of("validatingwebhookconfigurations", "mutatingwebhookconfigurations"));
    byGroup.put("apiextensions.k8s.io", List.of("customresourcedefinitions"));
    byGroup.put(
        "cluster.seedmatic.io",
        List.of(
            "clusteradoptions",
            "clusterintentions",
            "nodeimages",
            "pooladoptions",
            "poolintentions",
            "poolreflections",
            "volumeintentions"));
    byGroup.put("flox.seedmatic.io", List.of("floxenvs"));
    byGroup.put(
        "cluster.x-k8s.io", List.of("clusters", "machines", "machinedeployments", "machinesets"));
    byGroup.put("controlplane.cluster.x-k8s.io", List.of("rke2controlplanes"));
    byGroup.put("bootstrap.cluster.x-k8s.io", List.of("rke2configs", "rke2configtemplates"));
    byGroup.put(
        "infrastructure.cluster.x-k8s.io",
        List.of("lxcclusters", "lxcmachines", "lxcmachinetemplates"));
    byGroup.put("source.toolkit.fluxcd.io", List.of("gitrepositories", "helmrepositories"));
    byGroup.put("kustomize.toolkit.fluxcd.io", List.of("kustomizations"));
    byGroup.put("fluxcd.controlplane.io", List.of("fluxinstances"));
    byGroup.put("helm.cattle.io", List.of("helmcharts", "helmchartconfigs"));
    byGroup.put(
        "cilium.io",
        List.of(
            "ciliumnetworkpolicies",
            "ciliumclusterwidenetworkpolicies",
            "ciliumloadbalancerippools",
            "ciliuml2announcementpolicies",
            "ciliumnodes",
            "ciliumendpoints"));
    byGroup.put("cert-manager.io", List.of("certificates", "issuers", "clusterissuers"));
    return byGroup;
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final ApiObject account =
        new ApiObject(
            scope,
            "serviceaccount-" + ACCOUNT,
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ServiceAccount")
                .metadata(metadata().namespace(NAMESPACE).build())
                .build());

    final ApiObject role =
        new ApiObject(
            scope,
            "clusterrole-" + ACCOUNT,
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRole")
                .metadata(metadata().build())
                .build());
    role.addJsonPatch(
        JsonPatch.add(
            "/rules",
            READABLE.entrySet().stream()
                .map(
                    entry ->
                        (Object)
                            Map.of(
                                "apiGroups", List.of(entry.getKey()),
                                "resources", entry.getValue(),
                                "verbs", List.of("get", "list", "watch")))
                .toList()));

    final ApiObject binding =
        new ApiObject(
            scope,
            "clusterrolebinding-" + ACCOUNT,
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRoleBinding")
                .metadata(metadata().build())
                .build());
    binding.addJsonPatch(
        JsonPatch.add(
            "/roleRef",
            Map.of(
                "apiGroup", "rbac.authorization.k8s.io", "kind", "ClusterRole", "name", ACCOUNT)));
    binding.addJsonPatch(
        JsonPatch.add(
            "/subjects",
            List.of(Map.of("kind", "ServiceAccount", "name", ACCOUNT, "namespace", NAMESPACE))));
    binding.addDependency(account);
    binding.addDependency(role);

    final ApiObject token =
        new ApiObject(
            scope,
            "secret-" + ACCOUNT + "-token",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(ACCOUNT + "-token")
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                Map.of("kubernetes.io/service-account.name", ACCOUNT)))
                        .build())
                .build());
    token.addJsonPatch(JsonPatch.add("/type", "kubernetes.io/service-account-token"));
    token.addDependency(account);
  }

  private ApiObjectMetadata.Builder metadata() {
    return ApiObjectMetadata.builder()
        .name(ACCOUNT)
        .annotations(packageProfile.packageAnnotations());
  }
}
