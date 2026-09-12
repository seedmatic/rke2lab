// @codebase
package io.seedmatic.rke2lab.manifests.units.networking;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.netplan.contract.ClusterAsn;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class CiliumAdvancedManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.NETWORKING + "/cilium-advanced";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("networking", "cilium-advanced");

  public CiliumAdvancedManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of(CiliumConfigManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    createLoadBalancerPools(scope);
    createBgpAdvertisement(scope);
    createL2AnnouncementPolicy(scope);
    createBgpClusterConfig(scope);
  }

  private void createLoadBalancerPools(final Construct scope) {
    // The vmnet LB pool is per-cluster (10.80.<clusterId*8>.64/26) — derive it from the blueprint
    // (SSOT), never a literal, so it MATCHES the LB route the tailscale Connector advertises for
    // the
    // same cluster (both are blueprint.loadBalancer().lbCidr()). A hardcode only ever matched
    // clusterId 0, so a workload cluster would announce one range and pool another → unreachable.
    final ClusterNetworkBlueprint blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster(
                ManifestSynthesisContext.current()
                    .bootstrapIdentity()
                    .clusterNameOrDefault(DefaultNodeEnvContext.DEFAULT_CLUSTER_NAME))
            .node("master")
            .deriveRecipeModel()
            .build();
    final String clusterLbCidr = blueprint.loadBalancer().lbCidr().toString();
    ApiObject cluster =
        new ApiObject(
            scope,
            "ciliumloadbalancerippool-cluster",
            ApiObjectProps.builder()
                .apiVersion("cilium.io/v2alpha1")
                .kind("CiliumLoadBalancerIPPool")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("cluster")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cilium.io|CiliumLoadBalancerIPPool|default|cluster"))
                        .build())
                .build());
    cluster.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "blocks",
                List.of(Map.of("cidr", clusterLbCidr)),
                "serviceSelector",
                Map.of(
                    "matchExpressions",
                    List.of(
                        Map.of("key", "io.cilium/lb-ipam-pool", "operator", "DoesNotExist"))))));

    ApiObject lan =
        new ApiObject(
            scope,
            "ciliumloadbalancerippool-lan",
            ApiObjectProps.builder()
                .apiVersion("cilium.io/v2alpha1")
                .kind("CiliumLoadBalancerIPPool")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("lan")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cilium.io|CiliumLoadBalancerIPPool|default|lan"))
                        .build())
                .build());
    // The LAN LB pool is this cluster's per-cluster lanLbCidr /29 (holds lanHeadscale = host(1) and
    // lanTailscale = host(2)) — NOT a literal. The old 192.168.1.192/27 matched no cluster's /29
    // (the four are .136/.160/.184/.208/29) and, being rendered identically for every cluster,
    // would
    // have every cluster claim .192-.223 on the SHARED home LAN → collision. Per-cluster /29s do
    // not
    // overlap.
    lan.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "blocks",
                List.of(Map.of("cidr", blueprint.lan().lbCidr().toString())),
                "serviceSelector",
                Map.of("matchLabels", Map.of("io.cilium/lb-ipam-pool", "lan")))));

    ApiObject vip =
        new ApiObject(
            scope,
            "ciliumloadbalancerippool-vip",
            ApiObjectProps.builder()
                .apiVersion("cilium.io/v2alpha1")
                .kind("CiliumLoadBalancerIPPool")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("vip")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cilium.io|CiliumLoadBalancerIPPool|default|vip"))
                        .build())
                .build());
    // Same per-cluster derivation as the cluster pool: the vip pool is this cluster's vipCidr
    // (10.80.<clusterId*8+7>.0/24), not a literal — 10.80.7.0/24 was clusterId 0 only.
    vip.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of("blocks", List.of(Map.of("cidr", blueprint.vip().vipCidr().toString())))));
  }

  private void createBgpAdvertisement(final Construct scope) {
    ApiObject advertisement =
        new ApiObject(
            scope,
            "ciliumbgpadvertisement-control-plane-advertisement",
            ApiObjectProps.builder()
                .apiVersion("cilium.io/v2")
                .kind("CiliumBGPAdvertisement")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("control-plane-advertisement")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cilium.io|CiliumBGPAdvertisement|default|control-plane-advertisement"))
                        .build())
                .build());

    advertisement.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "advertisements",
                List.of(
                    Map.of(
                        "advertisementType",
                        "Service",
                        "selector",
                        Map.of("matchLabels", Map.of("io.cilium/lb-ipam-pool", "cluster")),
                        "service",
                        Map.of("addresses", List.of("ExternalIP", "LoadBalancerIP"))),
                    Map.of("advertisementType", "PodCIDR")))));
  }

  private void createL2AnnouncementPolicy(final Construct scope) {
    ApiObject policy =
        new ApiObject(
            scope,
            "ciliuml2announcementpolicy-host",
            ApiObjectProps.builder()
                .apiVersion("cilium.io/v2alpha1")
                .kind("CiliumL2AnnouncementPolicy")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("host")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cilium.io|CiliumL2AnnouncementPolicy|default|host"))
                        .build())
                .build());

    policy.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "interfaces",
                List.of("eth0", "lan0"),
                "loadBalancerIPs",
                true,
                "nodeSelector",
                Map.of(
                    "matchExpressions",
                    List.of(
                        Map.of(
                            "key", "node-role.kubernetes.io/control-plane", "operator", "Exists"))),
                "serviceSelector",
                Map.of("matchLabels", Map.of()))));
  }

  private void createBgpClusterConfig(final Construct scope) {
    // The BGP localASN is per-cluster (64512 + clusterId); derived on the canonical master since
    // the
    // ASN is cluster-scoped (node-independent) — the cluster name carries the identity.
    final ClusterNetworkBlueprint blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster(
                ManifestSynthesisContext.current()
                    .bootstrapIdentity()
                    .clusterNameOrDefault(DefaultNodeEnvContext.DEFAULT_CLUSTER_NAME))
            .node("master")
            .deriveRecipeModel()
            .build();
    // BGP Configuration Strategy for Multi-Host Cluster Mesh
    //
    // See docs/cilium-bgp-multi-host-topology.adoc for comprehensive documentation
    // covering:
    // - Multi-host topology with Incus containers
    // - BGP Router ID allocation (automatic from InternalIP)
    // - iBGP mesh configuration and peer discovery
    // - Multi-homed node peering (Router ID vs Peering Address)
    // - Implementation guide and troubleshooting
    //
    // Quick Summary:
    // - Nodes multi-homed: InternalIP (10.80.x) + LAN (192.168.1.x)
    // - Router ID: Auto-assigned from InternalIP
    // - Current: eBGP to external gateway (AS 65020)
    // - Multi-host: Switch to iBGP mesh (same AS, peerSelector, LAN interface)

    ApiObject bgpClusterConfig =
        new ApiObject(
            scope,
            "ciliumbgpclusterconfig-bgp-cluster-config",
            ApiObjectProps.builder()
                .apiVersion("cilium.io/v2")
                .kind("CiliumBGPClusterConfig")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("bgp-cluster-config")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cilium.io|CiliumBGPClusterConfig|default|bgp-cluster-config"))
                        .build())
                .build());

    bgpClusterConfig.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "bgpInstances",
                List.of(
                    Map.of(
                        "localASN",
                        blueprint.bgpLocalAsn(),
                        "name",
                        "control-plane-bgp",
                        "peers",
                        List.of(
                            Map.of(
                                "name",
                                "gateway-peer",
                                "peerASN",
                                ClusterAsn.GATEWAY.number(),
                                "peerAddress",
                                ClusterNetworkBlueprint.GATEWAY_ADDRESS,
                                "peerConfigRef",
                                Map.of("name", "cilium-peer"))))),
                "nodeSelector",
                Map.of(
                    "matchExpressions",
                    List.of(
                        Map.of(
                            "key",
                            "node-role.kubernetes.io/control-plane",
                            "operator",
                            "Exists"))))));
  }
}
