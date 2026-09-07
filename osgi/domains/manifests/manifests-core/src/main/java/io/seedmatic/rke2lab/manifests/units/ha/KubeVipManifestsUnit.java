// @codebase
package io.seedmatic.rke2lab.manifests.units.ha;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class KubeVipManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.HIGH_AVAILABILITY + "/kube-vip";

  public static final String PATH_PREFIX = "high-availability/kube-vip/";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("high-availability", "kube-vip");

  public KubeVipManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String kubeVipVersion =
        ManifestSynthesisContext.current().componentVersions().of(Component.KUBE_VIP);
    // The control-plane VIP is a PER-CLUSTER netplan address, never a literal:
    // ClusterNetworkBlueprint
    // derives it (vipHostInetAddr) and it rides the synth request's NetworkTopology, so this
    // renders
    // 10.80.7.10 for the mgmt cluster and 10.80.15.10 for bioskop-wrkld from the SAME code — the
    // render's own cluster decides.
    final String vip = ManifestSynthesisContext.current().networkTopology().vipHostInetAddr();

    ApiObject namespace = createNamespace(scope);
    ApiObject serviceAccount = createServiceAccount(scope, namespace);
    ApiObject clusterRole = createClusterRole(scope);
    ApiObject clusterRoleBinding = createClusterRoleBinding(scope, clusterRole, serviceAccount);
    createDaemonSet(scope, namespace, serviceAccount, clusterRoleBinding, kubeVipVersion, vip);
    createService(scope);
  }

  private ApiObject createNamespace(final Construct scope) {
    ApiObject namespace =
        new ApiObject(
            scope,
            "namespace-kube-vip",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Namespace")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kube-vip")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Namespace|default|${kube-vip-namespace}"))
                        .labels(Map.of("name", "kube-vip"))
                        .build())
                .build());
    return namespace;
  }

  private ApiObject createServiceAccount(final Construct scope, final ApiObject namespace) {
    ApiObject serviceAccount =
        new ApiObject(
            scope,
            "serviceaccount-kube-vip",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ServiceAccount")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kube-vip")
                        .namespace("kube-vip")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ServiceAccount|${kube-vip-namespace}|kube-vip"))
                        .build())
                .build());
    serviceAccount.addDependency(namespace);
    return serviceAccount;
  }

  private ApiObject createClusterRole(final Construct scope) {
    ApiObject clusterRole =
        new ApiObject(
            scope,
            "clusterrole-system-kube-vip-role",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRole")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("system:kube-vip-role")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "", Map.of("rbac.authorization.kubernetes.io/autoupdate", "true")))
                        .build())
                .build());

    clusterRole.addJsonPatch(
        JsonPatch.add(
            "/rules",
            List.of(
                Map.of(
                    "apiGroups",
                    List.of(""),
                    "resources",
                    List.of("services", "services/status", "nodes", "endpoints"),
                    "verbs",
                    List.of("list", "get", "watch", "update")),
                Map.of(
                    "apiGroups",
                    List.of("coordination.k8s.io"),
                    "resources",
                    List.of("leases"),
                    "verbs",
                    List.of("list", "get", "watch", "update", "create")))));

    return clusterRole;
  }

  private ApiObject createClusterRoleBinding(
      final Construct scope, final ApiObject clusterRole, final ApiObject serviceAccount) {
    ApiObject clusterRoleBinding =
        new ApiObject(
            scope,
            "clusterrolebinding-system-kube-vip-binding",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRoleBinding")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("system:kube-vip-binding")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|ClusterRoleBinding|default|system:kube-vip-binding"))
                        .build())
                .build());
    clusterRoleBinding.addDependency(clusterRole);
    clusterRoleBinding.addDependency(serviceAccount);

    clusterRoleBinding.addJsonPatch(
        JsonPatch.add(
            "/roleRef",
            Map.of(
                "apiGroup",
                "rbac.authorization.k8s.io",
                "kind",
                "ClusterRole",
                "name",
                "system:kube-vip-role")),
        JsonPatch.add(
            "/subjects",
            List.of(
                Map.of("kind", "ServiceAccount", "name", "kube-vip", "namespace", "kube-vip"))));
    return clusterRoleBinding;
  }

  private void createDaemonSet(
      final Construct scope,
      final ApiObject namespace,
      final ApiObject serviceAccount,
      final ApiObject clusterRoleBinding,
      final String kubeVipVersion,
      final String vip) {
    ApiObject daemonSet =
        new ApiObject(
            scope,
            "daemonset-kube-vip-ds",
            ApiObjectProps.builder()
                .apiVersion("apps/v1")
                .kind("DaemonSet")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kube-vip-ds")
                        .namespace("kube-vip")
                        .labels(Map.of("app", "kube-vip"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "apps|DaemonSet|${kube-vip-namespace}|kube-vip-ds"))
                        .build())
                .build());

    daemonSet.addDependency(namespace);
    daemonSet.addDependency(serviceAccount);
    daemonSet.addDependency(clusterRoleBinding);

    daemonSet.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "selector",
                Map.of("matchLabels", Map.of("name", "kube-vip-ds")),
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        packageProfile.packageAnnotationsWithoutUpstream(),
                        "labels",
                        Map.of("app", "kube-vip", "name", "kube-vip-ds")),
                    "spec",
                    Map.of(
                        "affinity",
                        Map.of(
                            "nodeAffinity",
                            Map.of(
                                "requiredDuringSchedulingIgnoredDuringExecution",
                                Map.of(
                                    "nodeSelectorTerms",
                                    List.of(
                                        Map.of(
                                            "matchExpressions",
                                            List.of(
                                                Map.of(
                                                    "key",
                                                    "node-role.kubernetes.io/master",
                                                    "operator",
                                                    "Exists"))),
                                        Map.of(
                                            "matchExpressions",
                                            List.of(
                                                Map.of(
                                                    "key",
                                                    "node-role.kubernetes.io/control-plane",
                                                    "operator",
                                                    "Exists"))))))),
                        "containers",
                        List.of(
                            Map.of(
                                "name",
                                "kube-vip",
                                "image",
                                "ghcr.io/kube-vip/kube-vip:" + kubeVipVersion,
                                "imagePullPolicy",
                                "Always",
                                "args",
                                List.of("manager"),
                                "env",
                                List.of(
                                    Map.of("name", "vip_arp", "value", "true"),
                                    Map.of("name", "port", "value", "6443"),
                                    // kube-vip binds the VIP as a secondary IP on an existing
                                    // interface — there is no dedicated `rke2-vip0` link in the
                                    // netplan, despite the historical blueprint slot suggesting
                                    // one. The cluster-internal bridge is `vmnet0` (10.80.0.0/21
                                    // cluster CIDR), and the per-cluster VIP sits in that cluster's
                                    // cluster-vip-cidr routed across that bridge — so vmnet0 is the
                                    // only correct binding target.
                                    Map.of("name", "vip_interface", "value", "vmnet0"),
                                    Map.of("name", "vip_cidr", "value", "32"),
                                    Map.of("name", "dns_mode", "value", "first"),
                                    Map.of("name", "cp_enable", "value", "true"),
                                    Map.of("name", "cp_namespace", "value", "kube-system"),
                                    Map.of("name", "svc_enable", "value", "false"),
                                    Map.of("name", "vip_leaderelection", "value", "true"),
                                    Map.of("name", "vip_leaseduration", "value", "5"),
                                    Map.of("name", "vip_renewdeadline", "value", "3"),
                                    Map.of("name", "vip_retryperiod", "value", "1"),
                                    Map.of("name", "address", "value", vip)),
                                "resources",
                                Map.of(
                                    "limits",
                                    Map.of("cpu", "100m", "memory", "128Mi"),
                                    "requests",
                                    Map.of("cpu", "50m", "memory", "64Mi")),
                                "securityContext",
                                Map.of(
                                    "capabilities",
                                    Map.of("add", List.of("NET_ADMIN", "NET_RAW", "SYS_TIME"))))),
                        "hostNetwork",
                        true,
                        "serviceAccountName",
                        "kube-vip",
                        "tolerations",
                        List.of(
                            Map.of("effect", "NoSchedule", "operator", "Exists"),
                            Map.of("effect", "NoExecute", "operator", "Exists")))))));
  }

  private void createService(final Construct scope) {
    ApiObject service =
        new ApiObject(
            scope,
            "service-control-plane-nodeport",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Service")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("control-plane-nodeport")
                        .namespace("kube-system")
                        .labels(Map.of("backup-service", "true"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Service|kube-system|control-plane-nodeport"))
                        .build())
                .build());

    service.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "externalTrafficPolicy",
                "Cluster",
                "ports",
                List.of(
                    Map.of(
                        "name",
                        "kube-apiserver",
                        "nodePort",
                        30443,
                        "port",
                        6443,
                        "protocol",
                        "TCP",
                        "targetPort",
                        6443)),
                "selector",
                Map.of("component", "kube-apiserver", "tier", "control-plane"),
                "type",
                "NodePort")));
  }
}
