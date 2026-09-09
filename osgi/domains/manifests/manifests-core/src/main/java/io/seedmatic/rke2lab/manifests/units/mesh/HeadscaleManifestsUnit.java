// @codebase
package io.seedmatic.rke2lab.manifests.units.mesh;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.FloxAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
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

/**
 * Headscale control-server manifests (deployment, config, bootstrap job, gateway, service, ACL,
 * DERP). NOTE: renders its resources as {@code Map.of} blobs across many private {@code createXxx}
 * helpers — a de-soup candidate (see docs/architecture/manifests/manifests-unit-lifecycle.adoc §
 * Known debt).
 */
public final class HeadscaleManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.MESH + "/headscale";

  private static final String HEADSCALE_NAMESPACE = MeshRefs.SYSTEM_NAMESPACE.name();

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("mesh", "headscale");

  public HeadscaleManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of(MeshSystemNamespaceManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String floxImage = ManifestSynthesisContext.current().floxDebugPolicy().prodImage();
    final String clusterName = context.nodeEnvContext().bootstrapIdentity().clusterName();

    ApiObject namespace = context.resolver().require(MeshRefs.SYSTEM_NAMESPACE);

    ApiObject saClient = createServiceAccount(scope, "headscale-client", namespace);
    ApiObject saBootstrap = createServiceAccount(scope, "headscale-bootstrap", namespace);
    ApiObject saGateway = createServiceAccount(scope, "headscale-gateway", namespace);

    ApiObject clusterRoleClient = createClusterRoleClient(scope);
    createClusterRoleBindingClient(scope, clusterRoleClient, saClient);

    ApiObject roleBootstrap = createRoleBootstrap(scope, namespace);
    createRoleBindingBootstrap(scope, saBootstrap, roleBootstrap, namespace);

    ApiObject cmHeadscaleConfig = createConfigMapHeadscaleConfig(scope, namespace);

    ApiObject cmConfigInitScript = createConfigMapConfigInitScript(scope, namespace);
    ApiObject cmHeadscaleEnv = createConfigMapHeadscaleEnv(scope, namespace, clusterName);

    // The bootstrap job creates the real client-auth Secret at runtime (with the
    // preauth key extracted from headscale). We pre-create an empty placeholder
    // carrying config.kubernetes.io/local-config so the resolver can address it
    // during synthesis without it ever being applied to the cluster — the install
    // script skips local-config manifests, so the runtime `kubectl create secret`
    // remains the sole creator and the client's `kubectl wait --for=create` barrier
    // still fires only on the real Secret.
    createClientAuthSecretPlaceholder(scope, namespace);

    ApiObject cmBootstrapScript = createConfigMapBootstrapScript(scope, namespace);
    ApiObject cmClientScripts = createConfigMapClientScripts(scope, namespace);
    ApiObject cmAcl = createConfigMapAcl(scope, namespace);
    ApiObject cmDerp = createConfigMapDerp(scope, namespace);
    ApiObject cmExtraRecords = createConfigMapExtraRecords(scope, namespace);
    ApiObject cmGatewayScript = createConfigMapGatewayScript(scope, namespace);

    ApiObject l2Policy = createLanPolicy(scope);
    ApiObject deploymentHeadscale =
        createDeploymentHeadscale(
            scope,
            floxImage,
            namespace,
            cmHeadscaleConfig,
            cmConfigInitScript,
            cmAcl,
            cmDerp,
            cmExtraRecords,
            l2Policy);
    ApiObject serviceHeadscale =
        createServiceHeadscale(scope, namespace, deploymentHeadscale, clusterName);
    ApiObject bootstrapJob =
        createBootstrapJob(
            scope,
            floxImage,
            namespace,
            saBootstrap,
            cmHeadscaleEnv,
            cmBootstrapScript,
            deploymentHeadscale);
    createDeploymentGateway(
        scope, floxImage, namespace, saGateway, cmHeadscaleEnv, cmGatewayScript, bootstrapJob);
    createDaemonsetClient(
        scope,
        floxImage,
        namespace,
        saClient,
        cmHeadscaleEnv,
        cmClientScripts,
        bootstrapJob,
        serviceHeadscale);
  }

  private ApiObject createServiceAccount(
      final Construct scope, final String name, final ApiObject namespace) {
    ApiObject serviceAccount =
        new ApiObject(
            scope,
            "serviceaccount-" + name,
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ServiceAccount")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ServiceAccount|${headscale-namespace}|" + name))
                        .build())
                .build());
    serviceAccount.addDependency(namespace);
    return serviceAccount;
  }

  private ApiObject createClientAuthSecretPlaceholder(
      final Construct scope, final ApiObject namespace) {
    ApiObject secret =
        new ApiObject(
            scope,
            "secret-" + MeshRefs.HEADSCALE_CLIENT_AUTH_SECRET.name(),
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(MeshRefs.HEADSCALE_CLIENT_AUTH_SECRET.name())
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|${headscale-namespace}|"
                                    + MeshRefs.HEADSCALE_CLIENT_AUTH_SECRET.name(),
                                Map.of(ManifestAnnotation.LOCAL_CONFIG.key(), "true")))
                        .build())
                .build());
    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"), JsonPatch.add("/data", Map.of()));
    secret.addDependency(namespace);
    return secret;
  }

  private ApiObject createClusterRoleClient(final Construct scope) {
    ApiObject role =
        new ApiObject(
            scope,
            "clusterrole-headscale-client",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRole")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headscale-client")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|ClusterRole|default|headscale-client"))
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
                    List.of("nodes"),
                    "verbs",
                    List.of("get", "list")),
                Map.of(
                    "apiGroups",
                    List.of(""),
                    "resources",
                    List.of("secrets"),
                    "verbs",
                    List.of("get", "list", "watch")),
                Map.of(
                    "apiGroups",
                    List.of("apps"),
                    "resources",
                    List.of("deployments"),
                    "verbs",
                    List.of("get", "list", "watch")),
                // wait-for-headscale.sh does `kubectl wait --for=create configmap/...`.
                Map.of(
                    "apiGroups",
                    List.of(""),
                    "resources",
                    List.of("configmaps"),
                    "verbs",
                    List.of("get", "list", "watch")))));
    return role;
  }

  private void createClusterRoleBindingClient(
      final Construct scope, final ApiObject role, final ApiObject serviceAccount) {
    ApiObject roleBinding =
        new ApiObject(
            scope,
            "clusterrolebinding-headscale-client",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("ClusterRoleBinding")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headscale-client")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|ClusterRoleBinding|default|headscale-client"))
                        .build())
                .build());
    roleBinding.addDependency(role);
    roleBinding.addDependency(serviceAccount);
    roleBinding.addJsonPatch(
        JsonPatch.add(
            "/roleRef",
            Map.of(
                "apiGroup",
                "rbac.authorization.k8s.io",
                "kind",
                "ClusterRole",
                "name",
                "headscale-client")),
        JsonPatch.add(
            "/subjects",
            List.of(
                Map.of(
                    "kind",
                    "ServiceAccount",
                    "name",
                    "headscale-client",
                    "namespace",
                    HEADSCALE_NAMESPACE))));
  }

  private ApiObject createRoleBootstrap(final Construct scope, final ApiObject namespace) {
    ApiObject role =
        new ApiObject(
            scope,
            "role-headscale-bootstrap",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("Role")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headscale-bootstrap")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|Role|${headscale-namespace}|headscale-bootstrap"))
                        .build())
                .build());
    role.addDependency(namespace);
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
                    List.of("get", "create", "update", "patch")),
                Map.of(
                    "apiGroups",
                    List.of(""),
                    "resources",
                    List.of("pods"),
                    "verbs",
                    List.of("get", "list", "watch")),
                Map.of(
                    "apiGroups",
                    List.of(""),
                    "resources",
                    List.of("pods/exec"),
                    "verbs",
                    List.of("create")),
                Map.of(
                    "apiGroups",
                    List.of("apps"),
                    "resources",
                    List.of("deployments"),
                    "verbs",
                    List.of("get", "list", "watch")))));
    return role;
  }

  private void createRoleBindingBootstrap(
      final Construct scope,
      final ApiObject serviceAccount,
      final ApiObject role,
      final ApiObject namespace) {
    ApiObject roleBinding =
        new ApiObject(
            scope,
            "rolebinding-headscale-bootstrap",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("RoleBinding")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headscale-bootstrap")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "rbac.authorization.k8s.io|RoleBinding|${headscale-namespace}|headscale-bootstrap"))
                        .build())
                .build());
    roleBinding.addDependency(namespace);
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
                "headscale-bootstrap")),
        JsonPatch.add(
            "/subjects",
            List.of(
                Map.of(
                    "kind",
                    "ServiceAccount",
                    "name",
                    "headscale-bootstrap",
                    "namespace",
                    HEADSCALE_NAMESPACE))));
  }

  private ApiObject createConfigMapHeadscaleConfig(
      final Construct scope, final ApiObject namespace) {
    ApiObject configMap =
        configMapWithData(
            scope,
            MeshRefs.HEADSCALE_CONFIG_CONFIGMAP.name(),
            "|ConfigMap|${headscale-namespace}|" + MeshRefs.HEADSCALE_CONFIG_CONFIGMAP.name(),
            Map.of(
                "config.yaml",
                "server_url: http://headscale.hs.net:8080\n"
                    + "listen_addr: 0.0.0.0:8080\n"
                    + "metrics_listen_addr: 0.0.0.0:9090\n"
                    + "grpc_listen_addr: 0.0.0.0:50443\n"
                    + "grpc_allow_insecure: true\n\n"
                    + "tls:\n"
                    + "  letsencrypt_hostname: \"\"\n"
                    + "  letsencrypt_cache_dir: \"\"\n"
                    + "  letsencrypt_challenge_type: \"\"\n"
                    + "  cert_path: \"\"\n"
                    + "  key_path: \"\"\n\n"
                    + "private_key_path: /var/lib/headscale/private.key\n"
                    + "noise:\n"
                    + "  private_key_path: /var/lib/headscale/noise_private.key\n\n"
                    + "prefixes:\n"
                    + "  v4: 100.64.0.0/10\n"
                    + "  v6: fd7a:115c:a1e0::/48\n\n"
                    + "derp:\n"
                    + "  server:\n"
                    + "    enabled: false\n"
                    + "  urls: []\n"
                    + "  paths:\n"
                    + "    - /etc/headscale/derp.yaml\n"
                    + "  auto_update_enabled: false\n"
                    + "  update_frequency: 24h\n\n"
                    + "disable_check_updates: true\n"
                    + "ephemeral_node_inactivity_timeout: 30m\n"
                    + "database:\n"
                    + "  type: sqlite\n"
                    + "  sqlite:\n"
                    + "    path: /var/lib/headscale/db.sqlite\n\n"
                    + "policy:\n"
                    + "  mode: file\n"
                    + "  path: /etc/headscale/acl.json\n\n"
                    + "dns:\n"
                    + "  base_domain: hs.net\n"
                    + "  nameservers:\n"
                    + "    global:\n"
                    + "      - 192.168.1.254\n"
                    + "      - 1.1.1.1\n"
                    + "      - 8.8.8.8\n"
                    + "  extra_records_path: /var/lib/headscale/extra_records.json\n"
                    + "  magic_dns: true\n\n"
                    + "log:\n"
                    + "  format: text\n"
                    + "  level: info"));
    configMap.addDependency(namespace);
    return configMap;
  }

  private ApiObject createConfigMapConfigInitScript(
      final Construct scope, final ApiObject namespace) {
    ApiObject configMap =
        configMapWithData(
            scope,
            "headscale-config-init-script",
            "|ConfigMap|${headscale-namespace}|headscale-config-init-script",
            Map.of(
                "config-init.sh",
                """
                #!/usr/bin/env bash
                set -exuo pipefail
                mkdir -p /config
                cp /config-source/config.yaml /config/config.yaml
                mkdir -p /var/lib/headscale
                if [ ! -f /var/lib/headscale/extra_records.json ]; then\s
                  echo "[]" > /var/lib/headscale/extra_records.json;\s
                fi
                cp /extra-records-source/extra_records.json /var/lib/headscale/extra_records.json\
                """));
    configMap.addDependency(namespace);
    configMap.addJsonPatch(JsonPatch.add("/metadata/labels", Map.of("app", "headscale")));
    return configMap;
  }

  private ApiObject createConfigMapHeadscaleEnv(
      final Construct scope, final ApiObject namespace, final String clusterName) {
    ApiObject configMap =
        configMapWithData(
            scope,
            MeshRefs.HEADSCALE_ENV_CONFIGMAP.name(),
            "|ConfigMap|${headscale-namespace}|" + MeshRefs.HEADSCALE_ENV_CONFIGMAP.name(),
            Map.of(
                "CLUSTER_LAN_HEADSCALE_INETADDR",
                "192.168.1.193",
                "DARWIN_HOST",
                clusterName,
                "HEADPLANE_NAMESPACE",
                "${headplane-namespace}",
                "HEADSCALE_NAMESPACE",
                HEADSCALE_NAMESPACE,
                "HEADSCALE_URL",
                "http://headscale." + HEADSCALE_NAMESPACE + ".svc.cluster.local:8080",
                "RKE2_CLUSTER_NAME",
                clusterName,
                "VIP_NETWORK_CIDR",
                "10.80.7.0/24"));
    configMap.addDependency(namespace);
    return configMap;
  }

  private ApiObject createConfigMapBootstrapScript(
      final Construct scope, final ApiObject namespace) {
    ApiObject configMap =
        configMapWithData(
            scope,
            "headscale-bootstrap-script",
            "|ConfigMap|${headscale-namespace}|headscale-bootstrap-script",
            Map.of(
                "bootstrap.sh",
                """
                #!/usr/bin/env bash
                set -exuo pipefail

                # headscale runs behind flox injection: `kubectl exec` starts a fresh process
                # that bypasses the container's `flox activate` entrypoint, so the headscale
                # binary is NOT on PATH. Re-activate inside the exec — with the flox background
                # check-for-upgrades disabled so it can't spike memory and OOM the live server.
                hs() {
                  kubectl exec -n "$HEADSCALE_NAMESPACE" -c headscale deployment/headscale -- \\
                    env _FLOX_TESTING_DISABLE_BG_SIDE_EFFECTS=true \\
                    flox activate --dir /root -- headscale "$@"
                }

                : "Waiting for headscale deployment to be available..."
                kubectl wait --for=condition=available deployment/headscale \\
                  -n "$HEADSCALE_NAMESPACE" --timeout=300s

                : "Waiting for headscale pod to be Ready..."
                kubectl wait --for=condition=Ready pod -l app=headscale \\
                  -n "$HEADSCALE_NAMESPACE" --timeout=300s

                : "Creating admin user..."
                hs users create admin 2>/dev/null || echo "User admin already exists"

                : "Getting admin user ID..."
                USER_ID=$( hs users list -o yaml |
                             yq -r '.[] | select( .name == "admin" ) | .id' - )
                if [ -z "$USER_ID" ]; then
                  echo "ERROR: Failed to get admin user ID"
                  exit 1
                fi

                : "Creating reusable preauth key... (10 years expiration)"
                PREAUTH_KEY=$( hs preauthkeys --user "$USER_ID" \\
                    create --reusable --expiration 87600h -o yaml |
                  yq -r '.key' - )
                if [ -z "$PREAUTH_KEY" ]; then
                  echo "ERROR: Failed to extract preauth key"
                  exit 1
                fi

                : "Storing preauth key in Secret..."
                kubectl create secret generic @CLIENT_AUTH_SECRET@ \\
                  --from-literal=authkey="$PREAUTH_KEY" \\
                  --dry-run=client -o yaml | kubectl apply -f -

                : "Creating Headplane API key..."
                HEADPLANE_API_KEY=$( hs apikeys create )
                if [ -z "$HEADPLANE_API_KEY" ]; then
                  echo "ERROR: Failed to create Headplane API key"
                  exit 1
                fi

                : "Updating headplane-secrets with API key..."
                kubectl patch secret @HEADPLANE_SECRETS@ \\
                  -n "$HEADSCALE_NAMESPACE" \\
                  --type merge \\
                  -p "{\\"stringData\\":{\\"api_key\\":\\"$HEADPLANE_API_KEY\\"}}" 2>/dev/null || \\
                  echo "Note: @HEADPLANE_SECRETS@ not yet created, will be updated when available"\
                """
                    .replace("@CLIENT_AUTH_SECRET@", MeshRefs.HEADSCALE_CLIENT_AUTH_SECRET.name())
                    .replace("@HEADPLANE_SECRETS@", MeshRefs.HEADPLANE_SECRETS_SECRET.name())));
    configMap.addDependency(namespace);
    configMap.addJsonPatch(JsonPatch.add("/metadata/labels", Map.of("app", "headscale")));
    return configMap;
  }

  private ApiObject createConfigMapClientScripts(final Construct scope, final ApiObject namespace) {
    ApiObject configMap =
        configMapWithData(
            scope,
            "headscale-client-scripts",
            "|ConfigMap|${headscale-namespace}|headscale-client-scripts",
            Map.of(
                "tailscale-client.sh",
                """
                #!/bin/sh
                set -exuo pipefail

                : "[i] Starting tailscaled..."
                tailscaled \\
                  --tun=userspace-networking \\
                  --state=/var/lib/tailscale/tailscaled.state \\
                  --socket=/var/run/tailscale/tailscaled.sock --verbose=1 &
                TAILSCALED_PID=$!

                : "[i] Waiting for tailscaled socket..."
                until [ -S /var/run/tailscale/tailscaled.sock ]; do sleep 1; done

                : "[i] Connecting to Headscale at $HEADSCALE_URL..."
                tailscale up \\
                  --login-server=$HEADSCALE_URL \\
                  --authkey=file:/var/secrets/authkey \\
                  --hostname=${RKE2_NODENAME} \\
                  --advertise-tags=tag:rke2,tag:nikopol \\
                  --accept-routes \\
                  --ssh \\
                  --reset

                wait $TAILSCALED_PID\
                """,
                "wait-for-headscale.sh",
                """
                #!/bin/sh
                set -exuo pipefail

                : "[i] Waiting for headscale deployment to be available..."
                kubectl wait --for=condition=available deployment/headscale \\
                  -n "$HEADSCALE_NAMESPACE" --timeout=300s

                : "[i] Waiting for required ConfigMaps..."
                kubectl wait --for=create configmap/headscale-client-scripts \\
                  -n "$HEADSCALE_NAMESPACE" --timeout=300s
                kubectl wait --for=create configmap/@HEADSCALE_ENV_CONFIGMAP@ \\
                  -n "$HEADSCALE_NAMESPACE" --timeout=300s

                : "[i] Waiting for required secrets..."
                kubectl wait --for=create secret/@CLIENT_AUTH_SECRET@ \\
                  -n "$HEADSCALE_NAMESPACE" --timeout=300s\
                """
                    .replace("@HEADSCALE_ENV_CONFIGMAP@", MeshRefs.HEADSCALE_ENV_CONFIGMAP.name())
                    .replace(
                        "@CLIENT_AUTH_SECRET@", MeshRefs.HEADSCALE_CLIENT_AUTH_SECRET.name())));
    configMap.addDependency(namespace);
    configMap.addJsonPatch(JsonPatch.add("/metadata/labels", Map.of("app", "headscale-client")));
    return configMap;
  }

  private ApiObject createConfigMapAcl(final Construct scope, final ApiObject namespace) {
    ApiObject configMap =
        configMapWithData(
            scope,
            "headscale-acl",
            "|ConfigMap|${headscale-namespace}|headscale-acl",
            Map.of(
                "acl.json",
                "{\n"
                    + "  \"groups\": {\n"
                    + "    \"group:admin\": []\n"
                    + "  },\n"
                    + "  \"tagOwners\": {\n"
                    + "    \"tag:darwin\": [\"group:admin\"],\n"
                    + "    \"tag:nixos\": [\"group:admin\"],\n"
                    + "    \"tag:rke2\": [\"group:admin\"]\n"
                    + "  },\n"
                    + "  \"acls\": [\n"
                    + "    {\n"
                    + "      \"action\": \"accept\",\n"
                    + "      \"src\": [\"*\"],\n"
                    + "      \"dst\": [\"*:*\"]\n"
                    + "    }\n"
                    + "  ],\n"
                    + "  \"ssh\": [\n"
                    + "    {\n"
                    + "      \"action\": \"accept\",\n"
                    + "      \"src\": [\"group:admin\", \"tag:darwin\", \"tag:nixos\"],\n"
                    // headscale v2 rejects a wildcard SSH dst AND forbids a TAG src from
                    // targeting autogroup:member (user-owned devices) — only USER sources may.
                    // Split into two rules: any source (admin users + tagged nodes) reaches the
                    // tagged fleet; admin USERS additionally reach user-owned devices.
                    + "      \"dst\": [\"autogroup:tagged\"],\n"
                    + "      \"users\": [\"autogroup:nonroot\", \"root\"]\n"
                    + "    },\n"
                    + "    {\n"
                    + "      \"action\": \"accept\",\n"
                    + "      \"src\": [\"group:admin\"],\n"
                    + "      \"dst\": [\"autogroup:member\"],\n"
                    + "      \"users\": [\"autogroup:nonroot\", \"root\"]\n"
                    + "    }\n"
                    + "  ]\n"
                    + "}"));
    configMap.addDependency(namespace);
    return configMap;
  }

  private ApiObject createConfigMapDerp(final Construct scope, final ApiObject namespace) {
    ApiObject configMap =
        configMapWithData(
            scope,
            "headscale-derp",
            "|ConfigMap|${headscale-namespace}|headscale-derp",
            Map.of(
                "derp.yaml",
                "regions:\n"
                    + "  900:\n"
                    + "    regionid: 900\n"
                    + "    regioncode: local\n"
                    + "    regionname: Local LAN\n"
                    + "    nodes: []"));
    configMap.addDependency(namespace);
    return configMap;
  }

  private ApiObject createConfigMapExtraRecords(final Construct scope, final ApiObject namespace) {
    ApiObject configMap =
        configMapWithData(
            scope,
            "headscale-extra-records",
            "|ConfigMap|${headscale-namespace}|headscale-extra-records",
            Map.of(
                "extra_records.json",
                "[\n"
                    + "  {\n"
                    + "    \"name\": \"headscale.hs.net\",\n"
                    + "    \"type\": \"A\",\n"
                    + "    \"value\": \"${cluster-lan-headscale-inetaddr}\"\n"
                    + "  },\n"
                    + "  {\n"
                    + "    \"name\": \"headplane.hs.net\",\n"
                    + "    \"type\": \"A\",\n"
                    + "    \"value\": \"${cluster-lan-headplane-inetaddr}\"\n"
                    + "  }\n"
                    + "]"));
    configMap.addDependency(namespace);
    return configMap;
  }

  private ApiObject createConfigMapGatewayScript(final Construct scope, final ApiObject namespace) {
    ApiObject configMap =
        configMapWithData(
            scope,
            "headscale-gateway-script",
            "|ConfigMap|${headscale-namespace}|headscale-gateway-script",
            Map.of(
                "gateway.sh",
                """
                #!/usr/bin/env bash
                set -exuo pipefail

                : "[i] Enabling IP forwarding..."
                echo 1 > /proc/sys/net/ipv4/ip_forward
                echo 1 > /proc/sys/net/ipv6/conf/all/forwarding || true

                : "[i] Starting tailscaled router..."
                tailscaled --state=/var/lib/tailscale/tailscaled.state --socket=/var/run/tailscale/tailscaled.sock &
                TAILSCALED_PID=$!

                until [ -S /var/run/tailscale/tailscaled.sock ]; do sleep 1; done

                tailscale up \\
                  --login-server=$HEADSCALE_URL \\
                  --authkey=file:/var/secrets/authkey \\
                  --hostname=${DARWIN_HOST}-gateway \\
                  --advertise-routes=${VIP_NETWORK_CIDR} \\
                  --accept-dns=false \\
                  --ssh \\
                  --reset

                tailscale set --advertise-routes=${VIP_NETWORK_CIDR}

                wait $TAILSCALED_PID\
                """));
    configMap.addDependency(namespace);
    configMap.addJsonPatch(JsonPatch.add("/metadata/labels", Map.of("app", "headscale-gateway")));
    return configMap;
  }

  private ApiObject createLanPolicy(final Construct scope) {
    ApiObject policy =
        new ApiObject(
            scope,
            "ciliuml2announcementpolicy-lan-policy",
            ApiObjectProps.builder()
                .apiVersion("cilium.io/v2alpha1")
                .kind("CiliumL2AnnouncementPolicy")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("lan-policy")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cilium.io|CiliumL2AnnouncementPolicy|default|lan-policy"))
                        .build())
                .build());
    policy.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "interfaces",
                List.of("^lan0$"),
                "loadBalancerIPs",
                true,
                "nodeSelector",
                Map.of(
                    "matchExpressions",
                    List.of(
                        Map.of(
                            "key", "node-role.kubernetes.io/control-plane", "operator", "Exists"))),
                "serviceSelector",
                Map.of("matchLabels", Map.of("io.cilium/lb-ipam-pool", "lan")))));
    return policy;
  }

  private ApiObject createDeploymentHeadscale(
      final Construct scope,
      final String floxImage,
      final ApiObject namespace,
      final ApiObject cmHeadscaleConfig,
      final ApiObject cmConfigInitScript,
      final ApiObject cmAcl,
      final ApiObject cmDerp,
      final ApiObject cmExtraRecords,
      final ApiObject l2Policy) {
    ApiObject deployment =
        new ApiObject(
            scope,
            "deployment-headscale",
            ApiObjectProps.builder()
                .apiVersion("apps/v1")
                .kind("Deployment")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headscale")
                        .namespace(HEADSCALE_NAMESPACE)
                        .labels(Map.of("app", "headscale"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "apps|Deployment|${headscale-namespace}|headscale"))
                        .build())
                .build());
    deployment.addDependency(namespace);
    deployment.addDependency(cmHeadscaleConfig);
    deployment.addDependency(cmConfigInitScript);
    deployment.addDependency(cmAcl);
    deployment.addDependency(cmDerp);
    deployment.addDependency(cmExtraRecords);
    deployment.addDependency(l2Policy);

    final FloxDebugPolicy debugPolicy = ManifestSynthesisContext.current().floxDebugPolicy();
    final FloxShellSidecarProfile shellSidecar =
        new FloxShellSidecarProfile(
            debugPolicy,
            debugPolicy.meshEnabled(),
            "headscale",
            "/root",
            "mesh/headscale-debug",
            "0",
            "0");

    final List<Map<String, Object>> headscaleMounts = new ArrayList<>();
    headscaleMounts.add(
        Map.of(
            "mountPath", "/etc/headscale/config.yaml",
            "name", "config",
            "subPath", "config.yaml"));
    headscaleMounts.add(
        Map.of(
            "mountPath", "/etc/headscale/acl.json",
            "name", "acl",
            "subPath", "acl.json"));
    headscaleMounts.add(
        Map.of(
            "mountPath", "/etc/headscale/derp.yaml",
            "name", "derp",
            "subPath", "derp.yaml"));
    headscaleMounts.add(Map.of("mountPath", "/var/lib/headscale", "name", "data"));
    headscaleMounts.add(Map.of("mountPath", "/var/run/headscale", "name", "run"));
    headscaleMounts.addAll(shellSidecar.extraProdMounts());

    final LinkedHashMap<String, Object> headscaleContainer = new LinkedHashMap<>();
    headscaleContainer.put("name", "headscale");
    headscaleContainer.put("image", floxImage);
    headscaleContainer.put(
        "command", List.of("flox", "activate", "--dir", "/root", "--", "headscale", "serve"));
    headscaleContainer.put(
        "livenessProbe",
        Map.of(
            "httpGet",
            Map.of("path", "/health", "port", "http"),
            "initialDelaySeconds",
            30,
            "periodSeconds",
            10));
    headscaleContainer.put(
        "readinessProbe",
        Map.of(
            "httpGet",
            Map.of("path", "/health", "port", "http"),
            "initialDelaySeconds",
            10,
            "periodSeconds",
            5));
    headscaleContainer.put(
        "ports",
        List.of(
            Map.of("containerPort", 8080, "name", "http", "protocol", "TCP"),
            Map.of("containerPort", 9090, "name", "metrics", "protocol", "TCP")));
    headscaleContainer.put(
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
    headscaleContainer.put("volumeMounts", List.copyOf(headscaleMounts));

    final List<Object> containers = new ArrayList<>();
    containers.add(headscaleContainer);
    shellSidecar.sidecar(headscaleMounts).ifPresent(containers::add);

    final List<Object> volumes = new ArrayList<>();
    volumes.add(
        Map.of(
            "name",
            "config-source",
            "configMap",
            Map.of("name", MeshRefs.HEADSCALE_CONFIG_CONFIGMAP.name())));
    volumes.add(Map.of("name", "config", "emptyDir", Map.of()));
    volumes.add(
        Map.of(
            "name",
            "config-init-script",
            "configMap",
            Map.of("defaultMode", 493, "name", "headscale-config-init-script")));
    volumes.add(
        Map.of(
            "name",
            "extra-records-source",
            "configMap",
            Map.of("name", "headscale-extra-records")));
    volumes.add(Map.of("name", "acl", "configMap", Map.of("name", "headscale-acl")));
    volumes.add(Map.of("name", "derp", "configMap", Map.of("name", "headscale-derp")));
    volumes.add(Map.of("name", "data", "emptyDir", Map.of()));
    volumes.add(Map.of("name", "run", "emptyDir", Map.of()));
    volumes.addAll(shellSidecar.extraVolumes());

    final LinkedHashMap<String, String> annotations = new LinkedHashMap<>();
    annotations.put(
        FloxAnnotation.ENVIRONMENT.forContainer("headscale"),
        debugPolicy.resolveMeshEnvironment("mesh/headscale", "mesh/headscale-debug"));
    // The config-init INIT container also runs `flox activate` (it renders the
    // headscale config), so it must opt into flox injection too — the NRI plugin
    // only puts flox on PATH for containers named by a flox.seedmatic.io/environment.<c>
    // annotation; without this the init container fails "flox not found in $PATH".
    annotations.put(
        FloxAnnotation.ENVIRONMENT.forContainer("config-init"),
        debugPolicy.resolveMeshEnvironment("mesh/headscale", "mesh/headscale-debug"));
    annotations.putAll(shellSidecar.sidecarAnnotations());

    final LinkedHashMap<String, Object> headscalePodSpec = new LinkedHashMap<>();
    headscalePodSpec.put("automountServiceAccountToken", false);
    headscalePodSpec.put("containers", List.copyOf(containers));
    headscalePodSpec.put(
        "initContainers",
        List.of(
            Map.of(
                "name",
                "config-init",
                "image",
                floxImage,
                "command",
                List.of("flox", "activate", "--dir", "/root", "--", "/scripts/config-init.sh"),
                "volumeMounts",
                List.of(
                    Map.of("mountPath", "/scripts", "name", "config-init-script"),
                    Map.of(
                        "mountPath", "/config-source", "name", "config-source", "readOnly", true),
                    Map.of("mountPath", "/config", "name", "config"),
                    Map.of(
                        "mountPath",
                        "/extra-records-source",
                        "name",
                        "extra-records-source",
                        "readOnly",
                        true),
                    Map.of("mountPath", "/var/lib/headscale", "name", "data")))));
    headscalePodSpec.put("nodeSelector", Map.of("node-role.kubernetes.io/control-plane", "true"));
    if (shellSidecar.shareProcessNamespace()) {
      headscalePodSpec.put("shareProcessNamespace", true);
    }
    headscalePodSpec.put("volumes", List.copyOf(volumes));

    deployment.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "replicas",
                1,
                "selector",
                Map.of("matchLabels", Map.of("app", "headscale")),
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        packageProfile.templateAnnotations(Map.copyOf(annotations)),
                        "labels",
                        Map.of("app", "headscale")),
                    "spec",
                    Map.copyOf(headscalePodSpec)))));
    return deployment;
  }

  private ApiObject createServiceHeadscale(
      final Construct scope,
      final ApiObject namespace,
      final ApiObject deployment,
      final String clusterName) {
    ApiObject service =
        new ApiObject(
            scope,
            "service-headscale",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Service")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headscale")
                        .namespace(HEADSCALE_NAMESPACE)
                        .labels(Map.of("app", "headscale", "io.cilium/lb-ipam-pool", "lan"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Service|${headscale-namespace}|headscale",
                                Map.of(
                                    "io.cilium/lb-ipam-ips",
                                    "192.168.1.193",
                                    "tailscale.com/expose",
                                    "true",
                                    "tailscale.com/hostname",
                                    clusterName + "-headscale")))
                        .build())
                .build());
    service.addDependency(namespace);
    service.addDependency(deployment);
    service.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "ports",
                List.of(
                    Map.of("name", "http", "port", 8080, "protocol", "TCP", "targetPort", 8080),
                    Map.of("name", "metrics", "port", 9090, "protocol", "TCP", "targetPort", 9090)),
                "selector",
                Map.of("app", "headscale"),
                "type",
                "LoadBalancer")));
    return service;
  }

  private ApiObject createBootstrapJob(
      final Construct scope,
      final String floxImage,
      final ApiObject namespace,
      final ApiObject serviceAccount,
      final ApiObject cmHeadscaleEnv,
      final ApiObject cmScript,
      final ApiObject deployment) {
    ApiObject job =
        new ApiObject(
            scope,
            "job-headscale-bootstrap",
            ApiObjectProps.builder()
                .apiVersion("batch/v1")
                .kind("Job")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headscale-bootstrap")
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "batch|Job|${headscale-namespace}|headscale-bootstrap"))
                        .build())
                .build());
    job.addDependency(namespace);
    job.addDependency(serviceAccount);
    job.addDependency(cmHeadscaleEnv);
    job.addDependency(cmScript);
    job.addDependency(deployment);
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
                                FloxAnnotation.ENVIRONMENT.forContainer("bootstrap"),
                                "mesh/headscale"))),
                    "spec",
                    Map.of(
                        "containers",
                        List.of(
                            Map.of(
                                "name",
                                "bootstrap",
                                "image",
                                floxImage,
                                "command",
                                List.of(
                                    "flox",
                                    "activate",
                                    "--dir",
                                    "/root",
                                    "--",
                                    "/scripts/bootstrap.sh"),
                                "envFrom",
                                List.of(
                                    Map.of(
                                        "configMapRef",
                                        Map.of("name", MeshRefs.HEADSCALE_ENV_CONFIGMAP.name()))),
                                "resources",
                                Map.of(
                                    "limits",
                                    Map.of(
                                        "cpu",
                                        "250m",
                                        "ephemeral-storage",
                                        "256Mi",
                                        "memory",
                                        "256Mi"),
                                    "requests",
                                    Map.of(
                                        "cpu",
                                        "50m",
                                        "ephemeral-storage",
                                        "64Mi",
                                        "memory",
                                        "64Mi")),
                                "volumeMounts",
                                List.of(
                                    Map.of(
                                        "mountPath",
                                        "/scripts",
                                        "name",
                                        "bootstrap-script",
                                        "readOnly",
                                        true)))),
                        "restartPolicy",
                        "OnFailure",
                        "serviceAccountName",
                        "headscale-bootstrap",
                        "volumes",
                        List.of(
                            Map.of(
                                "name",
                                "bootstrap-script",
                                "configMap",
                                Map.of(
                                    "defaultMode", 493, "name", "headscale-bootstrap-script"))))),
                "ttlSecondsAfterFinished",
                300)));
    return job;
  }

  private void createDeploymentGateway(
      final Construct scope,
      final String floxImage,
      final ApiObject namespace,
      final ApiObject serviceAccount,
      final ApiObject cmHeadscaleEnv,
      final ApiObject cmScript,
      final ApiObject bootstrapJob) {
    ApiObject deployment =
        new ApiObject(
            scope,
            "deployment-headscale-gateway",
            ApiObjectProps.builder()
                .apiVersion("apps/v1")
                .kind("Deployment")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headscale-gateway")
                        .namespace(HEADSCALE_NAMESPACE)
                        .labels(Map.of("app", "headscale-gateway"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "apps|Deployment|${headscale-namespace}|headscale-gateway"))
                        .build())
                .build());
    deployment.addDependency(namespace);
    deployment.addDependency(serviceAccount);
    deployment.addDependency(cmHeadscaleEnv);
    deployment.addDependency(cmScript);
    deployment.addDependency(bootstrapJob);

    final FloxDebugPolicy gatewayDebugPolicy = ManifestSynthesisContext.current().floxDebugPolicy();
    final FloxShellSidecarProfile gatewayShellSidecar =
        new FloxShellSidecarProfile(
            gatewayDebugPolicy,
            gatewayDebugPolicy.meshEnabled(),
            "tailscale",
            "/root",
            "mesh/tailscale-debug",
            "0",
            "0");

    final List<Map<String, Object>> gatewayMounts = new ArrayList<>();
    gatewayMounts.add(Map.of("mountPath", "/dev/net/tun", "name", "dev-net-tun"));
    gatewayMounts.add(Map.of("mountPath", "/var/lib/tailscale", "name", "tailscale-state"));
    gatewayMounts.add(Map.of("mountPath", "/var/run/tailscale", "name", "tailscale-socket"));
    gatewayMounts.add(
        Map.of(
            "mountPath", "/var/secrets",
            "name", "authkey",
            "readOnly", true));
    gatewayMounts.add(
        Map.of(
            "mountPath", "/scripts",
            "name", "gateway-script",
            "readOnly", true));
    gatewayMounts.addAll(gatewayShellSidecar.extraProdMounts());

    final LinkedHashMap<String, Object> gatewayContainer = new LinkedHashMap<>();
    gatewayContainer.put("name", "tailscale");
    gatewayContainer.put("image", floxImage);
    gatewayContainer.put(
        "command", List.of("flox", "activate", "--dir", "/root", "--", "/scripts/gateway.sh"));
    gatewayContainer.put(
        "env",
        List.of(
            Map.of(
                "name",
                "TS_AUTHKEY",
                "valueFrom",
                Map.of(
                    "secretKeyRef",
                    Map.of(
                        "key", "authkey", "name", MeshRefs.HEADSCALE_CLIENT_AUTH_SECRET.name())))));
    gatewayContainer.put(
        "envFrom",
        List.of(Map.of("configMapRef", Map.of("name", MeshRefs.HEADSCALE_ENV_CONFIGMAP.name()))));
    gatewayContainer.put(
        "resources",
        Map.of(
            "limits",
            Map.of(
                "cpu", "200m",
                "ephemeral-storage", "500Mi",
                "memory", "256Mi"),
            "requests",
            Map.of(
                "cpu", "50m",
                "ephemeral-storage", "100Mi",
                "memory", "64Mi")));
    gatewayContainer.put(
        "securityContext",
        Map.of("capabilities", Map.of("add", List.of("NET_ADMIN", "NET_RAW")), "privileged", true));
    gatewayContainer.put("volumeMounts", List.copyOf(gatewayMounts));

    final List<Object> gatewayContainers = new ArrayList<>();
    gatewayContainers.add(gatewayContainer);
    gatewayShellSidecar.sidecar(gatewayMounts).ifPresent(gatewayContainers::add);

    final List<Object> gatewayVolumes = new ArrayList<>();
    gatewayVolumes.add(
        Map.of(
            "name",
            "dev-net-tun",
            "hostPath",
            Map.of("path", "/dev/net/tun", "type", "CharDevice")));
    gatewayVolumes.add(
        Map.of(
            "name",
            "tailscale-state",
            "hostPath",
            Map.of("path", "/var/lib/tailscale", "type", "DirectoryOrCreate")));
    gatewayVolumes.add(Map.of("name", "tailscale-socket", "emptyDir", Map.of()));
    gatewayVolumes.add(
        Map.of(
            "name",
            "gateway-script",
            "configMap",
            Map.of("defaultMode", 493, "name", "headscale-gateway-script")));
    gatewayVolumes.add(
        Map.of(
            "name",
            "authkey",
            "secret",
            Map.of(
                "items",
                List.of(Map.of("key", "authkey", "path", "authkey")),
                "secretName",
                MeshRefs.HEADSCALE_CLIENT_AUTH_SECRET.name())));
    gatewayVolumes.addAll(gatewayShellSidecar.extraVolumes());

    final LinkedHashMap<String, String> gatewayAnnotations = new LinkedHashMap<>();
    gatewayAnnotations.put(
        FloxAnnotation.ENVIRONMENT.forContainer("tailscale"),
        gatewayDebugPolicy.resolveMeshEnvironment("mesh/tailscale", "mesh/tailscale-debug"));
    gatewayAnnotations.putAll(gatewayShellSidecar.sidecarAnnotations());

    final LinkedHashMap<String, Object> gatewayPodSpec = new LinkedHashMap<>();
    gatewayPodSpec.put("automountServiceAccountToken", false);
    gatewayPodSpec.put("containers", List.copyOf(gatewayContainers));
    gatewayPodSpec.put("dnsPolicy", "ClusterFirstWithHostNet");
    gatewayPodSpec.put("hostNetwork", true);
    gatewayPodSpec.put("nodeSelector", Map.of("node-role.kubernetes.io/control-plane", "true"));
    gatewayPodSpec.put("serviceAccountName", "headscale-gateway");
    if (gatewayShellSidecar.shareProcessNamespace()) {
      gatewayPodSpec.put("shareProcessNamespace", true);
    }
    gatewayPodSpec.put(
        "tolerations",
        List.of(
            Map.of(
                "effect",
                "NoSchedule",
                "key",
                "node-role.kubernetes.io/control-plane",
                "operator",
                "Exists")));
    gatewayPodSpec.put("volumes", List.copyOf(gatewayVolumes));

    deployment.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "replicas",
                1,
                // hostNetwork singleton: a surged rolling-update pod can't bind the host ports
                // (tailscaled) while the old one holds them (single-node deadlock). Recreate tears
                // the old pod down first.
                "strategy",
                Map.of("type", "Recreate"),
                "selector",
                Map.of("matchLabels", Map.of("app", "headscale-gateway")),
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        packageProfile.templateAnnotations(Map.copyOf(gatewayAnnotations)),
                        "labels",
                        Map.of("app", "headscale-gateway")),
                    "spec",
                    Map.copyOf(gatewayPodSpec)))));
  }

  private void createDaemonsetClient(
      final Construct scope,
      final String floxImage,
      final ApiObject namespace,
      final ApiObject serviceAccount,
      final ApiObject cmHeadscaleEnv,
      final ApiObject cmScripts,
      final ApiObject bootstrapJob,
      final ApiObject serviceHeadscale) {
    ApiObject daemonSet =
        new ApiObject(
            scope,
            "daemonset-headscale-client",
            ApiObjectProps.builder()
                .apiVersion("apps/v1")
                .kind("DaemonSet")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("headscale-client")
                        .namespace(HEADSCALE_NAMESPACE)
                        .labels(Map.of("app", "headscale-client"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "apps|DaemonSet|${headscale-namespace}|headscale-client"))
                        .build())
                .build());
    daemonSet.addDependency(namespace);
    daemonSet.addDependency(serviceAccount);
    daemonSet.addDependency(cmHeadscaleEnv);
    daemonSet.addDependency(cmScripts);
    daemonSet.addDependency(bootstrapJob);
    daemonSet.addDependency(serviceHeadscale);

    final FloxDebugPolicy clientDebugPolicy = ManifestSynthesisContext.current().floxDebugPolicy();
    final FloxShellSidecarProfile clientShellSidecar =
        new FloxShellSidecarProfile(
            clientDebugPolicy,
            clientDebugPolicy.meshEnabled(),
            "tailscale",
            "/root",
            "mesh/tailscale-debug",
            "0",
            "0");

    final List<Map<String, Object>> clientMounts = new ArrayList<>();
    clientMounts.add(Map.of("mountPath", "/dev/net/tun", "name", "dev-net-tun"));
    clientMounts.add(Map.of("mountPath", "/var/lib/tailscale", "name", "tailscale-state"));
    clientMounts.add(Map.of("mountPath", "/var/run/tailscale", "name", "tailscale-socket"));
    clientMounts.add(
        Map.of(
            "mountPath", "/var/secrets",
            "name", "authkey",
            "readOnly", true));
    clientMounts.add(
        Map.of(
            "mountPath", "/scripts",
            "name", "client-scripts",
            "readOnly", true));
    clientMounts.addAll(clientShellSidecar.extraProdMounts());

    final LinkedHashMap<String, Object> clientContainer = new LinkedHashMap<>();
    clientContainer.put("name", "tailscale");
    clientContainer.put("image", floxImage);
    clientContainer.put(
        "command",
        List.of("flox", "activate", "--dir", "/root", "--", "/scripts/tailscale-client.sh"));
    clientContainer.put(
        "env",
        List.of(
            Map.of(
                "name",
                "RKE2_NODENAME",
                "valueFrom",
                Map.of("fieldRef", Map.of("fieldPath", "spec.nodeName"))),
            Map.of(
                "name",
                "TS_AUTHKEY",
                "valueFrom",
                Map.of(
                    "secretKeyRef",
                    Map.of(
                        "key", "authkey", "name", MeshRefs.HEADSCALE_CLIENT_AUTH_SECRET.name()))),
            Map.of("name", "TS_STATE_DIR", "value", "/var/lib/tailscale"),
            Map.of("name", "TS_SOCKET", "value", "/var/run/tailscale/tailscaled.sock"),
            Map.of("name", "TS_USERSPACE", "value", "true"),
            Map.of("name", "TS_KUBE_SECRET", "value", "")));
    clientContainer.put(
        "envFrom",
        List.of(Map.of("configMapRef", Map.of("name", MeshRefs.HEADSCALE_ENV_CONFIGMAP.name()))));
    clientContainer.put(
        "resources",
        Map.of(
            "limits",
            Map.of(
                "cpu", "200m",
                "ephemeral-storage", "256Mi",
                "memory", "256Mi"),
            "requests",
            Map.of(
                "cpu", "50m",
                "ephemeral-storage", "64Mi",
                "memory", "64Mi")));
    clientContainer.put(
        "securityContext",
        Map.of("capabilities", Map.of("add", List.of("NET_ADMIN", "NET_RAW")), "privileged", true));
    clientContainer.put("volumeMounts", List.copyOf(clientMounts));

    final List<Object> clientContainers = new ArrayList<>();
    clientContainers.add(clientContainer);
    clientShellSidecar.sidecar(clientMounts).ifPresent(clientContainers::add);

    final List<Object> clientVolumes = new ArrayList<>();
    clientVolumes.add(
        Map.of(
            "name",
            "dev-net-tun",
            "hostPath",
            Map.of("path", "/dev/net/tun", "type", "CharDevice")));
    clientVolumes.add(
        Map.of(
            "name",
            "tailscale-state",
            "hostPath",
            Map.of("path", "/var/lib/tailscale", "type", "DirectoryOrCreate")));
    clientVolumes.add(Map.of("name", "tailscale-socket", "emptyDir", Map.of()));
    clientVolumes.add(
        Map.of(
            "name",
            "client-scripts",
            "configMap",
            Map.of("defaultMode", 493, "name", "headscale-client-scripts")));
    clientVolumes.add(
        Map.of(
            "name",
            "authkey",
            "secret",
            Map.of(
                "items",
                List.of(Map.of("key", "authkey", "path", "authkey")),
                "secretName",
                MeshRefs.HEADSCALE_CLIENT_AUTH_SECRET.name())));
    clientVolumes.addAll(clientShellSidecar.extraVolumes());

    final LinkedHashMap<String, String> clientAnnotations = new LinkedHashMap<>();
    clientAnnotations.put(
        FloxAnnotation.ENVIRONMENT.forContainer("tailscale"),
        clientDebugPolicy.resolveMeshEnvironment("mesh/tailscale", "mesh/tailscale-debug"));
    // The wait-for-headscale INIT container runs `flox activate` + `kubectl wait`, so it
    // needs flox injection AND an env carrying kubectl — the headscale env (same as the
    // bootstrap script), NOT the tailscale env its main container uses.
    clientAnnotations.put(
        FloxAnnotation.ENVIRONMENT.forContainer("wait-for-headscale"),
        clientDebugPolicy.resolveMeshEnvironment("mesh/headscale", "mesh/headscale-debug"));
    clientAnnotations.putAll(clientShellSidecar.sidecarAnnotations());

    daemonSet.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "selector",
                Map.of("matchLabels", Map.of("app", "headscale-client")),
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        packageProfile.templateAnnotations(Map.copyOf(clientAnnotations)),
                        "labels",
                        Map.of("app", "headscale-client")),
                    "spec",
                    Map.of(
                        "containers",
                        List.copyOf(clientContainers),
                        "dnsPolicy",
                        "ClusterFirstWithHostNet",
                        "hostNetwork",
                        true,
                        "hostPID",
                        true,
                        "initContainers",
                        List.of(
                            Map.of(
                                "name",
                                "wait-for-headscale",
                                "image",
                                floxImage,
                                "command",
                                List.of(
                                    "flox",
                                    "activate",
                                    "--dir",
                                    "/root",
                                    "--",
                                    "/scripts/wait-for-headscale.sh"),
                                "envFrom",
                                List.of(
                                    Map.of(
                                        "configMapRef",
                                        Map.of("name", MeshRefs.HEADSCALE_ENV_CONFIGMAP.name()))),
                                "volumeMounts",
                                List.of(
                                    Map.of(
                                        "mountPath",
                                        "/scripts",
                                        "name",
                                        "client-scripts",
                                        "readOnly",
                                        true)))),
                        "nodeSelector",
                        Map.of("node-role.kubernetes.io/control-plane", "true"),
                        "serviceAccountName",
                        "headscale-client",
                        "tolerations",
                        List.of(
                            Map.of(
                                "effect",
                                "NoSchedule",
                                "key",
                                "node-role.kubernetes.io/control-plane",
                                "operator",
                                "Exists")),
                        "volumes",
                        List.copyOf(clientVolumes))))));
  }

  private ApiObject configMapWithData(
      final Construct scope,
      final String name,
      final String upstream,
      final Map<String, String> data) {
    ApiObject configMap =
        new ApiObject(
            scope,
            "configmap-" + name,
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(HEADSCALE_NAMESPACE)
                        .annotations(packageProfile.packageAnnotations(upstream))
                        .build())
                .build());
    configMap.addJsonPatch(JsonPatch.add("/data", data));
    return configMap;
  }
}
