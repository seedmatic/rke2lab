// @codebase
package io.seedmatic.rke2lab.manifests.units.runtime.rke2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ClusterRole;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Renders the RKE2 boot config ({@code config.yaml.d} fragments) for a MANAGEMENT render — the
 * management cluster serves the config of everything it manages. It emits fragments for the SUBJECT
 * (only when the subject is itself a management cluster — a workload's config lives on its
 * manager's branch, not its own) PLUS every {@link ManifestSynthesisContext#workloadTargets()
 * workload target}. A workload render produces nothing here.
 *
 * <p>The fragments are the CONTROL-PLANE pool's config: the only consumers of these branch
 * fragments are control-plane nodes. Workers are bootstrap-injected by CAPRKE2 (their config rides
 * the {@code RKE2ConfigTemplate}), never {@code install-rke2-config}, so there is no cross-pool
 * "common" set to factor out — everything rendered here is server config for the {@code
 * control-node} pool. Per cluster the fragments land under {@code
 * rke2-config/<cluster>/control-node/} (the branch subtree {@code install-rke2-config} roots its
 * fetch at) and are stamped into namespace {@code rke2lab-<cluster>} (the SAME namespace the
 * cluster-api units create + own — this unit references it, does not create a second one). Dropping
 * {@code LOCAL_CONFIG} makes them REAL Flux-applied ConfigMaps, so seed-incluster can read the
 * visible resource to bootstrap-inject a managed cluster.
 *
 * <p>Per-node facts are NOT baked here — {@code node-ip}, {@code node-name}, {@code advertise-
 * address} are the node's own vmnet address / hostname, resolved on-node by the nixos oneshots
 * ({@code rke2lab-node-labels}/{@code -provider-id}/{@code -node-ip}), never rendered per node. The
 * rke2 join token is NOT rendered either — CAPRKE2's bootstrap provider owns it.
 */
