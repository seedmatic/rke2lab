package io.seedmatic.rke2lab.ndh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.seed.broker.port.EnclosureGate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The identity is what `pass show seedmatic/cluster-age/<n>` hands over: age-keygen's comment lines
 * and ONE identity line. Every refusal is checked for the one property that matters most here: it
 * never quotes what it read.
 */
class FdClusterAgeIdentityTest {

  // A fake identity, assembled so the file holds no age-secret shape: the pre-commit leak guard
  // refuses one, and is right to.
  private static final String IDENTITY = "AGE-SECRET-KEY-" + "1" + "Q".repeat(58);
  private static final String KEYGEN_OUTPUT =
      "# created: 2026-10-18T08:30:23+02:00\n# public key: age1test\n" + IDENTITY + "\n";

  @TempDir Path dir;

  private static final EnclosureGate IN_CLUSTER = () -> true;
  private static final EnclosureGate OPERATOR = () -> false;

  @Test
  void the_identity_line_is_read_and_the_comments_left() throws IOException {
    final Path source = Files.writeString(dir.resolve("fd"), KEYGEN_OUTPUT);
    assertEquals(IDENTITY, FdClusterAgeIdentity.read(source));
  }

  @Test
  void anything_but_exactly_one_identity_is_refused_without_quoting_it() throws IOException {
    for (String content :
        new String[] {"", "# only comments\n", IDENTITY + "\n" + IDENTITY + "\n"}) {
      final Path source = Files.writeString(dir.resolve("fd"), content);
      final IllegalStateException refused =
          assertThrows(IllegalStateException.class, () -> FdClusterAgeIdentity.read(source));
      assertTrue(refused.getMessage().contains("not exactly one"), refused.getMessage());
      assertFalse(refused.getMessage().contains("AGE-SECRET-KEY"), refused.getMessage());
    }
  }

  @Test
  void an_unreadable_descriptor_is_refused_by_its_path_alone() {
    final IllegalStateException refused =
        assertThrows(
            IllegalStateException.class, () -> FdClusterAgeIdentity.read(dir.resolve("absent")));
    assertTrue(refused.getMessage().contains("cannot read"), refused.getMessage());
  }

  @Test
  void present_follows_the_variable_and_the_enclosure() {
    assertTrue(new FdClusterAgeIdentity("3", Optional.of(OPERATOR)).present());
    assertTrue(new FdClusterAgeIdentity("3", Optional.empty()).present());
    assertFalse(new FdClusterAgeIdentity(null, Optional.of(OPERATOR)).present());
    assertFalse(new FdClusterAgeIdentity(" ", Optional.of(OPERATOR)).present());
    // In-cluster the descriptor is never read, even when the variable leaks into the pod.
    assertFalse(new FdClusterAgeIdentity("3", Optional.of(IN_CLUSTER)).present());
  }

  @Test
  void stdin_stdout_stderr_and_garbage_are_not_descriptors_of_the_identity() {
    for (String fd : new String[] {"0", "1", "2", "-3", "03", "three", "3a"}) {
      final IllegalStateException refused =
          assertThrows(
              IllegalStateException.class,
              () -> new FdClusterAgeIdentity(fd, Optional.of(OPERATOR)).identity());
      assertTrue(refused.getMessage().contains("3 or more"), fd + ": " + refused.getMessage());
    }
  }

  @Test
  void an_unset_variable_says_how_to_hand_the_identity_over() {
    final IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> new FdClusterAgeIdentity(null, Optional.of(OPERATOR)).identity());
    assertTrue(
        refused.getMessage().contains("pass show seedmatic/cluster-age/"), refused.getMessage());
  }
}
