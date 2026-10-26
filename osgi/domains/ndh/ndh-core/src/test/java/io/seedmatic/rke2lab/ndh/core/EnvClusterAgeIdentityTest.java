package io.seedmatic.rke2lab.ndh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.seed.broker.port.EnclosureGate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The identity is what `pass show seedmatic/cluster-age/<n>` hands over: age-keygen's comment lines
 * and ONE identity line. Every refusal is checked for the one property that matters most here: it
 * never quotes what it read.
 */
class EnvClusterAgeIdentityTest {

  // A fake identity, assembled so the file holds no age-secret shape: the pre-commit leak guard
  // refuses one, and is right to.
  private static final String IDENTITY = "AGE-SECRET-KEY-" + "1" + "Q".repeat(58);
  private static final String KEYGEN_OUTPUT =
      "# created: 2026-10-18T08:30:23+02:00\n# public key: age1test\n" + IDENTITY + "\n";

  private static final EnclosureGate IN_CLUSTER = () -> true;
  private static final EnclosureGate OPERATOR = () -> false;

  @Test
  void the_identity_line_is_read_and_the_comments_left() {
    assertEquals(
        IDENTITY, new EnvClusterAgeIdentity(KEYGEN_OUTPUT, Optional.of(OPERATOR)).identity());
  }

  @Test
  void the_identity_is_read_again_on_every_call() {
    // The fd-3 pipe was drained by its first reader (T4's preview, 2026-10-26); a variable is not.
    final EnvClusterAgeIdentity identity =
        new EnvClusterAgeIdentity(KEYGEN_OUTPUT, Optional.of(OPERATOR));
    identity.identity();
    assertEquals(IDENTITY, identity.identity());
  }

  @Test
  void anything_but_exactly_one_identity_is_refused_without_quoting_it() {
    for (String content : new String[] {"# only comments\n", IDENTITY + "\n" + IDENTITY + "\n"}) {
      final IllegalStateException refused =
          assertThrows(
              IllegalStateException.class,
              () -> new EnvClusterAgeIdentity(content, Optional.of(OPERATOR)).identity());
      assertTrue(refused.getMessage().contains("not exactly one"), refused.getMessage());
      assertFalse(refused.getMessage().contains("AGE-SECRET-KEY"), refused.getMessage());
    }
  }

  @Test
  void present_follows_the_variable_and_the_enclosure() {
    assertTrue(new EnvClusterAgeIdentity(KEYGEN_OUTPUT, Optional.of(OPERATOR)).present());
    assertTrue(new EnvClusterAgeIdentity(KEYGEN_OUTPUT, Optional.empty()).present());
    assertFalse(new EnvClusterAgeIdentity(null, Optional.of(OPERATOR)).present());
    assertFalse(new EnvClusterAgeIdentity(" ", Optional.of(OPERATOR)).present());
    // In-cluster the variable is never read, even when it leaks into the pod.
    assertFalse(new EnvClusterAgeIdentity(KEYGEN_OUTPUT, Optional.of(IN_CLUSTER)).present());
  }

  @Test
  void an_unset_variable_says_how_to_hand_the_identity_over() {
    final IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> new EnvClusterAgeIdentity(null, Optional.of(OPERATOR)).identity());
    assertTrue(
        refused.getMessage().contains("pass show seedmatic/cluster-age/"), refused.getMessage());
  }
}
