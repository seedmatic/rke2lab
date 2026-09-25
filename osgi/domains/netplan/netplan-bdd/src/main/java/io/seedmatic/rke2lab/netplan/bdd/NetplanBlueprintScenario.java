package io.seedmatic.rke2lab.netplan.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import inet.ipaddr.IPAddressString;
import io.seedmatic.rke2lab.manifests.contract.ClusterRole;
import io.seedmatic.rke2lab.netplan.contract.ClusterAsn;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint.ClusterTopology;
import io.seedmatic.rke2lab.netplan.contract.NetplanRunbookInput;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.InputReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioInputSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The netplan blueprint-export scenario — a production jGiven scenario told in the NETPLAN DOMAIN's
 * own vocabulary and played IN-CONTAINER by the engine. It is the transposition of the former flat
 * {@code BlueprintExportCommand}: from the (hardcoded) cluster/node topology it derives every
 * node's {@link ClusterNetworkBlueprint} and projects the complete addressing metadata (MACs, IPv4
 * + the ULA v6 mirror, DHCP leases), then MATERIALISES it as {@code blueprint.json} into the SOIL.
 *
 * <p>Why in-container and not a flat CLI dump: {@link ClusterNetworkBlueprint} is a {@code
 * type=contract} bundle record — it lives in the bundle realm and cannot cross the host frontier as
 * a TYPE (a flat reference {@code NoClassDefFoundError}s, the realm-boundary law is right). So the
 * derivation runs HERE, where the type is reachable, and the result crosses to the host as pure
 * JSON: the scion serialises the metadata tree to {@code blueprint.json}, the plan CLI (network
 * plane) reads that generic JSON (never the contract type) and converts it to YAML flat. SAFE for
 * the flake bridge — nix-darwin-home re-parses via {@code yq -o=json}, so only the DATA matters,
 * not YAML formatting.
 *
 * <p>MODE-BLIND like the manifests scion: a pure FS materialiser with no live touch, so it runs
 * identically in both modes; the materialisation target is carried by the SOIL amendment alone (the
 * host's export dir when amended, a temp dir for a bare survey). The input is seeded by the
 * front-door via the inbound {@link #INPUT} channel and received here ({@link InputReceiver})
 * before the play.
 */
@SeedScenario
public class NetplanBlueprintScenario
    extends ScenarioTestBase<
        NetplanBlueprintScenario.Given,
        NetplanBlueprintScenario.When,
        NetplanBlueprintScenario.Then>
    implements InputReceiver<NetplanRunbookInput>, ScenarioPlayer.Playable {

  /**
   * The inbound channel the runbook handler ({@code NetplanRunbookHandler.seedFrom}) seeds the
   * {@link NetplanRunbookInput} through and this scenario receives it from. Single-sourced here.
   */
  @RegisterExtension
  public static final ScenarioInputSeed<NetplanRunbookInput> INPUT =
      new ScenarioInputSeed<>(NetplanRunbookInput.class, "netplan-runbook-input");

  private final Scenario<Given, When, Then> scenario = createScenario();

  @MonotonicNonNull private NetplanRunbookInput input;

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveInput(NetplanRunbookInput input) {
    this.input = input;
  }

  @Test
  void the_blueprint_is_exported_to_the_soil() {
    final NetplanRunbookInput facet =
        Objects.requireNonNull(input, "the netplan runbook input was not seeded before the body");
    given().the_runbook_input(facet);
    when().the_blueprint_metadata_is_derived().and().the_blueprint_is_written_as_json();
    then().the_blueprint_file_is_written();
  }

  /** Given: the runbook input carrying the SOIL to materialise into. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState NetplanRunbookInput facet;

    @Hidden
    public Given the_runbook_input(NetplanRunbookInput facet) {
      this.facet = facet;
      return self();
    }
  }

  /**
   * When: the transposition of {@code BlueprintExportCommand.execute}. Derives the complete
   * addressing metadata from the cluster/node topology (each node's {@link
   * ClusterNetworkBlueprint}) and writes it as {@code blueprint.json} into the SOIL.
   */
  public static class When extends Stage<When> {

    @ExpectedScenarioState NetplanRunbookInput facet;

    @ProvidedScenarioState Path blueprintFile;

    private final SeedCodec codec = new SeedCodec();

    // Derived by the first WHEN step, read by the second on the same stage instance — intra-stage,
    // so a plain field, not a cross-stage @ProvidedScenarioState.
    @MonotonicNonNull private NetworkBlueprintMetadata metadata;

    // One blueprint, for one (cluster, node). Five identical builder chains lived here; the shape
    // is the netplan's, not this scenario's, so it is named once.
    private static ClusterNetworkBlueprint blueprintOf(final String cluster, final String node) {
      return ClusterNetworkBlueprint.builder()
          .cluster(cluster)
          .node(node)
          .deriveRecipeModel()
          .build();
    }

    public When the_blueprint_metadata_is_derived() {
      // The REAL clusters, host x role — not the hosts. This projection used to iterate
      // {bioskop, nikopol} and pass each as a cluster NAME, which ClusterRole.of() read as MGMT
      // (its documented fallback for a dash-less name). So it described two pseudo-clusters, both
      // management, and never mentioned a workload one: 10.80.8.0/21 — live on bioskop-wrkld's node
      // — was absent, its gateway and DHCP range with it. The map also carried HOST ids where
      // cluster ids belong. Both are derived from the blueprint below instead of restated here.
      final List<String> clusterNames = new ArrayList<>();
      for (String host : List.of("bioskop", "nikopol")) {
        for (ClusterRole role : ClusterRole.values()) {
          clusterNames.add(host + "-" + role.token());
        }
      }

      // clusters / nodes were hand-maintained tables of facts the netplan already owns, and both
      // had
      // drifted: the node ids said worker1=10/worker2=11 where nodeId() says 4 and 5. Derived now.
      final Map<String, Integer> clusters = new LinkedHashMap<>();
      final Map<String, Integer> nodes = new LinkedHashMap<>();
      for (String node : ClusterNetworkBlueprint.CANONICAL_NODE_NAMES) {
        nodes.put(node, blueprintOf(clusterNames.get(0), node).node().id());
      }

      final Map<String, String> macPatterns = new LinkedHashMap<>();
      macPatterns.put("fabric", "10:66:6a:4c:{clusterId:02x}:{nodeId:02x}");
      macPatterns.put("wan", "52:54:00:{clusterId:02x}:{nodeType:02x}:{nodeId:02x}");
      macPatterns.put("fabricBridge", "02:00:00:bb:{clusterId:02x}:{nodeId:02x}");

      final Map<String, Integer> nodeTypes = new LinkedHashMap<>();
      nodeTypes.put("SERVER", 0); // master, peer1-3
      nodeTypes.put("AGENT", 1); // worker1-2

      final Map<String, Map<String, NodeAddressing>> allAddressing = new LinkedHashMap<>();
      for (String cluster : clusterNames) {
        final Map<String, NodeAddressing> clusterAddressing = new LinkedHashMap<>();
        // The ROLE's roster, not the canonical superset: a management cluster is single-node, and
        // enumerating six of them would place worker1 on its /29's broadcast address and worker2 on
        // the neighbouring lb network.
        for (String node : ClusterTopology.of(ClusterRole.of(cluster)).nodeNames()) {
          final ClusterNetworkBlueprint bp = blueprintOf(cluster, node);
          clusters.putIfAbsent(cluster, bp.cluster().id());

          final NodeMacs macs =
              new NodeMacs(
                  bp.fabric().hostMacaddr().value(),
                  bp.wan().hostMacaddr().value(),
                  bp.fabric().bridgeMacaddr().value());

          // Present only where a reservation can bind to it — a cattle node's fabric address is the
          // pool's to give, so publishing the one its window WOULD hold is the fiction we removed.
          final boolean reserved = bp.fabricMacIsPredictable();
          final NodeIPs ips =
              new NodeIPs(
                  reserved
                      ? Optional.of(bp.fabric().hostInetaddr().getHostAddress())
                      : Optional.empty(),
                  bp.nodeNetwork().nodeHostInetaddr().getHostAddress(),
                  bp.vip().vipHostInetaddr().getHostAddress(),
                  bp.fabric().gatewayInetaddr().getHostAddress(),
                  bp.nodeNetwork().nodeGatewayInetaddr().getHostAddress(),
                  reserved ? Optional.of(mixed(bp.fabric().hostInetaddr6())) : Optional.empty(),
                  mixed(bp.nodeNetwork().nodeHostInetaddr6()),
                  mixed(bp.fabric().gatewayInetaddr6()),
                  mixed(bp.nodeNetwork().nodeGatewayInetaddr6()),
                  bp.fabric().nodeCidr6().toString(),
                  bp.nodeNetwork().nodeCidr6().toString());

          final NodeLeases leases =
              new NodeLeases(new WanLease(bp.wan().hostMacaddr().value(), bp.wan().dhcpRange()));

          clusterAddressing.put(
              node, new NodeAddressing(macs, ips, leases, bp.names().nodeFabricFqdn()));
        }
        allAddressing.put(cluster, clusterAddressing);
      }

      // Cluster-owned network SEGMENTS — the single source nnh/ndh derive the flow→AS labelling
      // from, now in the uniform shape ndh's baremetal segments share (see the Segment record).
      // rke2lab publishes ONLY what it owns (ClusterAsn 65010/65020); the home segments (65000)
      // belong to ndh. Per cluster: its two FABRIC /26s — the static-reservation window and the LB
      // range — and the vmnet /21 rke2lab actually manages (gateway + DHCP + a dhcp-host per node).
      // The /18 supernet + pod/service/gateway/ULA are attribution spans.
      //
      // A NOTE ON AUTHORITY. The fabric window carries `hosts` but no `gateway`/`dhcp`: rke2lab no
      // longer RUNS this network's DHCP, it REQUESTS reservations on one ndh runs. That is the
      // whole
      // move — the reservations left the bbox, a server on a network rke2lab had no title to
      // allocate
      // in, for the bare-metal's own dnsmasq. Same information, an authority we control.
      //
      // Only MAC-PREDICTABLE nodes get a row, exactly as the bbox enumerator scoped itself to
      // `<host>-mgmt`: a reservation binds by MAC, and a cattle node's is minted by the provider.
      // The
      // rule now lives in the blueprint (fabricMacIsPredictable) rather than in the consumer, so it
      // travels with the addressing law instead of being restated per reader. A cattle node is
      // absent
      // here on purpose — it draws from the bare-metal's shared pool and is found by NAME.
      final List<Segment> segments = new ArrayList<>();
      for (String cluster : clusterNames) {
        final ClusterNetworkBlueprint bp = blueprintOf(cluster, "master");
        final List<SegmentHost> fabricHosts = new ArrayList<>();
        if (bp.fabricMacIsPredictable()) {
          for (String node : ClusterTopology.of(ClusterRole.of(cluster)).nodeNames()) {
            final ClusterNetworkBlueprint nodeBp = blueprintOf(cluster, node);
            fabricHosts.add(
                new SegmentHost(
                    cluster + "-" + node,
                    Optional.of(nodeBp.fabric().hostMacaddr().value()),
                    nodeBp.fabric().hostInetaddr().getHostAddress(),
                    Optional.empty()));
          }
        }
        segments.add(
            new Segment(
                bp.fabric().nodeCidr().toString(),
                cluster + "-fabric",
                bp.bgpLocalAsn(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.copyOf(fabricHosts),
                Optional.empty(),
                Optional.empty()));
        segments.add(
            Segment.attribution(
                bp.fabric().lbCidr().toString(), cluster + "-fabric-lb", bp.bgpLocalAsn()));

        // The per-cluster vmnet /21 (Incus dnsmasq): gateway .1, the WAN DHCP range, and one
        // dhcp-host reservation per canonical node — the SAME tuples GrowNetworkResolver emits into
        // raw.dnsmasq, derived here from the same blueprint source (dns.mode=none → no DNS domain).
        final List<SegmentHost> nodeHosts = new ArrayList<>();
        for (String node : ClusterTopology.of(ClusterRole.of(cluster)).nodeNames()) {
          final ClusterNetworkBlueprint nodeBp = blueprintOf(cluster, node);
          nodeHosts.add(
              new SegmentHost(
                  cluster + "-" + node,
                  Optional.of(nodeBp.wan().hostMacaddr().value()),
                  nodeBp.nodeNetwork().nodeHostInetaddr().getHostAddress(),
                  Optional.of(nodeBp.nodeNetwork().nodeHostInetaddr6().getHostAddress())));
        }
        segments.add(
            new Segment(
                bp.host().clusterCidr().toString(),
                cluster + "-net",
                bp.bgpLocalAsn(),
                Optional.of(bp.host().clusterGatewayInetaddr().getHostAddress()),
                Optional.of(bp.wan().dhcpRange()),
                Optional.empty(),
                nodeHosts,
                // The v6 half of this bridge, and why both halves are needed rather than one
                // `addr/prefix` string: the /64 is NOT derivable from this segment's `cidr` (that
                // is
                // the v4 /21), and a DHCPv6 server needs the prefix and the gateway separately. The
                // node /64 rather than the cluster /56 — dnsmasq rejects a DHCPv6 prefix shorter
                // than /64.
                Optional.of(bp.nodeNetwork().nodeCidr6().toString()),
                Optional.of(bp.nodeNetwork().nodeGatewayInetaddr6().getHostAddress())));
      }
      final ClusterNetworkBlueprint anyNode = blueprintOf(clusterNames.get(0), "master");
      // Shared/attribution spans, labelled with a representative cluster's ASN (anyNode = the mgmt
      // cluster). NOTE: pod/service are per-cluster now (10.<44+id>/10.<48+id>); this vestigial
      // netplan narration emits ONE representative span — not fully per-cluster — pending the
      // netplan-off-NixOS follow-up (the node network is NixOS-declared, so this is off the live
      // path).
      segments.add(
          Segment.attribution(
              anyNode.host().superNetworkCidr().toString(), "vmnet", anyNode.bgpLocalAsn()));
      segments.add(
          Segment.attribution(
              ClusterNetworkBlueprint.GATEWAY_ADDRESS + "/32",
              ClusterAsn.GATEWAY.asName(),
              ClusterAsn.GATEWAY.number()));
      segments.add(Segment.attribution(anyNode.podCidr(), "pod", anyNode.bgpLocalAsn()));
      segments.add(Segment.attribution(anyNode.serviceCidr(), "service", anyNode.bgpLocalAsn()));
      segments.add(
          Segment.attribution(
              ClusterNetworkBlueprint.ULA_PREFIX + "::/48", "ula", anyNode.bgpLocalAsn()));

      // asn → canonical AS name (the 'asns' dictionary nnh renders), derived from the enum. Home
      // (65000) is ndh's, added by its catalog when it unions the home segments.
      final Map<String, String> asns = new LinkedHashMap<>();
      for (ClusterAsn asn : ClusterAsn.values()) {
        asns.put(Integer.toString(asn.number()), asn.asName());
      }

      // The bare-metal host ids, published so ndh DERIVES each bare-metal's fabric slice
      // (172.16.<hostId*16>.0/20) instead of re-declaring a number this table already fixes. The
      // direction matters: ndh imports this blueprint at flake-eval time, so the number could not
      // travel the other way without closing an eval cycle.
      this.metadata =
          new NetworkBlueprintMetadata(
              clusters,
              nodes,
              macPatterns,
              nodeTypes,
              allAddressing,
              segments,
              asns,
              ClusterNetworkBlueprint.HOST_IDS);
      return self();
    }

    public When the_blueprint_is_written_as_json() {
      final Path root = resolveSoil();
      final Path file = root.resolve("blueprint.json");
      try {
        Files.createDirectories(root);
        // SeedCodec renders the metadata tree to JSON — the wire the host reaps. The scion never
        // hands the host a ClusterNetworkBlueprint type, only this serialized String.
        Files.writeString(
            file,
            codec.encode(
                Objects.requireNonNull(
                    metadata, "the metadata step must run before the write step")));
      } catch (IOException ex) {
        throw new UncheckedIOException("cannot write the blueprint export " + file, ex);
      }
      this.blueprintFile = file;
      return self();
    }

    private Path resolveSoil() {
      return facet
          .materializationRoot()
          .map(soil -> Path.of(soil).toAbsolutePath().normalize())
          .orElseGet(this::freshTempDir);
    }

    private Path freshTempDir() {
      try {
        return Files.createTempDirectory("rke2lab-netplan-").toAbsolutePath().normalize();
      } catch (IOException ex) {
        throw new UncheckedIOException("cannot create the blueprint export dir", ex);
      }
    }

    /** Format a v6 address in IPv4-embedded mixed notation (fd..:CCRR::a.b.c.d) for readability. */
    private static String mixed(InetAddress v6) {
      return new IPAddressString(v6.getHostAddress()).getAddress().toIPv6().toMixedString();
    }
  }

  /** Then: the export landed — {@code blueprint.json} exists and is non-empty. */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState Path blueprintFile;

    public Then the_blueprint_file_is_written() {
      if (!Files.exists(blueprintFile)) {
        throw new NetplanExportError(blueprintFile, NetplanExportError.Reason.MISSING);
      }
      final long size;
      try {
        size = Files.size(blueprintFile);
      } catch (IOException ex) {
        throw new UncheckedIOException("cannot stat the blueprint export " + blueprintFile, ex);
      }
      if (size <= 0) {
        throw new NetplanExportError(blueprintFile, NetplanExportError.Reason.EMPTY);
      }
      return self();
    }
  }

  // The exported metadata tree, mirroring the former flat BlueprintExportCommand records EXACTLY so
  // the JSON keys the flake consumes (clusters/nodes/macPatterns/nodeTypes/addressing) are
  // byte-identical. SeedCodec serialises records by component name.
  record NetworkBlueprintMetadata(
      Map<String, Integer> clusters,
      Map<String, Integer> nodes,
      Map<String, String> macPatterns,
      Map<String, Integer> nodeTypes,
      Map<String, Map<String, NodeAddressing>> addressing,
      List<Segment> segments,
      Map<String, String> asns,
      Map<String, Integer> hosts) {}

  /**
   * A network span the cluster owns — uniform with ndh's baremetal segments: the attribution triple
   * ({@code cidr → name → asn}) plus the managed-network facts a segment's dnsmasq carries — a
   * {@code gateway}, the DHCP range ({@code dhcp}), a DNS {@code domain}, and the static {@code
   * hosts} reservations. The rich fields are present ONLY for a network rke2lab actually runs a
   * gateway/DHCP for (the per-cluster vmnet {@code /21}); pure attribution spans (the {@code /18}
   * supernet, the LAN/LB {@code /27}s, pod, service, ula, the gateway {@code /32}) carry empty
   * Optionals and an empty host list — see {@link #attribution}. Optionals (not nulls) so a record
   * built on this JSON stays null-free; Jackson's jdk8 module omits an empty Optional's key
   * (NON_ABSENT), so an attribution span serialises back to just {@code cidr/name/asn/hosts:[]}.
   */
  record Segment(
      String cidr,
      String name,
      int asn,
      Optional<String> gateway,
      Optional<String> dhcp,
      Optional<String> domain,
      List<SegmentHost> hosts,
      Optional<String> cidr6,
      Optional<String> gateway6) {

    /** An attribution-only span: no gateway/DHCP/domain and no static reservations. */
    static Segment attribution(String cidr, String name, int asn) {
      return new Segment(
          cidr,
          name,
          asn,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          List.of(),
          Optional.empty(),
          Optional.empty());
    }
  }

  /**
   * A static reservation on a segment's dnsmasq: a {@code dhcp-host} (a MAC-known cluster node —
   * {@code mac} present) or a {@code host-record} (a MAC-less name — {@code mac} empty). rke2lab
   * emits only dhcp-host nodes; the empty-mac form keeps the shape uniform with ndh's {@code
   * vzhost.<host>} host-record.
   *
   * <p>{@code ip6} is the node's DETERMINISTIC ULA, and it is exported rather than left to the
   * consumer because it cannot be derived from anything else in this JSON: it embeds the node's v4
   * in the low 32 bits of the per-cluster {@code /64}. A consumer running DHCPv6 needs it as a
   * STATEFUL reservation — SLAAC would hand out an EUI-64 address instead, which {@code node-ip}
   * cannot name.
   */
  record SegmentHost(String name, Optional<String> mac, String ip, Optional<String> ip6) {}

  /**
   * {@code fabricFqdn} is a NAME beside the addresses, and it is here because the thing it names
   * has no predictable address: it is what the host-side reader dials for first contact with a
   * node, resolved by that bare-metal's dnsmasq.
   */
  record NodeAddressing(NodeMacs macs, NodeIPs ips, NodeLeases leases, String fabricFqdn) {}

  record NodeMacs(String fabric, String wan, String fabricBridge) {}

  /**
   * A node's addresses, plus the one CLUSTER-scoped address a consumer of this projection needs
   * beside them: {@code vipHost}, the kube-vip control-plane endpoint.
   *
   * <p>It repeats for every node of a cluster, which is the point — the projection is read by node
   * ({@code addressing.<cluster>.<node>.ips}), and a reader that has a node in hand should not have
   * to re-derive {@code 10.80.<clusterId*8+7>.10} to learn where that cluster's apiserver answers.
   * Re-deriving it is exactly what the host would otherwise do, and the netplan is the only thing
   * entitled to: it owns the addressing law.
   *
   * <p>{@code fabricHost} is EMPTY for a cattle node, and the key is then ABSENT from the JSON
   * (Jackson's jdk8 module omits an empty Optional). That absence is the point: a blank string
   * reads as a value, and an address the node never holds is precisely the fiction this migration
   * removed. It is present only where a reservation can bind — where the blueprint predicts the
   * hwaddr, see {@code fabricMacIsPredictable()}.
   *
   * <p>What holds for EVERY node is {@code fabricFqdn}, this record's sibling. So a reader holding
   * a cattle node asks for the name; one holding a management node may ask for either.
   */
  record NodeIPs(
      Optional<String> fabricHost,
      String nodeHost,
      String vipHost,
      String fabricGateway,
      String nodeGateway,
      Optional<String> fabricHost6,
      String nodeHost6,
      String fabricGateway6,
      String nodeGateway6,
      String fabricCidr6,
      String nodeCidr6) {}

  /**
   * Only the vmnet lease remains. The LAN one was a bbox reservation keyed on a MAC that only the
   * standalone node ever carried, so it reserved an address no CAPN node could claim; nothing
   * replaces it, because the fabric pool is dynamic by design.
   */
  record NodeLeases(WanLease wan) {}

  record WanLease(String mac, String dhcpRange) {}
}
