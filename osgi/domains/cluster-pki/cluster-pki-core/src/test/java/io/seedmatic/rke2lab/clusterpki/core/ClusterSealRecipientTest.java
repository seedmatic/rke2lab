package io.seedmatic.rke2lab.clusterpki.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.clusterpki.contract.SopsEncryptor;
import io.seedmatic.rke2lab.ndh.contract.ClusterAgeIdentity;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The seal refuses a cluster-age identity that is not a .sops.yaml recipient, and refuses it FIRST:
 * before the TLS authority's private is read and before anything is encrypted, since a bundle
 * sealed to recipients the node cannot be one of would make the node skip sops and rke2 sign its
 * own CA, silently.
 *
 * <p>The identity is a throwaway key generated for this test alone, assembled from fragments.
 */
class ClusterSealRecipientTest {

  private static final String PREFIX = "AGE-SECRET" + "-KEY-1";
  private static final String IDENTITY =
      PREFIX + "WKYHDKUZ3D0YKS" + "4LWJE0DQ6DS4TL" + "W0GN5CENGAKDQ3" + "XPG8C0KPLQUQDGW4";
  private static final String PUBLIC =
      "age16skjxeq7r27ale73hcgynpy0sxzthn47t5nuxl677y522uhjze8sg9nps5";

  /** A store whose every read fails: the refusal must come before any of them. */
  private static final NdhKeystoreReader UNTOUCHED =
      new NdhKeystoreReader() {
        public boolean present() {
          return true;
        }

        public String authorityCert(String authority) {
          throw new AssertionError("the authority's cert was read before the recipient check");
        }

        public String authorityDomain(String authority) {
          throw new AssertionError("the authority was read before the recipient check");
        }

        public String authorityPrivate(String authority) {
          throw new AssertionError("the authority's PRIVATE was read before the recipient check");
        }

        public String sshPrivate(String keyName) {
          throw new AssertionError("an ssh private was read before the recipient check");
        }

        public String sshPublic(String keyName) {
          throw new AssertionError("an ssh public was read before the recipient check");
        }
      };

  /** An encryptor that fails if called: nothing may be sealed for a refused identity. */
  private static final SopsEncryptor NEVER =
      new SopsEncryptor() {
        public String encryptYaml(String plaintextYaml, List<String> recipients) {
          throw new AssertionError("something was sealed for a refused identity");
        }

        public String encryptYaml(String plaintextYaml, Path policy, String path) {
          throw new AssertionError("something was sealed for a refused identity");
        }
      };

  private static ClusterAgeIdentity identity(String value) {
    return new ClusterAgeIdentity() {
      public boolean present() {
        return true;
      }

      public String identity() {
        return value;
      }
    };
  }

  @Test
  void an_identity_that_is_not_a_recipient_is_refused_before_anything_is_read_or_sealed() {
    final ClusterSeal seal =
        new ClusterSeal(UNTOUCHED, identity(IDENTITY), NEVER, List.of("age1" + "someoneelse"));
    final IllegalStateException refused = assertThrows(IllegalStateException.class, seal::seal);
    final String message = refused.getMessage();
    assertTrue(message.contains(PUBLIC), "the refusal names the identity's public: " + message);
    assertTrue(message.contains("age1" + "someoneelse"), "and the expected recipients: " + message);
    assertFalse(message.contains(PREFIX), "the refusal quotes the identity: " + message);
  }

  @Test
  void a_recipient_identity_passes_the_check_and_reaches_the_authority() {
    final ClusterSeal seal =
        new ClusterSeal(UNTOUCHED, identity(IDENTITY), NEVER, List.of("age1" + "other", PUBLIC));
    final AssertionError reached = assertThrows(AssertionError.class, seal::seal);
    assertEquals(
        "the authority's cert was read before the recipient check",
        reached.getMessage(),
        "past the check, the seal goes on to read the authority");
  }

  /** A second throwaway key, so a kept CA can be filed under a key that is not the one on fd 3. */
  private static final String OTHER_IDENTITY =
      PREFIX + "GHTYPU7V2LY957" + "8PNJ8VLVA3LYW7" + "3WZTJHP3JSUH59" + "H7KF6Q36LQ6ZJFAT";

  private static final String OTHER_PUBLIC =
      "age1duvkuvvn6nhujxmgp03yr3kuhr2w3xdsexm07tdurw7jp29zcprqclvjt7";

  @Test
  void a_kept_ca_filed_under_another_identity_is_refused_naming_reseal() {
    final ClusterSeal seal = new ClusterSeal(UNTOUCHED, identity(IDENTITY), NEVER, List.of(PUBLIC));
    final IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                seal.checkKept(
                    new io.seedmatic.rke2lab.clusterpki.contract.ClusterAgeKey(OTHER_IDENTITY)));
    final String message = refused.getMessage();
    assertTrue(message.contains("reseal"), "the refusal names the way out: " + message);
    assertTrue(
        message.contains(OTHER_PUBLIC) && message.contains(PUBLIC), "and both publics: " + message);
    assertFalse(message.contains(PREFIX), "the refusal quotes an identity: " + message);
  }

  @Test
  void a_kept_ca_filed_under_the_same_identity_is_kept() {
    final ClusterSeal seal = new ClusterSeal(UNTOUCHED, identity(IDENTITY), NEVER, List.of(PUBLIC));
    seal.checkKept(new io.seedmatic.rke2lab.clusterpki.contract.ClusterAgeKey(IDENTITY));
  }

  @Test
  void a_kept_ca_is_still_refused_when_the_identity_is_not_a_recipient() {
    final ClusterSeal seal =
        new ClusterSeal(UNTOUCHED, identity(IDENTITY), NEVER, List.of("age1" + "someoneelse"));
    assertThrows(
        IllegalStateException.class,
        () -> seal.checkKept(new io.seedmatic.rke2lab.clusterpki.contract.ClusterAgeKey(IDENTITY)));
  }
}
