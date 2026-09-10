package io.seedmatic.rke2lab.manifests.units.runtime.flox;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRefs;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRuntimeNamespaceManifestsUnit;
import io.seedmatic.rke2lab.manifests.upstream.UpstreamYamlInclusion;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Deploys the flox-controller node-agent + its {@code FloxEnv} CRD (the companion that provisions
 * what the NRI plugin injects). Sibling of {@link FloxEnvManifestsUnit} in the runtime domain.
 *
 * <p>Layering: the DaemonSet + RBAC are on the {@code operators} layer (the controller is healthy
 * before workload {@code FloxEnv} CRs); the {@code CustomResourceDefinition} is forced to the
 * {@code crds} layer by kind. Its namespace ({@code rke2lab-system}) is created in the {@code
 * foundation} layer (ClusterRuntimeNamespaceManifestsUnit) so it exists by the time this
 * operators-layer SA/DaemonSet apply.
 *
 * <p>The CRD is single-sourced from the flox-controller flake (its controller-gen output, staged
 * onto the classpath at {@code /crds/} by seedMasterJar / {@code nix run
 * .#stage-flox-controller-crd}) — never re-modelled or vendored.
 */
public final class FloxControllerManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.RUNTIME + "/flox-controller";

  /** Exploded package dir (relative to the runtime domain); diverges from the id segment. */
  public static final String OUTPUT_DIR = "flox-controller";

  private static final String NAME = "flox-controller";

  /** The staged CRD classpath resources (single source: the flox-controller flake). */
  private static final String FLOXENV_CRD_RESOURCE = "/crds/flox.seedmatic.io_floxenvs.yaml";

  private static final String FLOXCATALOG_CRD_RESOURCE =
      "/crds/flox.seedmatic.io_floxcatalogs.yaml";

  /** The replicated FloxHub token Secret (name + key match .secrets kubernetes.secrets.flox). */
  private static final String FLOXHUB_TOKEN_SECRET = "floxhub-token";

  private static final String FLOXHUB_TOKEN_KEY = "token";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(
          ManifestDomainCatalog.RUNTIME, OUTPUT_DIR, false, ManifestLayer.OPERATORS);

  public FloxControllerManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of(ClusterRuntimeNamespaceManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public String outputDir() {
    return OUTPUT_DIR;
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    // FloxEnv + FloxCatalog CRDs — CustomResourceDefinitions, auto-routed to the crds layer by
    // kind.
    new UpstreamYamlInclusion(scope, FLOXENV_CRD_RESOURCE, packageProfile, context.yaml());
    new UpstreamYamlInclusion(scope, FLOXCATALOG_CRD_RESOURCE, packageProfile, context.yaml());

    final String namespace = ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name();
    final ApiObject serviceAccount = createServiceAccount(scope, context.resolver(), namespace);
    final ApiObject clusterRole = createClusterRole(scope);
    final ApiObject clusterRoleBinding = createClusterRoleBinding(scope, namespace);
    clusterRoleBinding.addDependency(serviceAccount);
    clusterRoleBinding.addDependency(clusterRole);
    createDaemonSet(scope, context.resolver(), namespace, serviceAccount, clusterRoleBinding);

    // The FloxHub token target stub: mittwald fills it from the replicator source (pull pattern, as
    // Tailscale/Tekton do), and the DaemonSet's FLOX_FLOXHUB_TOKEN env + the webhook injection
    // consume it. Without it the source secret (authorised to this namespace) is never pulled and
    // the controller wedges on a missing secret.
    createTokenReplicaStub(scope, context.resolver(), namespace);
  }

  private void createTokenReplicaStub(
      final Construct scope, final Cdk8sApiObjectResolver resolver, final String namespace) {
    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-floxhub-token",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(FLOXHUB_TOKEN_SECRET)
                        .namespace(namespace)
                        .labels(Map.of("app.kubernetes.io/replicated", "true"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|" + namespace + "|" + FLOXHUB_TOKEN_SECRET,
                                Map.of(
                                    "replicator.v1.mittwald.de/replicate-from",
                                    ClusterRefs.SECRETS_NAMESPACE + "/" + FLOXHUB_TOKEN_SECRET)))
                        .build())
                .build());
    secret.addDependency(resolver.require(ClusterRefs.RUNTIME_SYSTEM_NAMESPACE));
    // Empty stub — no placeholder sentinel: mittwald's replicate-from fills the data from the
    // source. Consumers reference the token as an OPTIONAL secretKeyRef, so the absent key before
    // replication is a clean "unset", not a wedge.
    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
  }

  private ApiObject createServiceAccount(
      final Construct scope, final Cdk8sApiObjectResolver resolver, final String namespace) {
    final ApiObject serviceAccount =
        new ApiObject(
            scope,
            "serviceaccount-flox-controller",
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
    // The controller's kubebuilder RBAC markers: watch FloxEnvs + FloxCatalogs cluster-wide and
    // patch their status; read the Flux GitRepository a FloxCatalog resolves its catalog artifact
    // from; ensure the nix-build PVC the webhook names; and watch + update pods for the
    // scheduling-gate reconciler.
    final ApiObject clusterRole =
        new ApiObject(
            scope,
            "clusterrole-flox-controller",
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
                  "apiGroups", new Object[] {"flox.seedmatic.io"},
                  "resources", new Object[] {"floxenvs", "floxcatalogs"},
                  // create: the controller self-provisions its embedded base carrier (EnsureBase).
                  "verbs",
                      new Object[] {"get", "list", "watch", "create", "update", "patch", "delete"}),
              Map.of(
                  "apiGroups", new Object[] {"flox.seedmatic.io"},
                  "resources", new Object[] {"floxenvs/status", "floxcatalogs/status"},
                  "verbs", new Object[] {"get", "update", "patch"}),
              // FloxCatalog resolves its nix-flake catalog from a Flux GitRepository's reconciled
              // artifact — a generic capability (read the Flux source), not an rke2lab coupling.
              Map.of(
                  "apiGroups", new Object[] {"source.toolkit.fluxcd.io"},
                  "resources", new Object[] {"gitrepositories"},
                  "verbs", new Object[] {"get", "list", "watch"}),
              // The pod-mutating webhook ensures (create-if-absent) the per-step persistent
              // nix-store
              // PVC named by a nix-build pod's flox.seedmatic.io/nix-build.<c> annotation.
              Map.of(
                  "apiGroups", new Object[] {""},
                  "resources", new Object[] {"persistentvolumeclaims"},
                  "verbs", new Object[] {"get", "create"}),
              // The scheduling-gate reconciler (PodGateReconciler) watches gated pods and, once
              // every referenced FloxEnv is realised at the current generation, narrows their
              // nodeAffinity + removes the env-ready gate — an update to the pod spec.
              Map.of(
                  "apiGroups", new Object[] {""},
                  "resources", new Object[] {"pods"},
                  "verbs", new Object[] {"get", "list", "watch", "update"})
            }));
    return clusterRole;
  }

  private ApiObject createClusterRoleBinding(final Construct scope, final String namespace) {
    final ApiObject binding =
        new ApiObject(
            scope,
            "clusterrolebinding-flox-controller",
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

  private void createDaemonSet(
      final Construct scope,
      final Cdk8sApiObjectResolver resolver,
      final String namespace,
      final ApiObject serviceAccount,
      final ApiObject clusterRoleBinding) {
    final String image = ManifestSynthesisContext.current().floxDebugPolicy().floxControllerImage();
    final Map<String, String> podLabels =
        Map.of("app.kubernetes.io/name", NAME, "app.kubernetes.io/component", "node-agent");

    final java.util.List<Object> args =
        new java.util.ArrayList<>(
            List.of(
                "--gcroot-base=/nix/var/nix/gcroots/flox-runtime/env",
                "--env-root=/var/lib/flox-controller/envs"));
    final java.util.List<Object> volumeMounts =
        new java.util.ArrayList<>(
            List.of(
                Map.of("name", "host-nix", "mountPath", "/nix"),
                Map.of(
                    "name", "containerd-sock",
                    "mountPath", "/run/k3s/containerd/containerd.sock"),
                Map.of("name", "env-root", "mountPath", "/var/lib/flox-controller/envs")));
    final java.util.List<Object> volumes =
        new java.util.ArrayList<>(
            List.of(
                Map.of("name", "host-nix", "hostPath", Map.of("path", "/nix", "type", "Directory")),
                Map.of(
                    "name",
                    "containerd-sock",
                    "hostPath",
                    Map.of("path", "/run/k3s/containerd/containerd.sock", "type", "Socket")),
                Map.of(
                    "name",
                    "env-root",
                    "hostPath",
                    Map.of("path", "/var/lib/flox-controller/envs", "type", "DirectoryOrCreate"))));
    final java.util.List<Object> env =
        new java.util.ArrayList<>(
            List.of(
                Map.of(
                    "name",
                    "NODE_NAME",
                    "valueFrom",
                    Map.of("fieldRef", Map.of("fieldPath", "spec.nodeName"))),
                // The controller ensures its embedded base carrier in its OWN namespace (exists).
                Map.of(
                    "name",
                    "POD_NAMESPACE",
                    "valueFrom",
                    Map.of("fieldRef", Map.of("fieldPath", "metadata.namespace"))),
                // flox lives on the NixOS SYSTEM profile (/run/current-system/sw/bin/flox), NOT the
                // default nix profile — so that MUST lead the PATH nsenter resolves against, else
                // `nsenter … flox` fails 127.
                Map.of(
                    "name", "PATH",
                    "value",
                        "/run/current-system/sw/bin:/nix/var/nix/profiles/default/bin:/usr/bin:/bin"),
                Map.of(
                    "name", "CONTAINERD_ADDRESS",
                    "value", "/run/k3s/containerd/containerd.sock")));
    // The pod-mutating webhook is served from every DaemonSet pod (behind
    // FloxWebhookManifestsUnit's
    // Service). Always on: the serving cert is minted in-cluster by cert-manager (no reveal-gate),
    // so enablement no longer depends on a grow-sealed secret that a secret-blind render would
    // strip.
    args.add("--enable-webhook");
    // Tell the webhook injector which (replicated) Secret carries the FloxHub token.
    args.add("--token-secret-name=" + FLOXHUB_TOKEN_SECRET);
    args.add("--token-secret-key=" + FLOXHUB_TOKEN_KEY);
    // The controller's OWN flox subprocesses (realise via nsenter) authenticate with the same
    // token. optional: the replicated Secret's key may not have landed yet (mittwald fills the
    // stub async) — the controller must NOT wedge on it, so an absent key just leaves flox
    // unauthenticated (warnings only) until a restart picks up the replicated value.
    env.add(
        Map.of(
            "name",
            "FLOX_FLOXHUB_TOKEN",
            "valueFrom",
            Map.of(
                "secretKeyRef",
                Map.of(
                    "name", FLOXHUB_TOKEN_SECRET,
                    "key", FLOXHUB_TOKEN_KEY,
                    "optional", true))));
    volumeMounts.add(
        Map.of(
            "name", "webhook-certs",
            "mountPath", "/tmp/k8s-webhook-server/serving-certs",
            "readOnly", true));
    volumes.add(
        Map.of(
            "name",
            "webhook-certs",
            "secret",
            Map.of("secretName", FloxWebhookManifestsUnit.TLS_SECRET_NAME)));

    final ApiObject daemonSet =
        new ApiObject(
            scope,
            "daemonset-flox-controller",
            ApiObjectProps.builder()
                .apiVersion("apps/v1")
                .kind("DaemonSet")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(NAME)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "apps|DaemonSet|" + namespace + "|" + NAME))
                        .labels(podLabels)
                        .build())
                .build());
    daemonSet.addDependency(resolver.require(ClusterRefs.RUNTIME_SYSTEM_NAMESPACE));
    daemonSet.addDependency(serviceAccount);
    daemonSet.addDependency(clusterRoleBinding);

    daemonSet.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "selector",
                Map.of("matchLabels", Map.of("app.kubernetes.io/name", NAME)),
                "updateStrategy",
                Map.of("type", "RollingUpdate", "rollingUpdate", Map.of("maxUnavailable", 1)),
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        packageProfile.templateAnnotations(Map.of()),
                        "labels",
                        podLabels),
                    "spec",
                    Map.ofEntries(
                        Map.entry("serviceAccountName", NAME),
                        // Share the host PID namespace so the controller can nsenter -t 1 into
                        // the node's namespaces (reach its flox/nix/ctr) and detect it's
                        // containerized (its mount ns differs from the host init's).
                        Map.entry("hostPID", true),
                        Map.entry(
                            "nodeSelector",
                            Map.of(ManifestAnnotation.NODE_FLOX_RUNTIME_LABEL.key(), "true")),
                        Map.entry("tolerations", new Object[] {Map.of("operator", "Exists")}),
                        Map.entry(
                            "containers",
                            new Object[] {
                              Map.ofEntries(
                                  Map.entry("name", "controller"),
                                  Map.entry("image", image),
                                  Map.entry("imagePullPolicy", "IfNotPresent"),
                                  // Defaults from cmd/flox-controller: --gcroot-base matches the
                                  // path the NRI plugin reads; --env-root is the .flox source root.
                                  Map.entry("args", args.toArray()),
                                  Map.entry(
                                      "ports",
                                      new Object[] {
                                        Map.of(
                                            "name", "webhook",
                                            "containerPort", 9443,
                                            "protocol", "TCP")
                                      }),
                                  Map.entry("env", env.toArray()),
                                  Map.entry(
                                      "securityContext",
                                      Map.of("privileged", true, "runAsUser", 0, "runAsGroup", 0)),
                                  Map.entry("volumeMounts", volumeMounts.toArray()),
                                  // memory limit is generous: realising a FloxEnv runs nix on
                                  // the node (via nsenter) — flake eval of a large lock + a
                                  // from-scratch build of the workload closure — in THIS
                                  // container's cgroup; 256Mi OOM-kills it (exit 137).
                                  Map.entry(
                                      "resources",
                                      Map.of(
                                          "requests", Map.of("cpu", "20m", "memory", "256Mi"),
                                          "limits", Map.of("cpu", "1", "memory", "2Gi"))))
                            }),
                        Map.entry("restartPolicy", "Always"),
                        Map.entry("volumes", volumes.toArray()))))));
  }
}
