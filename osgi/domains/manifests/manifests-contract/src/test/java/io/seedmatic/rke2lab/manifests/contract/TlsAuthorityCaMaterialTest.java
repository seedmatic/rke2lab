package io.seedmatic.rke2lab.manifests.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.manifests.contract.profiles.TlsAuthorityCaMaterial;
import org.junit.jupiter.api.Test;

/**
 * The trust anchor the CAPN provider verifies the Incus listener against, once its identity Secret
 * stopped pinning the leaf. The whole type is one derivation — "the root is the LAST certificate of
 * our own issuer chain" — so these tests are about that sentence being true and loud.
 */
class TlsAuthorityCaMaterialTest {

  private static String pem(final String body) {
    return "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n";
  }

  private static final String ISSUER_CA = pem("ISSUERCA");
  private static final String INTERMEDIATE = pem("INTERMEDIATE");
  private static final String ROOT = pem("MAMMOTHSKATEROOT");

  @Test
  void the_root_is_the_LAST_certificate_of_the_chain_not_the_first() {
    // The order IS the contract: ClusterCaGenerator assembles chainPem(issuerCa, intermediate,
    // root). Taking the first would hand the pod the leaf CA — which verifies our own leaves and
    // NOT the Incus listener, so every handshake would fail with "unknown authority" while the
    // Secret looked perfectly populated.
    final TlsAuthorityCaMaterial material =
        TlsAuthorityCaMaterial.rootOf(ISSUER_CA + INTERMEDIATE + ROOT);
    assertTrue(material.caCertPem().contains("MAMMOTHSKATEROOT"), "the anchor must be the root");
    assertTrue(
        !material.caCertPem().contains("ISSUERCA")
            && !material.caCertPem().contains("INTERMEDIATE"),
        "exactly ONE certificate travels — a bundle would work by accident and hide a wrong order");
  }

  @Test
  void a_chain_of_one_is_a_directly_rooted_ca_and_not_an_error() {
    assertEquals(ROOT, TlsAuthorityCaMaterial.rootOf(ROOT).caCertPem());
  }

  @Test
  void a_longer_chain_still_yields_its_last_block_so_a_second_intermediate_is_survivable() {
    // Why the last block rather than "the self-signed one": matching on issuer==subject would pick
    // the wrong certificate the day a chain gains a level, and this stays correct.
    final TlsAuthorityCaMaterial material =
        TlsAuthorityCaMaterial.rootOf(ISSUER_CA + INTERMEDIATE + pem("SECONDINTER") + ROOT);
    assertTrue(material.caCertPem().contains("MAMMOTHSKATEROOT"));
  }

  @Test
  void a_chain_with_no_certificate_is_REFUSED_rather_than_yielding_an_empty_anchor() {
    // The failure this guards: an empty trust store and a missing one are indistinguishable at
    // runtime — both are "x509: unknown authority" — so the door is where it has to be caught.
    assertThrows(IllegalArgumentException.class, () -> TlsAuthorityCaMaterial.rootOf(""));
    assertThrows(
        IllegalArgumentException.class, () -> TlsAuthorityCaMaterial.rootOf("not a pem at all"));
  }

  @Test
  void a_truncated_block_is_REFUSED_rather_than_silently_shortened() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TlsAuthorityCaMaterial.rootOf(ROOT + "-----BEGIN CERTIFICATE-----\nCUTOFF\n"));
  }

  @Test
  void the_record_itself_refuses_anything_that_is_not_a_pem_certificate() {
    assertThrows(NullPointerException.class, () -> new TlsAuthorityCaMaterial(null));
    assertThrows(IllegalArgumentException.class, () -> new TlsAuthorityCaMaterial(""));
    assertThrows(IllegalArgumentException.class, () -> new TlsAuthorityCaMaterial("   "));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TlsAuthorityCaMaterial("-----BEGIN CERTIFICATE-----\nno end marker"));
  }
}
