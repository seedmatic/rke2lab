// @codebase
package io.seedmatic.rke2lab.manifests.units.networking;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.FloxAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class EnvoyGatewayManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.NETWORKING + "/envoy-gateway";

  // The installer resources (Job + its namespace/SA/CRB/ConfigMap) belong to the operators layer —
  // they register the Gateway API CRDs at runtime; the GatewayClass overrides itself back to
  // workloads (below), so it dry-runs only after this installer has run.
  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("networking", "envoy-gateway", false, ManifestLayer.OPERATORS);

  public EnvoyGatewayManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of(CiliumAdvancedManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String envoyGatewayVersion =
        ManifestSynthesisContext.current().componentVersions().of(Component.ENVOY_GATEWAY);

    ApiObject namespace = createNamespace(scope);
    ApiObject serviceAccount = createServiceAccount(scope, namespace);
    ApiObject clusterRoleBinding = createClusterRoleBinding(scope, serviceAccount);
    ApiObject configMap = createInstallerScriptConfigMap(scope, namespace);
    createInstallerJob(
        scope, namespace, serviceAccount, configMap, clusterRoleBinding, envoyGatewayVersion);
    createGatewayClass(scope);
  }

  private ApiObject createClusterRoleBinding(
      final Construct scope, final ApiObject serviceAccount) {
    ApiObject clusterRoleBinding =
        new ApiObject(
            scope,
            "clusterrolebinding-envoy-gateway-installer",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRoleBinding")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("envoy-gateway-installer")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|ClusterRoleBinding|default|envoy-gateway-installer"))
                        .build())
                .build());

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
                "cluster-admin")),
        JsonPatch.add(
            "/subjects",
            List.of(
                Map.of(
                    "kind",
                    "ServiceAccount",
                    "name",
                    "envoy-gateway-installer",
                    "namespace",
                    "envoy-gateway-system"))));
    return clusterRoleBinding;
  }

  private void createGatewayClass(final Construct scope) {
    ApiObject gatewayClass =
        new ApiObject(
            scope,
            "gatewayclass-envoy",
            ApiObjectProps.builder()
                .apiVersion("gateway.networking.k8s.io/v1")
                .kind("GatewayClass")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("envoy")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "gateway.networking.k8s.io|GatewayClass|default|envoy",
                                Map.of(
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.WORKLOADS.value())))
                        .build())
                .build());

    gatewayClass.addJsonPatch(
        JsonPatch.add(
            "/spec", Map.of("controllerName", "gateway.envoyproxy.io/gatewayclass-controller")));
  }

  private ApiObject createNamespace(final Construct scope) {
    return new ApiObject(
        scope,
        "namespace-envoy-gateway-system",
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name("envoy-gateway-system")
                    .annotations(
                        packageProfile.packageAnnotations(
                            "|Namespace|default|${envoy-gateway-namespace}"))
                    .build())
            .build());
  }

  private ApiObject createInstallerScriptConfigMap(
      final Construct scope, final ApiObject namespace) {
    ApiObject configMap =
        new ApiObject(
            scope,
            "configmap-envoy-gateway-installer-script",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("envoy-gateway-installer-script")
                        .namespace("envoy-gateway-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ConfigMap|${envoy-gateway-namespace}|envoy-gateway-installer-script"))
                        .build())
                .build());
    configMap.addDependency(namespace);

    configMap.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "install.sh",
                """
                #!/usr/bin/env -S bash -exuo pipefail

                : "[i] Installing Envoy Gateway ${ENVOY_GATEWAY_VERSION}..."
                kubectl apply \\
                  --server-side \\
                  --force-conflicts \\
                  -f "https://github.com/envoyproxy/gateway/releases/download/${ENVOY_GATEWAY_VERSION}/install.yaml"

                : "[i] Waiting for Envoy Gateway deployment..."
                kubectl wait --timeout=300s \\
                  --namespace "${ENVOY_GATEWAY_NAMESPACE}" \\
                  --for=condition=available \\
                  deployment/envoy-gateway
                """)));
    return configMap;
  }

  private ApiObject createServiceAccount(final Construct scope, final ApiObject namespace) {
    ApiObject serviceAccount =
        new ApiObject(
            scope,
            "serviceaccount-envoy-gateway-installer",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ServiceAccount")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("envoy-gateway-installer")
                        .namespace("envoy-gateway-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ServiceAccount|${envoy-gateway-namespace}|envoy-gateway-installer"))
                        .build())
                .build());
    serviceAccount.addDependency(namespace);
    return serviceAccount;
  }

  private void createInstallerJob(
      final Construct scope,
      final ApiObject namespace,
      final ApiObject serviceAccount,
      final ApiObject configMap,
      final ApiObject clusterRoleBinding,
      final String envoyGatewayVersion) {
    // A Job's spec.template is IMMUTABLE, so bumping ENVOY_GATEWAY_VERSION cannot patch the
    // existing installer Job — Flux apply fails "field is immutable" and stalls the whole
    // operators layer (and workloads, which depends on it). Name the Job per version so a
    // bump renders a NEW Job (Flux prunes the old one), making the installer an idempotent
    // per-version run instead of an in-place mutation.
    final String jobName =
        "envoy-gateway-installer-"
            + envoyGatewayVersion.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    ApiObject job =
        new ApiObject(
            scope,
            "job-envoy-gateway-installer",
            ApiObjectProps.builder()
                .apiVersion("batch/v1")
                .kind("Job")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(jobName)
                        .namespace("envoy-gateway-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "batch|Job|${envoy-gateway-namespace}|" + jobName))
                        .build())
                .build());

    job.addDependency(namespace);
    job.addDependency(serviceAccount);
    job.addDependency(configMap);
    job.addDependency(clusterRoleBinding);

    job.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "backoffLimit",
                3,
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        packageProfile.templateAnnotations(
                            Map.of(
                                FloxAnnotation.ENVIRONMENT.forContainer("installer"),
                                "kube/base"))),
                    "spec",
                    Map.of(
                        "containers",
                        List.of(
                            Map.of(
                                "name",
                                "installer",
                                // The flox-carrier runtime (prodImage) like every rke2lab workload
                                // —
                                // kubectl + yq arrive via the injected kube/base env (annotation
                                // above), not a stock alpine/k8s image.
                                "image",
                                ManifestSynthesisContext.current().floxDebugPolicy().prodImage(),
                                "command",
                                List.of("bash", "/scripts/install.sh"),
                                "env",
                                List.of(
                                    Map.of(
                                        "name",
                                        "ENVOY_GATEWAY_VERSION",
                                        "value",
                                        envoyGatewayVersion),
                                    Map.of(
                                        "name",
                                        "ENVOY_GATEWAY_NAMESPACE",
                                        "value",
                                        "envoy-gateway-system")),
                                "resources",
                                Map.of(
                                    "limits",
                                    Map.of(
                                        "cpu",
                                        "200m",
                                        "ephemeral-storage",
                                        "256Mi",
                                        "memory",
                                        "256Mi"),
                                    "requests",
                                    Map.of(
                                        "cpu",
                                        "50m",
                                        "ephemeral-storage",
                                        "128Mi",
                                        "memory",
                                        "64Mi")),
                                "volumeMounts",
                                List.of(
                                    Map.of(
                                        "mountPath",
                                        "/scripts",
                                        "name",
                                        "installer-script",
                                        "readOnly",
                                        true)))),
                        "restartPolicy",
                        "OnFailure",
                        "serviceAccountName",
                        "envoy-gateway-installer",
                        "volumes",
                        List.of(
                            Map.of(
                                "name",
                                "installer-script",
                                "configMap",
                                Map.of(
                                    "name",
                                    "envoy-gateway-installer-script",
                                    "defaultMode",
                                    493))))),
                "ttlSecondsAfterFinished",
                300)));
  }
}
