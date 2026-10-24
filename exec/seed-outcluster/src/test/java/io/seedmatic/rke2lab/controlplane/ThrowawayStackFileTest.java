package io.seedmatic.rke2lab.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.seedmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.seedmatic.rke2lab.controlplane.config.ConfigLoader;
import io.seedmatic.rke2lab.controlplane.config.Rke2labConfig;
import io.seedmatic.rke2lab.host.runtime.GrowProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The committed throwaway stack file, read the way the grow reads it: it says throwaway, it derives
 * the outputs the installed command checks, and it names nothing of the fleet.
 */
class ThrowawayStackFileTest {

  private static final String STACK = "nikopol";
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private static JsonNode stackConfig() throws IOException {
    Path dir = Path.of("").toAbsolutePath();
    while (!Files.exists(dir.resolve("Pulumi.yaml"))) {
      dir = dir.getParent();
    }
    return YAML.readTree(dir.resolve("Pulumi." + STACK + ".yaml").toFile()).path("config");
  }

  /** {@code rke2lab:<section>} keys to the nested root the config loader reads. */
  private static BootstrapConfig bootstrap(JsonNode config) {
    final Map<String, Object> root = new LinkedHashMap<>();
    config
        .properties()
        .forEach(
            entry ->
                root.put(
                    entry.getKey().substring("rke2lab:".length()),
                    YAML.convertValue(entry.getValue(), Map.class)));
    return BootstrapConfig.from(Rke2labConfig.from(ConfigLoader.ofNestedRoot(root)));
  }

  @Test
  void it_is_a_throwaway_stack() throws IOException {
    final JsonNode config = stackConfig();
    assertEquals(
        GrowProfile.THROWAWAY,
        GrowProfile.resolve(
            Optional.of(config.path("rke2lab:cluster").path("profile").asText()),
            Optional.of(GrowProfile.THROWAWAY_NAME)));
  }

  @Test
  void it_derives_the_outputs_the_installed_command_checks() throws IOException {
    assertEquals(
        Map.of(
            ThrowawayOutputs.INCUS_PROJECT, "throwaway-nikopol",
            ThrowawayOutputs.NODE, "nikopol-mgmt-master",
            ThrowawayOutputs.API_SERVER, "https://nikopol-mgmt-master.nikopol:6443"),
        new ThrowawayOutputs(STACK, bootstrap(stackConfig())).values());
  }

  @Test
  void it_names_nothing_of_the_fleet() throws IOException {
    final JsonNode config = stackConfig();
    assertTrue(config.findValues("secure").isEmpty(), "a throwaway stack holds no secret");
    assertFalse(config.path("rke2lab:cluster").has("remoteIncus"), "the remote derives from host");
    assertFalse(config.path("rke2lab:incus").has("remoteAddress"), "the address derives from host");
    assertFalse(config.path("rke2lab:manifests").has("incusTargets"), "it spans no fleet");
    assertEquals("false", config.path("rke2lab:manifests").path("delivery").path("push").asText());
  }
}
