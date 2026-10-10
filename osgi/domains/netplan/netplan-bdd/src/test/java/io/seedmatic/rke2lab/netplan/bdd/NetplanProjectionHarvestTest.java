package io.seedmatic.rke2lab.netplan.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import inet.ipaddr.IPAddressString;
import io.seedmatic.rke2lab.netplan.contract.Cidr;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import io.seedmatic.rke2lab.netplan.ingress.NetplanIngressCoordinate;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.testkit.InMemoryCellar;
import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The netplan export files its projection where the seed host reads the operator's addressing — in
 * a seed run, which publishes a parcel — and files nothing in a plan-CLI run, which publishes none.
 * The projection is the one the export derives from the netplan code, not a file.
 */
class NetplanProjectionHarvestTest {

  private static final Parcel PARCEL = new Parcel("rke2lab", "bioskop-mgmt");

  private static NetplanBlueprintScenario.Then derived() {
    final NetplanBlueprintScenario.When when = new NetplanBlueprintScenario.When();
    when.the_blueprint_metadata_is_derived();
    final NetplanBlueprintScenario.Then then = new NetplanBlueprintScenario.Then();
    then.metadata = Objects.requireNonNull(when.metadata);
    return then;
  }

  @Test
  void a_seed_run_harvests_the_derived_projection() {
    final NetplanBlueprintScenario.Then then = derived();
    final InMemoryCellar cellar = new InMemoryCellar();

    then.the_projection_is_harvested(cellar, Optional.of(PARCEL));

    final Optional<NetplanBlueprintScenario.NetworkBlueprintMetadata> harvested =
        cellar.fetch(
            PARCEL,
            NetplanIngressCoordinate.PROJECTION,
            NetplanBlueprintScenario.NetworkBlueprintMetadata.class);
    assertTrue(harvested.isPresent());
    assertEquals(then.metadata, harvested.orElseThrow());
  }

  /**
   * One address, one spelling — the netplan contract's, since {@link Cidr} is the only thing that
   * renders one. The projection used to publish the same address twice: {@code
   * fd96:6924:3693:120::10.80.8.10} in a node's {@code ips} and {@code
   * fd96:6924:3693:120:0:0:a50:80a} in the segment carrying it, 18 of them in one export, which no
   * consumer could match by string. A site that renders an address its own way fails here.
   */
  @Test
  void every_ipv6_address_is_published_in_one_spelling_the_contract_renders() {
    final String published = new SeedCodec().canonical().writeJson(derived().metadata);
    final Cidr ula = Cidr.parse(ClusterNetworkBlueprint.ULA_PREFIX + "::/48");

    final Matcher literals = Pattern.compile("\"([0-9a-fA-F:.]+)(?:/\\d+)?\"").matcher(published);
    final Set<InetAddress> seen = new LinkedHashSet<>();
    while (literals.find()) {
      final IPAddressString candidate = new IPAddressString(literals.group(1));
      if (!candidate.isValid() || !candidate.getAddress().isIPv6()) {
        continue;
      }
      final InetAddress address = candidate.getAddress().toInetAddress();
      seen.add(address);
      assertEquals(
          ula.text(address),
          literals.group(1),
          "a second spelling of " + address + " reached the projection");
    }
    assertFalse(seen.isEmpty(), "the projection published no IPv6 address at all");
  }

  @Test
  void a_run_without_a_parcel_harvests_nothing() {
    final InMemoryCellar cellar = new InMemoryCellar();

    derived().the_projection_is_harvested(cellar, Optional.empty());

    assertTrue(
        cellar
            .fetch(
                PARCEL,
                NetplanIngressCoordinate.PROJECTION,
                NetplanBlueprintScenario.NetworkBlueprintMetadata.class)
            .isEmpty());
  }
}