public final class RuntimeRke2ConfigManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.RUNTIME + "/rke2-config";

  /** The one control-plane pool; the branch subtree + the config namespace both key on it. */
  private static final String CONTROL_NODE_POOL = "control-node";

  private static final ObjectMapper YAML_SCALAR_SERIALIZER = createYamlScalarSerializer();

  public RuntimeRke2ConfigManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final ManifestSynthesisContext synth = ManifestSynthesisContext.current();
    // The clusters this management render serves config for: the subject IFF it is a management
    // cluster (a workload's config lives on its manager's branch), plus every workload target.
    final Set<String> clusters = new LinkedHashSet<>();
    final String subject =
        synth.bootstrapIdentity().clusterNameOrDefault(DefaultNodeEnvContext.DEFAULT_CLUSTER_NAME);
    if (ClusterRole.of(subject) == ClusterRole.MGMT) {
      clusters.add(subject);
    }
    synth.workloadTargets().forEach(target -> clusters.add(target.clusterName()));
    clusters.forEach(cluster -> renderControlNodeConfig(scope, cluster));
  }

  private void renderControlNodeConfig(final Construct scope, final String cluster) {
    final ClusterNetworkBlueprint blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster(cluster)
            .node("master")
            .deriveRecipeModel()
            .build();
    final String namespace = "rke2lab-" + cluster;
    final PackageMetadataProfile profile =
        new PackageMetadataProfile(
            ManifestDomainCatalog.RUNTIME, "rke2-config/" + cluster + "/" + CONTROL_NODE_POOL);

    createConfigMap(
        scope,
        cluster,
        namespace,
        profile,
        "cidrs.yaml",
        "Network CIDRs fragment",
        orderedMap(
            entry("kube-controller-manager-arg", List.of("node-cidr-mask-size-ipv4=24")),
            entry("service-cidr", blueprint.serviceCidrDualStack()),
            entry("cluster-cidr", blueprint.podCidrDualStack())));
    createConfigMap(
        scope,
        cluster,
        namespace,
        profile,
        "core.yaml",
        "Core RKE2 settings",
        // cni is NOT set here: it is STATIC (cilium on every node) and lives in the node-base's
        // `services.rke2.cni = "cilium"` (nixos/rke2.nix). rke2 CONCATENATES list-valued flags like
        // --cni across config.yaml + config.yaml.d, so setting it in both produced `[cilium,
        // cilium]`
        // — a fatal "may only provide multiple values if multus is the first value".
        orderedMap(entry("write-kubeconfig-mode", "0640"), entry("bind-address", "0.0.0.0")));
    createConfigMap(
        scope,
        cluster,
        namespace,
        profile,
        "debug.yaml",
        "Enable RKE2 debug logging for manifest watcher",
        orderedMap(entry("v", "4"), entry("debug", "false")));
    createConfigMap(
        scope,
        cluster,
        namespace,
        profile,
        "disable.yaml",
        "Disable list fragment",
        Map.of(
            "disable",
            List.of(
                // Keep RKE2's snapshot controller + validation webhook disabled: the openebs-zfs
                // chart ships its own snapshot-controller sidecar, so running RKE2's alongside
                // would
                // mean two controllers reconciling the same VolumeSnapshot CRs. rke2-snapshot-
                // controller-crd stays ENABLED (not listed) so the upstream VolumeSnapshot CRDs
                // exist
                // — the openebs-zfs snapshot-controller v8 hard-fails without them.
                "rke2-snapshot-controller",
                "rke2-snapshot-validation-webhook",
                "rke2-ingress-nginx")));
    createConfigMap(
        scope,
        cluster,
        namespace,
        profile,
        "etcd-metrics.yaml",
        "Etcd metrics fragment",
        Map.of("etcd-expose-metrics", true));
    createConfigMap(
        scope,
        cluster,
        namespace,
        profile,
        "etcd.yaml",
        "Etcd settings fragment",
        // node-name dropped: it is the node's own hostname (a per-node fact) — rke2 derives it on
        // the node; not rendered here.
        orderedMap(entry("with-node-id", false), entry("etcd-expose-metrics", false)));
    createConfigMap(
        scope,
        cluster,
        namespace,
        profile,
        "tls-san.yaml",
        "TLS SAN fragment (all-nodes superset)",
        Map.of("tls-san", tlsSanSuperset(cluster, blueprint)));
  }

  /**
   * The cert's SAN set, a cluster-wide SUPERSET valid for every control-plane node regardless of
   * which one this render's node is: the VIP (kube-vip binds the apiserver here; CAPI's
   * clustercache dials it), every canonical node's mDNS {@code .local} FQDN (kubectl/operator dial
   * {@code <cluster>-<node>.local}; rke2 auto-adds the bare hostname but NOT the FQDN), the
   * node-network gateway + LAN host address, and the loopback set. rke2 auto-adds each node's own
   * IP.
   */
  private static List<Object> tlsSanSuperset(
      final String cluster, final ClusterNetworkBlueprint blueprint) {
    final LinkedHashSet<Object> sans = new LinkedHashSet<>();
    sans.add("localhost");
    sans.add("gateway");
    sans.add("0.0.0.0");
    sans.add("127.0.0.1");
    sans.add(blueprint.vip().vipHostInetaddr().getHostAddress());
    sans.add(blueprint.nodeNetwork().nodeGatewayInetaddr().getHostAddress());
    sans.add(blueprint.lan().hostInetaddr().getHostAddress());
    for (final String node : ClusterNetworkBlueprint.CANONICAL_NODE_NAMES) {
      final ClusterNetworkBlueprint per =
          ClusterNetworkBlueprint.builder().cluster(cluster).node(node).deriveRecipeModel().build();
      sans.add(per.names().nodeMdnsFqdn());
    }
    return List.copyOf(sans);
  }

  private void createConfigMap(
      final Construct scope,
      final String cluster,
      final String namespace,
      final PackageMetadataProfile profile,
      final String name,
      final String description,
      final Map<String, Object> data) {
    // One RKE2_CONFIG ConfigMap fragment (payload under /data). RKE2_CONFIG marks it for the boot-
    // time install-rke2-config app; NO LOCAL_CONFIG, so Flux applies it as a real ConfigMap that
    // seed-incluster can read to bootstrap-inject a managed cluster. metadata.name doubles as the
    // config.yaml.d filename install-rke2-config writes; namespace rke2lab-<cluster> scopes it.
    final ApiObject fragment =
        new ApiObject(
            scope,
            "configmap-rke2-" + cluster + "-" + name.replace('.', '-'),
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(namespace)
                        .annotations(
                            profile.packageAnnotations(
                                "|ConfigMap|" + namespace + "|" + name,
                                Map.of(
                                    ManifestAnnotation.RKE2_CONFIG.key(),
                                    "true",
                                    "description.kpt.dev",
                                    description)))
                        .build())
                .build());

    fragment.addJsonPatch(JsonPatch.add("/data", toConfigMapData(data)));
  }

  private static Map<String, String> toConfigMapData(final Map<String, Object> data) {
    final LinkedHashMap<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : data.entrySet()) {
      out.put(entry.getKey(), yamlScalarString(entry.getValue()));
    }
    return Collections.unmodifiableMap(out);
  }

  private static String yamlScalarString(final Object value) {
    try {
      final String dumped = YAML_SCALAR_SERIALIZER.writeValueAsString(value);
      return dumped.endsWith("\n") ? dumped.substring(0, dumped.length() - 1) : dumped;
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to render YAML scalar for value: " + value, ex);
    }
  }

  private static ObjectMapper createYamlScalarSerializer() {
    final YAMLFactory factory =
        YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .disable(YAMLGenerator.Feature.SPLIT_LINES)
            .build();
    return new ObjectMapper(factory);
  }

  @SafeVarargs
  private static Map<String, Object> orderedMap(final Map.Entry<String, Object>... entries) {
    final LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : entries) {
      out.put(entry.getKey(), entry.getValue());
    }
    return Map.copyOf(out);
  }

  private static Map.Entry<String, Object> entry(final String key, final Object value) {
    return Map.entry(key, value);
  }
}
