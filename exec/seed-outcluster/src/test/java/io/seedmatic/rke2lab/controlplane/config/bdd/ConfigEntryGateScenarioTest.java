package io.seedmatic.rke2lab.controlplane.config.bdd;

import com.tngtech.jgiven.junit5.ScenarioTest;
import io.seedmatic.rke2lab.controlplane.config.ConfigLoader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The configuration entry gate, told as behaviour. Asserts the gate OUTCOME only — ready vs which
 * mandatory inputs are missing. Resolved-value coverage lives in {@code Rke2labConfigTest} (the
 * gate does not re-assert it, to avoid duplicating coverage). Live plays this same gate at
 * provisioning time against the real Pulumi config loader.
 */
class ConfigEntryGateScenarioTest
    extends ScenarioTest<ConfigEntryGate.Given, ConfigEntryGate.When, ConfigEntryGate.Then> {

  private static ConfigLoader loaderOf(Map<String, Map<String, Object>> sections) {
    final Map<String, Object> root = new LinkedHashMap<>(sections);
    return ConfigLoader.ofNestedRoot(root);
  }

  private static Map<String, Map<String, Object>> completeConfiguration() {
    final Map<String, Map<String, Object>> sections = new HashMap<>();
    sections.put("incus", Map.of("configDir", "/Users/nxmatic/.config/incus"));
    return sections;
  }

  @Test
  void complete_configuration_passes_the_gate() {
    given().the_operator_configuration(loaderOf(completeConfiguration()));
    when().the_configuration_is_loaded();
    then().the_configuration_is_ready();
  }

  @Test
  void missing_mandatory_inputs_are_reported() {
    // Empty configuration: the mandatory input absent.
    given().the_operator_configuration(loaderOf(Map.of()));
    when().the_configuration_is_loaded();
    then().the_missing_inputs_are("incus.configDir");
  }
}
