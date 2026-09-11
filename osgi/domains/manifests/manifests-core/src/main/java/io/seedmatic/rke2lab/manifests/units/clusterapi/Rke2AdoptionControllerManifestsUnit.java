package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRefs;
import io.seedmatic.rke2lab.manifests.upstream.UpstreamYamlInclusion;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Deploys the in-cluster {@code rke2-adoption-controller} + its {@code ClusterAdoption} CRD. The
 * controller reconciles the {@code ClusterAdoption} recipe {@link
 * ClusterApiManagementManifestsUnit} renders: it creates the CAPI CR-set and the OWNED
 * control-plane {@code Machine} + concrete {@code LXCMachine}(providerID) — the piece GitOps cannot
 * do (the ownerRef UID is assigned in-cluster) — so CAPRKE2/CAPN adopt the RUNNING
 * Pulumi-bootstrapped control plane instead of provisioning a fresh one, closing the cold-start
 * leak.
 *
 * <p>Layering (mirrors {@code FloxControllerManifestsUnit}): the Deployment + RBAC are on the
 * {@code operators} layer (the controller is healthy before the {@code ClusterAdoption} CR
 * reconciles); the {@code CustomResourceDefinition} is auto-routed to the {@code crds} layer by
 * kind. Its namespace ({@code rke2lab-system}) is created in the {@code foundation} layer ({@code
 * ClusterRuntimeNamespaceManifestsUnit}). dependsOn the CAPI operator unit so the CAPI/CAPN/CAPRKE2
 * CRDs the controller creates CRs against exist.
 *
 * <p>The CRD is single-sourced from the rke2-adoption-controller flake (its controller-gen output,
 * staged onto the classpath at {@code /crds/} by seedMasterJar / {@code nix run
 * .#stage-rke2-adoption-controller-crd}) — never re-modelled or vendored. The image is baked into
 * the node-base ({@code nixos/rke2-adoption-controller.nix}) and pulled {@code IfNotPresent}: the
 * RepoTag here MUST match that image's tag (the flake VERSION).
 */
public final class Rke2AdoptionControllerManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.CLUSTER_API + "/adoption-controller";

  /** Exploded package dir (relative to the cluster-api domain); diverges from the id segment. */
  public static final String OUTPUT_DIR = "rke2-adoption-controller";

  private static final String NAME = "rke2-adoption-controller";

  /**
   * The baked image RepoTag — MUST match {@code nixos/rke2-adoption-controller.nix}'s tar (the
   * flake VERSION). Pulled IfNotPresent: rke2 air-imports the baked tar, so it is always local.
   */
  private static final String IMAGE = "io.seedmatic.rke2-adoption-controller:0.0.0-develop";

  /** The staged CRD classpath resource (single source: the rke2-adoption-controller flake). */
  private static final String CLUSTERADOPTION_CRD_RESOURCE =
      "/crds/adoption.seedmatic.io_clusteradoptions.yaml";

  // Deployment + RBAC ride the operators layer; the CRD auto-routes to crds by kind.
  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(
          ManifestDomainCatalog.CLUSTER_API, OUTPUT_DIR, false, ManifestLayer.OPERATORS);

  public Rke2AdoptionControllerManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of(ClusterApiOperatorManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public String outputDir() {
    return OUTPUT_DIR;
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    // The ClusterAdoption CRD — a CustomResourceDefinition, auto-routed to the crds layer by kind.
    new UpstreamYamlInclusion(scope, CLUSTERADOPTION_CRD_RESOURCE, packageProfile, context.yaml());

    final String namespace = ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name();
    final ApiObject serviceAccount = createServiceAccount(scope, context.resolver(), namespace);
    final ApiObject clusterRole = createClusterRole(scope);
    final ApiObject binding = createClusterRoleBinding(scope, namespace);
    binding.addDependency(serviceAccount);
    binding.addDependency(clusterRole);
    createDeployment(scope, context.resolver(), namespace, serviceAccount, binding);
  }

  private ApiObject createServiceAccount(
      final Construct scope, final Cdk8sApiObjectResolver resolver, final String namespace) {
    final ApiObject serviceAccount =
        new ApiObject(
            scope,
            "serviceaccount-rke2-adoption-controller",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ServiceAccount")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(NAME)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ServiceAccount|" + namespace + "|" + NAME))
                        .build())
                .build());
    serviceAccount.addDependency(resolver.require(ClusterRefs.RUNTIME_SYSTEM_NAMESPACE));
    return serviceAccount;
  }

  private ApiObject createClusterRole(final Construct scope) {
    // The controller's kubebuilder RBAC markers: own the ClusterAdoption CRs + status; create the
    // CAPI/CAPN/CAPRKE2 CR-set (Cluster/Machine, RKE2ControlPlane, LXC*); read/create the Secrets
    // seed-master delivers (BYO-CA + identity). Cluster-scoped: it reconciles per-namespace CRs.
    final ApiObject clusterRole =
        new ApiObject(
            scope,
            "clusterrole-rke2-adoption-controller",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRole")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(NAME)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|ClusterRole||" + NAME))
                        .build())
                .build());
    clusterRole.addJsonPatch(
        JsonPatch.add(
            "/rules",
            new Object[] {
              Map.of(
                  "apiGroups", new Object[] {"adoption.seedmatic.io"},
                  "resources", new Object[] {"clusteradoptions"},
                  "verbs",
                      new Object[] {"get", "list", "watch", "create", "update", "patch", "delete"}),
              Map.of(
                  "apiGroups", new Object[] {"adoption.seedmatic.io"},
                  "resources", new Object[] {"clusteradoptions/status"},
                  "verbs", new Object[] {"get", "update", "patch"}),
              Map.of(
                  "apiGroups", new Object[] {"cluster.x-k8s.io"},
                  "resources", new Object[] {"clusters", "machines"},
                  "verbs", new Object[] {"get", "list", "watch", "create", "update", "patch"}),
              Map.of(
                  "apiGroups", new Object[] {"controlplane.cluster.x-k8s.io"},
                  "resources", new Object[] {"rke2controlplanes"},
                  "verbs", new Object[] {"get", "list", "watch", "create", "update", "patch"}),
              Map.of(
                  "apiGroups", new Object[] {"infrastructure.cluster.x-k8s.io"},
                  "resources", new Object[] {"lxcclusters", "lxcmachines", "lxcmachinetemplates"},
                  "verbs", new Object[] {"get", "list", "watch", "create", "update", "patch"}),
              Map.of(
                  "apiGroups", new Object[] {""},
                  "resources", new Object[] {"secrets"},
                  "verbs", new Object[] {"get", "list", "watch", "create"})
            }));
    return clusterRole;
  }

  private ApiObject createClusterRoleBinding(final Construct scope, final String namespace) {
    final ApiObject binding =
        new ApiObject(
            scope,
            "clusterrolebinding-rke2-adoption-controller",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRoleBinding")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(NAME)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|ClusterRoleBinding||" + NAME))
                        .build())
                .build());
    binding.addJsonPatch(
        JsonPatch.add(
            "/roleRef",
            Map.of(
                "apiGroup", "rbac.authorization.k8s.io",
                "kind", "ClusterRole",
                "name", NAME)));
    binding.addJsonPatch(
        JsonPatch.add(
            "/subjects",
            new Object[] {Map.of("kind", "ServiceAccount", "name", NAME, "namespace", namespace)}));
    return binding;
  }

  private void createDeployment(
      final Construct scope,
      final Cdk8sApiObjectResolver resolver,
      final String namespace,
      final ApiObject serviceAccount,
      final ApiObject clusterRoleBinding) {
    final Map<String, String> labels =
        Map.of("app.kubernetes.io/name", NAME, "app.kubernetes.io/component", "controller");
    final ApiObject deployment =
        new ApiObject(
            scope,
            "deployment-rke2-adoption-controller",
            ApiObjectProps.builder()
                .apiVersion("apps/v1")
                .kind("Deployment")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(NAME)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "apps|Deployment|" + namespace + "|" + NAME))
                        .build())
                .build());
    deployment.addDependency(serviceAccount);
    deployment.addDependency(clusterRoleBinding);
    deployment.addDependency(resolver.require(ClusterRefs.RUNTIME_SYSTEM_NAMESPACE));
    deployment.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "replicas",
                1,
                "selector",
                Map.of("matchLabels", labels),
                "template",
                Map.of(
                    "metadata",
                    Map.of("labels", labels),
                    "spec",
                    Map.of(
                        "serviceAccountName",
                        NAME,
                        "containers",
                        new Object[] {
                          Map.of(
                              "name",
                              "controller",
                              "image",
                              IMAGE,
                              "imagePullPolicy",
                              "IfNotPresent",
                              "args",
                              new Object[] {
                                "--health-probe-bind-address=:8081", "--metrics-bind-address=:8080"
                              },
                              "livenessProbe",
                              Map.of(
                                  "httpGet", Map.of("path", "/healthz", "port", 8081),
                                  "initialDelaySeconds", 15,
                                  "periodSeconds", 20),
                              "readinessProbe",
                              Map.of(
                                  "httpGet", Map.of("path", "/readyz", "port", 8081),
                                  "initialDelaySeconds", 5,
                                  "periodSeconds", 10),
                              "resources",
                              Map.of(
                                  "requests", Map.of("cpu", "10m", "memory", "64Mi"),
                                  "limits", Map.of("memory", "128Mi")))
                        })))));
  }
}
