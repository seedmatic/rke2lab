package io.seedmatic.rke2lab.manifests.contract;

/**
 * How many control nodes a WORKLOAD cluster's control-plane pool asks for — declared as a SHAPE,
 * not a number.
 *
 * <p>★ A word rather than an integer because the two values are not arbitrary points on a scale:
 * {@code single} is one node with no quorum, and {@code ha} is THREE because three is the smallest
 * etcd quorum that survives one loss. Two would be strictly worse than one (it needs both members
 * to keep quorum, so it doubles the failure surface for no gain), and four buys nothing over three
 * while costing a node. Exposing a free integer would invite exactly those.
 *
 * <p>A MANAGEMENT cluster is not configurable: it is ONE control node by construction (see the
 * seeding spec), so this applies to {@link ClusterRole#WRKLD} alone.
 */
public enum ControlPlaneShape {
  /** One control node, no quorum — a lab or a resource-constrained host. */
  SINGLE("single", 1),
  /** Three control nodes = the smallest etcd quorum that survives losing one. */
  HA("ha", 3);

  private final String token;
  private final int replicas;

  ControlPlaneShape(final String token, final int replicas) {
    this.token = token;
    this.replicas = replicas;
  }

  public String token() {
    return token;
  }

  /** The control-node count this shape asks for. */
  public int replicas() {
    return replicas;
  }

  /**
   * The shape for a declared token. LOUD on anything it cannot read — the same discipline as {@link
   * ClusterRole#ofToken}, and for the same reason: a catch-all here would silently size a control
   * plane, which is the one thing that must never be guessed.
   */
  public static ControlPlaneShape ofToken(final String token) {
    return switch (token) {
      case "single" -> SINGLE;
      case "ha" -> HA;
      default ->
          throw new IllegalArgumentException(
              "unknown control-plane shape '" + token + "' — expected one of single, ha");
    };
  }
}
