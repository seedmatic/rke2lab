// @codebase
package io.seedmatic.rke2lab.manifests.units.mesh;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.FloxAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.profiles.FloxDebugPolicy;
import io.seedmatic.rke2lab.manifests.profiles.FloxShellSidecarProfile;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class HeadplaneManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.MESH + "/headplane";

  private static final String HEADSCALE_NAMESPACE = MeshRefs.SYSTEM_NAMESPACE.name();

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("mesh", "headplane");

  public HeadplaneManifestsUnit() {
    super(
        MANIFEST_UNIT_ID,
        List.of(
            MeshSystemNamespaceManifestsUnit.MANIFEST_UNIT_ID,
            HeadscaleManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    buildResources(scope);
  }

  private void buildResources(final Construct scope) {
    final String floxImage = ManifestSynthesisContext.current().floxDebugPolicy().prodImage();
    ApiObject serviceAccount = createServiceAccount(scope);
    ApiObject envConfigMap = createEnvConfigMap(scope);
    ApiObject syncScriptConfigMap = createSyncScriptConfigMap(scope);
    ApiObject configTemplateSecret = createConfigTemplateSecret(scope);
    ApiObject secretsSecret = createSecretsSecret(scope);
    ApiObject agentAuthSecret = createAgentAuthSecret(scope);
    ApiObject pvc = createDataPvc(scope);
    ApiObject agentSyncRole = createAgentSyncRole(scope);
    ApiObject agentSyncRoleBinding =
        createAgentSyncRoleBinding(scope, serviceAccount, agentSyncRole);
    ApiObject k8sIntegrationRole = createK8sIntegrationRole(scope);
    ApiObject k8sIntegrationRoleBinding =
        createK8sIntegrationRoleBinding(scope, serviceAccount, k8sIntegrationRole);
    ApiObject syncJob =
        createAgentSyncJob(
            scope,
            serviceAccount,
            envConfigMap,
            syncScriptConfigMap,
            configTemplateSecret,
            agentSyncRoleBinding);
    ApiObject deployment =
        createDeployment(
            scope,
            floxImage,
            serviceAccount,
            envConfigMap,
            secretsSecret,
            agentAuthSecret,
            pvc,
            k8sIntegrationRoleBinding,
            syncJob);
    ApiObject service = createService(scope, deployment);
    createIngress(scope, service);
  }

  private ApiObject createServiceAccount(final Construct scope) {
    return new ApiObject(
        scope,
        "serviceaccount-headplane",
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("ServiceAccount")
            .metadata(
                ApiObjectMetadata.builder()
                    .name("headplane")
                    .namespace(HEADSCALE_NAMESPACE)
                    .annotations(
                        packageProfile.packageAnnotations(
                            "|ServiceAccount|${headscale-namespace}|headplane"))
                    .build())
            .build());
  }

  private ApiObject createEnvConfigMap(final Construct scope) {
    ApiObject configMap =
        new ApiObject(
            scope,
            "configmap-" + MeshRefs.HEADPLANE_ENV_CONFIGMAP.name(),
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(MeshRefs.HEADPLANE_ENV_CONFIGMAP.name())
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ConfigMap|${headscale-namespace}|"
                                    + MeshRefs.HEADPLANE_ENV_CONFIGMAP.name()))
                        .build())
                .build());

    configMap.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "HEADPLANE_BASE_URL",
                "http://headplane." + HEADSCALE_NAMESPACE + ".svc.cluster.local:3000",
                "HEADPLANE_COOKIE_SECURE",
                "false",
                "HEADPLANE_NAMESPACE",
                HEADSCALE_NAMESPACE,
                "HEADSCALE_NAMESPACE",
                HEADSCALE_NAMESPACE,
                "HEADSCALE_SERVICE_URL",
                "http://headscale." + HEADSCALE_NAMESPACE + ".svc.cluster.local:8080")));

    return configMap;
  }

  private ApiObject createSyncScriptConfigMap(final Construct scope) {
    ApiObject configMap =
        new ApiObject(
            scope,
            "configmap-headplane-agent-sync-script",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-agent-sync-script")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ConfigMap|${headscale-namespace}|headplane-agent-sync-script"))
                        .build())
                .build());

    configMap.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "agent-sync.sh",
                """
                #!/bin/sh
                set -exuo pipefail

                # Read the authkey from the mounted secret volume (k8s already decoded it).
                AUTHKEY=$(cat /secrets/headscale/authkey)
                kubectl create secret generic headplane-agent-auth \\
                  -n "${HEADPLANE_NAMESPACE}" \\
                  --from-literal=preauthkey="${AUTHKEY}" \\
                  --dry-run=client -o yaml | kubectl apply -f -

                yq eval '.server.base_url = strenv(HEADPLANE_BASE_URL) |
                  .server.cookie_secure = (strenv(HEADPLANE_COOKIE_SECURE) | downcase == "true") |
                  .headscale.url = strenv(HEADSCALE_SERVICE_URL)' \\
                  /etc/headplane-template/config.yaml > /tmp/config.$$.yaml
                kubectl create secret generic headplane-config \\
                  -n "${HEADPLANE_NAMESPACE}" \\
                  --from-file=config.yaml=/tmp/config.$$.yaml \\
                  --dry-run=client -o yaml | kubectl apply -f -\
                """)));

    return configMap;
  }

  private ApiObject createConfigTemplateSecret(final Construct scope) {
    ApiObject secret =
        new ApiObject(
            scope,
            "secret-headplane-config-tmpl",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-config-tmpl")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|${headscale-namespace}|headplane-config-tmpl"))
                        .build())
                .build());

    secret.addJsonPatch(
        JsonPatch.add(
            "/stringData",
            Map.of(
                "config.yaml",
                "server:\n"
                    + "  host: \"0.0.0.0\"\n"
                    + "  port: 3000\n"
                    + "  base_url: \"${HEADPLANE_BASE_URL}\"\n"
                    + "  cookie_secret_path: \"/etc/headplane/secrets/cookie_secret\"\n"
                    + "  cookie_secure: ${HEADPLANE_COOKIE_SECURE}\n"
                    + "  data_path: \"/var/lib/headplane\"\n\n"
                    + "headscale:\n"
                    + "  url: \"${HEADSCALE_SERVICE_URL}\"\n"
                    + "  config_path: \"/etc/headscale/config.yaml\"\n"
                    + "  dns_records_path: \"/var/lib/headscale/extra_records.json\"\n"
                    + "  config_strict: false\n\n"
                    + "integration:\n"
                    + "  agent:\n"
                    + "    enabled: true\n"
                    + "    pre_authkey_path: \"/etc/headplane/agent/preauthkey\"\n"
                    + "  kubernetes:\n"
                    + "    enabled: false\n"
                    + "    validate_manifest: false\n"
                    + "    pod_name: \"headscale\"")),
        JsonPatch.add("/type", "Opaque"));

    return secret;
  }

  private ApiObject createSecretsSecret(final Construct scope) {
    ApiObject secret =
        new ApiObject(
            scope,
            "secret-" + MeshRefs.HEADPLANE_SECRETS_SECRET.name(),
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(MeshRefs.HEADPLANE_SECRETS_SECRET.name())
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|${headscale-namespace}|"
                                    + MeshRefs.HEADPLANE_SECRETS_SECRET.name()))
                        .build())
                .build());

    secret.addJsonPatch(
        JsonPatch.add("/stringData", Map.of("cookie_secret", "0123456789abcdef0123456789abcdef")),
        JsonPatch.add("/type", "Opaque"));

    return secret;
  }

  private ApiObject createAgentAuthSecret(final Construct scope) {
    ApiObject secret =
        new ApiObject(
            scope,
            "secret-headplane-agent-auth",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-agent-auth")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|${headscale-namespace}|headplane-agent-auth"))
                        .build())
                .build());

    secret.addJsonPatch(
        JsonPatch.add("/stringData", Map.of("preauthkey", "REPLACE_WITH_HEADSCALE_PREAUTH_KEY")),
        JsonPatch.add("/type", "Opaque"));

    return secret;
  }

  private ApiObject createDataPvc(final Construct scope) {
    ApiObject pvc =
        new ApiObject(
            scope,
            "persistentvolumeclaim-headplane-data",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("PersistentVolumeClaim")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-data")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|PersistentVolumeClaim|${headscale-namespace}|headplane-data"))
                        .build())
                .build());

    pvc.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "accessModes",
                List.of("ReadWriteOnce"),
                "resources",
                Map.of("requests", Map.of("storage", "128Mi")))));

    return pvc;
  }

  private ApiObject createAgentSyncRole(final Construct scope) {
    ApiObject role =
        new ApiObject(
            scope,
            "role-headplane-agent-sync",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("Role")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-agent-sync")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|Role|${headscale-namespace}|headplane-agent-sync"))
                        .build())
                .build());

    role.addJsonPatch(
        JsonPatch.add(
            "/rules",
            List.of(
                Map.of(
                    "apiGroups",
                    List.of(""),
                    "resources",
                    List.of("secrets"),
                    "verbs",
                    List.of("get", "create", "update", "patch")))));

    return role;
  }

  private ApiObject createAgentSyncRoleBinding(
      final Construct scope, final ApiObject serviceAccount, final ApiObject role) {
    ApiObject roleBinding =
        new ApiObject(
            scope,
            "rolebinding-headplane-agent-sync",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("RoleBinding")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-agent-sync")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|RoleBinding|${headscale-namespace}|headplane-agent-sync"))
                        .build())
                .build());

    roleBinding.addDependency(serviceAccount);
    roleBinding.addDependency(role);

    roleBinding.addJsonPatch(
        JsonPatch.add(
            "/roleRef",
            Map.of(
                "apiGroup",
                "rbac.authorization.k8s.io",
                "kind",
                "Role",
                "name",
                "headplane-agent-sync")),
        JsonPatch.add(
            "/subjects",
            List.of(
                Map.of(
                    "kind",
                    "ServiceAccount",
                    "name",
                    "headplane",
                    "namespace",
                    HEADSCALE_NAMESPACE))));

    return roleBinding;
  }

  private ApiObject createK8sIntegrationRole(final Construct scope) {
    ApiObject role =
        new ApiObject(
            scope,
            "role-headplane-k8s-integration",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("Role")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-k8s-integration")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|Role|${headscale-namespace}|headplane-k8s-integration"))
                        .build())
                .build());

    role.addJsonPatch(
        JsonPatch.add(
            "/rules",
            List.of(
                Map.of(
                    "apiGroups",
                    List.of(""),
                    "resources",
                    List.of("pods", "pods/exec"),
                    "verbs",
                    List.of("get", "list", "create")),
                Map.of(
                    "apiGroups",
                    List.of("apps"),
                    "resources",
                    List.of("deployments"),
                    "verbs",
                    List.of("get", "list")),
                Map.of(
                    "apiGroups",
                    List.of(""),
                    "resources",
                    List.of("secrets"),
                    "verbs",
                    List.of("get")))));

    return role;
  }

  private ApiObject createK8sIntegrationRoleBinding(
      final Construct scope, final ApiObject serviceAccount, final ApiObject role) {
    ApiObject roleBinding =
        new ApiObject(
            scope,
            "rolebinding-headplane-k8s-integration",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("RoleBinding")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-k8s-integration")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|RoleBinding|${headscale-namespace}|headplane-k8s-integration"))
                        .build())
                .build());

    roleBinding.addDependency(serviceAccount);
    roleBinding.addDependency(role);

    roleBinding.addJsonPatch(
        JsonPatch.add(
            "/roleRef",
            Map.of(
                "apiGroup",
                "rbac.authorization.k8s.io",
                "kind",
                "Role",
                "name",
                "headplane-k8s-integration")),
        JsonPatch.add(
            "/subjects",
            List.of(
                Map.of(
                    "kind",
                    "ServiceAccount",
                    "name",
                    "headplane",
                    "namespace",
                    HEADSCALE_NAMESPACE))));

    return roleBinding;
  }

  private ApiObject createAgentSyncJob(
      final Construct scope,
      final ApiObject serviceAccount,
      final ApiObject envConfigMap,
      final ApiObject syncScriptConfigMap,
      final ApiObject configTemplateSecret,
      final ApiObject roleBinding) {
    ApiObject job =
        new ApiObject(
            scope,
            "job-headplane-agent-sync",
            ApiObjectProps.builder()
                .apiVersion("batch/v1")
                .kind("Job")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane-agent-sync")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "batch|Job|${headscale-namespace}|headplane-agent-sync"))
                        .build())
                .build());

    job.addDependency(serviceAccount);
    job.addDependency(envConfigMap);
    job.addDependency(syncScriptConfigMap);
    job.addDependency(configTemplateSecret);
    job.addDependency(roleBinding);

    job.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        packageProfile.templateAnnotations(
                            Map.of(
                                FloxAnnotation.ENVIRONMENT.forContainer("sync"),
                                "mesh/headplane"))),
                    "spec",
                    Map.of(
                        "containers",
                        List.of(
                            Map.of(
                                "name",
                                "sync",
                                "image",
                                ManifestSynthesisContext.current().floxDebugPolicy().prodImage(),
                                "command",
                                List.of("/scripts/agent-sync.sh"),
                                "envFrom",
                                List.of(
                                    Map.of(
                                        "configMapRef",
                                        Map.of("name", MeshRefs.HEADPLANE_ENV_CONFIGMAP.name()))),
                                "resources",
                                Map.of(
                                    "limits",
                                    Map.of(
                                        "cpu",
                                        "200m",
                                        "ephemeral-storage",
                                        "256Mi",
                                        "memory",
                                        "128Mi"),
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
                                        "agent-sync-script",
                                        "readOnly",
                                        true),
                                    Map.of(
                                        "mountPath",
                                        "/etc/headplane-template/config.yaml",
                                        "name",
                                        "config-template",
                                        "readOnly",
                                        true,
                                        "subPath",
                                        "config.yaml"),
                                    Map.of(
                                        "mountPath",
                                        "/secrets/headscale",
                                        "name",
                                        "headscale-auth",
                                        "readOnly",
                                        true)))),
                        "restartPolicy",
                        "OnFailure",
                        "serviceAccountName",
                        "headplane",
                        "volumes",
                        List.of(
                            Map.of(
                                "name",
                                "agent-sync-script",
                                "configMap",
                                Map.of("defaultMode", 493, "name", "headplane-agent-sync-script")),
                            Map.of(
                                "name",
                                "config-template",
                                "secret",
                                Map.of("secretName", "headplane-config-tmpl")),
                            Map.of(
                                "name",
                                "headscale-auth",
                                "secret",
                                Map.of(
                                    "optional",
                                    false,
                                    "secretName",
                                    MeshRefs.HEADSCALE_CLIENT_AUTH_SECRET.name()))))),
                "ttlSecondsAfterFinished",
                300)));

    return job;
  }

  private ApiObject createDeployment(
      final Construct scope,
      final String floxImage,
      final ApiObject serviceAccount,
      final ApiObject envConfigMap,
      final ApiObject secretsSecret,
      final ApiObject agentAuthSecret,
      final ApiObject pvc,
      final ApiObject roleBinding,
      final ApiObject syncJob) {
    ApiObject deployment =
        new ApiObject(
            scope,
            "deployment-headplane",
            ApiObjectProps.builder()
                .apiVersion("apps/v1")
                .kind("Deployment")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane")
                        .namespace(HEADSCALE_NAMESPACE)
                        .labels(Map.of("app", "headplane"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "apps|Deployment|${headscale-namespace}|headplane"))
                        .build())
                .build());

    deployment.addDependency(serviceAccount);
    deployment.addDependency(envConfigMap);
    deployment.addDependency(secretsSecret);
    deployment.addDependency(agentAuthSecret);
    deployment.addDependency(pvc);
    deployment.addDependency(roleBinding);
    deployment.addDependency(syncJob);

    final FloxDebugPolicy debugPolicy = ManifestSynthesisContext.current().floxDebugPolicy();
    final FloxShellSidecarProfile shellSidecar =
        new FloxShellSidecarProfile(
            debugPolicy,
            debugPolicy.meshEnabled(),
            "headplane",
            "/root",
            "mesh/headplane-debug",
            "0",
            "0");

    final List<Map<String, Object>> headplaneMounts = new ArrayList<>();
    headplaneMounts.add(
        Map.of(
            "mountPath", "/etc/headplane/config.yaml",
            "name", "config",
            "subPath", "config.yaml"));
    headplaneMounts.add(
        Map.of(
            "mountPath", "/etc/headplane/secrets",
            "name", "secrets",
            "readOnly", true));
    headplaneMounts.add(
        Map.of(
            "mountPath", "/etc/headplane/agent",
            "name", "agent",
            "readOnly", true));
    headplaneMounts.add(Map.of("mountPath", "/var/lib/headplane", "name", "data"));
    headplaneMounts.add(
        Map.of(
            "mountPath", "/etc/headscale",
            "name", "headscale-config",
            "readOnly", true));
    headplaneMounts.add(Map.of("mountPath", "/usr/libexec/headplane", "name", "shared-bin"));
    headplaneMounts.addAll(shellSidecar.extraProdMounts());

    final LinkedHashMap<String, Object> headplaneContainer = new LinkedHashMap<>();
    headplaneContainer.put("name", "headplane");
    headplaneContainer.put("image", floxImage);
    // headplane's agent integration (integration.agent.enabled) spawns hp_agent from the fixed
    // path /usr/libexec/headplane/agent. Symlink it to the REAL binary resolved THROUGH the flox
    // env (`command -v`, once activated) — not a hardcoded /.flox/run/<name> path, which is exactly
    // what left it dangling (the NRI env activates as "default", not the catalog name). Then exec
    // headplane. The shell sidecar shares /usr/libexec/headplane (shared-bin), so it sees it too.
    headplaneContainer.put(
        "command",
        List.of(
            "sh",
            "-c",
            "ln -sf \"$(command -v hp_agent)\" /usr/libexec/headplane/agent && exec headplane serve"));
    headplaneContainer.put(
        "env",
        List.of(
            Map.of("name", "HEADPLANE_LOAD_ENV_OVERRIDES", "value", "true"),
            Map.of(
                "name",
                "HEADPLANE_INTEGRATION__KUBERNETES__POD_NAME",
                "valueFrom",
                Map.of("fieldRef", Map.of("fieldPath", "metadata.name")))));
    headplaneContainer.put(
        "envFrom",
        List.of(Map.of("configMapRef", Map.of("name", MeshRefs.HEADPLANE_ENV_CONFIGMAP.name()))));
    // Resolve hp_healthcheck THROUGH the flox env (same as the container command runs
    // `flox activate --dir /root -- headplane`), not via a hardcoded /.flox/run/<name> path:
    // the NRI-injected env activates as "default" in the guest's HOME (/root/.flox/run/
    // aarch64-linux.default-*), NOT the env's catalog name — hardcoding "headplane.run" is
    // exactly what left the probe binary unresolvable. `flox activate -- hp_healthcheck` finds
    // it on the env PATH regardless of the run-dir name. Timeouts are generous to absorb the
    // activation (a baked cache-hit; background side effects are disabled so no OOM).
    final List<String> healthcheck =
        List.of("flox", "activate", "--dir", "/root", "--", "hp_healthcheck");
    headplaneContainer.put(
        "livenessProbe",
        Map.of(
            "exec",
            Map.of("command", healthcheck),
            "failureThreshold",
            3,
            "initialDelaySeconds",
            45,
            "periodSeconds",
            20,
            "timeoutSeconds",
            10));
    headplaneContainer.put(
        "readinessProbe",
        Map.of(
            "exec",
            Map.of("command", healthcheck),
            "failureThreshold",
            3,
            "initialDelaySeconds",
            15,
            "periodSeconds",
            10,
            "timeoutSeconds",
            10));
    headplaneContainer.put("ports", List.of(Map.of("containerPort", 3000, "name", "http")));
    headplaneContainer.put(
        "resources",
        Map.of(
            "limits",
            Map.of(
                "cpu", "500m",
                "ephemeral-storage", "512Mi",
                "memory", "512Mi"),
            "requests",
            Map.of(
                "cpu", "100m",
                "ephemeral-storage", "256Mi",
                "memory", "128Mi")));
    headplaneContainer.put("volumeMounts", List.copyOf(headplaneMounts));

    final List<Object> containers = new ArrayList<>();
    containers.add(headplaneContainer);
    shellSidecar.sidecar(headplaneMounts).ifPresent(containers::add);

    final List<Object> volumes = new ArrayList<>();
    volumes.add(Map.of("name", "config", "secret", Map.of("secretName", "headplane-config")));
    volumes.add(
        Map.of(
            "name",
            "secrets",
            "secret",
            Map.of("secretName", MeshRefs.HEADPLANE_SECRETS_SECRET.name())));
    volumes.add(Map.of("name", "agent", "secret", Map.of("secretName", "headplane-agent-auth")));
    volumes.add(
        Map.of("name", "data", "persistentVolumeClaim", Map.of("claimName", "headplane-data")));
    volumes.add(
        Map.of(
            "name",
            "headscale-config",
            "configMap",
            Map.of("name", MeshRefs.HEADSCALE_CONFIG_CONFIGMAP.name())));
    volumes.add(Map.of("name", "shared-bin", "emptyDir", Map.of()));
    volumes.addAll(shellSidecar.extraVolumes());

    // When debug is on, the prod container runs the debug env so its node has --enable-source-maps
    // + --inspect; the shell sidecar (with SYS_PTRACE + shareProcessNamespace) can attach to the
    // running node process from the shared PID namespace.
    final LinkedHashMap<String, String> annotations = new LinkedHashMap<>();
    annotations.put(
        FloxAnnotation.ENVIRONMENT.forContainer("headplane"),
        debugPolicy.resolveMeshEnvironment("mesh/headplane", "mesh/headplane-debug"));
    annotations.putAll(shellSidecar.sidecarAnnotations());

    deployment.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "replicas",
                1,
                // Single replica on a ReadWriteOnce PVC (headplane-data): a RollingUpdate starts
                // the new pod before terminating the old, and the new pod cannot attach the RWO
                // volume the old one still holds (multi-attach → ContainerCreating →
                // ProgressDeadlineExceeded). Recreate terminates the old pod first.
                "strategy",
                Map.of("type", "Recreate"),
                "selector",
                Map.of("matchLabels", Map.of("app", "headplane")),
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        packageProfile.templateAnnotations(Map.copyOf(annotations)),
                        "labels",
                        Map.of("app", "headplane")),
                    "spec",
                    Map.of(
                        "containers",
                        List.copyOf(containers),
                        "serviceAccountName",
                        "headplane",
                        "shareProcessNamespace",
                        true,
                        "volumes",
                        List.copyOf(volumes))))));

    return deployment;
  }

  private ApiObject createService(final Construct scope, final ApiObject deployment) {
    ApiObject service =
        new ApiObject(
            scope,
            "service-headplane",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Service")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane")
                        .namespace(HEADSCALE_NAMESPACE)
                        .labels(Map.of("app", "headplane"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Service|${headscale-namespace}|headplane"))
                        .build())
                .build());
    service.addDependency(deployment);

    service.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "ports",
                List.of(Map.of("name", "http", "port", 3000, "targetPort", "http")),
                "selector",
                Map.of("app", "headplane"),
                "type",
                "ClusterIP")));

    return service;
  }

  private void createIngress(final Construct scope, final ApiObject service) {
    ApiObject ingress =
        new ApiObject(
            scope,
            "ingress-headplane",
            ApiObjectProps.builder()
                .apiVersion("networking.k8s.io/v1")
                .kind("Ingress")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headplane")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "networking.k8s.io|Ingress|${headscale-namespace}|headplane",
                                Map.of(
                                    "io.cilium/lb-ipam-ips",
                                    "${cluster-lan-headplane-inetaddr}",
                                    "kubernetes.io/ingress.class",
                                    "cilium",
                                    "lab42.io/mdns.enabled",
                                    "true",
                                    "lab42.io/mdns.host",
                                    "headplane",
                                    "lab42.io/mdns.name",
                                    "headplane")))
                        .build())
                .build());
    ingress.addDependency(service);

    ingress.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "ingressClassName",
                "cilium",
                "rules",
                List.of(
                    Map.of(
                        "host",
                        "headplane.local",
                        "http",
                        Map.of(
                            "paths",
                            List.of(
                                Map.of(
                                    "backend",
                                    Map.of(
                                        "service",
                                        Map.of(
                                            "name", "headplane", "port", Map.of("number", 3000))),
                                    "path",
                                    "/admin",
                                    "pathType",
                                    "Prefix"))))))));
  }
}
