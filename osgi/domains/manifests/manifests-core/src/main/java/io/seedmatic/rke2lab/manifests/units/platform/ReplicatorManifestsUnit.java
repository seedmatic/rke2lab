package io.seedmatic.rke2lab.manifests.units.platform;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.profiles.ReplicatorSourceSecretsMaterial;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRefs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class ReplicatorManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.PLATFORM + "/replicator";

  private static final String DOMAIN_NAME = "platform";
  private static final String PACKAGE_NAME = "replicator";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(DOMAIN_NAME, PACKAGE_NAME);

  public ReplicatorManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String replicatorVersion =
        ManifestSynthesisContext.current().componentVersions().of(Component.KUBERNETES_REPLICATOR);

    final ApiObject sourceNamespace = createSourceNamespace(scope);
    ApiObject clusterRole = createClusterRole(scope, replicatorVersion);
    ApiObject serviceAccount = createServiceAccount(scope, replicatorVersion);
    createClusterRoleBinding(scope, clusterRole, serviceAccount, replicatorVersion);
    createDeployment(scope, serviceAccount, replicatorVersion);
    createSourceSecrets(scope, sourceNamespace);
  }

  /**
   * The SOURCE secrets (git auth, docker config, tailscale oauth) the seal rehydrated from {@code
   * .secrets} and the manifests scion revealed onto the context. Rendered on the NODE_BOOTSTRAP
   * lane — seeded node-side over devlxd, NEVER committed to the branch (real credentials) — and
   * annotated so the mittwald replicator fans each out to the target namespaces where the {@code
   * replicate-from} placeholders live. Absent material (a bare survey / before the seal filed) ⇒
   * nothing rendered; the source namespace still stands for the controller.
   */
  private void createSourceSecrets(final Construct scope, final ApiObject sourceNamespace) {
    final Optional<ReplicatorSourceSecretsMaterial> material =
        ManifestSynthesisContext.current().replicatorSources();
    if (material.isEmpty()) {
      return;
    }
    for (final ReplicatorSourceSecretsMaterial.SourceSecret source :
        material.orElseThrow().sources()) {
      final Map<String, String> extra = new LinkedHashMap<>();
      extra.put(ManifestAnnotation.NODE_BOOTSTRAP.key(), "true");
      extra.put("replicator.v1.mittwald.de/replication-allowed", "true");
      extra.put(
          "replicator.v1.mittwald.de/replication-allowed-namespaces",
          String.join(",", source.replicationAllowedNamespaces()));

      final ApiObject secret =
          new ApiObject(
              scope,
              "secret-" + source.name(),
              ApiObjectProps.builder()
                  .apiVersion("v1")
                  .kind("Secret")
                  .metadata(
                      ApiObjectMetadata.builder()
                          .name(source.name())
                          .namespace(source.namespace())
                          .annotations(packageProfile.templateAnnotations(extra))
                          .build())
                  .build());

      // Emitted after the source namespace so the bootstrap multi-doc file lists the Namespace
      // first — rke2's server-manifests applier creates it before these Secrets land in it.
      secret.addDependency(sourceNamespace);
      secret.addJsonPatch(
          JsonPatch.add("/type", source.type()), JsonPatch.add("/stringData", source.stringData()));
    }
  }

  // NODE_BOOTSTRAP: the source namespace rides the bootstrap lane WITH its secrets (a self-
  // contained set seeded node-side at grow), so the Secrets have a namespace to land in before Flux
  // — never split across the bootstrap lane and the rendered branch.
  private ApiObject createSourceNamespace(final Construct scope) {
    return new ApiObject(
        scope,
        "namespace-" + ClusterRefs.SECRETS_NAMESPACE,
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name(ClusterRefs.SECRETS_NAMESPACE)
                    .annotations(
                        packageProfile.templateAnnotations(
                            Map.of(ManifestAnnotation.NODE_BOOTSTRAP.key(), "true")))
                    .labels(
                        Map.of(
                            "app.kubernetes.io/name",
                            ClusterRefs.SECRETS_NAMESPACE,
                            "app.kubernetes.io/managed-by",
                            "rke2lab"))
                    .build())
            .build());
  }

  private ApiObject createClusterRole(final Construct scope, final String replicatorVersion) {
    ApiObject clusterRole =
        new ApiObject(
            scope,
            "clusterrole-kubernetes-replicator",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRole")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kubernetes-replicator")
                        .annotations(packageProfile.packageAnnotationsWithoutUpstream())
                        .labels(commonLabels(replicatorVersion))
                        .build())
                .build());

    clusterRole.addJsonPatch(
        JsonPatch.add(
            "/rules",
            new Object[] {
              Map.of(
                  "apiGroups",
                  new Object[] {""},
                  "resources",
                  new Object[] {"namespaces"},
                  "verbs",
                  new Object[] {"get", "watch", "list"}),
              Map.of(
                  "apiGroups",
                  new Object[] {""},
                  "resources",
                  new Object[] {"secrets", "configmaps", "serviceaccounts"},
                  "verbs",
                  new Object[] {"get", "watch", "list", "create", "update", "patch", "delete"}),
              Map.of(
                  "apiGroups",
                  new Object[] {"rbac.authorization.k8s.io"},
                  "resources",
                  new Object[] {"roles", "rolebindings"},
                  "verbs",
                  new Object[] {"get", "watch", "list", "create", "update", "patch", "delete"})
            }));

    return clusterRole;
  }

  private ApiObject createServiceAccount(final Construct scope, final String replicatorVersion) {
    ApiObject serviceAccount =
        new ApiObject(
            scope,
            "serviceaccount-kubernetes-replicator",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ServiceAccount")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kubernetes-replicator")
                        .namespace("kube-system")
                        .annotations(packageProfile.packageAnnotationsWithoutUpstream())
                        .labels(commonLabels(replicatorVersion))
                        .build())
                .build());

    serviceAccount.addJsonPatch(JsonPatch.add("/automountServiceAccountToken", true));
    return serviceAccount;
  }

  private void createClusterRoleBinding(
      final Construct scope,
      final ApiObject clusterRole,
      final ApiObject serviceAccount,
      final String replicatorVersion) {
    ApiObject clusterRoleBinding =
        new ApiObject(
            scope,
            "clusterrolebinding-kubernetes-replicator",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRoleBinding")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kubernetes-replicator")
                        .annotations(packageProfile.packageAnnotationsWithoutUpstream())
                        .labels(commonLabels(replicatorVersion))
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
                "kubernetes-replicator")),
        JsonPatch.add(
            "/subjects",
            new Object[] {
              Map.of(
                  "kind",
                  "ServiceAccount",
                  "name",
                  "kubernetes-replicator",
                  "namespace",
                  "kube-system")
            }));
  }

  private void createDeployment(
      final Construct scope, final ApiObject serviceAccount, final String replicatorVersion) {
    ApiObject deployment =
        new ApiObject(
            scope,
            "deployment-kubernetes-replicator",
            ApiObjectProps.builder()
                .apiVersion("apps/v1")
                .kind("Deployment")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("kubernetes-replicator")
                        .namespace("kube-system")
                        .annotations(packageProfile.packageAnnotationsWithoutUpstream())
                        .labels(commonLabels(replicatorVersion))
                        .build())
                .build());

    deployment.addDependency(serviceAccount);

    deployment.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "replicas",
                1,
                "revisionHistoryLimit",
                10,
                "selector",
                Map.of(
                    "matchLabels",
                    Map.of(
                        "app.kubernetes.io/instance",
                        "kubernetes-replicator",
                        "app.kubernetes.io/name",
                        "kubernetes-replicator")),
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        packageProfile.packageAnnotationsWithoutUpstream(),
                        "labels",
                        Map.of(
                            "app.kubernetes.io/instance",
                            "kubernetes-replicator",
                            "app.kubernetes.io/name",
                            "kubernetes-replicator")),
                    "spec",
                    Map.of(
                        "automountServiceAccountToken",
                        true,
                        "serviceAccountName",
                        "kubernetes-replicator",
                        "containers",
                        new Object[] {
                          Map.of(
                              "name",
                              "kubernetes-replicator",
                              "image",
                              "quay.io/mittwald/kubernetes-replicator:" + replicatorVersion,
                              "imagePullPolicy",
                              "Always",
                              "args",
                              new Object[] {
                                "-replicate-secrets=true",
                                "-replicate-configmaps=true",
                                "-replicate-roles=true",
                                "-replicate-role-bindings=true",
                                "-replicate-service-accounts=true"
                              },
                              "ports",
                              new Object[] {
                                Map.of("containerPort", 9102, "name", "health", "protocol", "TCP")
                              },
                              "livenessProbe",
                              Map.of(
                                  "httpGet",
                                  Map.of("path", "/healthz", "port", "health"),
                                  "initialDelaySeconds",
                                  60,
                                  "periodSeconds",
                                  10,
                                  "timeoutSeconds",
                                  1,
                                  "successThreshold",
                                  1,
                                  "failureThreshold",
                                  3),
                              "readinessProbe",
                              Map.of(
                                  "httpGet",
                                  Map.of("path", "/readyz", "port", "health"),
                                  "initialDelaySeconds",
                                  60,
                                  "periodSeconds",
                                  10,
                                  "timeoutSeconds",
                                  1,
                                  "successThreshold",
                                  1,
                                  "failureThreshold",
                                  3),
                              "resources",
                              Map.of(),
                              "securityContext",
                              Map.of())
                        },
                        "securityContext",
                        Map.of())))));
  }

  private Map<String, String> commonLabels(final String replicatorVersion) {
    return Map.of(
        "app.kubernetes.io/instance",
        "kubernetes-replicator",
        "app.kubernetes.io/managed-by",
        "Helm",
        "app.kubernetes.io/name",
        "kubernetes-replicator",
        "app.kubernetes.io/version",
        replicatorVersion,
        "helm.sh/chart",
        "kubernetes-replicator-" + replicatorVersion.replaceFirst("^v", ""));
  }
}
