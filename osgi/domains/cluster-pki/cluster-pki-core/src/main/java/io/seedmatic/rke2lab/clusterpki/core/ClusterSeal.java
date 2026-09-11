package io.seedmatic.rke2lab.clusterpki.core;

import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.seedmatic.rke2lab.clusterpki.contract.AdminCredentials;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterAgeKey;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterCaBundle;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterIssuerCa;
import io.seedmatic.rke2lab.clusterpki.contract.ManagementClusterCa;
import io.seedmatic.rke2lab.clusterpki.contract.SopsDecryptor;
import io.seedmatic.rke2lab.clusterpki.contract.SopsEncryptor;
import io.seedmatic.rke2lab.clusterpki.contract.WorkloadClusterCas;
import io.seedmatic.rke2lab.clusterpki.core.internal.ClusterCaGenerator;
import io.seedmatic.rke2lab.clusterpki.core.internal.SopsRecipients;
import io.seedmatic.rke2lab.manifests.contract.SshToAgeConverter;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The cluster-PKI seal, in one act — the domain logic the seal scion drives (instance-passing: the
 * three external seams are handed in, never reached statically). It reads the operator's ndh key
 * inventory through {@link NdhKeystoreReader} and the repo's {@code .sops.yaml} in-container, mints
 * the deterministic CA with {@link ClusterCaGenerator}, seals the eleven-key bundle for the
 * operator + cluster age recipients, and derives the cluster age identity the node decrypts it
 * with:
 *
 * <ol>
 *   <li>{@link NdhKeystoreReader} → the {@code mammoth-skate-tls} root (cert + key) and the {@code
 *       rke2-cluster} SSH key;
 *   <li>{@link ClusterCaGenerator} → the eleven-key node bundle ({@code fileName -> PEM});
 *   <li>render it to YAML (literal block scalars) and seal it via {@link SopsEncryptor} for the
 *       {@link SopsRecipients} — the node consumes the sops envelope via sops-nix;
 *   <li>{@link SshToAgeConverter} on the {@code rke2-cluster} key → the cluster age identity.
 * </ol>
 *
 * <p>The {@code mammoth-skate-tls} / {@code rke2-cluster} names are cluster-pki's semantic choice
 * of which inventory entries to read (the reader is name-generic). Nothing is written to disk: the
 * cleartext bundle is piped straight into sops, and only the sealed forms leave as a {@link
 * SealedClusterPki}. Called ONCE per cluster — the scion's idempotency gate (a cellar hit) skips it
 * on a re-grow, so the CA stays stable. See
 * docs/architecture/cluster-api/deterministic-cluster-access.adoc.
 */
public final class ClusterSeal {

  /**
   * The ndh inventory entries cluster-pki roots the CA on / derives the cluster age identity from.
   */
  private static final String TLS_AUTHORITY = "mammoth-skate-tls";

  private static final String CLUSTER_SSH_KEY = "rke2-cluster";

  private final NdhKeystoreReader keystore;
  private final SshToAgeConverter sshToAge;
  private final SopsEncryptor encryptor;

  public ClusterSeal(
      NdhKeystoreReader keystore, SshToAgeConverter sshToAge, SopsEncryptor encryptor) {
    this.keystore = keystore;
    this.sshToAge = sshToAge;
    this.encryptor = encryptor;
  }

  public SealedClusterPki seal() {
    final List<String> recipients = SopsRecipients.fromDefault();
    final long timestamp = Instant.now().getEpochSecond();

    final ClusterCaGenerator generator = new ClusterCaGenerator();
    final ClusterCaGenerator.ClusterCaSet caSet =
        generator.generate(
            keystore.authorityCert(TLS_AUTHORITY),
            keystore.authorityPrivate(TLS_AUTHORITY),
            timestamp);
    final LinkedHashMap<String, String> bundle = caSet.nodeBundle();
    final String sealed = encryptor.encryptYaml(renderYaml(bundle), recipients);
    final String ageIdentity = sshToAge.toAgeKey(keystore.sshPrivate(CLUSTER_SSH_KEY));

    // The operator's admin creds: an admin clientAuth leaf minted from the client-ca just
    // generated,
    // paired with the server-ca chain (which ends at the mammoth-skate-tls root) so the operator
    // verifies kube-apiserver natively. Endpoint-independent — the host wraps a kubeconfig around
    // it.
    final ClusterCaGenerator.AdminLeaf admin =
        generator.mintAdminClient(bundle.get("client-ca.crt"), bundle.get("client-ca.key"));
    final AdminCredentials adminCredentials =
        new AdminCredentials(admin.certPem(), admin.keyPem(), bundle.get("server-ca.crt"));

    // The cert-manager ClusterIssuer root: full chain (to the mammoth-skate root) + CA key,
    // delivered in-cluster so every leaf chains to our own CA.
    final ClusterIssuerCa clusterIssuerCa =
        new ClusterIssuerCa(caSet.issuerCaChainPem(), caSet.issuerCaKeyPem());

    // The mgmt cluster's OWN four CAs in render-usable form — the same node-bundle pairs, exposed
    // so the mgmt-adoption CR set renders them as BYO-CA Secrets and CAPRKE2 adopts the running
    // control plane with its LIVE CA. Nameless: the render stamps the mgmt cluster name.
    final ManagementClusterCa managementCas = managementCasFrom(bundle);

    return new SealedClusterPki(
        new ClusterCaBundle(sealed),
        new ClusterAgeKey(ageIdentity),
        adminCredentials,
        clusterIssuerCa,
        managementCas);
  }

