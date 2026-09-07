package io.seedmatic.rke2lab.netplan.contract;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Stage B network blueprint for clusters provisioned by the management control-plane.
 *
 * <p>Derived from the same recipe model used in rke2lab network make rules (host/lan/lb/node/vip).
 */
public record ClusterNetworkBlueprint(
    ClusterRef cluster,
    NodeRef node,
    HostPlan host,
    NodeNetworkPlan nodeNetwork,
    VipPlan vip,
    LoadBalancerPlan loadBalancer,
    LanPlan lan,
    WanPlan wan,
    InterfacePlan interfaces,
    VlanPlan vlan,
    NamePlan names) {

  /**
   * The canonical node names of every cluster, in topology order (1 master + 3 peers + 2 workers).
   * The single source of truth the netplan domain OWNS — it already derives each node's id/type and
   * validates names against this topology, so a consumer that must enumerate the cluster's nodes
   * (the incus grow's dnsmasq {@code dhcp-host} lines, the bbox reservation rows) reads THIS rather
   * than duplicating the list.
   */
  public static final List<String> CANONICAL_NODE_NAMES =
      List.of("master", "peer1", "peer2", "peer3", "worker1", "worker2");

  /**
   * IPv6 ULA underlay — a deterministic mirror of the IPv4 plan. The /48 global ID is the first 40
   * bits of {@code sha256("mammoth-skate")} (fixed forever; random-looking for RFC 4193 collision
   * resistance). The hierarchy mirrors IPv4 on byte boundaries: {@code /48} super ⊃ {@code /56} per
   * cluster ({@code {clusterId}00}) ⊃ {@code /64} per role ({@code {clusterId}{role}}). Each host
   * embeds its IPv4 verbatim in the low 32 bits ({@code fd96:6924:3693:{CC}{RR}::a.b.c.d}) so v4↔v6
   * is inferable by inspection.
   */
  public static final String ULA_PREFIX = "fd96:6924:3693";

  /**
   * Pod / Service CIDRs — PER-CLUSTER, a pure function of {@code clusterId} (see the cluster
   * addressing plan). Under one cluster these were global constants (10.42/10.43); with four
   * clusters sharing each host's L2 fabric they MUST be globally disjoint or pod routes collide on
   * the bridge, so every span is {@code f(clusterId)}: pod {@code 10.<44+id>.0.0/16}, service
   * {@code 10.<48+id>.0.0/16}. The v6 halves mirror the v4 index (digits match: pod {@code 10.44} ↔
   * {@code fd00:44}). Cilium {@code routingMode: native} REQUIRES the v6 native-routing CIDR
   * whenever v6 is enabled, and the node bakes the dual-stack {@code cluster-cidr} from {@link
   * #podCidrDualStack()} — so these track the nix {@code cluster-cidr}, both derived here.
   */
  public String podCidr() {
    return "10." + (44 + cluster().id()) + ".0.0/16";
  }

  public String serviceCidr() {
    return "10." + (48 + cluster().id()) + ".0.0/16";
  }

  public String podCidrV6() {
    return "fd00:" + (44 + cluster().id()) + "::/56";
  }

  public String serviceCidrV6() {
    return "fd00:" + (48 + cluster().id()) + "::/112";
  }

  /** The dual-stack {@code cluster-cidr} the node bakes (nix drop-in + rke2 config fragment). */
  public String podCidrDualStack() {
    return podCidr() + "," + podCidrV6();
  }

  /** The dual-stack {@code service-cidr} the node bakes. */
  public String serviceCidrDualStack() {
    return serviceCidr() + "," + serviceCidrV6();
  }

  /** Cilium BGP {@code localASN} — {@code 64512 + clusterId}, anchored on {@link ClusterAsn}. */
  public int bgpLocalAsn() {
    return ClusterAsn.RKE2_CLUSTER.number() + cluster().id();
  }

  /** Cilium clustermesh {@code cluster.id} — {@code clusterId + 1} (globally unique, 1..). */
  public int meshClusterId() {
    return cluster().id() + 1;
  }

  /**
   * vmnet gateway address — Cilium BGP {@code peerAddress}; equals cluster 0's {@code
   * clusterCidr.gateway()}.
   */
  public static final String GATEWAY_ADDRESS = "10.80.0.1";

  private static final int ROLE_NODE = 0x20;
  private static final int ROLE_VIP = 0x30;
  private static final int ROLE_LB = 0x40;
  private static final int ROLE_LAN_NODE = 0x50;
  private static final int ROLE_LAN_LB = 0x60;

  private static Cidr ula64(int clusterId, int role) {
    return Cidr.parse(String.format("%s:%02x%02x::/64", ULA_PREFIX, clusterId, role));
  }

  /** The address in a role's {@code /64} that embeds {@code ipv4} verbatim in its low 32 bits. */
  private static InetAddress ula6(int clusterId, int role, InetAddress ipv4) {
    final String value =
        String.format("%s:%02x%02x::%s", ULA_PREFIX, clusterId, role, ipv4.getHostAddress());
    try {
      return InetAddress.getByName(value);
    } catch (java.net.UnknownHostException exception) {
      throw new IllegalArgumentException("Invalid ULA v6 host: " + value, exception);
    }
  }

  /** Create a fluent builder for blueprint derivation. */
  public static Builder builder() {
    return new Builder();
  }

  private static ClusterNetworkBlueprint derive(String clusterName, String nodeName) {
    validateNodeName(nodeName);

    final int hostId = hostId(hostOf(clusterName));
    final int roleId = roleId(roleOf(clusterName));
    final int clusterId = (hostId << 1) | roleId;

    final int nodeId = nodeId(nodeName);
    final NodeType nodeType = nodeType(nodeName);

    final int hostThirdOctet = clusterId * 8;
    final int vipThirdOctet = hostThirdOctet + 7;

    final Cidr clusterCidr = Cidr.parse("10.80." + hostThirdOctet + ".0/21");
    final Cidr nodeCidr = Cidr.parse("10.80." + hostThirdOctet + ".0/23");
    final Cidr vipCidr = Cidr.parse("10.80." + vipThirdOctet + ".0/24");
    final Cidr lbCidr = Cidr.parse("10.80." + hostThirdOctet + ".64/26");

    // LAN: the HIGH half of 192.168.1.0/24 (.128-.255) — the LOW half is the home network's (DHCP +
    // devices; gateway .254). Carved asymmetrically by role from base = 128 + hostId*48 +
    // roleId*16:
    // single-node mgmt takes a /28 (node /29 + lb /29); multi-node wrkld a /27 (node /28 =
    // host(3)..
    // host(14) = 4 control + 8 workers, + lb /29). Real clusters fill .128-.223 (bioskop hostId 0,
    // nikopol hostId 1); the reserved TEST host (hostId 2 -> base .224) gives a blank/unknown
    // identity its own valid slice instead of overflowing. A genuinely unknown host fails fast in
    // hostId().
    final boolean wrkldRole = roleId == 1;
    final int lanSliceBase = 128 + hostId * 48 + roleId * 16;
    final Cidr lanNodeCidr = Cidr.parse("192.168.1." + lanSliceBase + (wrkldRole ? "/28" : "/29"));
    final Cidr lanLbCidr = Cidr.parse("192.168.1." + (lanSliceBase + (wrkldRole ? 16 : 8)) + "/29");

    // Each host derives from the CIDR we already hold — ask the network for its host, instead of
    // rebuilding and re-parsing an address string that re-encodes the same octets.
    final InetAddress clusterGatewayInetaddr = clusterCidr.gateway();
    final InetAddress nodeGatewayInetaddr = clusterGatewayInetaddr;
    final InetAddress nodeHostInetaddr = nodeCidr.host(10 + nodeId);

    final InetAddress vipGatewayInetaddr = vipCidr.gateway();
    final InetAddress vipHostInetaddr = vipCidr.host(10);

    final InetAddress lanHostInetaddr = lanNodeCidr.host(3 + nodeId);
    // The fixed LAN gateway lies outside the allocated /27 slice — a foreign address, resolved by a
    // Cidr in whose 192.168.1.0 space it lives (address manipulation is part of the type's role).
    final InetAddress lanGatewayInetaddr = lanNodeCidr.address("192.168.1.254");
    final InetAddress lanHeadscaleInetaddr = lanLbCidr.host(1);
    final InetAddress lanTailscaleInetaddr = lanLbCidr.host(2);

    final String wanDhcpRange =
        "10.80."
            + hostThirdOctet
            + ".2-10.80."
            + hostThirdOctet
            + ".9,10.80."
            + hostThirdOctet
            + ".31-10.80."
            + (hostThirdOctet + 7)
            + ".254";

    final MacAddress wanHostMacaddr =
        MacAddress.parse(
            String.format("52:54:00:%02x:%02x:%02x", clusterId, nodeType.numericCode(), nodeId));
    final MacAddress lanHostMacaddr =
        MacAddress.parse(String.format("10:66:6a:4c:%02x:%02x", clusterId, nodeId));
    final MacAddress lanBridgeMacaddr =
        MacAddress.parse(String.format("02:00:00:bb:%02x:%02x", clusterId, nodeId));

    // IPv6 ULA mirror (see ULA_PREFIX): /48 super ⊃ /56 cluster ⊃ /64 per role, each host
    // embedding its IPv4 in the low 32 bits.
    final Cidr superNetworkCidr6 = Cidr.parse(ULA_PREFIX + "::/48");
    final Cidr clusterCidr6 = Cidr.parse(String.format("%s:%02x00::/56", ULA_PREFIX, clusterId));
    final Cidr nodeCidr6 = ula64(clusterId, ROLE_NODE);
    final Cidr vipCidr6 = ula64(clusterId, ROLE_VIP);
    final Cidr lbCidr6 = ula64(clusterId, ROLE_LB);
    final Cidr lanNodeCidr6 = ula64(clusterId, ROLE_LAN_NODE);
    final Cidr lanLbCidr6 = ula64(clusterId, ROLE_LAN_LB);

    final InetAddress nodeGateway6 = ula6(clusterId, ROLE_NODE, nodeGatewayInetaddr);
    final InetAddress nodeHost6 = ula6(clusterId, ROLE_NODE, nodeHostInetaddr);
    final InetAddress vipGateway6 = ula6(clusterId, ROLE_VIP, vipGatewayInetaddr);
    final InetAddress vipHost6 = ula6(clusterId, ROLE_VIP, vipHostInetaddr);
    final InetAddress lanHost6 = ula6(clusterId, ROLE_LAN_NODE, lanHostInetaddr);
    final InetAddress lanGateway6 = ula6(clusterId, ROLE_LAN_NODE, lanGatewayInetaddr);
    final InetAddress lanHeadscale6 = ula6(clusterId, ROLE_LAN_LB, lanHeadscaleInetaddr);
    final InetAddress lanTailscale6 = ula6(clusterId, ROLE_LAN_LB, lanTailscaleInetaddr);

    return new ClusterNetworkBlueprint(
        new ClusterRef(clusterName, clusterId),
        new NodeRef(nodeName, nodeId, nodeType),
        new HostPlan(
            Cidr.parse("10.80.0.0/18"),
            clusterCidr,
            clusterGatewayInetaddr,
            superNetworkCidr6,
            clusterCidr6,
            nodeGateway6),
        new NodeNetworkPlan(
            nodeCidr, nodeGatewayInetaddr, nodeHostInetaddr, nodeCidr6, nodeGateway6, nodeHost6),
        new VipPlan(vipCidr, vipGatewayInetaddr, vipHostInetaddr, vipCidr6, vipGateway6, vipHost6),
        new LoadBalancerPlan(lbCidr, lbCidr6),
        new LanPlan(
            lanNodeCidr,
            lanLbCidr,
            lanHostInetaddr,
            lanGatewayInetaddr,
            lanHeadscaleInetaddr,
            lanTailscaleInetaddr,
            lanHostMacaddr,
            lanBridgeMacaddr,
            lanNodeCidr6,
            lanLbCidr6,
            lanHost6,
            lanGateway6,
            lanHeadscale6,
            lanTailscale6),
        new WanPlan(wanDhcpRange, wanHostMacaddr),
        new InterfacePlan(nodeName + "-lan0", nodeName + "-vmnet0", "vmnet0"),
        new VlanPlan(100, "rke2-vlan"),
        new NamePlan(
            clusterName + "-" + nodeName,
            clusterName + "-" + nodeName + ".local",
            // The incus/nixos daemon host = <host>-nixos (the bare host, not the <host>-<role>
            // cluster). The operator-facing authority is config's rke2lab:cluster:remoteIncus; this
            // is the netplan-domain mirror derived from the same bare host.
            hostOf(clusterName) + "-nixos"));
  }

  /** Stable ref/id for contract exports. */
  public String ref() {
    return "cluster:"
        + cluster.name()
        + "("
        + cluster.id()
        + ")"
        + ",node:"
        + node.name()
        + "("
        + node.id()
        + ")"
        + ",host:"
        + host.superNetworkCidr()
        + ",cluster:"
        + host.clusterCidr()
        + ",node:"
        + nodeNetwork.nodeCidr()
        + ",vip:"
        + vip.vipCidr()
        + ",lb:"
        + loadBalancer.lbCidr()
        + ",lan-node:"
        + lan.nodeCidr()
        + ",lan-lb:"
        + lan.lbCidr();
  }

  /** The bare host token — the {@code <host>} of {@code <host>-<role>} (up to the first dash). */
  private static String hostOf(String clusterName) {
    final int dash = clusterName.indexOf('-');
    return dash < 0 ? clusterName : clusterName.substring(0, dash);
  }

  /** The role token — the {@code <role>} of {@code <host>-<role>} (after the first dash). */
  private static String roleOf(String clusterName) {
    final int dash = clusterName.indexOf('-');
    return dash < 0 ? "" : clusterName.substring(dash + 1);
  }

  private static int hostId(String host) {
    return switch (host) {
      case "bioskop" -> 0;
      case "nikopol" -> 1; // renamed from alcide, keeping same host id
      case "test" -> 2; // reserved LAN slice for the blank/unknown cluster identity (surveys/tests)
      default ->
          throw new IllegalArgumentException(
              "unknown host '"
                  + host
                  + "' — no LAN slice allocated; add it to hostId() + the"
                  + " addressing plan (only bioskop/nikopol/test are carved into 192.168.1.128/25)");
    };
  }

  private static int roleId(String role) {
    return switch (role) {
      case "mgmt" -> 0;
      case "wrkld" -> 1;
      default -> 0;
    };
  }

  private static int nodeId(String nodeName) {
    return switch (nodeName) {
      case "master" -> 0;
      case "peer1" -> 1;
      case "peer2" -> 2;
      case "peer3" -> 3;
      case "worker1" -> 4;
      case "worker2" -> 5;
      case "worker3" -> 6;
      case "worker4" -> 7;
      case "worker5" -> 8;
      case "worker6" -> 9;
      case "worker7" -> 10;
      case "worker8" -> 11;
      default -> throw new IllegalArgumentException("Unsupported node.name: " + nodeName);
    };
  }

  private static NodeType nodeType(String nodeName) {
    return switch (nodeName) {
      case "master", "peer1", "peer2", "peer3" -> NodeType.SERVER;
      case "worker1", "worker2" -> NodeType.AGENT;
      default -> throw new IllegalArgumentException("Unsupported node.name: " + nodeName);
    };
  }

  private static void validateNodeName(String nodeName) {
    if (!CANONICAL_NODE_NAMES.contains(nodeName)) {
      throw new IllegalArgumentException(
          "Node name '"
              + nodeName
              + "' does not conform to canonical topology "
              + CANONICAL_NODE_NAMES);
    }
  }

  public enum NodeType {
    SERVER(0),
    AGENT(1);

    private final int numericCode;

    NodeType(int numericCode) {
      this.numericCode = numericCode;
    }

    public int numericCode() {
      return numericCode;
    }

    /**
     * The rke2 wire form of the role ({@code server}/{@code agent}) — the single source both the
     * manifests node-env identity and the incus grow-plan identity project as {@code
     * RKE2LAB_NODE_KIND}. The enum constant lowercased IS that form, so the mapping lives once
     * here.
     */
    public String kind() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  public record ClusterRef(String name, int id) {}

  public record NodeRef(String name, int id, NodeType type) {}

  public record HostPlan(
      Cidr superNetworkCidr,
      Cidr clusterCidr,
      InetAddress clusterGatewayInetaddr,
      Cidr superNetworkCidr6,
      Cidr clusterCidr6,
      InetAddress clusterGatewayInetaddr6) {}

  public record NodeNetworkPlan(
      Cidr nodeCidr,
      InetAddress nodeGatewayInetaddr,
      InetAddress nodeHostInetaddr,
      Cidr nodeCidr6,
      InetAddress nodeGatewayInetaddr6,
      InetAddress nodeHostInetaddr6) {}

  public record VipPlan(
      Cidr vipCidr,
      InetAddress vipGatewayInetaddr,
      InetAddress vipHostInetaddr,
      Cidr vipCidr6,
      InetAddress vipGatewayInetaddr6,
      InetAddress vipHostInetaddr6) {}

  public record LoadBalancerPlan(Cidr lbCidr, Cidr lbCidr6) {}

  public record LanPlan(
      Cidr nodeCidr,
      Cidr lbCidr,
      InetAddress hostInetaddr,
      InetAddress gatewayInetaddr,
      InetAddress headscaleInetaddr,
      InetAddress tailscaleInetaddr,
      MacAddress hostMacaddr,
      MacAddress bridgeMacaddr,
      Cidr nodeCidr6,
      Cidr lbCidr6,
      InetAddress hostInetaddr6,
      InetAddress gatewayInetaddr6,
      InetAddress headscaleInetaddr6,
      InetAddress tailscaleInetaddr6) {}

  public record WanPlan(String dhcpRange, MacAddress hostMacaddr) {}

  public record InterfacePlan(String lanInterface, String wanInterface, String vipInterface) {}

  public record VlanPlan(int id, String name) {}

  /**
   * The identity-derived NAMES the cluster resolves nodes and infra hosts by — the single source
   * for hostnames domains otherwise re-concatenate. {@code nodeHostname} is the bare {@code
   * <cluster>-<node>}; {@code nodeMdnsFqdn} its mDNS name ({@code .local}, how a same-LAN host —
   * e.g. the seed's systemd probe — reaches it); {@code nixosHost} the {@code <cluster>-nixos}
   * builder/daemon host. Ports are NOT here: a port is a fixed service constant, not
   * identity-derived — each domain pairs a name from here with its own port.
   */
  public record NamePlan(String nodeHostname, String nodeMdnsFqdn, String nixosHost) {}

  /**
   * Canonical cluster topology: 1 master, 3 control nodes (peers), 2 worker nodes.
   *
   * <p>This is the expected node composition for every cluster managed by the rke2lab
   * control-plane.
   */
  public record ClusterTopology(int masterCount, int controlNodeCount, int workerNodeCount) {
    public static final ClusterTopology CANONICAL = new ClusterTopology(1, 3, 2);

    public int totalNodeCount() {
      return masterCount + controlNodeCount + workerNodeCount;
    }
  }

  /** Fluent API for deriving blueprint from cluster/node identity. */
  public static final class Builder {
    @MonotonicNonNull private String clusterName;
    @MonotonicNonNull private String nodeName;
    private boolean deriveRecipeModel;

    public Builder cluster(String clusterName) {
      this.clusterName = clusterName;
      return this;
    }

    public Builder node(String nodeName) {
      this.nodeName = nodeName;
      return this;
    }

    public Builder deriveRecipeModel() {
      this.deriveRecipeModel = true;
      return this;
    }

    public ClusterNetworkBlueprint build() {
      if (!deriveRecipeModel) {
        throw new IllegalStateException("Builder requires deriveRecipeModel() before build()");
      }
      final String cluster = Objects.requireNonNull(clusterName, "clusterName must be set");
      final String node = Objects.requireNonNull(nodeName, "nodeName must be set");
      if (cluster.isBlank()) {
        throw new IllegalArgumentException("clusterName must be set");
      }
      if (node.isBlank()) {
        throw new IllegalArgumentException("nodeName must be set");
      }
      return derive(cluster, node);
    }
  }
}
