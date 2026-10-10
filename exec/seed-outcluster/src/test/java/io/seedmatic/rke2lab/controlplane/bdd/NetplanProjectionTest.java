package io.seedmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.netplan.ingress.NetplanIngressCoordinate;
import io.seedmatic.rke2lab.seed.broker.internal.CodecCellar;
import io.seedmatic.rke2lab.seed.broker.port.OpaqueCellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The operator kubeconfig contexts read netplan's addressing from the projection the netplan soil
 * harvested this run — through the real codec cellar, so a record stored scion-side is read back as
 * JSON host-side, the way the realms meet. A missing harvest fails the run, and the checked-in
 * {@code network-blueprint.json} is never a fallback.
 */
class NetplanProjectionTest {

  private static final Parcel PARCEL = new Parcel("rke2lab", "bioskop-mgmt");

  // A backend that keeps what it is given, keyed like the real one by domain and coordinate slug.
  private static final class KeptCellar implements OpaqueCellar {
    private final Map<String, SeedEnvelope> kept = new LinkedHashMap<>();

    private static String key(String domain, String coordinate) {
      return domain + "/" + coordinate;
    }

    @Override
    public void store(Parcel parcel, SeedEnvelope envelope) {
      kept.put(key(envelope.domain(), envelope.coordinate()), envelope);
    }

    @Override
    public List<SeedEnvelope> fetch(Parcel parcel) {
      return new ArrayList<>(kept.values());
    }

    @Override
    public Optional<SeedEnvelope> fetch(Parcel parcel, SeedCoordinate coordinate) {
      return Optional.ofNullable(kept.get(key(coordinate.domain(), coordinate.slug())));
    }

    @Override
    public Optional<SeedEnvelope> withdraw(Parcel parcel, SeedCoordinate coordinate) {
      return Optional.ofNullable(kept.remove(key(coordinate.domain(), coordinate.slug())));
    }

    @Override
    public List<Parcel> neighbours(Parcel parcel) {
      return List.of();
    }
  }

  // Stands for the scion's NetworkBlueprintMetadata: any record the codec renders to the same tree.
  record Projection(Map<String, Object> addressing) {}

  private static ClusterSeedScenario.When when(CodecCellar cellar) {
    final ClusterSeedScenario.When when = new ClusterSeedScenario.When();
    when.workingCellar = cellar;
    when.parcel = PARCEL;
    return when;
  }

  @Test
  void the_addressing_is_read_from_the_harvested_projection() {
    final CodecCellar cellar = new CodecCellar(new KeptCellar(), null);
    cellar.store(
        PARCEL,
        NetplanIngressCoordinate.PROJECTION,
        new Projection(
            Map.of("bioskop-mgmt", Map.of("master", Map.of("ips", Map.of("v4", "172.16.1.10"))))));

    assertEquals(
        "172.16.1.10",
        when(cellar)
            .addressing()
            .path("bioskop-mgmt")
            .path("master")
            .path("ips")
            .path("v4")
            .asText());
  }

  @Test
  void a_missing_harvest_fails_even_with_the_root_file_present() throws Exception {
    final Path rootFile = Path.of("network-blueprint.json").toAbsolutePath();
    final boolean planted = !Files.exists(rootFile);
    if (planted) {
      Files.writeString(rootFile, "{\"addressing\":{\"bioskop-mgmt\":{}}}");
    }
    try {
      final IllegalStateException refused =
          assertThrows(
              IllegalStateException.class,
              () -> when(new CodecCellar(new KeptCellar(), null)).addressing());
      assertTrue(
          refused.getMessage().contains("was not harvested in this run"), refused.getMessage());
    } finally {
      if (planted) {
        Files.delete(rootFile);
      }
    }
  }

  @Test
  void a_projection_without_an_addressing_tree_fails() {
    final CodecCellar cellar = new CodecCellar(new KeptCellar(), null);
    cellar.store(PARCEL, NetplanIngressCoordinate.PROJECTION, Map.of("segments", Map.of()));

    final IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> when(cellar).addressing());
    assertTrue(refused.getMessage().contains("carries no addressing tree"), refused.getMessage());
  }
}
