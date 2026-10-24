package io.seedmatic.rke2lab.host.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The double lock: the stack's committed profile and the run's marker must agree. */
class GrowProfileTest {

  private static final Optional<String> NONE = Optional.empty();
  private static final Optional<String> THROWAWAY = Optional.of("throwaway");

  @Test
  void no_profile_and_no_marker_is_the_fleet() {
    assertEquals(GrowProfile.FLEET, GrowProfile.resolve(NONE, NONE));
    assertEquals(GrowProfile.FLEET, GrowProfile.resolve(Optional.of(" "), Optional.of("")));
  }

  @Test
  void a_throwaway_stack_with_its_marker_is_the_throwaway_profile() {
    assertEquals(GrowProfile.THROWAWAY, GrowProfile.resolve(THROWAWAY, THROWAWAY));
  }

  @Test
  void a_throwaway_stack_without_the_marker_is_refused() {
    final IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> GrowProfile.resolve(THROWAWAY, NONE));
    assertTrue(refused.getMessage().contains("rke2lab-throwaway"), refused.getMessage());
  }

  @Test
  void a_stray_marker_on_a_fleet_stack_is_refused() {
    final IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> GrowProfile.resolve(NONE, THROWAWAY));
    assertTrue(refused.getMessage().contains(GrowProfile.MARKER_ENV), refused.getMessage());
  }

  @Test
  void an_unknown_profile_name_or_marker_value_is_refused() {
    assertThrows(
        IllegalStateException.class, () -> GrowProfile.resolve(Optional.of("fleet"), NONE));
    assertThrows(
        IllegalStateException.class, () -> GrowProfile.resolve(NONE, Optional.of("fleet")));
    assertThrows(
        IllegalStateException.class, () -> GrowProfile.resolve(THROWAWAY, Optional.of("fleet")));
  }
}
