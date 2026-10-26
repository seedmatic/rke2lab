package io.seedmatic.rke2lab.netplan.contract;

/**
 * The pod and service bands every cluster id draws its CIDRs from: pod {@code 10.<podBase+id>},
 * service {@code 10.<serviceBase+id>}, each {@code width} ids wide, with the v6 halves taking the
 * same digits ({@code 10.128} ↔ {@code fd00:128}). The service band starts where the pod band ends,
 * so the two are disjoint for every id the bands hold, and an id past them is refused rather than
 * wrapped into a neighbour's range.
 *
 * <p>They used to sit four apart ({@code 10.44+id} / {@code 10.48+id}), which made the {@code test}
 * host's pod {@code 10.48} the root cluster's service {@code 10.48}. The plan is the netplan
 * atlas's ({@code atlas/netplan.adoc}, "make clusterId an explicit bit-field").
 */
public record ClusterBands(int podBase, int width) {

  public ClusterBands {
    if (width < 1 || podBase < 0 || podBase + 2 * width > 256) {
      throw new IllegalArgumentException(
          "bands of " + width + " ids from 10." + podBase + " do not fit the second octet");
    }
  }

  /**
   * The bands the fleet uses: pod from {@code 10.128}, service from {@code 10.160}, 32 ids each,
   * one per cluster a byte-wide {@code clusterId} names. The pod base clears the vmnet supernet at
   * {@code 10.80}, and the v6 digits land in {@code [0x128, 0x159]} and {@code [0x160, 0x191]},
   * disjoint too.
   */
  public static ClusterBands defaults() {
    return new ClusterBands(128, 32);
  }

  public int serviceBase() {
    return podBase + width;
  }

  public String podCidr(int id) {
    return "10." + (podBase + inBand(id)) + ".0.0/16";
  }

  public String serviceCidr(int id) {
    return "10." + (serviceBase() + inBand(id)) + ".0.0/16";
  }

  public String podCidrV6(int id) {
    return "fd00:" + (podBase + inBand(id)) + "::/56";
  }

  public String serviceCidrV6(int id) {
    return "fd00:" + (serviceBase() + inBand(id)) + "::/112";
  }

  private int inBand(int id) {
    if (id < 0 || id >= width) {
      throw new IllegalArgumentException(
          "cluster id " + id + " is outside the pod and service bands (0–" + (width - 1) + ")");
    }
    return id;
  }
}
