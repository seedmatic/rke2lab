package io.seedmatic.rke2lab.manifests.contract;

/**
 * A cluster's COORDINATE — the {@code {host, role}} pair every cluster-level name projects from:
 * {@link #clusterName()} is {@code <host>-<role>}, the key the {@code ClusterNetworkBlueprint}, the
 * rendered branch {@code manifests/<host>-<role>} and the addressing plan's {@code clusterId} are
 * all derived with.
 *
 * <p>Replaces {@code WorkloadTarget}, whose name claimed "workload" for a list that carried {@code
 * {nikopol, mgmt}} — a management cluster birthed by another management cluster (model B). And the
 * set is no longer enumerated by the operator: it DERIVES from the Incus host list, see {@link
 * ClusterFleet}.
 *
 * <p>The role is TYPED here rather than a token string. The pair used to hold a raw {@code String
 * role} that every reader re-parsed through {@link ClusterRole#ofToken}; typing it moves that
 * single loud parse to the edge (yaml decoding / a cluster name) so no reader can forget it.
 */
public record ClusterCoordinate(String host, ClusterRole role) {

  public ClusterCoordinate {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("a cluster coordinate needs an Incus host");
    }
    if (host.indexOf('-') >= 0) {
      // A dash would make clusterName() ambiguous to parse back, and it is also the symptom of the
      // confusion this type exists to end: a caller passing `bioskop-mgmt` (a cluster name) where
      // the HOST belongs. Loud, because the silent version of this mistake made half the clusters
      // vanish from the netplan projection — see ClusterRole.of's hazard note.
      throw new IllegalArgumentException(
          "'"
              + host
              + "' is not an Incus host — a host carries no dash; did you pass a"
              + " <host>-<role> cluster name?");
    }
    if (role == null) {
      throw new IllegalArgumentException("a cluster coordinate needs a role — host " + host);
    }
  }

  /** The cluster's name {@code <host>-<role>} — the key every derivation starts from. */
  public String clusterName() {
    return host + "-" + role.token();
  }

  /**
   * The coordinate of a {@code <host>-<role>} cluster name — the inverse of {@link #clusterName()},
   * LOUD on anything it cannot read (it routes through {@link ClusterRole#ofToken}, never the
   * catch-all {@link ClusterRole#of}).
   */
  public static ClusterCoordinate ofClusterName(final String clusterName) {
    final int dash = clusterName == null ? -1 : clusterName.lastIndexOf('-');
    if (dash <= 0 || dash == clusterName.length() - 1) {
      throw new IllegalArgumentException(
          "cluster name '" + clusterName + "' is not a <host>-<role> coordinate");
    }
    return new ClusterCoordinate(
        clusterName.substring(0, dash), ClusterRole.ofToken(clusterName.substring(dash + 1)));
  }
}
