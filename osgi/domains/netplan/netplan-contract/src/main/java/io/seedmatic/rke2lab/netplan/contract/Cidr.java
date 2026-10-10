package io.seedmatic.rke2lab.netplan.contract;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Strongly typed CIDR value backed by IPAddress validation. */
public record Cidr(InetAddress networkAddress, int prefixLength) {

  /** Parse CIDR notation (example: {@code 10.80.0.0/21}). */
  public static Cidr parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("CIDR value must not be blank");
    }

    final IPAddressString addressString = new IPAddressString(value.trim());
    if (!addressString.isValid()) {
      throw new IllegalArgumentException(
          "Invalid CIDR value: " + value + " (" + addressString.getAddressStringException() + ")");
    }

    if (addressString.getAddress() == null || !addressString.getAddress().isPrefixed()) {
      throw new IllegalArgumentException("CIDR prefix is required: " + value);
    }

    final IPAddress address = addressString.getAddress();
    final Integer prefix = address.getNetworkPrefixLength();
    final int maxPrefix = address.isIPv4() ? 32 : 128;
    if (prefix == null || prefix < 0 || prefix > maxPrefix) {
      throw new IllegalArgumentException(
          "CIDR prefix out of range (0.." + maxPrefix + "): " + value);
    }

    return new Cidr(address.toInetAddress(), prefix);
  }

  /**
   * The host at {@code offset} from this network's base address — derived from the CIDR we already
   * hold, so callers ask the network for its hosts instead of rebuilding and re-parsing an address
   * string. Full integer addition, so an offset that crosses an octet boundary is handled
   * correctly.
   */
  public InetAddress host(int offset) {
    return new IPAddressString(networkAddress.getHostAddress())
        .getAddress()
        .increment(offset)
        .toInetAddress();
  }

  /** The conventional gateway of this network: the first host (offset 1). */
  public InetAddress gateway() {
    return host(1);
  }

  /**
   * Resolve a foreign address into an {@link InetAddress} with this type's consistent exception
   * semantics — manipulating inet addresses is part of a network value-type's role. Used for an
   * address that does not derive from this network's own range (e.g. a fixed LAN gateway outside
   * the allocated slice), asked of the {@code Cidr} in whose address space it lives.
   */
  public InetAddress address(String value) {
    try {
      return InetAddress.getByName(value);
    } catch (UnknownHostException exception) {
      throw new IllegalArgumentException("Invalid inet address: " + value, exception);
    }
  }

  /**
   * An address of this network, in the one notation this contract publishes: RFC 5952 — the longest
   * run of zero groups compressed — keeping our ULA mirror's embedded IPv4 readable in the low 32
   * bits ({@code fd96:…:<cc><rr>::10.80.8.10}), the form {@code
   * docs/architecture/atlas/netplan.adoc} documents so the v4 can be read out of the v6. RFC 5952
   * §5 reserves the mixed form for IPv4-mapped addresses, and ours are ULAs that merely carry a v4
   * verbatim: the deviation is deliberate, taken for readability. A v4 network renders its
   * addresses unchanged.
   *
   * <p>Rendering is the network's job for the same reason parsing is ({@link #address(String)}):
   * one notation per address space, settled here, so no caller can publish a second form of the
   * same address.
   */
  public String text(InetAddress address) {
    final IPAddress parsed = new IPAddressString(address.getHostAddress()).getAddress();
    return parsed.isIPv6() ? parsed.toIPv6().toMixedString() : parsed.toCanonicalString();
  }

  @Override
  public String toString() {
    return text(networkAddress) + "/" + prefixLength;
  }
}
