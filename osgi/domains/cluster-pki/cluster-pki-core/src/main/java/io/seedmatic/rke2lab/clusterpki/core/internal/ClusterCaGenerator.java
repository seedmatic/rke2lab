package io.seedmatic.rke2lab.clusterpki.core.internal;

import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;

/**
 * The deterministic cluster CA-hierarchy generator — a faithful pure-Java port of k3s {@code
 * contrib/generate-custom-ca-certs.sh} (proven structurally identical cert-by-cert against OpenSSL
 * 3 in {@code scratchpad/pki-proof}). Given the operator's existing ndh {@code mammoth-skate-tls}
 * root (its certificate + private key, whether PEM or OpenSSH), it mints the rke2 bring-your-own-CA
 * set:
 *
 * <ul>
 *   <li>an INTERMEDIATE CA (RSA 4096) signed by the root,
 *   <li>the five leaf CAs — {@code client}, {@code server}, {@code request-header}, {@code
 *       etcd-peer}, {@code etcd-server} (EC prime256v1) — signed by the intermediate,
 *   <li>the service-account issuer key (RSA 2048),
 *   <li>the {@code cluster-issuer} CA (EC prime256v1) — a SIXTH CA signed by the SAME intermediate,
 *       distinct from the five rke2 leaf CAs and NOT part of the node bundle. Its key is delivered
 *       in-cluster (kube-system) so a cert-manager {@code CA} {@code ClusterIssuer} signs app
 *       leaves (flox webhook, CAPI, …) that chain to the mammoth-skate root. Deliberately
 *       low-privilege: cert-manager never sees an rke2 CA key.
 * </ul>
 *
 * <p>Every cert carries the k3s {@code v3_ca} profile: SKI (hash), AKI (issuer keyid only),
 * BasicConstraints critical {@code CA:true} (no pathlen), KeyUsage critical {@code
 * digitalSignature|keyEncipherment|keyCertSign}, sha256, 3700-day validity. Each {@code *-ca.crt}
 * is the FULL CHAIN (leaf + intermediate + root); each leaf key is SEC1 PEM, {@code service.key} is
 * PKCS#1. The root + intermediate PRIVATE keys never leave the operator's host — they are NOT in
 * the returned set.
 *
 * <p>The return {@link ClusterCaSet} carries the exact eleven-key node bundle {@code
 * nixos/sops.nix} declares (assembled into YAML + sops-sealed for the node) PLUS the cluster-issuer
 * CA chain + key (sealed separately, delivered in-cluster). See
 * docs/architecture/cluster-api/deterministic-cluster-access.adoc.
 */
public final class ClusterCaGenerator {

  private static final int VALIDITY_DAYS = 3700;
  private static final SecureRandom RANDOM = new SecureRandom();

  /** The five rke2 leaf CAs, by the flat bundle-key stem the node re-nests under server/tls. */
  private static final List<String> LEAF_CAS =
      List.of("client", "server", "request-header", "etcd-peer", "etcd-server");

  /**
   * CN stem of the sixth CA — the cert-manager {@code ClusterIssuer} root, delivered in-cluster.
   */
  private static final String CLUSTER_ISSUER_CN = "rke2lab-cluster-issuer-ca";

  /**
   * The generated CA hierarchy: the eleven-key node bundle {@code nixos/sops.nix} declares, plus
   * the cluster-issuer CA (its full chain to the root + its private key) minted under the SAME
   * intermediate but kept OUT of the node bundle — it is sealed separately and delivered
   * in-cluster.
   */
  public record ClusterCaSet(
      LinkedHashMap<String, String> nodeBundle, String issuerCaChainPem, String issuerCaKeyPem) {}

