package io.seedmatic.rke2lab.netplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.netplan.contract.Cidr;
import io.seedmatic.rke2lab.netplan.contract.ClusterBands;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * No two clusters' address spans overlap: pod and service, in both families, vmnet and the ULA
 * cluster prefix. A pod route and a service route on the same fabric must never cover the same
 * address, or traffic for one is captured by the other. Checked for every declared cluster, the
 * {@code test} host's included (the blank-identity fallback), and for every id a fleet of up to
 * four hosts can take, so the carve fails here before a host is added.
 */
class ClusterSpansDoNotOverlapTest {

  private record Span(String owner, Cidr cidr) {
    BigInteger first() {
      return new BigInteger(1, cidr.networkAddress().getAddress());
    }

    BigInteger last() {
      final int bits = cidr.networkAddress().getAddress().length * 8;
      return first()
          .add(BigInteger.ONE.shiftLeft(bits - cidr.prefixLength()))
          .subtract(BigInteger.ONE);
    }

    boolean sameFamily(Span other) {
      return cidr.networkAddress().getAddress().length
          == other.cidr.networkAddress().getAddress().length;
    }

    boolean overlaps(Span other) {
      return sameFamily(other)
          && first().compareTo(other.last()) <= 0
          && other.first().compareTo(last()) <= 0;
    }
  }

  private static List<Span> spansOf(ClusterNetworkBlueprint blueprint) {
    final String name = blueprint.cluster().name();
    final List<Span> spans = new ArrayList<>();
    spans.add(new Span(name + " pod", Cidr.parse(blueprint.podCidr())));
    spans.add(new Span(name + " pod6", Cidr.parse(blueprint.podCidrV6())));
    spans.add(new Span(name + " service", Cidr.parse(blueprint.serviceCidr())));
    spans.add(new Span(name + " service6", Cidr.parse(blueprint.serviceCidrV6())));
    spans.add(new Span(name + " vmnet", blueprint.host().clusterCidr()));
    spans.add(new Span(name + " ula", blueprint.host().clusterCidr6()));
    return spans;
  }

  private static List<String> overlapsIn(List<Span> spans) {
    final List<String> found = new ArrayList<>();
    for (int i = 0; i < spans.size(); i++) {
      for (int j = i + 1; j < spans.size(); j++) {
        if (spans.get(i).overlaps(spans.get(j))) {
          found.add(spans.get(i).owner() + " ∩ " + spans.get(j).owner());
        }
      }
    }
    return found;
  }

  private static ClusterNetworkBlueprint blueprint(String cluster) {
    return ClusterNetworkBlueprint.builder()
        .cluster(cluster)
        .node("master")
        .deriveRecipeModel()
        .build();
  }

  @Test
  void no_two_declared_clusters_share_an_address() {
    final List<Span> spans = new ArrayList<>();
    for (ClusterNetworkBlueprint.HostRef host : ClusterNetworkBlueprint.HOST_SLOTS) {
      for (String role : List.of("mgmt", "wrkld")) {
        spans.addAll(spansOf(blueprint(host.name() + "-" + role)));
      }
    }
    assertEquals(
        ClusterNetworkBlueprint.HOST_SLOTS.size() * 2 * 6,
        spans.size(),
        "every cluster contributes");
    assertEquals(List.of(), overlapsIn(spans));
  }

  @Test
  void every_id_a_byte_wide_cluster_id_names_stays_disjoint() {
    final List<Span> spans = new ArrayList<>();
    final ClusterBands bands = ClusterNetworkBlueprint.BANDS;
    for (int id = 0; id < bands.width(); id++) {
      spans.add(new Span("pod " + id, Cidr.parse(bands.podCidr(id))));
      spans.add(new Span("pod6 " + id, Cidr.parse(bands.podCidrV6(id))));
      spans.add(new Span("service " + id, Cidr.parse(bands.serviceCidr(id))));
      spans.add(new Span("service6 " + id, Cidr.parse(bands.serviceCidrV6(id))));
      spans.add(new Span("vmnet " + id, Cidr.parse("10.80." + (8 * id) + ".0/21")));
    }
    assertEquals(32 * 5, spans.size(), "32 clusters, five spans each");
    assertEquals(List.of(), overlapsIn(spans));
  }

  @Test
  void every_vmnet_lies_in_the_supernet_the_nodes_route() {
    final Span supernet = new Span("supernet", blueprint("bioskop-mgmt").host().superNetworkCidr());
    for (int id = 0; id < ClusterNetworkBlueprint.BANDS.width(); id++) {
      final Span vmnet = new Span("vmnet " + id, Cidr.parse("10.80." + (8 * id) + ".0/21"));
      assertTrue(
          vmnet.first().compareTo(supernet.first()) >= 0
              && vmnet.last().compareTo(supernet.last()) <= 0,
          vmnet.owner() + " lies outside " + supernet.cidr());
    }
  }

  @Test
  void the_overlap_check_itself_sees_an_overlap() {
    final List<Span> spans =
        List.of(
            new Span("a", Cidr.parse("10.48.0.0/16")),
            new Span("b", Cidr.parse("10.48.4.0/24")),
            new Span("c", Cidr.parse("fd00:48::/56")),
            new Span("d", Cidr.parse("fd00:48::/112")));
    assertEquals(List.of("a ∩ b", "c ∩ d"), overlapsIn(spans));
    assertTrue(
        overlapsIn(
                List.of(
                    new Span("e", Cidr.parse("10.48.0.0/16")),
                    new Span("f", Cidr.parse("fd00:48::/56"))))
            .isEmpty(),
        "two families never overlap");
  }
}