  /**
   * Mint the deterministic CAPRKE2 BYO-CA set for each workload cluster the mgmt greenfields —
   * ADDITIVELY and idempotently: a cluster already present in {@code existing} keeps its CA (stable
   * across re-grows, never re-minted — a re-mint would rotate every workload cert), a cluster newly
   * appearing gets a FRESH hierarchy rooted DIRECTLY on {@code mammoth-skate-tls} (a SIBLING of the
   * mgmt CA — independent keys). Clusters no longer requested drop out (deprovision). Each entry
   * carries the four CAs CAPRKE2 looks up by name ({@code <cluster>-{ca,cca,etcd,peer-etcd}}); the
   * {@code *-ca.crt} values are the full chain to the root, byte-identical to the mgmt node's
   * {@code server/tls} files. Unlike the node bundle, no sops/age here: these are rendered as
   * in-cluster Secrets, not decrypted node-side.
   */
  public WorkloadClusterCas sealWorkloadCas(
      List<String> clusterNames, Optional<WorkloadClusterCas> existing) {
    if (clusterNames.isEmpty()) {
      return new WorkloadClusterCas(List.of());
    }
    final String rootCert = keystore.authorityCert(TLS_AUTHORITY);
    final String rootKey = keystore.authorityPrivate(TLS_AUTHORITY);
    final Map<String, WorkloadClusterCas.Entry> kept =
        existing.map(WorkloadClusterCas::entries).orElseGet(List::of).stream()
            .collect(
                Collectors.toMap(
                    WorkloadClusterCas.Entry::clusterName,
                    entry -> entry,
                    (a, b) -> a,
                    LinkedHashMap::new));
    final ClusterCaGenerator generator = new ClusterCaGenerator();
    final List<WorkloadClusterCas.Entry> entries = new ArrayList<>();
    for (final String clusterName : clusterNames) {
      final WorkloadClusterCas.Entry existingEntry = kept.get(clusterName);
      if (existingEntry != null) {
        entries.add(existingEntry);
        continue;
      }
      final LinkedHashMap<String, String> bundle =
          generator.generate(rootCert, rootKey, Instant.now().getEpochSecond()).nodeBundle();
      entries.add(
          new WorkloadClusterCas.Entry(
              clusterName,
              pair(bundle, "server-ca"),
              pair(bundle, "client-ca"),
              pair(bundle, "etcd-server-ca"),
              pair(bundle, "etcd-peer-ca")));
    }
    return new WorkloadClusterCas(entries);
  }

  private WorkloadClusterCas.Pair pair(Map<String, String> bundle, String stem) {
    return new WorkloadClusterCas.Pair(bundle.get(stem + ".crt"), bundle.get(stem + ".key"));
  }

  /**
   * The mgmt cluster's OWN four CAs (server/client/etcd-server/etcd-peer) as render-usable pairs,
   * pulled from its node bundle — the LIVE CA the running control plane issues from, so the
   * mgmt-adoption render can hand it to CAPRKE2 as BYO-CA Secrets and adoption never rotates it.
   */
  public ManagementClusterCa managementCasFrom(Map<String, String> bundle) {
    return new ManagementClusterCa(
        pair(bundle, "server-ca"),
        pair(bundle, "client-ca"),
        pair(bundle, "etcd-server-ca"),
        pair(bundle, "etcd-peer-ca"));
  }

  /**
   * Recover {@link ManagementClusterCa} from an ALREADY-sealed bundle — the keep-path backfill. On
   * a re-grow the CA is KEPT ({@link #seal()} is skipped for stability), so the plaintext node
   * bundle is gone; but the adoption render still needs the four pairs. Decrypt the sops blob with
   * the cluster age identity (both already in the cellar) to recover the same bundle {@link
   * #seal()} built, then map it. Idempotent (decrypt is pure); the recovered CA is byte-identical
   * to the node's live one.
   */
  public ManagementClusterCa recoverManagementCas(
      ClusterCaBundle bundle, ClusterAgeKey ageKey, SopsDecryptor decryptor) {
    final String plainYaml = decryptor.decryptYaml(bundle.sops(), ageKey.identity());
    return managementCasFrom(parseBundleYaml(plainYaml));
  }

  private static LinkedHashMap<String, String> parseBundleYaml(String yaml) {
    final YAMLMapper mapper = YAMLMapper.builder().build();
    try {
      return mapper.readValue(
          yaml,
          mapper
              .getTypeFactory()
              .constructMapType(LinkedHashMap.class, String.class, String.class));
    } catch (Exception ex) {
      throw new IllegalStateException("failed to parse the decrypted cluster CA bundle YAML", ex);
    }
  }

  /**
   * Render {@code fileName -> PEM} to the flat YAML the node's {@code sops.nix} declares — literal
   * block scalars so each PEM stays readable and its trailing newline is preserved.
   */
  private static String renderYaml(LinkedHashMap<String, String> bundle) {
    final YAMLMapper mapper =
        YAMLMapper.builder()
            .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .build();
    try {
      return mapper.writeValueAsString(bundle);
    } catch (Exception ex) {
      throw new IllegalStateException("failed to render the cluster CA bundle YAML", ex);
    }
  }
}
