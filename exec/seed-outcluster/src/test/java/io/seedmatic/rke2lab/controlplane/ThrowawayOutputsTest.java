package io.seedmatic.rke2lab.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.seedmatic.rke2lab.controlplane.config.ConfigLoader;
import io.seedmatic.rke2lab.controlplane.config.Rke2labConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The throwaway stack's outputs: the three keys, each derived, and refused when it is not. */
class ThrowawayOutputsTest {

  private static BootstrapConfig config(String project, String role) {
    return BootstrapConfig.from(
        Rke2labConfig.from(
            ConfigLoader.ofNestedRoot(
                Map.of(
                    "cluster", Map.of("host", "nikopol", "role", role, "profile", "throwaway"),
                    "incus", Map.of("project", project, "configDir", "/tmp/incus")))));
  }

  @Test
  void the_outputs_carry_the_three_derived_names() {
    final Map<String, String> outputs =
        new ThrowawayOutputs("nikopol", config("throwaway-nikopol", "mgmt")).values();
    assertEquals(
        Map.of(
            ThrowawayOutputs.INCUS_PROJECT, "throwaway-nikopol",
            ThrowawayOutputs.NODE, "nikopol-mgmt-master",
            ThrowawayOutputs.API_SERVER, "https://nikopol-mgmt-master.nikopol:6443"),
        outputs);
  }

  @Test
  void a_project_that_is_not_the_derived_one_is_refused() {
    for (String project : new String[] {"rke2lab", "default", "throwaway-other"}) {
      final IllegalStateException refused =
          assertThrows(
              IllegalStateException.class,
              () -> new ThrowawayOutputs("nikopol", config(project, "mgmt")).values());
      assertTrue(refused.getMessage().contains("throwaway-nikopol"), refused.getMessage());
    }
  }

  @Test
  void a_workload_role_is_refused() {
    assertThrows(
        IllegalStateException.class,
        () -> new ThrowawayOutputs("nikopol", config("throwaway-nikopol", "wrkld")).values());
  }

  @Test
  void a_stack_name_that_is_not_plain_is_refused() {
    assertThrows(IllegalStateException.class, () -> ThrowawayOutputs.incusProjectOf("../dev"));
    assertThrows(IllegalStateException.class, () -> ThrowawayOutputs.incusProjectOf("Dev"));
  }
}
