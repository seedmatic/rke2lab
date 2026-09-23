package io.seedmatic.rke2lab.bbox.core;

import io.seedmatic.rke2lab.bbox.contract.BboxReservationRequest;
import io.seedmatic.rke2lab.manifests.contract.ClusterRole;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint.ClusterTopology;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Enumerates the canonical RKE2 reservation requests from {@link ClusterNetworkBlueprint}.
 *
 * <p>Owns the canonical clusters/nodes lists and the MAC-prefix safety check. Knows nothing about
 * the bbox API — it produces flat {@link BboxReservationRequest}s (the bbox-record vocabulary)
 * carrying the {@code (cluster, node)} identity alongside the MAC/IP/hostname triple; the bbox-edge
 * turns each into the library's reservation behind the contact. Pure (bbox-record + netplan-port,
 * no Pulumi), it is the bbox scion's collaborator that is not a resolved service — so it lives here
 * in the domain core, wired bundle-to-bundle to the scion that drives it.
 */
public final class BlueprintRowEnumerator {

  /**
   * MAC prefix the {@link ClusterNetworkBlueprint} assigns to RKE2 LAN interfaces: {@code
   * 10:66:6a:4c:{clusterId:02x}:{nodeId:02x}}. Used here as the ownership boundary so non-RKE2
   * reservations stay out of scope.
   */
  public static final String RKE2_LAN_MAC_PREFIX = "10:66:6a:4c:";

  /** The bare hosts whose clusters this provisioner reserves for. */
  public static final List<String> CANONICAL_HOSTS = List.of("bioskop", "nikopol");

  /**
   * The clusters a LAN reservation can actually serve — {@code <host>-mgmt} only.
   *
   * <p>A reservation is keyed by MAC, and only a HOST-GROWN node has one this blueprint predicts
   * ({@code 10:66:6a:4c:{clusterId}:{nodeId}}, posed by the grow). A workload cluster's nodes are
   * CAPN-provisioned cattle whose MAC the provider mints at random — measured 2026-09-23: {@code
   * bioskop-wrkld-control-plane-899lr} came up on {@code 192.168.1.18}, an ordinary router lease,
   * with a MAC sharing only the OUI. So a row for a workload node describes a match that can never
   * happen, and asking the bbox to hold an address nothing will ever claim is worse than useless:
   * it consumes the carve.
   *
   * <p>⚠️ This list used to be the bare HOSTS, which was the same defect the netplan projection
   * carried until {@code 237d4be65}: {@code ClusterRole.of("bioskop")} has no dash to split, so it
   * falls back to MGMT and the enumeration silently described two pseudo-clusters instead of
   * failing. Naming the role explicitly is what keeps that fallback from hiding the question.
   */
  public static final List<String> RESERVED_CLUSTERS =
      CANONICAL_HOSTS.stream().map(host -> host + "-" + ClusterRole.MGMT.token()).toList();

  private final List<String> clusters;

  public BlueprintRowEnumerator() {
    this(RESERVED_CLUSTERS);
  }

  /** Visible for tests — pin the cluster list explicitly. */
  public BlueprintRowEnumerator(List<String> clusters) {
    this.clusters = List.copyOf(clusters);
  }

  /**
   * Materialise a {@link BboxReservationRequest} per (cluster, node) of every reserved cluster,
   * deriving each from the blueprint and asserting the RKE2 MAC-prefix invariant.
   *
   * <p>The nodes come from the cluster's ROLE ({@link ClusterTopology#nodeNames()}), never from
   * {@link ClusterNetworkBlueprint#CANONICAL_NODE_NAMES}: that list is the ordered SUPERSET a
   * roster is cut from, and enumerating it whole for a single-node management cluster walks
   * straight off the end of its {@code /29} LAN slice — {@code worker1} would land on the slice's
   * broadcast address and {@code worker2} on the neighbouring lb network.
   */
  public List<BboxReservationRequest> rows() {
    final List<BboxReservationRequest> out = new ArrayList<>();
    for (String cluster : clusters) {
      for (String node : ClusterTopology.of(ClusterRole.of(cluster)).nodeNames()) {
        final ClusterNetworkBlueprint bp =
            ClusterNetworkBlueprint.builder()
                .cluster(cluster)
                .node(node)
                .deriveRecipeModel()
                .build();
        final String mac = bp.lan().hostMacaddr().value();
        if (!mac.toLowerCase(Locale.ROOT).startsWith(RKE2_LAN_MAC_PREFIX)) {
          throw new IllegalStateException(
              "Blueprint produced non-RKE2 MAC for "
                  + cluster
                  + "/"
                  + node
                  + " ("
                  + mac
                  + "); reconciliation aborted to avoid scope creep.");
        }
        out.add(
            new BboxReservationRequest(
                cluster,
                node,
                mac,
                bp.lan().hostInetaddr().getHostAddress(),
                cluster + "-" + node));
      }
    }
    return List.copyOf(out);
  }
}