  static {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  /**
   * Mint the CA set rooted on {@code rootCertPem}/{@code rootKeyPem}. {@code timestampSeconds} pins
   * the {@code @<ts>} suffix on each CA's CN (cosmetic uniqueness, as in the k3s script).
   *
   * @return the {@link ClusterCaSet}: the eleven node-bundle entries ({@code fileName -> PEM}, in a
   *     stable order) plus the cluster-issuer CA chain + key.
   */
  public ClusterCaSet generate(String rootCertPem, String rootKeyPem, long timestampSeconds) {
    try {
      final X509Certificate root = readCert(rootCertPem);
      final PrivateKey rootKey = readKey(rootKeyPem);
      final LinkedHashMap<String, String> bundle = new LinkedHashMap<>();

      // intermediate: RSA 4096, signed by the root, kept operator-side (NOT in the bundle).
      final KeyPair intermediate = rsa(4096);
      final X509Certificate interCert =
          sign(
              root,
              rootKey,
              "CN=rke2-intermediate-ca@" + timestampSeconds,
              intermediate.getPublic(),
              timestampSeconds);

      // five leaf CAs: EC prime256v1, signed by the intermediate; .crt is the full chain.
      for (String leaf : LEAF_CAS) {
        final KeyPair key = ec();
        final X509Certificate cert =
            sign(
                interCert,
                intermediate.getPrivate(),
                "CN=rke2-" + leaf + "-ca@" + timestampSeconds,
                key.getPublic(),
                timestampSeconds);
        bundle.put(leaf + "-ca.crt", chainPem(cert, interCert, root));
        bundle.put(leaf + "-ca.key", keyPem(key.getPrivate()));
      }

      // service-account issuer key: RSA 2048, PKCS#1.
      bundle.put("service.key", keyPem(rsa(2048).getPrivate()));

      // cluster-issuer CA: EC prime256v1, signed by the SAME intermediate. Kept OUT of the node
      // bundle — sealed separately and delivered in-cluster as the cert-manager ClusterIssuer's CA.
      final KeyPair issuerCaKey = ec();
      final X509Certificate issuerCaCert =
          sign(
              interCert,
              intermediate.getPrivate(),
              "CN=" + CLUSTER_ISSUER_CN + "@" + timestampSeconds,
              issuerCaKey.getPublic(),
              timestampSeconds);
      return new ClusterCaSet(
          bundle, chainPem(issuerCaCert, interCert, root), keyPem(issuerCaKey.getPrivate()));
    } catch (RuntimeException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("cluster CA generation failed", ex);
    }
  }

  /**
   * Mint the operator's admin clientAuth leaf, signed by the cluster {@code client-ca} (the FIRST
   * cert of {@code clientCaChainPem} — the client-CA itself, ahead of the intermediate + root).
   * Subject {@code CN=rke2lab-admin, O=system:masters} — the {@code system:masters} group RBAC
   * binds to cluster-admin. EC prime256v1 key, SEC1 PEM; the cert PEM is the leaf alone (kube's
   * {@code client-certificate-data}). Endpoint-independent — the host wraps a kubeconfig around it.
   */
  public AdminLeaf mintAdminClient(String clientCaChainPem, String clientCaKeyPem) {
    try {
      final X509Certificate clientCa = readCert(clientCaChainPem);
      final PrivateKey clientCaKey = readKey(clientCaKeyPem);
      final KeyPair adminKey = ec();
      final X509Certificate adminCert =
          signLeaf(
              clientCa,
              clientCaKey,
              "CN=rke2lab-admin,O=system:masters",
              adminKey.getPublic(),
              KeyPurposeId.id_kp_clientAuth,
              Optional.empty());
      return new AdminLeaf(chainPem(adminCert), keyPem(adminKey.getPrivate()));
    } catch (RuntimeException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("admin client certificate minting failed", ex);
    }
  }

  /** The operator's admin leaf: its certificate PEM + private-key PEM (endpoint-independent). */
  public record AdminLeaf(String certPem, String keyPem) {}

  /**
   * The end-entity leaf profile: NOT a CA (BasicConstraints CA:false), KeyUsage critical {@code
   * digitalSignature|keyEncipherment}, the supplied EKU ({@code clientAuth} for the admin leaf,
   * {@code serverAuth} for a serving leaf), an optional {@code subjectAlternativeName} (DNS names,
   * non-critical — present only for serving leaves), SKI + AKI (issuer keyid), sha256, {@link
   * #VALIDITY_DAYS} validity. notBefore is {@code now} — the node clock is disciplined at the
   * source (chrony on the VZ host), so no skew backdate is needed.
   */
  private static X509Certificate signLeaf(
      X509Certificate issuer,
      PrivateKey issuerKey,
      String subjectDn,
      PublicKey subjectPub,
      KeyPurposeId eku,
      Optional<GeneralNames> subjectAltNames)
      throws Exception {
    final Instant notBefore = Instant.now();
    final X500Name issuerDn = new JcaX509CertificateHolder(issuer).getSubject();
    final BigInteger serial = new BigInteger(64, RANDOM).abs().add(BigInteger.ONE);
    final JcaX509v3CertificateBuilder b =
        new JcaX509v3CertificateBuilder(
            issuerDn,
            serial,
            Date.from(notBefore),
            Date.from(notBefore.plus(VALIDITY_DAYS, ChronoUnit.DAYS)),
            new X500Name(subjectDn),
            subjectPub);
    final JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
    b.addExtension(
        Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(subjectPub));
    b.addExtension(
        Extension.authorityKeyIdentifier,
        false,
        ext.createAuthorityKeyIdentifier(issuer.getPublicKey()));
    b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
    b.addExtension(
        Extension.keyUsage,
        true,
        new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
    b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(eku));
    if (subjectAltNames.isPresent()) {
      b.addExtension(Extension.subjectAlternativeName, false, subjectAltNames.orElseThrow());
    }
    final String sigAlg =
        issuerKey.getAlgorithm().startsWith("EC") ? "SHA256withECDSA" : "SHA256withRSA";
    final X509CertificateHolder holder =
        b.build(
            new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder(sigAlg)
                .setProvider("BC")
                .build(issuerKey));
    return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
  }

  /** The k3s {@code v3_ca} profile — the one certificate shape every CA in the chain wears. */
  private static X509Certificate sign(
      X509Certificate issuer, PrivateKey issuerKey, String subjectDn, PublicKey subjectPub, long ts)
      throws Exception {
    final Instant notBefore = Instant.ofEpochSecond(ts);
    final X500Name issuerDn = new JcaX509CertificateHolder(issuer).getSubject();
    final BigInteger serial = new BigInteger(64, RANDOM).abs().add(BigInteger.ONE);
    final JcaX509v3CertificateBuilder b =
        new JcaX509v3CertificateBuilder(
            issuerDn,
            serial,
            Date.from(notBefore),
            Date.from(notBefore.plus(VALIDITY_DAYS, ChronoUnit.DAYS)),
            new X500Name(subjectDn),
            subjectPub);
    final JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
    b.addExtension(
        Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(subjectPub));
    // keyid:always — the issuer's key id ONLY (no DirName/serial), matching openssl's default.
    b.addExtension(
        Extension.authorityKeyIdentifier,
        false,
        ext.createAuthorityKeyIdentifier(issuer.getPublicKey()));
    b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
    b.addExtension(
        Extension.keyUsage,
        true,
        new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment | KeyUsage.keyCertSign));
    final String sigAlg =
        issuerKey.getAlgorithm().startsWith("EC") ? "SHA256withECDSA" : "SHA256withRSA";
    final X509CertificateHolder holder =
        b.build(
            new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder(sigAlg)
                .setProvider("BC")
                .build(issuerKey));
    return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
  }

