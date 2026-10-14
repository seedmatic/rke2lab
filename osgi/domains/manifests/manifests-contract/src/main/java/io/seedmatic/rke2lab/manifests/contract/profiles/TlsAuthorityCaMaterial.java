package io.seedmatic.rke2lab.manifests.contract.profiles;

import java.util.List;
import java.util.Objects;

/**
 * The fleet's TLS authority CERTIFICATE — the {@code mammoth-skate-tls} root, published to
 * synth-time layers via {@code ManifestSynthesisContext} so a workload that must trust something we
 * signed is handed a CA bundle instead of a pinned leaf.
 *
 * <p>Its consumer is the CAPN provider pod: {@code cluster-api-provider-incus} reads exactly {@code
 * server, server-crt, client-crt, client-key, project, insecure-skip-verify} — there is no {@code
 * ca-crt} key — and passes {@code server-crt} straight to the incus client's {@code TLSServerCert},
 * the PINNED remote certificate. Pinning is what made the Incus listener certificate unreplaceable:
 * each regeneration invalidates every pinned copy at once. The way out is the one the incus client
 * documents — <i>"Unless the remote server is trusted by the system CA, the remote certificate must
 * be provided"</i> — so this CA goes into the pod's trust store and {@code server-crt} goes empty.
 *
 * <h2>★ Derived from the chain we already deliver, NOT read from the key-store</h2>
 *
 * <p>The obvious source is {@code authorities.mammoth-skate-tls.ca_crt} through the ndh key-store,
 * and it is the wrong one: IN_CLUSTER the key-store is unreachable, and the in-cluster render is
 * the steady state. A host-only read would give the operator's render a CA and the cluster's render
 * none — and once {@code server-crt} is empty, "none" is a provider that trusts nothing we signed.
 *
 * <p>So it is the LAST certificate of {@link
 * io.seedmatic.rke2lab.clusterpki.contract.ClusterIssuerCa#caCertChainPem()}, which {@code
 * ClusterCaGenerator} assembles as {@code chainPem(issuerCa, intermediate, root)} with {@code root}
 * being exactly {@code keystore.authorityCert(TLS_AUTHORITY)}. That material is cellar-borne, so it
 * is present in BOTH realms, and the derivation states the invariant that actually matters: the CA
 * we ask a pod to trust is the root our own chain ends at. Two independent reads could disagree;
 * this one cannot.
 *
 * <p>Unlike its neighbours this record carries no private key, and must not grow one — a CA
 * certificate is public by construction, and the signing side has its own path ({@code
 * ClusterSeal}). Absence, carried as an empty {@code Optional}, means no cluster PKI was revealed
 * (a bare survey, a secret-blind render); the delivering unit then renders neither the Secret nor
 * the trust-store patch, which is safe only while {@code server-crt} is still pinned.
 */
public record TlsAuthorityCaMaterial(String caCertPem) {

  private static final String BEGIN = "-----BEGIN CERTIFICATE-----";
  private static final String END = "-----END CERTIFICATE-----";

  public TlsAuthorityCaMaterial {
    caCertPem = Objects.requireNonNull(caCertPem, "caCertPem");
    if (!caCertPem.contains(BEGIN) || !caCertPem.contains(END)) {
      throw new IllegalArgumentException(
          "not a PEM certificate — an unparseable trust store is indistinguishable from a missing"
              + " one at runtime, so refuse it at the door");
    }
  }

  /**
   * The ROOT of a PEM chain — its last certificate, which for every chain {@code
   * ClusterCaGenerator} mints is the ndh TLS authority.
   *
   * <p>Takes the LAST block rather than searching for a self-signed one: the chain's ORDER is the
   * contract ({@code chainPem(leafCa, intermediate, root)}), and matching on "issuer equals
   * subject" would silently pick the wrong certificate the day a chain gains a second intermediate.
   * A chain of one is accepted — that is a directly-rooted CA, not an error.
   */
  public static TlsAuthorityCaMaterial rootOf(final String chainPem) {
    Objects.requireNonNull(chainPem, "chainPem");
    final List<String> blocks = blocks(chainPem);
    if (blocks.isEmpty()) {
      throw new IllegalArgumentException(
          "no certificate in the chain — refusing to derive a trust anchor from nothing");
    }
    return new TlsAuthorityCaMaterial(blocks.getLast());
  }

  private static List<String> blocks(final String pem) {
    final List<String> found = new java.util.ArrayList<>();
    int from = 0;
    while (true) {
      final int begin = pem.indexOf(BEGIN, from);
      if (begin < 0) {
        return List.copyOf(found);
      }
      final int end = pem.indexOf(END, begin);
      if (end < 0) {
        throw new IllegalArgumentException(
            "truncated PEM block — a BEGIN with no END would yield a trust anchor no parser accepts");
      }
      found.add(pem.substring(begin, end + END.length()) + "\n");
      from = end + END.length();
    }
  }
}
