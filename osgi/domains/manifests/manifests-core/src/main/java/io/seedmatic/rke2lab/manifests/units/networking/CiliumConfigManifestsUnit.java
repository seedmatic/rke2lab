// @codebase
package io.seedmatic.rke2lab.manifests.units.networking;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class CiliumConfigManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.NETWORKING + "/cilium-config";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("networking", "cilium-config", true);

  public CiliumConfigManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    createHelmChartConfig(scope);
    createClustermeshRemoteUsersConfigMap(scope);
  }

  private void createClustermeshRemoteUsersConfigMap(final Construct scope) {
    // Mounted by clustermesh-apiserver as `etcd-users-config`; without this
    // ConfigMap the pod stays in Init:0/1 waiting for the volume. We ship it
    // with no data — peer entries get appended (out-of-band, by `cilium
    // clustermesh users add`) once federated clusters come online.
    new ApiObject(
        scope,
        "configmap-clustermesh-remote-users",
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("ConfigMap")
            .metadata(
                ApiObjectMetadata.builder()
                    .name("clustermesh-remote-users")
                    .namespace("kube-system")
                    .annotations(
                        packageProfile.packageAnnotations(
                            "|ConfigMap|kube-system|clustermesh-remote-users"))
                    .build())
            .build());
  }

  private void createHelmChartConfig(final Construct scope) {
    final BootstrapIdentity identity = ManifestSynthesisContext.current().bootstrapIdentity();
    // The pod/mesh spans are cluster-scoped (a pure function of clusterId, node-independent), so
    // the
    // blueprint is derived on the canonical master — the cluster name carries the identity.
    final ClusterNetworkBlueprint blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster(identity.clusterNameOrDefault(DefaultNodeEnvContext.DEFAULT_CLUSTER_NAME))
            .node("master")
            .deriveRecipeModel()
            .build();

    ApiObject helmChartConfig =
        new ApiObject(
            scope,
            "helmchartconfig-rke2-cilium",
            ApiObjectProps.builder()
                .apiVersion("helm.cattle.io/v1")
                .kind("HelmChartConfig")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("rke2-cilium")
                        .namespace("kube-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "helm.cattle.io|HelmChartConfig|kube-system|rke2-cilium"))
                        .build())
                .build());

    helmChartConfig.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "valuesContent",
                """
                installCRDs: true
                k8sServiceHost: "127.0.0.1"
                k8sServicePort: "6443"
                debug:
                  enabled: true
                  verbose: datapath
                bpf:
                  hostLegacyRouting: false
                  # eBPF masquerading rather than the iptables path — this REMOVES the ipset
                  # requirement instead of satisfying it.  cilium's DaemonConfig:
                  #   NodeIpsetNeeded() = !TunnelingEnabled() && IptablesMasqueradingEnabled()
                  #   IptablesMasqueradingEnabled() = !EnableBPFMasquerade && (v4 || v6 masq)
                  # We run routingMode: native, so without this the agent creates
                  # cilium_node_set_v4 at start and dies with "error while creating ipset" on a
                  # host whose kernel carries no loaded ip_set modules — which is what our
                  # nftables-only substrate is.  The node then stays NotReady with
                  # "cni plugin not initialized".
                  # Both prerequisites are already met: BPF NodePort via kubeProxyReplacement,
                  # and eBPF host-routing via hostLegacyRouting: false above.
                  # Caveat: upstream calls IPv4 production-ready and IPv6 masquerading beta, and
                  # this cluster is dual-stack.
                  masquerade: true
                bgpControlPlane:
                  enabled: true
                cluster:
                  name: %s
                  id: %d
                clustermesh:
                  enabled: true
                  useAPIServer: true
                  apiserver:
                    enabled: true
                    service:
                      type: LoadBalancer
                      # Using BGP announcements for cluster mesh
                envoy:
                  enabled: true
                gatewayAPI:
                  enabled: true
                ingressController:
                  default: true
                  enabled: true
                  loadBalancerMode: dedicated
                  service:
                    annotations:
                      io.cilium/lb-ipam-pool: lan
                      io.cilium/lb-ipam-ips: lan-headplane-inetaddr
                hubble:
                  enabled: true
                  relay:
                    enabled: true
                  ui:
                    enabled: true
                ipv4:
                  enabled: true
                ipv6:
                  enabled: true
                kubeProxyReplacement: true
                l2announcements:
                  enabled: true
                  leaseDuration: 15s
                  leaseRenewDeadline: 5s
                  leaseRetryPeriod: 2s
                l2NeighDiscovery:
                  enabled: true
                  refresh: true
                  refreshPeriod: 30s
                l7Proxy: true
                # The 'protocol not supported' failure in the route reconciler's netlink init was
                # long attributed here to missing ip_set kernel modules; that was WRONG, and it
                # cost an evening. The reconciler calls safenetlink.NewHandle(nil), which opens a
                # socket for every supported netlink family — ROUTE, XFRM, NETFILTER — so the one
                # missing module is xfrm_user, now declared in the node profile's
                # linux.kernel_modules (InstanceGrow.nodeProfileConfig). Loading it took the agent
                # to 1/1 Running; loading ip_set changed nothing.
                #
                # native routing is kept on its own merits: all control nodes run as containers on
                # the same host, so it is more efficient and avoids encapsulation overhead. Cluster
                # mesh works via apiserver regardless.
                routingMode: native
                autoDirectNodeRoutes: true
                ipv4NativeRoutingCIDR: %s
                ipv6NativeRoutingCIDR: %s
                operator:
                  replicas: 1
                  podDisruptionBudget:
                    enabled: true
                socketLB:
                  enabled: true"""
                    .formatted(
                        identity.clusterName(),
                        blueprint.meshClusterId(),
                        blueprint.podCidr(),
                        blueprint.podCidrV6()))));
  }
}
