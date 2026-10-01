package io.seedmatic.rke2lab.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Rke2labConfigTest {

  @Test
  void populates_infra_and_cross_cutting() {
    final Rke2labConfig config = OperatorConfiguration.full().asDto();
    assertEquals(Optional.of("bioskop"), config.cluster().host());
    assertEquals(Optional.of("mgmt"), config.cluster().role());
    assertEquals(Optional.of("rke2lab"), config.incus().project());
    assertEquals(Path.of("/Users/nxmatic/.config/incus"), config.incus().configDir());
    assertEquals(Optional.of("bioskop-master"), config.systemd().dbusHost());
    assertEquals(Optional.of(12434), config.systemd().dbusPort());
  }

  @Test
  void omitted_optional_is_empty() {
    final Rke2labConfig config = OperatorConfiguration.full().asDto();
    assertTrue(config.cluster().remoteIncus().isEmpty());
    assertTrue(config.network().fabricBridgeParent().isEmpty());
    assertTrue(config.kubeconfig().ref().isEmpty());
  }

  @Test
  void single_missing_mandatory_reported_by_name() {
    final MissingRequiredConfiguration ex =
        assertThrows(
            MissingRequiredConfiguration.class,
            () -> OperatorConfiguration.full().without("incus.configDir").asDto());
    assertEquals(List.of("incus.configDir"), ex.keys());
  }

  @Test
  void defaults_path_does_not_validate_mandatory() {
    // Offline path: empty config must NOT throw.
    final Rke2labConfig config = Rke2labConfig.defaults();
    assertTrue(config.cluster().host().isEmpty());
  }
}