  private static KeyPair rsa(int bits) throws Exception {
    final KeyPairGenerator g = KeyPairGenerator.getInstance("RSA", "BC");
    g.initialize(bits);
    return g.generateKeyPair();
  }

  private static KeyPair ec() throws Exception {
    final KeyPairGenerator g = KeyPairGenerator.getInstance("EC", "BC");
    g.initialize(new ECGenParameterSpec("prime256v1"));
    return g.generateKeyPair();
  }

  private static X509Certificate readCert(String pem) throws Exception {
    try (PEMParser p = new PEMParser(new StringReader(pem))) {
      return new JcaX509CertificateConverter()
          .setProvider("BC")
          .getCertificate((X509CertificateHolder) p.readObject());
    }
  }

  /** Robust: standard PEM (SEC1/PKCS#8) OR OpenSSH ("BEGIN OPENSSH PRIVATE KEY", the ndh root). */
  private static PrivateKey readKey(String pem) throws Exception {
    final PemObject pemObj;
    try (PemReader r = new PemReader(new StringReader(pem))) {
      pemObj = r.readPemObject();
    }
    final JcaPEMKeyConverter conv = new JcaPEMKeyConverter().setProvider("BC");
    if ("OPENSSH PRIVATE KEY".equals(pemObj.getType())) {
      final AsymmetricKeyParameter p =
          OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(pemObj.getContent());
      return conv.getPrivateKey(PrivateKeyInfoFactory.createPrivateKeyInfo(p));
    }
    try (PEMParser p = new PEMParser(new StringReader(pem))) {
      final Object o = p.readObject();
      if (o instanceof PEMKeyPair kp) {
        return conv.getKeyPair(kp).getPrivate();
      }
      if (o instanceof PrivateKeyInfo pki) {
        return conv.getPrivateKey(pki);
      }
      throw new IllegalStateException("unrecognised root key PEM: " + o);
    }
  }

  private static String chainPem(X509Certificate... chain) throws Exception {
    final StringWriter sw = new StringWriter();
    try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
      for (X509Certificate c : chain) {
        w.writeObject(c);
      }
    }
    return sw.toString();
  }

  /** EC -> SEC1 "EC PRIVATE KEY"; RSA -> PKCS#1 "RSA PRIVATE KEY" (matching openssl defaults). */
  private static String keyPem(PrivateKey key) throws Exception {
    final PrivateKeyInfo pki = PrivateKeyInfo.getInstance(key.getEncoded());
    final ASN1Encodable naked = pki.parsePrivateKey();
    final PemObject pem =
        key.getAlgorithm().startsWith("EC")
            ? new PemObject("EC PRIVATE KEY", ECPrivateKey.getInstance(naked).getEncoded())
            : new PemObject("RSA PRIVATE KEY", naked.toASN1Primitive().getEncoded());
    final StringWriter sw = new StringWriter();
    try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
      w.writeObject(pem);
    }
    return sw.toString();
  }
}
