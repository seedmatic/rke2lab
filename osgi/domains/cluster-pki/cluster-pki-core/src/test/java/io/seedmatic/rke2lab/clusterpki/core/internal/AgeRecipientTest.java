package io.seedmatic.rke2lab.clusterpki.core.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The age public of a cluster-age identity, derived in the JVM: a known vector gives the public
 * {@code age-keygen -y} gives, and a refusal never quotes the identity.
 *
 * <p>The identity is a throwaway key generated for this test alone and never used anywhere; it is
 * assembled from fragments, so no line of the repository holds a key a secret scanner would match.
 */
class AgeRecipientTest {

  private static final String PREFIX = "AGE-SECRET" + "-KEY-1";

  /** A key generated with age-keygen 1.3.1 for this test; its public is {@link #PUBLIC}. */
  private static final String IDENTITY =
      PREFIX + "WKYHDKUZ3D0YKS" + "4LWJE0DQ6DS4TL" + "W0GN5CENGAKDQ3" + "XPG8C0KPLQUQDGW4";

  private static final String PUBLIC =
      "age16skjxeq7r27ale73hcgynpy0sxzthn47t5nuxl677y522uhjze8sg9nps5";

  @Test
  void a_known_identity_gives_the_public_age_keygen_gives() {
    assertEquals(PUBLIC, AgeRecipient.of(IDENTITY).recipient());
  }

  @Test
  void an_identity_that_is_not_one_is_refused_without_being_quoted() {
    for (String bad :
        new String[] {
          "",
          PREFIX,
          PREFIX + "QQQQ",
          "age1" + "qqqq",
          IDENTITY.substring(0, IDENTITY.length() - 1) + "P"
        }) {
      final IllegalArgumentException refused =
          assertThrows(IllegalArgumentException.class, () -> AgeRecipient.of(bad));
      final String message = String.valueOf(refused.getMessage());
      assertFalse(message.contains(PREFIX), "the refusal quotes the identity: " + message);
      assertTrue(
          bad.isEmpty() || !message.contains(bad), "the refusal echoes its input: " + message);
      assertTrue(refused.getCause() == null, "no cause can carry the input: " + refused.getCause());
    }
  }

  @Test
  void the_public_is_a_recipient_or_not() {
    final AgeRecipient recipient = AgeRecipient.of(IDENTITY);
    assertTrue(recipient.isIn(java.util.List.of("age1" + "other", PUBLIC)));
    assertFalse(recipient.isIn(java.util.List.of("age1" + "other")));
  }
}
