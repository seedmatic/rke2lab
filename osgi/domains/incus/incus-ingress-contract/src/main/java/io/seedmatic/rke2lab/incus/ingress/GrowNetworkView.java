package io.seedmatic.rke2lab.incus.ingress;

import java.util.Map;
import java.util.TreeMap;

/**
 * The flat NETWORK view the GROW poses on the Pulumi graph — the grown node's two NIC hardware
 * addresses (fabric0 on the canonical fabric bridge, vmnet0 on its cluster's bridge), the name of
 * that vmnet bridge, and the resolved config of EVERY vmnet bridge the host must carry.
 *
 * <p>vmnet is isolated per-cluster (each cluster gets its own bridge + /21 + dnsmasq reservations),
 * so a host that also hosts a workload cluster needs that cluster's bridge to exist BEFORE CAPN can
 * DHCP-provision its instances in-cluster — even though only the management node grows standalone.
 * {@link #clusterBridges} is therefore keyed by CLUSTER NAME ({@code
 * ClusterNetworkBlueprint#clustersOnSameHost}), one entry per cluster on the host, each carrying
 * its bridge name + resolved config — the cluster name is what names the per-cluster {@code
 * node-<cluster>} incus profile (the NIC-bearing profile CAPN references, created at grow); {@link
 * #nodeBridgeName} is the one the grown node attaches to.
 *
 * <p>★ {@link #profiledClusters} is a DIFFERENT set, and separating the two is the point. A bridge
 * is created on THIS host, so {@code clusterBridges} must stay strictly co-located. A {@code
 * node-<cluster>} PROFILE is a cluster-wide incus object, and it is needed by every cluster this
 * render provides for — including one whose bridge lives on ANOTHER member (model B: bioskop-mgmt
 * births nikopol-mgmt, whose vmnet bridge is nikopol-nixos'). The two sets coincided while every
 * child was co-located, and conflating them was measured on 2026-09-27: CAPN refused the birth with
 * "Requested profile node-nikopol-mgmt doesn't exist", while widening {@code clusterBridges}
 * instead would have created a bridge on the wrong host — and collided, since the bridge name is
 * ROLE-scoped ({@code vmnet-mgmt}) so two mgmt clusters on different members want the same name.
 *
 * <p>★ {@link #grownCluster} names the cluster the grown node belongs to, and it exists because the
 * bridge name cannot identify it: a bridge name is ROLE-scoped ({@code vmnet-mgmt}), so every mgmt
 * cluster in the fleet shares one. Matching the grown node's profile on the bridge was unambiguous
 * only while the set was co-located — widening it to the fleet made the last entry win, silently,
 * and the profiles being identical today is what would have hidden it.
 *
 * <p>The value is just the bridge NAME, which is all a profile needs (the NIC's {@code parent}) and
 * which resolves PER MEMBER: an instance targeted at nikopol-nixos attaches to nikopol's {@code
 * vmnet-mgmt}. That is what makes a non-co-located profile possible without any remote discovery.
 *
 * <p>These all originate in the {@code ClusterNetworkBlueprint} ({@code netplan-contract},
 * OSGi-only), which the host cannot read TYPED. So the scion resolves {@code
 * NetplanSynthesisService} (a published {@code @Component}), assembles the bridge configs OSGi-side
 * (pure netplan logic — no host state, no com.pulumi), and projects the flat values here. The host
 * receives the result and only poses it; it computes nothing of the network.
 */
public record GrowNetworkView(
    String fabricHwaddr,
    String wanHwaddr,
    String nodeBridgeName,
    String grownCluster,
    Map<String, ClusterBridge> clusterBridges,
    Map<String, String> profiledClusters) {

  public GrowNetworkView {
    clusterBridges = new TreeMap<>(clusterBridges);
    profiledClusters = new TreeMap<>(profiledClusters);
  }

  /** One co-located cluster's vmnet bridge: its name and the resolved incus network config. */
  public record ClusterBridge(String bridgeName, Map<String, String> config) {
    public ClusterBridge {
      config = new TreeMap<>(config);
    }
  }
}
