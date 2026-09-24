package io.seedmatic.rke2lab.netplan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class ClusterNetworkBlueprintTest {

  @Test
  void topology_isCanonical_withOnemaster_threeControlNodes_twoWorkers() {
    ClusterNetworkBlueprint.ClusterTopology topology =
        ClusterNetworkBlueprint.ClusterTopology.CANONICAL;

    assertEquals(1, topology.masterCount());
    assertEquals(3, topology.controlNodeCount());
    assertEquals(2, topology.workerNodeCount());
    assertEquals(6, topology.totalNodeCount());
  }

  @Test
  void deriveRecipeModel_forNikopolMgmtMaster_producesDeterministicAddressing() {
    ClusterNetworkBlueprint blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster("nikopol-mgmt")
            .node("master")
            .deriveRecipeModel()
            .build();

    // nikopol-mgmt packs clusterId (host<<1)|role = (1<<1)|0 = 2 (see the cluster addressing plan).
    assertEquals(2, blueprint.cluster().id());
    assertEquals("10.80.16.0/21", blueprint.host().clusterCidr().toString());
    assertEquals("10.80.16.1", blueprint.host().clusterGatewayInetaddr().getHostAddress());
    assertEquals("10.80.16.10", blueprint.nodeNetwork().nodeHostInetaddr().getHostAddress());
    assertEquals("52:54:00:02:00:00", blueprint.wan().hostMacaddr().value());
  }

  @Test
  void deriveRecipeModel_forNikopolMgmtMaster_producesDeterministicIPv6Mirror() throws Exception {
    ClusterNetworkBlueprint blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster("nikopol-mgmt")
            .node("master")
            .deriveRecipeModel()
            .build();

    // /48 super ⊃ /56 cluster (CC=02) ⊃ /64 per role — byte-boundary mirror of the IPv4 hierarchy.
    assertEquals(48, blueprint.host().superNetworkCidr6().prefixLength());
    assertEquals(
        InetAddress.getByName("fd96:6924:3693::"),
        blueprint.host().superNetworkCidr6().networkAddress());
    assertEquals(56, blueprint.host().clusterCidr6().prefixLength());
    assertEquals(
        InetAddress.getByName("fd96:6924:3693:200::"),
        blueprint.host().clusterCidr6().networkAddress());
    assertEquals(64, blueprint.nodeNetwork().nodeCidr6().prefixLength());
    assertEquals(
        InetAddress.getByName("fd96:6924:3693:220::"),
        blueprint.nodeNetwork().nodeCidr6().networkAddress());

    // Each host embeds its IPv4 verbatim in the low 32 bits, so v4↔v6 is inferable by inspection.
    assertEquals(
        InetAddress.getByName("fd96:6924:3693:220::10.80.16.10"),
        blueprint.nodeNetwork().nodeHostInetaddr6());
    assertEquals(
        InetAddress.getByName("fd96:6924:3693:250::172.16.16.1"),
        blueprint.fabric().gatewayInetaddr6());
  }

  @Test
  void deriveRecipeModel_forNikopolMgmtMaster_carvesTheMgmtFabricSlot() {
    ClusterNetworkBlueprint blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster("nikopol-mgmt")
            .node("master")
            .deriveRecipeModel()
            .build();

    // nikopol hostId 1, mgmt roleId 0 -> slot 1*16 + 1 + 0 = 17, so the cluster's /24 is
    // 172.16.17.0:
    // nodes draw from .0/26, the LB pool is .64/26 (headscale host(1), tailscale host(2)). The
    // gateway is the bare-metal's bridge address in SLOT 0 — inside the /21 the bridge carries,
    // outside this cluster's /24, which is why it reads 172.16.16.1 and not 172.16.17.1.
    assertEquals("172.16.17.0/26", blueprint.fabric().nodeCidr().toString());
    assertEquals("172.16.17.64/26", blueprint.fabric().lbCidr().toString());
    assertEquals("172.16.16.1", blueprint.fabric().gatewayInetaddr().getHostAddress());
    assertEquals("172.16.17.65", blueprint.fabric().headscaleInetaddr().getHostAddress());
    assertEquals("172.16.17.66", blueprint.fabric().tailscaleInetaddr().getHostAddress());
  }

  @Test
  void deriveRecipeModel_forTheReservedTestCluster_carvesSlot33() {
    // The blank/unknown cluster identity falls back to the reserved "test-mgmt" cluster
    // (DefaultNodeEnvContext), which must derive a valid, distinct slice — NOT overflow the octet.
    // test hostId 2, mgmt roleId 0 -> slot 2*16 + 1 = 33; clusterId (2<<1)|0 = 4.
    ClusterNetworkBlueprint blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster("test-mgmt")
            .node("master")
            .deriveRecipeModel()
            .build();

    assertEquals(4, blueprint.cluster().id());
    assertEquals("172.16.33.0/26", blueprint.fabric().nodeCidr().toString());
    assertEquals("172.16.33.64/26", blueprint.fabric().lbCidr().toString());
    assertEquals("172.16.32.1", blueprint.fabric().gatewayInetaddr().getHostAddress());
  }
}
