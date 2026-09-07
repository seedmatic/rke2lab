package io.seedmatic.rke2lab.manifests.contract;

/**
 * A workload cluster the MANAGEMENT render must create. Carried on the manifests FACET (as {@link
 * ManifestsRunbookInput.Facets#workloadTargets()}) so a run rendering {@code manifests/<host>-mgmt}
 * can emit the CAPI CR set for these DIFFERENT clusters — model B: the CRs live where CAPI runs
 * (the management cluster), never on a {@code -wrkld} branch.
 *
 * <p>{@code host} + {@code role} name the target; its {@link #clusterName()} is {@code
 * <host>-<role>}, the key from which the synthesis derives the whole {@code
 * ClusterNetworkBlueprint} (a workload is HA — the CANONICAL {@code master + peer1 + peer2 (+
 * workers)} topology, not carried here). An empty target list means the management cluster creates
 * nothing yet.
 */
public record WorkloadTarget(String host, String role) {

  /**
   * The target cluster's name {@code <host>-<role>} — the key the blueprint and branch derive from.
   */
  public String clusterName() {
    return host + "-" + role;
  }
}
