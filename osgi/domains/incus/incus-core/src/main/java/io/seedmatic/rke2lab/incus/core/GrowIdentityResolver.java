package io.seedmatic.rke2lab.incus.core;

import io.seedmatic.rke2lab.incus.ingress.GrowIdentityView;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import io.seedmatic.rke2lab.netplan.contract.NetplanSynthesisRequest;
import io.seedmatic.rke2lab.netplan.contract.NetplanSynthesisService;

/**
 * Assembles the flat {@link GrowIdentityView} the host GROW poses on the instance's {@code
 * user.rke2lab.node-*} keys — the four per-node scalars (name, {@code <cluster>-<node>} hostname,
 * server/agent kind, numeric id). Like {@link GrowNetworkResolver} it reads the {@link
 * NetplanSynthesisService} and reduces the blueprint's node ref OSGi-side (the
 * scion-projects/host-actualises rule), so the host derives NO identity — it only poses what the
 * scion resolved.
 *
 * <p>The kind is {@code NodeType.kind()} — the SAME single source the manifests node-env identity
 * projects — so a node's role never diverges between the two channels. The hostname mirrors the
 * dnsmasq lease {@link GrowNetworkResolver} emits ({@code <cluster>-<node>}), the name the instance
 * grows under.
 */
public final class GrowIdentityResolver {

  private final NetplanSynthesisService netplan;

  public GrowIdentityResolver(NetplanSynthesisService netplan) {
    this.netplan = netplan;
  }

  /** Resolve the identity view for the {@code node} of {@code cluster} the instance grows for. */
  public GrowIdentityView resolve(String cluster, String node) {
    final ClusterNetworkBlueprint blueprint =
        netplan.synthesize(new NetplanSynthesisRequest(cluster, node)).blueprint();
    final String hostname = cluster + "-" + blueprint.node().name();
    return new GrowIdentityView(
        blueprint.node().name(), hostname, blueprint.node().type().kind(), blueprint.node().id());
  }
}
