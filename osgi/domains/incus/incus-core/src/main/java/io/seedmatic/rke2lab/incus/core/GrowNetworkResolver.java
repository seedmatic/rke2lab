package io.seedmatic.rke2lab.incus.core;

import io.seedmatic.rke2lab.incus.ingress.GrowNetworkView;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import io.seedmatic.rke2lab.netplan.contract.NetplanSynthesisRequest;
import io.seedmatic.rke2lab.netplan.contract.NetplanSynthesisService;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assembles the flat {@link GrowNetworkView} the host GROW poses on the Pulumi graph — the two NIC
 * hardware addresses and the dnsmasq config the per-cluster {@code vmnet} bridge carries. It reads
 * the {@link NetplanSynthesisService} (the scion resolves it from its registry, like {@code
 * ImageBuilder}) and reduces the blueprint's raw pieces to the three flat values, OSGi-side — the
 * scion-projects/host-actualises rule (the host receives the result and only poses it).
 *
 * <p>The dnsmasq map is the incus metier, not netplan's: it is the config an Incus {@code Network}
 * resource takes ({@code ipv4.*} + a {@code raw.dnsmasq} block of one {@code dhcp-host} line per
 * cluster node). Its {@code dhcp-host} lines need the WAN MAC + node IP of EVERY node, so the
 * resolver synthesizes the whole cluster ({@link ClusterNetworkBlueprint#CANONICAL_NODE_NAMES}),
 * not just the node being grown — the netplan domain owns that node list, so this reads its single
 * source. The lease hostname is {@code <cluster>-<node>}, matching the instance name the host
 * grows.
 */
public final class GrowNetworkResolver {

  private final NetplanSynthesisService netplan;

  public GrowNetworkResolver(NetplanSynthesisService netplan) {
    this.netplan = netplan;
  }

  /**
   * Assemble the view for the {@code node} of {@code cluster} (the node the instance grows for).
   * The grown node's NIC hwaddrs + the bridge it attaches to come from its own blueprint; the
   * bridges to ensure are EVERY cluster co-located on the host ({@link
   * ClusterNetworkBlueprint#clustersOnSameHost}) — vmnet is isolated per-cluster, so a workload
   * cluster's bridge + dnsmasq reservations must exist for CAPN to DHCP-provision it in-cluster,
   * even though only this node grows standalone.
   */
  public GrowNetworkView resolve(String cluster, String node) {
    final ClusterNetworkBlueprint grown = synthesize(cluster, node);
    final Map<String, GrowNetworkView.ClusterBridge> clusterBridges = new LinkedHashMap<>();
    for (final String coLocated : grown.clustersOnSameHost()) {
      final ClusterNetworkBlueprint coLocatedBlueprint = synthesize(coLocated, "master");
      clusterBridges.put(
          coLocated,
          new GrowNetworkView.ClusterBridge(
              coLocatedBlueprint.vmnetBridgeName(),
              vmnetBridgeConfig(coLocated, coLocatedBlueprint)));
    }
    return new GrowNetworkView(
        grown.lan().hostMacaddr().value(),
        grown.wan().hostMacaddr().value(),
        grown.vmnetBridgeName(),
        clusterBridges);
  }

  private ClusterNetworkBlueprint synthesize(String cluster, String node) {
    return netplan.synthesize(new NetplanSynthesisRequest(cluster, node)).blueprint();
  }

  /**
   * The Incus {@code vmnet} network's config map — the keys the bridge takes. The addresses (v4 +
   * v6), DHCP ranges and per-node leases derive from the blueprint; {@code ipv4.nat}/{@code
   * ipv4.dhcp}/{@code ipv6.*}/{@code dns.mode}/{@code bridge.driver} are the fixed policy of a
   * per-cluster provisioning bridge.
   */
  private Map<String, String> vmnetBridgeConfig(String cluster, ClusterNetworkBlueprint local) {
    final Map<String, String> config = new LinkedHashMap<>();
    config.put(
        "ipv4.address",
        local.host().clusterGatewayInetaddr().getHostAddress()
            + "/"
            + local.host().clusterCidr().prefixLength());
    config.put("ipv4.nat", "false");
    config.put("ipv4.dhcp", "true");
    config.put("ipv4.dhcp.ranges", local.wan().dhcpRange());
    // Dual-stack: pin the vmnet's DETERMINISTIC ULA prefix — without ipv6.address incus
    // auto-assigns
    // a RANDOM ULA and the node's v6 could never be predicted for node-ip. Use the NODE /64
    // (fd96:…:{cc}20::/64, where every node's embedded-v4 v6 lives), NOT the cluster /56: dnsmasq's
    // DHCPv6 rejects a prefix shorter than /64 ("prefix length must be at least 64"). Stateful
    // DHCPv6
    // so the per-node embedded-v4 reservations below (raw.dnsmasq) are honoured; SLAAC would
    // instead
    // hand out EUI-64 addresses node-ip cannot name. NAT off — the vmnet is internal.
    config.put(
        "ipv6.address",
        local.nodeNetwork().nodeGatewayInetaddr6().getHostAddress()
            + "/"
            + local.nodeNetwork().nodeCidr6().prefixLength());
    config.put("ipv6.nat", "false");
    config.put("ipv6.dhcp", "true");
    config.put("ipv6.dhcp.stateful", "true");
    config.put("dns.mode", "none");
    config.put("bridge.driver", "native");
    config.put("raw.dnsmasq", rawDnsmasq(cluster));
    return config;
  }

  /**
   * One dual-stack {@code dhcp-host=<wanMac>,<nodeIpv4>,[<nodeIpv6>],<cluster>-<node>} line per
   * cluster node, newline-joined. The bracketed IPv6 is the stateful DHCPv6 reservation (see {@code
   * ipv6.dhcp.stateful} above): the node's embedded-v4 ULA, so it deterministically holds the
   * address node-ip names.
   */
  private String rawDnsmasq(String cluster) {
    return ClusterNetworkBlueprint.CANONICAL_NODE_NAMES.stream()
        .map(node -> synthesize(cluster, node))
        .map(
            blueprint ->
                "dhcp-host="
                    + blueprint.wan().hostMacaddr().value()
                    + ","
                    + blueprint.nodeNetwork().nodeHostInetaddr().getHostAddress()
                    + ",["
                    + blueprint.nodeNetwork().nodeHostInetaddr6().getHostAddress()
                    + "],"
                    + cluster
                    + "-"
                    + blueprint.node().name())
        .reduce((left, right) -> left + "\n" + right)
        .orElse("");
  }
}
