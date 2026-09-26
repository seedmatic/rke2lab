package io.seedmatic.rke2lab.netplan.contract;

import io.seedmatic.rke2lab.manifests.contract.ClusterRole;
import java.net.InetAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    FabricPlan fabric,
    WanPlan wan,
    InterfacePlan interfaces,
    VlanPlan vlan,
    NamePlan names) {

  /**
   * The ordered SUPERSET of node names, in topology order (1 master + 3 peers + 2 workers). The
   * single source of truth the netplan domain OWNS — it derives each node's id/type and validates
   * names against it, so nothing duplicates the list.
   *
   * <p>⚠️ It is a superset, NOT a roster: how many of these a cluster actually has is decided by
   * its ROLE, and only {@link ClusterTopology#nodeNames()} answers that. A consumer that enumerates
   * this list directly is enumerating a WORKLOAD cluster's shape, whatever cluster it is looking at
   * — and for a management cluster (single-node) that walks straight off the end of its {@code /29}
   * LAN slice.
   */
  public static final List<String> CANONICAL_NODE_NAMES =
      List.of("master", "peer1", "peer2", "peer3", "worker1", "worker2");

  /**
   * The bare-metal hosts and their ids — the ONE table every per-host span derives from: the
   * cluster id, the vmnet plane, the ULA mirror, the MACs, and (published, so ndh derives it rather
   * than re-declaring it) each bare-metal's fabric slice.
   *
   * <p>Ids are LOAD-BEARING and written explicitly, never derived from position in this map:
   * nikopol kept id 1 through its rename from alcide, and a reordering that silently reassigned ids
   * would renumber live networks. {@code test} is the reserved slice a blank/unknown cluster
   * identity falls into (surveys, tests).
   *
   * <p>Ordered ({@link LinkedHashMap}) because it is serialised into {@code
   * network-blueprint.json}, whose byte-stability is what makes a regeneration reviewable as a
   * diff.
   */
  public static final Map<String, Integer> HOST_IDS = hostIds();

  private static Map<String, Integer> hostIds() {
    final Map<String, Integer> ids = new LinkedHashMap<>();
    ids.put("bioskop", 0);
    ids.put("nikopol", 1);
    ids.put("test", 2);
    return Collections.unmodifiableMap(ids);
  }

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

  /**
   * The spans that make this cluster REACHABLE from outside its own fabric, as a tailscale subnet
   * router advertises them: the kube-vip control-plane VIP as a {@code /32} (what CAPI dials, and
   * what a kubeconfig context targets) and the cilium LoadBalancer IP pool (so its {@code Service}s
   * answer too).
   *
   * <p>Both are pure functions of {@code clusterId}, so this is the thing a Connector advertises
   * for ITSELF and equally what a MANAGEMENT plane advertises on behalf of each cluster it manages
   * — the management plane being the one always standing, and the only one reachable while a
   * workload is still half-born.
   */
  public List<String> tailnetReachRoutes() {
    return List.of(
        vip().vipHostInetaddr().getHostAddress() + "/32", loadBalancer().lbCidr().toString());
  }

  /**
   * Is this node's fabric hwaddr one this blueprint PREDICTS — and therefore is {@code
   * fabric().hostInetaddr()} an address a {@code dhcp-host} reservation can actually bind?
   *
   * <p>Only a HOST-GROWN node carries the {@code 10:66:6a:4c:<clusterId>:<nodeId>} hwaddr posed by
   * the grow. A workload cluster's nodes are CAPN-provisioned cattle whose hwaddr the provider
   * mints — its {@code LXCMachineTemplate} is attached to the control plane and so is shared by
   * every node it makes, leaving nothing to individuate. Measured 2026-09-23: {@code
   * bioskop-wrkld-control-plane-899lr} came up on an ordinary lease with a hwaddr sharing only the
   * OUI. So a reservation for a cattle node describes a match that can never happen, and holding an
   * address nothing will claim is worse than useless — it consumes the carve.
   *
   * <p>This is the addressing law, so it lives here rather than in the consumer that asks for the
   * reservations. It used to live in the bbox domain's row enumerator, which is what made that
   * domain's scope {@code <host>-mgmt} only; the move follows the reservations themselves, from the
   * bbox's DHCP to the bare-metal's.
   */
  public boolean fabricMacIsPredictable() {
    return ClusterRole.of(cluster().name()) == ClusterRole.MGMT;
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

  /**
   * The fabric tier's first two octets, and the width of one bare-metal's slice in third-octet
   * slots. A slice is {@code 172.16.<hostId*16>.0/20}: slot 0 is ndh's host infra, slots 1+roleId
   * are the clusters, slot 8 is the vz {@code /30}. One bit of the third octet therefore encodes
   * the link — offset &lt; 8 is on the bare-metal's bridge, ≥ 8 is another link.
   */
  private static final String FABRIC_PREFIX = "172.16";

  private static final int FABRIC_SLOTS_PER_HOST = 16;

  private static final int ROLE_NODE = 0x20;
  private static final int ROLE_VIP = 0x30;
  private static final int ROLE_LB = 0x40;
  private static final int ROLE_FABRIC_NODE = 0x50;
  private static final int ROLE_FABRIC_LB = 0x60;

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
    final int roleId = ClusterRole.of(clusterName).ordinal();
    final int clusterId = (hostId << 1) | roleId;

    final int nodeId = nodeId(nodeName);
    final NodeType nodeType = nodeType(nodeName);

    final int hostThirdOctet = clusterId * 8;
    final int vipThirdOctet = hostThirdOctet + 7;

    final Cidr clusterCidr = Cidr.parse("10.80." + hostThirdOctet + ".0/21");
    final Cidr nodeCidr = Cidr.parse("10.80." + hostThirdOctet + ".0/23");
    final Cidr vipCidr = Cidr.parse("10.80." + vipThirdOctet + ".0/24");
    final Cidr lbCidr = Cidr.parse("10.80." + hostThirdOctet + ".64/26");

    // FABRIC: one /24 per cluster inside its bare-metal's /20, at slot 1+roleId — slot 0 being
    // ndh's
    // host infra (the bridge gateway, the dynamic pool, the vz /30 at slot 8). Third octet reads as
    // hostId*16 + slot, so the address says which machine and which cluster it belongs to.
    //
    // This REPLACES a slice of the home network (the high half of 192.168.1.0/24, carved
    // asymmetrically by role from 128 + hostId*48 + roleId*16). That carve was contorted by a
    // constraint that no longer exists: it had to dodge the bbox's DHCP pool and the family's
    // devices, in a /24 rke2lab did not own. The fabric /20 IS ours, so each cluster takes a whole
    // /24 and the asymmetry goes. That rke2lab emits nothing on the home tier is the tier-purity
    // invariant this buys — see docs/architecture/atlas/netplan.adoc.
    //
    // The /26s are ALLOCATIONS, not broadcast domains: one bridge per bare-metal carries the whole
    // /21, so a node's prefix and gateway come from DHCP (the bridge's own, in slot 0). The
    // reserved
    // TEST host (hostId 2) gets slot 33/34, a valid slice, rather than overflowing; a genuinely
    // unknown host fails fast in hostId().
    //
    // The per-node address is derived for EVERY node, but it is only HELD where a reservation can
    // bind — see fabricMacIsPredictable(). That is not a hedge, it is the same split the bbox rows
    // encoded: a host-grown node carries the hwaddr this blueprint poses, a CAPN node's is minted
    // by
    // the provider because the LXCMachineTemplate is attached to the control plane and therefore
    // SHARED by every node it makes.
    //
    // What changes with the move is the AUTHORITY, not the information. The reservation leaves the
    // bbox — a DHCP server on a network rke2lab had no title to allocate in — for the bare-metal's
    // own dnsmasq, which we run. The same dnsmasq answers the NAME, so address and name stop being
    // served by two different things (bbox for DHCP, avahi for mDNS), which is the split that made
    // an mDNS name necessary in the first place.
    //
    // A cattle node is found by NAME: dns.mode=dynamic registers `<cluster>-<node>.<host>` from its
    // own DHCP hostname, which is what serves the BOOTSTRAP direction where the cluster cannot yet
    // be reached. Past that first contact the cluster is authoritative about its own nodes.
    final int fabricThirdOctet = hostId * FABRIC_SLOTS_PER_HOST + 1 + roleId;
    final Cidr fabricNodeCidr = Cidr.parse(FABRIC_PREFIX + "." + fabricThirdOctet + ".0/26");
    final Cidr fabricLbCidr = Cidr.parse(FABRIC_PREFIX + "." + fabricThirdOctet + ".64/26");

    // Each host derives from the CIDR we already hold — ask the network for its host, instead of
    // rebuilding and re-parsing an address string that re-encodes the same octets.
    final InetAddress clusterGatewayInetaddr = clusterCidr.gateway();
    final InetAddress nodeGatewayInetaddr = clusterGatewayInetaddr;
    final InetAddress nodeHostInetaddr = nodeCidr.host(10 + nodeId);

    final InetAddress vipGatewayInetaddr = vipCidr.gateway();
    final InetAddress vipHostInetaddr = vipCidr.host(10);

    final InetAddress fabricHostInetaddr = fabricNodeCidr.host(3 + nodeId);
    // The fabric gateway is the bare-metal's bridge address, in slot 0: inside the /21 the bridge
    // carries, outside this cluster's /24. A foreign address resolved by a Cidr in whose space it
    // lives (address manipulation is part of the type's role) — the same shape the bbox's .254 had.
    final InetAddress fabricGatewayInetaddr =
        fabricNodeCidr.address(FABRIC_PREFIX + "." + (hostId * FABRIC_SLOTS_PER_HOST) + ".1");
    final InetAddress fabricHeadscaleInetaddr = fabricLbCidr.host(1);
    final InetAddress fabricTailscaleInetaddr = fabricLbCidr.host(2);

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
    // Honoured only on the STANDALONE node, which attaches its own inline NICs; a CAPN node's
    // hwaddr
    // is incus-minted because its template is shared (see the fabric derivation above). Kept
    // because
    // the standalone path is real, not as a promise about CAPN nodes.
    final MacAddress fabricHostMacaddr =
        MacAddress.parse(String.format("10:66:6a:4c:%02x:%02x", clusterId, nodeId));
    final MacAddress fabricBridgeMacaddr =
        MacAddress.parse(String.format("02:00:00:bb:%02x:%02x", clusterId, nodeId));

    // IPv6 ULA mirror (see ULA_PREFIX): /48 super ⊃ /56 cluster ⊃ /64 per role, each host
    // embedding its IPv4 in the low 32 bits.
    final Cidr superNetworkCidr6 = Cidr.parse(ULA_PREFIX + "::/48");
    final Cidr clusterCidr6 = Cidr.parse(String.format("%s:%02x00::/56", ULA_PREFIX, clusterId));
    final Cidr nodeCidr6 = ula64(clusterId, ROLE_NODE);
    final Cidr vipCidr6 = ula64(clusterId, ROLE_VIP);
    final Cidr lbCidr6 = ula64(clusterId, ROLE_LB);
    final Cidr fabricNodeCidr6 = ula64(clusterId, ROLE_FABRIC_NODE);
    final Cidr fabricLbCidr6 = ula64(clusterId, ROLE_FABRIC_LB);

    final InetAddress nodeGateway6 = ula6(clusterId, ROLE_NODE, nodeGatewayInetaddr);
    final InetAddress nodeHost6 = ula6(clusterId, ROLE_NODE, nodeHostInetaddr);
    final InetAddress vipGateway6 = ula6(clusterId, ROLE_VIP, vipGatewayInetaddr);
    final InetAddress vipHost6 = ula6(clusterId, ROLE_VIP, vipHostInetaddr);
    final InetAddress fabricHost6 = ula6(clusterId, ROLE_FABRIC_NODE, fabricHostInetaddr);
    final InetAddress fabricGateway6 = ula6(clusterId, ROLE_FABRIC_NODE, fabricGatewayInetaddr);
    final InetAddress fabricHeadscale6 = ula6(clusterId, ROLE_FABRIC_LB, fabricHeadscaleInetaddr);
    final InetAddress fabricTailscale6 = ula6(clusterId, ROLE_FABRIC_LB, fabricTailscaleInetaddr);

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
        new FabricPlan(
            fabricNodeCidr,
            fabricLbCidr,
            fabricHostInetaddr,
            fabricGatewayInetaddr,
            fabricHeadscaleInetaddr,
            fabricTailscaleInetaddr,
            fabricHostMacaddr,
            fabricBridgeMacaddr,
            fabricNodeCidr6,
            fabricLbCidr6,
            fabricHost6,
            fabricGateway6,
            fabricHeadscale6,
            fabricTailscale6),
        new WanPlan(wanDhcpRange, wanHostMacaddr),
        new InterfacePlan(nodeName + "-fabric0", nodeName + "-vmnet0", "fabric0", "vmnet0"),
        new VlanPlan(100, "rke2-vlan"),
        new NamePlan(
            clusterName + "-" + nodeName,
            clusterName + "-" + nodeName + "." + hostOf(clusterName),
            // The incus/nixos daemon host = <host>-nixos (the bare host, not the <host>-<role>
            // cluster). The operator-facing authority is config's rke2lab:cluster:remoteIncus; this
            // is the netplan-domain mirror derived from the same bare host.
            hostOf(clusterName) + "-nixos",
            // Same zone as nodeFabricFqdn above — the bare-metal's OWN dnsmasq serves both, so the
            // infra host and its instances answer from one authority.
            "nixos." + hostOf(clusterName)));
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
        + ",fabric-node:"
        + fabric.nodeCidr()
        + ",fabric-lb:"
        + fabric.lbCidr();
  }

  /** The bare host token — the {@code <host>} of {@code <host>-<role>} (up to the first dash). */
  private static String hostOf(String clusterName) {
    final int dash = clusterName.indexOf('-');
    return dash < 0 ? clusterName : clusterName.substring(0, dash);
  }

  /** This cluster's {@link ClusterRole} — parsed from its {@code <host>-<role>} name. */
  public ClusterRole role() {
    return ClusterRole.of(cluster.name());
  }

  /**
   * This cluster's Incus {@code vmnet} bridge name — the per-cluster INTERNAL bridge each cluster
   * gets its own (isolated /21, gateway .1). Deterministic + role-scoped ({@code vmnet-mgmt},
   * {@code vmnet-wrkld}) so BOTH the standalone grow (which creates + attaches the node's NIC) and
   * CAPN (which attaches provisioned workload instances) name the SAME bridge. Role-scoped, not
   * host-scoped: each host runs its own Incus daemon, so the name need not carry the host. Stays
   * within Incus's 15-char network-name limit ({@code vmnet-} + role ≤ 11).
   */
  public String vmnetBridgeName() {
    return "vmnet-" + role().token();
  }

  /**
   * Every cluster co-located on the SAME host as THIS one — one per {@link ClusterRole}, itself
   * included. The host's grow ensures a vmnet bridge (+ dnsmasq reservations) for each, so a
   * workload cluster is DHCP-provisionable in-cluster (CAPN) even though only the management node
   * is grown standalone. Purely functional off the blueprint (no Incus query, no manifests facet).
   */
  public List<String> clustersOnSameHost() {
    final String host = hostOf(cluster.name());
    return List.of(ClusterRole.values()).stream().map(role -> host + "-" + role.token()).toList();
  }

  private static int hostId(String host) {
    final Integer id = HOST_IDS.get(host);
    if (id == null) {
      throw new IllegalArgumentException(
          "unknown host '"
              + host
              + "' — no slice allocated; add it to HOST_IDS and to the addressing plan (carved"
              + " hosts: "
              + String.join(", ", HOST_IDS.keySet())
              + ")");
    }
    return id;
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

  /**
   * The cluster's plane in the FABRIC tier — what is reachable across bare-metals, and the tier a
   * node now lives on instead of the home network.
   *
   * <p>{@code nodeCidr} is an ALLOCATION, not an interface prefix: one bridge per bare-metal
   * carries the whole /21, so a node's prefix and gateway come from DHCP. This /26 holds only
   * STATIC reservations, which is why no dynamic range is carved beside them — the bare-metal's
   * pool lives in slot 0 (ndh's {@code dynamicCidr}), one broadcast domain below, and cannot be
   * subdivided per cluster: dnsmasq has no discriminator to bind a cattle node to its own cluster's
   * window.
   *
   * <p>Whether {@code hostInetaddr} is actually HELD therefore depends on {@link
   * ClusterNetworkBlueprint#fabricMacIsPredictable()}: a reservation binds by MAC, and only a
   * host-grown node has one this blueprint predicts. A cattle node draws from slot 0 instead and is
   * found by NAME, not by address.
   */
  public record FabricPlan(
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

  /**
   * Interface names, on BOTH sides of the container boundary — the distinction is load-bearing and
   * was long implicit here.
   *
   * <p>{@code fabricInterface} / {@code wanInterface} are the HOST-side nics incus attaches to its
   * two bridges, so they carry the node name ({@code <node>-fabric0}, {@code <node>-vmnet0}) — the
   * host needs them unique across every instance it runs.
   *
   * <p>{@code nodeFabricInterface} / {@code vipInterface} are the names the node sees from INSIDE
   * ({@code fabric0}, {@code vmnet0}) — node-independent, because each container has its own
   * namespace. They are what in-cluster workloads must be told: kube-vip announces the VIP on
   * {@code vipInterface}, and cilium's device set is exactly this pair (see {@code
   * CiliumConfigManifestsUnit} for why declaring it is not optional).
   */
  public record InterfacePlan(
      String fabricInterface,
      String wanInterface,
      String nodeFabricInterface,
      String vipInterface) {}

  public record VlanPlan(int id, String name) {}

  /**
   * The identity-derived NAMES the cluster resolves nodes and infra hosts by — the single source
   * for hostnames domains otherwise re-concatenate. {@code nodeHostname} is the bare {@code
   * <cluster>-<node>}; {@code nodeFabricFqdn} the same name under its bare-metal's zone, served by
   * that bare-metal's dnsmasq; {@code nixosHost} the {@code <cluster>-nixos} builder/daemon host.
   * Ports are NOT here: a port is a fixed service constant, not identity-derived — each domain
   * pairs a name from here with its own port.
   *
   * <p>{@code nodeFabricFqdn} REPLACES an mDNS {@code .local} name, and the swap is one of
   * authority, not spelling. A node is no longer on the home L2, so mDNS — which is link-local and
   * never routed — could only ever have answered a same-LAN asker. Its bare-metal's dnsmasq
   * registers the name from the node's own DHCP hostname and is reachable over the tailnet, so it
   * serves the asker who cannot yet reach the cluster. That is the only audience needing it: once
   * the cluster answers, the cluster is authoritative about its own nodes.
   *
   * <p>The infra host keeps {@code nixosHost} (bare) for two uses, and the distinction between them
   * is the whole point. As a NAME TO RESOLVE it is correct only on the host itself, which means
   * MYSELF by it — NixOS writes {@code 127.0.0.2 <hostname>} in {@code /etc/hosts}. Everything with
   * an audience resolves {@link #nixosFabricFqdn} instead, and that is ONE form, not three.
   *
   * <p>As an IDENTITY it is the Incus CLUSTER MEMBER name, and the warning above does not apply
   * because nothing resolves it: it is a key in Incus's own member table, used to place an instance
   * ({@code LXCMachineTemplate.spec.target}, {@code incus cluster add}). ndh derives the same
   * string independently ({@code memberNameOf entry = "${entry.domain}-nixos"} in {@code
   * modules/nixos/incus-cluster.nix}), which is the contract the two repos share — one is an
   * address, the other an identity, and conflating them is how a pod came to dial itself.
   *
   * <p>Why one and not three. The two forms it replaces each had a hidden dependency the name did
   * not show. A bare host name is worse than unresolvable from a pod: the vmnet bridge's dnsmasq —
   * first in the resolver list a pod inherits — answered it from that {@code /etc/hosts}, so a pod
   * dialling {@code <host>-nixos} could reach ITSELF ("certificate is valid for localhost"). And a
   * {@code .lan} name depended on the RESIDENTIAL ROUTER, an authority that stops answering for a
   * bare-metal the moment it leaves the home LAN — a dependency that looks green right up to the
   * day it breaks. {@code nixosFabricFqdn} has neither: the answering authority is the bare-metal
   * that OWNS the network, the same one already trusted for {@code nodeFabricFqdn}, and it is
   * reachable over the tailnet split-DNS. So resolution does not change with the transport, and the
   * same-host and cross-host cases stop being different cases — which is why no consumer needs to
   * ask whose host a cluster is on.
   *
   * <p>The record beside it in ndh: a {@code host-record} for {@code nixos.<host>} on the
   * bare-metal's own bridge, next to the {@code vzhost.<host>} already there ({@code
   * catalog/default.nix}, the {@code <domain>-baremetal-net} segment).
   */
  public record NamePlan(
      String nodeHostname, String nodeFabricFqdn, String nixosHost, String nixosFabricFqdn) {}

  /**
   * Canonical cluster topology: 1 master, 3 control nodes (peers), 2 worker nodes.
   *
   * <p>This is the expected node composition for every cluster managed by the rke2lab
   * control-plane.
   */
  public record ClusterTopology(int masterCount, int controlNodeCount, int workerNodeCount) {

    /**
     * The shape of a WORKLOAD cluster: 1 master + 3 peers + 2 workers, the full canonical roster.
     */
    public static final ClusterTopology CANONICAL = new ClusterTopology(1, 3, 2);

    /**
     * The topology a cluster of this ROLE actually has. Management is SINGLE-NODE, and that is not
     * a temporary state: the LAN carve sizes it as one ({@code /29} node slice, filled at {@code
     * host(3)}), so a management cluster that enumerated the full roster would place {@code
     * worker1} on the {@code /29}'s broadcast address and {@code worker2} on the neighbouring
     * {@code lb} network. {@link #CANONICAL} described "every cluster" while the carve described
     * one — the disagreement is what let the overflow go unseen, because a consumer that read the
     * roster without a role got plausible-looking addresses outside its own slice.
     */
    public static ClusterTopology of(final ClusterRole role) {
      return switch (role) {
        case MGMT -> new ClusterTopology(1, 0, 0);
        case WRKLD -> CANONICAL;
      };
    }

    public int totalNodeCount() {
      return masterCount + controlNodeCount + workerNodeCount;
    }

    /**
     * This topology's node names, in order — the prefix of {@link #CANONICAL_NODE_NAMES} it fills.
     * A consumer that must ENUMERATE a cluster's nodes reads this, not the canonical list: the list
     * is the ordered superset every roster is cut from, and cutting it is exactly what the role
     * decides.
     */
    public List<String> nodeNames() {
      return CANONICAL_NODE_NAMES.subList(0, totalNodeCount());
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
