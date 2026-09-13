// @codebase
package io.seedmatic.rke2lab.manifests.units.runtime.rke2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.node.NodeEnvContext;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.contract.profiles.NetworkTopology;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class RuntimeRke2ConfigManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.RUNTIME + "/rke2-config";

  private static final ObjectMapper YAML_SCALAR_SERIALIZER = createYamlScalarSerializer();

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("runtime", "rke2-config");

  public RuntimeRke2ConfigManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final NodeEnvContext nodeEnvContext = context.nodeEnvContext();
    final BootstrapIdentity id = nodeEnvContext.bootstrapIdentity();
    final NetworkTopology net = nodeEnvContext.networkTopology();

    createConfigMap(
        scope,
        "advertise-address.yaml",
        "Advertise address fragment",
        "|ConfigMap|default|rke2-advertise-address",
        Map.of("advertise-address", net.nodeHostInetAddr()));
    createConfigMap(
        scope,
        "cidrs.yaml",
        "Network CIDRs fragment",
        "|ConfigMap|default|rke2-cidrs",
        orderedMap(
            entry("kube-controller-manager-arg", List.of("node-cidr-mask-size-ipv4=24")),
            entry("service-cidr", net.clusterServiceCidr()),
            entry("cluster-cidr", net.clusterPodCidr())));
    createConfigMap(
        scope,
        "core.yaml",
        "Core RKE2 settings",
        "|ConfigMap|default|rke2-core",
        // cni is NOT set here: it is STATIC (cilium on every node) and lives in the node-base's
        // `services.rke2.cni = "cilium"` (nixos/rke2.nix), which writes it to config.yaml. rke2
        // merges
        // config.yaml + config.yaml.d and CONCATENATES list-valued flags like --cni, so setting it
        // in
        // both produced `[cilium, cilium]` — a fatal "may only provide multiple values if multus is
        // the first value". Per-cluster config belongs on the branch; static config stays
        // node-base.
        orderedMap(entry("write-kubeconfig-mode", "0640"), entry("bind-address", "0.0.0.0")));
    createConfigMap(
        scope,
        "debug.yaml",
        "Enable RKE2 debug logging for manifest watcher",
        "|ConfigMap|default|debug",
        orderedMap(entry("v", "4"), entry("debug", "false")));
    createConfigMap(
        scope,
        "disable.yaml",
        "Disable list fragment",
        "|ConfigMap|default|rke2-disable",
        Map.of(
            "disable",
            List.of(
                // Keep RKE2's snapshot controller + validation webhook disabled.
                // The openebs-zfs chart already ships its own snapshot-controller
                // sidecar in its localpv-controller deployment; running RKE2's
                // alongside would mean two controllers reconciling the same
                // VolumeSnapshot CRs.
                "rke2-snapshot-controller",
                "rke2-snapshot-validation-webhook",
                // rke2-snapshot-controller-crd is *enabled* (i.e. not in this
                // list) so the upstream VolumeSnapshot{,Content,Class} CRDs
                // exist on the cluster. Without them the openebs-zfs-bundled
                // snapshot-controller v8 hard-fails at startup ("Exiting due
                // to failure to ensure CRDs exist"). The CRDs themselves are
                // pure data — they don't bring a controller of their own —
                // so installing them is the minimum-viable fix that makes the
                // openebs-zfs controller pod healthy without introducing a
                // second snapshot-controller.
                "rke2-ingress-nginx")));
    createConfigMap(
        scope,
        "etcd-metrics.yaml",
        "Etcd metrics fragment",
        "|ConfigMap|default|rke2-etcd-metrics",
        Map.of("etcd-expose-metrics", true));
    createConfigMap(
        scope,
        "etcd.yaml",
        "Etcd settings fragment",
        "|ConfigMap|default|rke2-etcd",
        orderedMap(
            entry("with-node-id", false),
            entry("node-name", id.nodeHostname()),
            entry("etcd-expose-metrics", false)));
    createConfigMap(
        scope,
        "node-inetaddr.yaml",
        "Node IP fragment",
        "|ConfigMap|default|rke2-node-inetaddr",
        // Dual-stack node-ip (v4,v6): cluster-cidr + service-cidr are dual-stack, and rke2 rejects
        // a
        // node-ip that does not share their IP version(s) ("must share the same IP version"). The
        // v6
        // is the node's vmnet ULA (embedded-v4, fd96:…:{cc}20::<ipv4>) delivered by the vmnet
        // bridge's
        // stateful DHCPv6 reservation (GrowNetworkResolver) — a real address the node holds, so
        // kube-
        // let can bind it.
        Map.of("node-ip", net.nodeHostInetAddr() + "," + net.nodeHostInet6Addr()));
    // Node labels are NOT delivered here: kubelet applies --node-labels only at the node's first
    // registration, so a fragment glob'd from the cluster post-join is ignored. They are written
    // at boot before rke2-server by the nixos oneshot rke2lab-node-labels (nixos/rke2.nix).
    createConfigMap(
        scope,
        "tls-san.yaml",
        "TLS SAN fragment",
        "|ConfigMap|default|rke2-tls-san",
        Map.of(
            "tls-san",
            List.of(
                "localhost",
                "gateway",
                "0.0.0.0",
                "127.0.0.1",
                // The node's mDNS FQDN (SOT: NamePlan.nodeMdnsFqdn, not re-concatenated): kubectl
                // and the operator dial the apiserver at <cluster>-<node>.local:6443 (avahi-
                // published, ./host-access.nix), so the serving cert MUST carry it or `x509:
                // certificate is valid for …, not <cluster>-<node>.local`. rke2 auto-adds the bare
                // hostname but NOT the .local FQDN.
                id.nodeMdnsFqdn(),
                // The kube-vip VIP — where kube-vip binds the apiserver and what CAPI's
                // clustercache
                // (+ any VIP-endpoint kubeconfig) dials, so the serving cert MUST be valid for it,
                // or
                // `x509: certificate is valid for …, not 10.80.<w>.10` → RemoteConnectionProbe
                // fails +
                // ControlPlaneInitialized stalls. REPLACES the VIP subnet GATEWAY (.1) that sat
                // here
                // by mistake: a router is never an apiserver endpoint, so it had no place in the
                // SAN.
                net.vipHostInetAddr(),
                net.nodeNetworkGatewayAddr(),
                net.nodeHostInetAddr(),
                net.lanHostInetAddr())));
    // The rke2 join token is SENSITIVE — a ConfigMap holding it would commit plaintext, so it is a
    // Secret. Its stringData rides the branch sops-encrypted (`.sops.yaml` encrypted_regex covers
    // stringData; the exploder gives an RKE2_CONFIG Secret the sops-guarded `.secret-*.yml` name),
    // and install-rke2-config decrypts it at boot before writing config.yaml.d/token.yaml.
    createSecret(
        scope,
        "token.yaml",
        "RKE2 token fragment (sops-encrypted on the branch)",
        "|Secret|default|rke2-token",
        Map.of("token", id.clusterToken()));
  }

  private void createConfigMap(
      final Construct scope,
      final String name,
      final String description,
      final String upstreamIdentifier,
      final Map<String, Object> data) {
    createFragment(scope, "ConfigMap", "/data", name, description, upstreamIdentifier, data);
  }

  private void createSecret(
      final Construct scope,
      final String name,
      final String description,
      final String upstreamIdentifier,
      final Map<String, Object> data) {
    createFragment(scope, "Secret", "/stringData", name, description, upstreamIdentifier, data);
  }

  // One RKE2_CONFIG fragment resource — a ConfigMap (payload under /data) or a Secret (under
  // /stringData). Both carry LOCAL_CONFIG (nothing applies them; they exist only for the boot-time
  // install-rke2-config app to extract) + RKE2_CONFIG (the app's marker). The Secret variant is
  // what
  // the exploder gives the sops-guarded `.secret-*.yml` name, so its stringData commits encrypted.
  private void createFragment(
      final Construct scope,
      final String kind,
      final String payloadPath,
      final String name,
      final String description,
      final String upstreamIdentifier,
      final Map<String, Object> data) {
    final ApiObject fragment =
        new ApiObject(
            scope,
            kind.toLowerCase(Locale.ROOT) + "-rke2-" + name.replace('.', '-'),
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind(kind)
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .annotations(
                            packageProfile.packageAnnotations(
                                upstreamIdentifier,
                                Map.of(
                                    ManifestAnnotation.LOCAL_CONFIG.key(),
                                    "true",
                                    ManifestAnnotation.RKE2_CONFIG.key(),
                                    "true",
                                    "description.kpt.dev",
                                    description)))
                        .build())
                .build());

    fragment.addJsonPatch(JsonPatch.add(payloadPath, toConfigMapData(data)));
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
