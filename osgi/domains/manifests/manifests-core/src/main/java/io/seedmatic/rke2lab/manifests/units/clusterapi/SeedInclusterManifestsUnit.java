package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.FloxAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRefs;
import io.seedmatic.rke2lab.manifests.units.platform.GithubTokenManagerManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.runtime.flox.FloxEnvFolder;
import io.seedmatic.rke2lab.manifests.upstream.UpstreamYamlInclusion;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Deploys the in-cluster {@code seed-incluster} + its four {@code cluster.seedmatic.io} CRDs (the
 * 2×2 intent/mirror set). The controller reconciles the {@code ClusterIntention} + {@code
 * PoolIntention} intent {@link ClusterApiManagementManifestsUnit} / {@link
 * ClusterApiWorkloadManifestsUnit} render: it creates the CAPI CR-set and the OWNED control-plane
 * {@code Machine} + concrete {@code LXCMachine}(providerID) — the piece GitOps cannot do (the
 * ownerRef UID is assigned in-cluster) — so CAPRKE2/CAPN adopt the RUNNING Pulumi-bootstrapped
 * control plane instead of provisioning a fresh one, closing the cold-start leak.
 *
 * <p>Layering (mirrors {@code FloxControllerManifestsUnit}): the Deployment + RBAC are on the
 * {@code operators} layer (the controller is healthy before the {@code ClusterAdoption} CR
 * reconciles); the {@code CustomResourceDefinition} is auto-routed to the {@code crds} layer by
 * kind. Its namespace ({@code rke2lab-system}) is created in the {@code foundation} layer ({@code
 * ClusterRuntimeNamespaceManifestsUnit}). dependsOn the CAPI operator unit so the CAPI/CAPN/CAPRKE2
 * CRDs the controller creates CRs against exist.
 *
 * <p>The CRDs AND the ClusterRole are single-sourced from the seed-incluster flake (its
 * controller-gen output: {@code crd} staged at {@code /crds/} by {@code nix run
 * .#stage-seed-incluster-crd}, and the {@code +kubebuilder:rbac} {@code role.yaml} staged at {@code
 * /rbac/} by {@code nix run .#stage-seed-incluster-rbac}) — never re-modelled or vendored (the RBAC
 * rules used to be hand-listed here and drifted). Only the ServiceAccount, ClusterRoleBinding and
 * Deployment stay authored here — the deployment-topology adaptation (namespace, flox carrier, env)
 * the consumer owns. The controller BINARY is delivered on the FLOX RUNTIME, not a baked image: the
 * Deployment runs the minimal flox carrier ({@code FloxDebugPolicy.prodImage()}) and the {@code
 * cluster-api/seed-incluster} flox env ({@link
 * io.seedmatic.rke2lab.manifests.units.runtime.flox.FloxEnvManifestsUnit}, sourced from the
 * flox-catalogue as {@code floxcatalog:catalogue#seed-incluster}) puts the binary on PATH via the
 * flox NRI plugin — the {@code environment.<c>} annotation on the pod template opts in.
 */
public final class SeedInclusterManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.CLUSTER_API + "/seed-incluster";

  /** Exploded package dir (relative to the cluster-api domain); diverges from the id segment. */
  public static final String OUTPUT_DIR = "seed-incluster";

  private static final String NAME = "seed-incluster";

  /**
   * The staged CRD classpath resources — the 2×2 set (single source: the seed-incluster flake,
   * controller-gen output staged at {@code /crds/} by {@code nix run .#stage-seed-incluster-crd}).
   */
  private static final List<String> CRD_RESOURCES =
      List.of(
          "/crds/cluster.seedmatic.io_clusterintentions.yaml",
          "/crds/cluster.seedmatic.io_poolintentions.yaml",
          "/crds/cluster.seedmatic.io_clusteradoptions.yaml",
          "/crds/cluster.seedmatic.io_pooladoptions.yaml");

  /**
   * The staged ClusterRole — single-sourced from the controller's {@code +kubebuilder:rbac} markers
   * ({@code make rbac} → {@code config/rbac/role.yaml}, staged at {@code /rbac/} by {@code nix run
   * .#stage-seed-incluster-rbac}, into a per-controller subdir so controllers' generic {@code
   * role.yaml} never collide). Its {@code metadata.name} is {@code seed-incluster} (controller-gen
   * {@code roleName}), matching the binding's {@code roleRef} below.
   */
  private static final String RBAC_ROLE_RESOURCE = "/rbac/seed-incluster/role.yaml";

  // Deployment + RBAC ride the operators layer; the CRD auto-routes to crds by kind.
  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(
          ManifestDomainCatalog.CLUSTER_API, OUTPUT_DIR, false, ManifestLayer.OPERATORS);

  public SeedInclusterManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of(ClusterApiOperatorManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public String outputDir() {
    return OUTPUT_DIR;
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    // The 2×2 cluster.seedmatic.io CRDs — CustomResourceDefinitions, auto-routed to the crds layer
    // by kind.
    for (final String crd : CRD_RESOURCES) {
      new UpstreamYamlInclusion(scope, crd, packageProfile, context.yaml());
    }

    final String namespace = ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name();
    final ApiObject serviceAccount = createServiceAccount(scope, context.resolver(), namespace);
    // Single-sourced from the controller's +kubebuilder:rbac markers: INCLUDE the staged
    // ClusterRole
    // (name "seed-incluster", matching the binding's roleRef) instead of hand-listing rules that
    // drift.
    final ApiObject clusterRole =
        new UpstreamYamlInclusion(scope, RBAC_ROLE_RESOURCE, packageProfile, context.yaml())
            .apiObjects()
            .get(0);
    final ApiObject binding = createClusterRoleBinding(scope, namespace);
    binding.addDependency(serviceAccount);
    binding.addDependency(clusterRole);
    createDeployment(scope, context.resolver(), namespace, serviceAccount, binding);
    // The reflector's WRITE token: gtm mints github-token-write (contents:write) into
    // rke2lab-secrets; this empty stub pulls it here (mittwald replicate-from) so the reflector
    // reads rke2lab-system/github-token-write to push PoolReflection docs. The read-only
    // github-token would 403 on push.
    createWriteTokenReplicaStub(scope, context.resolver(), namespace);
  }

  private void createWriteTokenReplicaStub(
      final Construct scope, final Cdk8sApiObjectResolver resolver, final String namespace) {
    final String secretName = GithubTokenManagerManifestsUnit.WRITE_TOKEN_SECRET_NAME;
    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-github-token-write",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(secretName)
                        .namespace(namespace)
                        .labels(Map.of("app.kubernetes.io/replicated", "true"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|" + namespace + "|" + secretName,
                                Map.of(
                                    "replicator.v1.mittwald.de/replicate-from",
                                    ClusterRefs.SECRETS_NAMESPACE + "/" + secretName)))
                        .build())
                .build());
    secret.addDependency(resolver.require(ClusterRefs.RUNTIME_SYSTEM_NAMESPACE));
    // Empty stub — mittwald's replicate-from fills the `token` key from the source.
    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
  }

  private ApiObject createServiceAccount(
      final Construct scope, final Cdk8sApiObjectResolver resolver, final String namespace) {
    final ApiObject serviceAccount =
        new ApiObject(
            scope,
            "serviceaccount-seed-incluster",
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

  private ApiObject createClusterRoleBinding(final Construct scope, final String namespace) {
    final ApiObject binding =
        new ApiObject(
            scope,
            "clusterrolebinding-seed-incluster",
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
            "deployment-seed-incluster",
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

    // The controller runs on the FLOX RUNTIME, not a baked node-base image: the container is the
    // minimal flox carrier (prodImage()); the controller BINARY comes from the
    // cluster-api/seed-incluster flox env (FloxEnvManifestsUnit), put on PATH by the flox
    // NRI plugin, so `command: [seed-incluster]` resolves. The environment.<c> annotation
    // opts the container in (the flox-controller webhook gates scheduling on the env being
    // realised);
    // HOME/UID/GID set the run context (root, mirroring kdns). Prod env only — no debug flavor.
    final String floxEnvironment = FloxEnvFolder.CLUSTER_API.value() + "/" + NAME;

    final Map<String, Object> container = new LinkedHashMap<>();
    container.put("name", "controller");
    container.put("image", ManifestSynthesisContext.current().floxDebugPolicy().prodImage());
    container.put("imagePullPolicy", "IfNotPresent");
    container.put("command", List.of(NAME));
    container.put(
        "args", List.of("--health-probe-bind-address=:8081", "--metrics-bind-address=:8080"));
    // SELF_CLUSTER_NAME = the cluster this controller runs IN (the render subject). It guards the
    // controller's OWN ClusterAdoption from deletion (no management-plane suicide, see the
    // reconciler).
    final String selfCluster =
        ManifestSynthesisContext.current()
            .bootstrapIdentity()
            .clusterNameOrDefault(DefaultNodeEnvContext.DEFAULT_CLUSTER_NAME);
    // REFLECTOR_WRITE=true enables the cluster→git reflector loop: it commits observed
    // PoolReflection
    // documents onto the managing branch and the adopt-vs-greenfield decision reads them back from
    // git.
    // Safe to enable (self-skip + empty-roster guards; the render's escape carve-out preserves the
    // documents). The repo URL is DERIVED from the Flux GitRepository, so no URL env is injected.
    container.put(
        "env",
        List.of(
            Map.of("name", "HOME", "value", "/root"),
            Map.of("name", "SELF_CLUSTER_NAME", "value", selfCluster),
            Map.of("name", "REFLECTOR_WRITE", "value", "true")));
    container.put(
        "livenessProbe",
        Map.of(
            "httpGet", Map.of("path", "/healthz", "port", 8081),
            "initialDelaySeconds", 15,
            "periodSeconds", 20));
    container.put(
        "readinessProbe",
        Map.of(
            "httpGet", Map.of("path", "/readyz", "port", 8081),
            "initialDelaySeconds", 5,
            "periodSeconds", 10));
    container.put(
        "resources",
        Map.of(
            // 128Mi OOM-killed the manager at startup (exit 137, crash-looping): a controller-
            // runtime manager's informer cache sync spikes past it, on top of the flox-carrier
            // runtime overhead the pod carries. 256Mi absorbs the startup spike.
            "requests", Map.of("cpu", "10m", "memory", "128Mi"),
            "limits", Map.of("memory", "256Mi")));
    container.put(
        "volumeMounts",
        List.of(
            Map.of("mountPath", "/.config/flox", "name", "flox-config"),
            Map.of("mountPath", "/.cache/flox", "name", "flox-cache")));

    final Map<String, String> floxAnnotations = new LinkedHashMap<>();
    floxAnnotations.put(FloxAnnotation.ENVIRONMENT.forContainer("controller"), floxEnvironment);
    floxAnnotations.put(FloxAnnotation.HOME.forContainer("controller"), "/root");
    floxAnnotations.put(FloxAnnotation.UID.forContainer("controller"), "0");
    floxAnnotations.put(FloxAnnotation.GID.forContainer("controller"), "0");

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
                    Map.of("labels", labels, "annotations", floxAnnotations),
                    "spec",
                    Map.of(
                        "serviceAccountName",
                        NAME,
                        "containers",
                        new Object[] {container},
                        "volumes",
                        new Object[] {
                          Map.of("name", "flox-config", "emptyDir", Map.of()),
                          Map.of("name", "flox-cache", "emptyDir", Map.of())
                        })))));
  }
}
