package io.seedmatic.rke2lab.clusterpki.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.clusterpki.contract.SopsEncryptor;
import io.seedmatic.rke2lab.clusterpki.contract.VclusterPki;
import io.seedmatic.rke2lab.ndh.contract.ClusterAgeIdentity;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A vcluster's PKI is AUTONOMOUS (the operator, 2026-10-26: ccr enters no fleet trust): its roots
 * are self-signed, minted with no read of the ndh key-store, and kept across re-grows. The grow
 * mints only the roots and the service-account pair; vcluster mints its leaves and their SAN from
 * them (upstream pkg/certs/ensure.go:122-149, read at loft-sh/vcluster e702d7ba).
 */
class ClusterSealVclusterPkiTest {

  /** A store whose every read fails: a vcluster's roots must not reach the fleet's authority. */
  private static final NdhKeystoreReader UNTOUCHED =
      new NdhKeystoreReader() {
        public boolean present() {
          return true;
        }

        public String authorityCert(String authority) {
          throw new AssertionError("a vcluster root read the fleet authority's cert");
        }

        public String authorityDomain(String authority) {
          throw new AssertionError("a vcluster root read the fleet authority");
        }

        public String authorityPrivate(String authority) {
          throw new AssertionError("a vcluster root read the fleet authority's PRIVATE");
        }

        public String sshPrivate(String keyName) {
          throw new AssertionError("a vcluster root read an ssh private");
        }

        public String sshPublic(String keyName) {
          throw new AssertionError("a vcluster root read an ssh public");
        }
      };

  private static final SopsEncryptor NEVER =
      new SopsEncryptor() {
        public String encryptYaml(String plaintextYaml, List<String> recipients) {
          throw new AssertionError("a vcluster's PKI is sealed in the cellar, not sops here");
        }

        public String encryptYaml(String plaintextYaml, Path policy, String path) {
          throw new AssertionError("a vcluster's PKI is sealed in the cellar, not sops here");
        }
      };

  private static final ClusterAgeIdentity NO_IDENTITY =
      new ClusterAgeIdentity() {
        public boolean present() {
          return false;
        }

        public String identity() {
          throw new AssertionError("a vcluster's PKI needs no cluster-age identity");
        }
      };

  private static final ClusterSeal SEAL = new ClusterSeal(UNTOUCHED, NO_IDENTITY, NEVER, List.of());

  private static X509Certificate cert(String pem) throws Exception {
    return (X509Certificate)
        CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void a_vcluster_gets_its_own_self_signed_roots_with_no_read_of_the_fleet_authority()
      throws Exception {
    final VclusterPki pki = SEAL.sealVclusterPki(List.of("ccr"), Optional.empty());
    final VclusterPki.Entry ccr = pki.forVcluster("ccr").orElseThrow();
    for (final VclusterPki.Pair root :
        List.of(ccr.ca(), ccr.serverCa(), ccr.clientCa(), ccr.frontProxyCa(), ccr.etcdCa())) {
      final X509Certificate c = cert(root.certPem());
      assertEquals(c.getSubjectX500Principal(), c.getIssuerX500Principal(), "self-signed");
      assertTrue(c.getBasicConstraints() >= 0, "a CA: " + c.getSubjectX500Principal());
      c.verify(c.getPublicKey());
      assertTrue(root.keyPem().contains("PRIVATE KEY"), "its private key is kept with it");
    }
    assertTrue(ccr.serviceAccountKeyPem().contains("PRIVATE KEY"));
    assertTrue(ccr.serviceAccountPubPem().contains("PUBLIC KEY"));
    assertNotEquals(
        cert(ccr.serverCa().certPem()).getPublicKey(),
        cert(ccr.clientCa().certPem()).getPublicKey(),
        "five roots, five keys");
  }

  @Test
  void its_admin_credential_is_signed_by_its_own_client_ca() throws Exception {
    final VclusterPki.Entry ccr =
        SEAL.sealVclusterPki(List.of("ccr"), Optional.empty()).forVcluster("ccr").orElseThrow();
    final X509Certificate admin = cert(ccr.admin().clientCertPem());
    admin.verify(cert(ccr.clientCa().certPem()).getPublicKey());
    assertTrue(admin.getSubjectX500Principal().getName().contains("O=system:masters"));
    assertEquals(ccr.serverCa().certPem(), ccr.admin().caCertPem(), "it trusts its server-ca");
  }

  @Test
  void a_re_grow_keeps_a_sealed_vcluster_and_mints_only_a_new_one() {
    final VclusterPki first = SEAL.sealVclusterPki(List.of("ccr"), Optional.empty());
    final VclusterPki second = SEAL.sealVclusterPki(List.of("ccr", "other"), Optional.of(first));
    assertSame(first.forVcluster("ccr").orElseThrow(), second.forVcluster("ccr").orElseThrow());
    assertTrue(second.forVcluster("other").isPresent());
    assertFalse(
        SEAL.sealVclusterPki(List.of(), Optional.of(first)).forVcluster("ccr").isPresent(),
        "a vcluster no longer declared is not carried");
  }

  @Test
  void its_certs_secret_holds_exactly_the_roots_vcluster_reads_by_name() {
    final VclusterPki.Entry ccr =
        SEAL.sealVclusterPki(List.of("ccr"), Optional.empty()).forVcluster("ccr").orElseThrow();
    assertEquals("ccr-certs", ccr.secretName());
    assertEquals(
        Set.of(
            "ca.crt",
            "ca.key",
            "server-ca.crt",
            "server-ca.key",
            "client-ca.crt",
            "client-ca.key",
            "front-proxy-ca.crt",
            "front-proxy-ca.key",
            "etcd-ca.crt",
            "etcd-ca.key",
            "sa.key",
            "sa.pub"),
        ccr.secretData().keySet());
  }
}
