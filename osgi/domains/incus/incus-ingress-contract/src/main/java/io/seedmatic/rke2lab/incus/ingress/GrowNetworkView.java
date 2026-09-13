package io.seedmatic.rke2lab.incus.ingress;

import java.util.Map;
import java.util.TreeMap;

/**
 * The flat NETWORK view the GROW poses on the Pulumi graph — the grown node's two NIC hardware
 * addresses (lan0 on the canonical LAN bridge, vmnet0 on its cluster's bridge), the name of that
 * vmnet bridge, and the resolved config of EVERY vmnet bridge the host must carry.
 *
 * <p>vmnet is isolated per-cluster (each cluster gets its own bridge + /21 + dnsmasq reservations),
 * so a host that also hosts a workload cluster needs that cluster's bridge to exist BEFORE CAPN can
 * DHCP-provision its instances in-cluster — even though only the management node grows standalone.
 * {@link #bridges} is therefore keyed by bridge name ({@code
 * ClusterNetworkBlueprint.vmnetBridgeName}), one entry per cluster on the host; {@link
 * #nodeBridgeName} is the one the grown node attaches to.
 *
 * <p>These all originate in the {@code ClusterNetworkBlueprint} ({@code netplan-contract},
 * OSGi-only), which the host cannot read TYPED. So the scion resolves {@code
 * NetplanSynthesisService} (a published {@code @Component}), assembles the bridge configs OSGi-side
 * (pure netplan logic — no host state, no com.pulumi), and projects the flat values here. The host
 * receives the result and only poses it; it computes nothing of the network.
 */
public record GrowNetworkView(
    String lanHwaddr,
    String wanHwaddr,
    String nodeBridgeName,
    Map<String, Map<String, String>> bridges) {

  public GrowNetworkView {
    final TreeMap<String, Map<String, String>> canonical = new TreeMap<>();
    bridges.forEach((name, config) -> canonical.put(name, new TreeMap<>(config)));
    bridges = canonical;
  }
}
