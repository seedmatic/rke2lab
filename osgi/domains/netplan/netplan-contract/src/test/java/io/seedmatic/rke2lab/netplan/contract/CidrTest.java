package io.seedmatic.rke2lab.netplan.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

/**
 * The notation a network publishes: RFC 5952 for IPv6 — zero groups compressed — with our ULA
 * mirror's embedded IPv4 left readable. Both come out of one renderer, so a network and a host of
 * that network can never disagree on how the same bytes are spelled.
 */
class CidrTest {

  @Test
  void an_ipv6_network_compresses_its_zero_groups() {
    assertEquals("fd96:6924:3693:120::/64", Cidr.parse("fd96:6924:3693:120:0:0:0:0/64").toString());
    assertEquals("fd96:6924:3693::/48", Cidr.parse("fd96:6924:3693:0:0:0:0:0/48").toString());
  }

  @Test
  void a_host_of_an_ipv6_network_keeps_its_embedded_ipv4_readable() throws Exception {
    final Cidr node6 = Cidr.parse("fd96:6924:3693:120::/64");

    // The same 128 bits, whichever way they are written on the way in.
    assertEquals(
        "fd96:6924:3693:120::10.80.8.10",
        node6.text(InetAddress.getByName("fd96:6924:3693:120::10.80.8.10")));
    assertEquals(
        "fd96:6924:3693:120::10.80.8.10",
        node6.text(InetAddress.getByName("fd96:6924:3693:120:0:0:a50:80a")));
  }

  @Test
  void an_ipv4_network_renders_as_it_always_did() {
    assertEquals("10.80.0.0/18", Cidr.parse("10.80.0.0/18").toString());
    assertEquals(
        "10.80.16.1", Cidr.parse("10.80.16.0/21").text(Cidr.parse("10.80.16.0/21").gateway()));
  }
}
