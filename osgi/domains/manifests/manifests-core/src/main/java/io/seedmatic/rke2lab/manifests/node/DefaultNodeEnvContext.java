package io.seedmatic.rke2lab.manifests.node;

import io.seedmatic.rke2lab.manifests.contract.node.NodeEnvContext;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.contract.profiles.NetworkTopology;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;

/**
 * Default synthesis-time {@link NodeEnvContext} backed by canonical netplan blueprint derivation.
 */
public final class DefaultNodeEnvContext implements NodeEnvContext {

  /**
   * The lab's control node. The blueprint requires a canonical node name; when the handed-over
   * identity carries none (a bare/ephemeral run with no seeded identity), fall back to the single
   * master this lab grows — the node role, not cluster identity.
   */
  private static final String DEFAULT_NODE_NAME = "master";

  /**
   * The reserved test cluster. When the handed-over identity carries no cluster (a bare survey / no
   * seeded identity), fall back to it so addressing derives a valid, distinct TEST slice
   * (192.168.1.224) instead of overflowing — never a real cluster's address space. Public because
   * it is the single source of the reserved default: the cluster-scoped Cilium units that derive a
   * blueprint from the raw thread-local identity pass THIS constant to {@link
   * BootstrapIdentity#clusterNameOrDefault(String)}.
   */
  public static final String DEFAULT_CLUSTER_NAME = "test-mgmt";

  private final ClusterNetworkBlueprint blueprint;

  /**
   * Derives the node's network blueprint from the run's handed-over {@link BootstrapIdentity} — the
   * single source of the cluster name (no compile-time literal). The topology is a pure function of
   * the cluster + node name (see {@code ClusterNetworkBlueprint.deriveRecipeModel}), so the
   * identity is all this context needs to project the whole node environment.
   */
  public DefaultNodeEnvContext(final BootstrapIdentity identity) {
    final String nodeName = identity.nodeNameOrDefault(DEFAULT_NODE_NAME);
    final String clusterName = identity.clusterNameOrDefault(DEFAULT_CLUSTER_NAME);
    this.blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster(clusterName)
            .node(nodeName)
            .deriveRecipeModel()
            .build();
  }

  @Override
  public BootstrapIdentity bootstrapIdentity() {
    final String clusterName = blueprint.cluster().name();
    return BootstrapIdentity.builder()
        .clusterName(clusterName)
        .clusterId(blueprint.cluster().id())
        .clusterToken(clusterName)
        .clusterDomain("cluster.local")
        .nodeName(blueprint.node().name())
        .nodeId(blueprint.node().id())
        .nodeKind(blueprint.node().type().kind())
        .incusRemoteName(clusterName)
        .build();
  }

  @Override
  public NetworkTopology networkTopology() {
    return NetworkTopology.builder()
        .clusterCidr(blueprint.host().clusterCidr().toString())
        .clusterPodCidr(blueprint.podCidrDualStack())
        .clusterServiceCidr(blueprint.serviceCidrDualStack())
        .nodeHostInetAddr(blueprint.nodeNetwork().nodeHostInetaddr().getHostAddress())
        .nodeHostInet6Addr(blueprint.nodeNetwork().nodeHostInetaddr6().getHostAddress())
        .nodeNetworkCidr(blueprint.nodeNetwork().nodeCidr().toString())
        .nodeNetworkGatewayAddr(blueprint.nodeNetwork().nodeGatewayInetaddr().getHostAddress())
        .clusterLoadBalancerCidr(blueprint.loadBalancer().lbCidr().toString())
        .clusterLoadBalancerGatewayAddr(blueprint.lan().headscaleInetaddr().getHostAddress())
        .lanInterface(blueprint.interfaces().lanInterface())
        .lanHostInetAddr(blueprint.lan().hostInetaddr().getHostAddress())
        .lanLoadBalancerCidr(blueprint.lan().lbCidr().toString())
        .wanInterface(blueprint.interfaces().wanInterface())
        .vipInterface(blueprint.interfaces().vipInterface())
        .vipCidr(blueprint.vip().vipCidr().toString())
        .vipGatewayInetAddr(blueprint.vip().vipGatewayInetaddr().getHostAddress())
        .vipHostInetAddr(blueprint.vip().vipHostInetaddr().getHostAddress())
        .lanHostMacAddr(blueprint.lan().hostMacaddr().value())
        .wanHostMacAddr(blueprint.wan().hostMacaddr().value())
        .build();
  }
}
