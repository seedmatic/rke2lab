// @codebase
package io.seedmatic.rke2lab.manifests.contract.profiles;

/**
 * Cluster network topology slice published to synth-time layers via {@link
 * io.seedmatic.rke2lab.manifests.ManifestSynthesisContext}. Centralizes the CIDRs and interface
 * names previously templated by kpt setters ({@code ${cluster-cidr}}, {@code
 * ${node-host-inetaddr}}, {@code ${cluster-vip-cidr}}, …).
 *
 * <p>Stage B (CAPN-managed nodes) will read most of these to produce {@code LXCMachineTemplate} /
 * {@code Cluster} resources, so the slice is shaped to match what the Cluster API provider needs.
 *
 * <p>Empty defaults are intentional: when nothing is bound, layers that look up topology data emit
 * a clearly-blank value, which is easier to spot in rendered manifests than a silent fallback to a
 * live address.
 */
public record NetworkTopology(
    String clusterCidr,
    String clusterPodCidr,
    String clusterServiceCidr,
    String nodeHostInetAddr,
    String nodeHostInet6Addr,
    String nodeNetworkCidr,
    String nodeNetworkGatewayAddr,
    String clusterLoadBalancerCidr,
    String clusterLoadBalancerGatewayAddr,
    String fabricInterface,
    String fabricLoadBalancerCidr,
    String wanInterface,
    String vipInterface,
    String vipCidr,
    String vipGatewayInetAddr,
    String vipHostInetAddr,
    String fabricHostMacAddr,
    String wanHostMacAddr) {

  private static final NetworkTopology DEFAULT = builder().build();

  public NetworkTopology {
    clusterCidr = nullToBlank(clusterCidr);
    clusterPodCidr = nullToBlank(clusterPodCidr);
    clusterServiceCidr = nullToBlank(clusterServiceCidr);
    nodeHostInetAddr = nullToBlank(nodeHostInetAddr);
    nodeHostInet6Addr = nullToBlank(nodeHostInet6Addr);
    nodeNetworkCidr = nullToBlank(nodeNetworkCidr);
    nodeNetworkGatewayAddr = nullToBlank(nodeNetworkGatewayAddr);
    clusterLoadBalancerCidr = nullToBlank(clusterLoadBalancerCidr);
    clusterLoadBalancerGatewayAddr = nullToBlank(clusterLoadBalancerGatewayAddr);
    fabricInterface = nullToBlank(fabricInterface);
    fabricLoadBalancerCidr = nullToBlank(fabricLoadBalancerCidr);
    wanInterface = nullToBlank(wanInterface);
    vipInterface = nullToBlank(vipInterface);
    vipCidr = nullToBlank(vipCidr);
    vipGatewayInetAddr = nullToBlank(vipGatewayInetAddr);
    vipHostInetAddr = nullToBlank(vipHostInetAddr);
    fabricHostMacAddr = nullToBlank(fabricHostMacAddr);
    wanHostMacAddr = nullToBlank(wanHostMacAddr);
  }

  /** Empty topology — used when nothing has been bound (tests, ephemeral runs). */
  public static NetworkTopology empty() {
    return DEFAULT;
  }

  public static Builder builder() {
    return new Builder();
  }

  private static String nullToBlank(String value) {
    return value == null ? "" : value;
  }

  /**
   * The recommended construction path: names each address/CIDR/interface so the sixteen network
   * strings can't be positionally swapped.
   */
  public static final class Builder {
    private String clusterCidr = "";
    private String clusterPodCidr = "";
    private String clusterServiceCidr = "";
    private String nodeHostInetAddr = "";
    private String nodeHostInet6Addr = "";
    private String nodeNetworkCidr = "";
    private String nodeNetworkGatewayAddr = "";
    private String clusterLoadBalancerCidr = "";
    private String clusterLoadBalancerGatewayAddr = "";
    private String fabricInterface = "";
    private String fabricLoadBalancerCidr = "";
    private String wanInterface = "";
    private String vipInterface = "";
    private String vipCidr = "";
    private String vipGatewayInetAddr = "";
    private String vipHostInetAddr = "";
    private String fabricHostMacAddr = "";
    private String wanHostMacAddr = "";

    private Builder() {}

    public Builder clusterCidr(final String v) {
      this.clusterCidr = v;
      return this;
    }

    public Builder clusterPodCidr(final String v) {
      this.clusterPodCidr = v;
      return this;
    }

    public Builder clusterServiceCidr(final String v) {
      this.clusterServiceCidr = v;
      return this;
    }

    public Builder nodeHostInetAddr(final String v) {
      this.nodeHostInetAddr = v;
      return this;
    }

    public Builder nodeHostInet6Addr(final String v) {
      this.nodeHostInet6Addr = v;
      return this;
    }

    public Builder nodeNetworkCidr(final String v) {
      this.nodeNetworkCidr = v;
      return this;
    }

    public Builder nodeNetworkGatewayAddr(final String v) {
      this.nodeNetworkGatewayAddr = v;
      return this;
    }

    public Builder clusterLoadBalancerCidr(final String v) {
      this.clusterLoadBalancerCidr = v;
      return this;
    }

    public Builder clusterLoadBalancerGatewayAddr(final String v) {
      this.clusterLoadBalancerGatewayAddr = v;
      return this;
    }

    public Builder fabricInterface(final String v) {
      this.fabricInterface = v;
      return this;
    }

    public Builder fabricLoadBalancerCidr(final String v) {
      this.fabricLoadBalancerCidr = v;
      return this;
    }

    public Builder wanInterface(final String v) {
      this.wanInterface = v;
      return this;
    }

    public Builder vipInterface(final String v) {
      this.vipInterface = v;
      return this;
    }

    public Builder vipCidr(final String v) {
      this.vipCidr = v;
      return this;
    }

    public Builder vipGatewayInetAddr(final String v) {
      this.vipGatewayInetAddr = v;
      return this;
    }

    public Builder vipHostInetAddr(final String v) {
      this.vipHostInetAddr = v;
      return this;
    }

    public Builder fabricHostMacAddr(final String v) {
      this.fabricHostMacAddr = v;
      return this;
    }

    public Builder wanHostMacAddr(final String v) {
      this.wanHostMacAddr = v;
      return this;
    }

    public NetworkTopology build() {
      return new NetworkTopology(
          clusterCidr,
          clusterPodCidr,
          clusterServiceCidr,
          nodeHostInetAddr,
          nodeHostInet6Addr,
          nodeNetworkCidr,
          nodeNetworkGatewayAddr,
          clusterLoadBalancerCidr,
          clusterLoadBalancerGatewayAddr,
          fabricInterface,
          fabricLoadBalancerCidr,
          wanInterface,
          vipInterface,
          vipCidr,
          vipGatewayInetAddr,
          vipHostInetAddr,
          fabricHostMacAddr,
          wanHostMacAddr);
    }
  }
}
