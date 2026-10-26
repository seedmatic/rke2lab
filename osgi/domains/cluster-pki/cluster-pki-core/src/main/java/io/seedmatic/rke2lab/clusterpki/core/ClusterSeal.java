package io.seedmatic.rke2lab.clusterpki.core;

import com.fasterxml.jackson.core.type.TypeReference;
import io.seedmatic.rke2lab.clusterpki.contract.AdminCredentials;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterAgeKey;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterCaBundle;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterIssuerCa;
import io.seedmatic.rke2lab.clusterpki.contract.ManagementClusterCa;
import io.seedmatic.rke2lab.clusterpki.contract.SopsDecryptor;
import io.seedmatic.rke2lab.clusterpki.contract.SopsEncryptor;
import io.seedmatic.rke2lab.clusterpki.contract.VclusterPki;
import io.seedmatic.rke2lab.clusterpki.contract.WorkloadAdminCredentials;
import io.seedmatic.rke2lab.clusterpki.contract.WorkloadClusterCas;
import io.seedmatic.rke2lab.clusterpki.core.internal.AgeRecipient;
import io.seedmatic.rke2lab.clusterpki.core.internal.ClusterCaGenerator;
import io.seedmatic.rke2lab.clusterpki.core.internal.SopsRecipients;
import io.seedmatic.rke2lab.ndh.contract.ClusterAgeIdentity;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreCatalog;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
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
 *   <li>{@link NdhKeystoreReader} → the {@code mammoth-skate-tls} root (cert + key);
 *   <li>{@link ClusterCaGenerator} → the eleven-key node bundle ({@code fileName -> PEM});
 *   <li>render it to YAML (literal block scalars) and seal it via {@link SopsEncryptor} for the
 *       {@link SopsRecipients} — the node consumes the sops envelope via sops-nix;
 *   <li>{@link ClusterAgeIdentity} → the cluster's age identity, {@code rke2lab-cluster-age-<n>},
 *       handed over by the operator on a file descriptor.
 * </ol>
 *
 * <p>The {@code mammoth-skate-tls} name is cluster-pki's semantic choice of which inventory entry
 * to read (the reader is name-generic). Nothing is written to disk: the cleartext bundle is piped
 * straight into sops, and only the sealed forms leave as a {@link SealedClusterPki}. Called ONCE
 * per cluster — the scion's idempotency gate (a cellar hit) skips it on a re-grow, so the CA stays
 * stable. See docs/architecture/cluster-api/deterministic-cluster-access.adoc.
 */
public final class ClusterSeal {

  private static final SeedCodec CODEC = new SeedCodec();

  private final NdhKeystoreReader keystore;
  private final ClusterAgeIdentity clusterAge;
  private final SopsEncryptor encryptor;
  private final List<String> recipients;

  /**
   * The seal over the {@code .sops.yaml} recipients of the working directory, as a grow runs it.
   */
  public static ClusterSeal of(
      NdhKeystoreReader keystore, ClusterAgeIdentity clusterAge, SopsEncryptor encryptor) {
    return new ClusterSeal(keystore, clusterAge, encryptor, SopsRecipients.fromDefault());
  }

  /** {@code recipients} are the {@code .sops.yaml} recipients the bundle is sealed to. */
  public ClusterSeal(
      NdhKeystoreReader keystore,
      ClusterAgeIdentity clusterAge,
      SopsEncryptor encryptor,
      List<String> recipients) {
    this.keystore = keystore;
    this.clusterAge = clusterAge;
    this.encryptor = encryptor;
    this.recipients = List.copyOf(recipients);
  }

  public SealedClusterPki seal() {
    // First, before the authority's private is read or anything is sealed: a bundle sealed to
    // recipients the identity is not one of cannot be opened on the node.
    final String ageIdentity = clusterAge.identity();
    requireRecipient(AgeRecipient.of(ageIdentity));
    final long timestamp = Instant.now().getEpochSecond();

    final ClusterCaGenerator generator = new ClusterCaGenerator();
    final ClusterCaGenerator.ClusterCaSet caSet =
        generator.generate(
            keystore.authorityCert(NdhKeystoreCatalog.TLS_AUTHORITY.entryName()),
            keystore.authorityPrivate(NdhKeystoreCatalog.TLS_AUTHORITY.entryName()),
            timestamp);
    final LinkedHashMap<String, String> bundle = caSet.nodeBundle();
    final String sealed = encryptor.encryptYaml(renderYaml(bundle), recipients);

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
  /**
   * The guard on a KEPT CA, where {@link #seal()} does not run: the identity in RKE2LAB_CLUSTER_AGE
   * must still be a {@code .sops.yaml} recipient, and it must be the one the kept bundle was filed
   * under. A node keeps the key it was born with, so a different identity would hand the render's
   * Flux a key the node's bundle was never sealed to; {@code reseal} is the way to change it.
   */
  public void checkKept(ClusterAgeKey filed) {
    final AgeRecipient current = AgeRecipient.of(clusterAge.identity());
    requireRecipient(current);
    final AgeRecipient kept = AgeRecipient.of(filed.identity());
    if (!kept.equals(current)) {
      throw new IllegalStateException(
          "the kept CA was filed under the cluster-age identity of "
              + kept.recipient()
              + ", but RKE2LAB_CLUSTER_AGE holds the one of "
              + current.recipient()
              + ": a grow does not re-wrap a kept bundle. Use reseal to move it to the new identity");
    }
  }

  private void requireRecipient(AgeRecipient recipient) {
    if (!recipient.isIn(recipients)) {
      throw new IllegalStateException(
          "the cluster-age identity's recipient "
              + recipient.recipient()
              + " is not among the .sops.yaml recipients "
              + recipients
              + ": the bundle could not be opened on the node. Fix .sops.yaml or the identity in RKE2LAB_CLUSTER_AGE");
    }
  }

  /**
   * Mint each declared vcluster's AUTONOMOUS PKI: five self-signed roots, a service-account pair,
   * and the operator's admin leaf off its own client-ca, paired with its server-ca. No read of the
   * ndh key-store (its tenant enters no fleet trust). ADDITIVE: a vcluster already in {@code
   * existing} is kept as it is, so its roots and the operator's context never churn; one no longer
   * declared is not carried.
   */
  public VclusterPki sealVclusterPki(List<String> names, Optional<VclusterPki> existing) {
    final ClusterCaGenerator generator = new ClusterCaGenerator();
    final List<VclusterPki.Entry> entries = new ArrayList<>();
    for (final String name : names) {
      final Optional<VclusterPki.Entry> kept = existing.flatMap(pki -> pki.forVcluster(name));
      if (kept.isPresent()) {
        entries.add(kept.orElseThrow());
        continue;
      }
      final long now = Instant.now().getEpochSecond();
      final VclusterPki.Pair serverCa = root(generator, name + "-server-ca", now);
      final VclusterPki.Pair clientCa = root(generator, name + "-client-ca", now);
      final ClusterCaGenerator.ServiceAccountPair sa = generator.serviceAccountPair();
      final ClusterCaGenerator.AdminLeaf admin =
          generator.mintAdminClient(clientCa.certPem(), clientCa.keyPem());
      entries.add(
          new VclusterPki.Entry(
              name,
              root(generator, name + "-ca", now),
              serverCa,
              clientCa,
              root(generator, name + "-front-proxy-ca", now),
              root(generator, name + "-etcd-ca", now),
              sa.privatePem(),
              sa.publicPem(),
              new AdminCredentials(admin.certPem(), admin.keyPem(), serverCa.certPem())));
    }
    return new VclusterPki(entries);
  }

  private static VclusterPki.Pair root(ClusterCaGenerator generator, String cn, long now) {
    final ClusterCaGenerator.RootCa root = generator.selfSignedRoot(cn, now);
    return new VclusterPki.Pair(root.certPem(), root.keyPem());
  }

  public WorkloadClusterCas sealWorkloadCas(
      List<String> clusterNames, Optional<WorkloadClusterCas> existing) {
    if (clusterNames.isEmpty()) {
      return new WorkloadClusterCas(List.of());
    }
    final String rootCert = keystore.authorityCert(NdhKeystoreCatalog.TLS_AUTHORITY.entryName());
    final String rootKey = keystore.authorityPrivate(NdhKeystoreCatalog.TLS_AUTHORITY.entryName());
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

  /**
   * Mint the operator's admin credentials for each workload cluster in {@code cas} — the same leaf
   * {@link #seal()} mints for the mgmt cluster, but from THAT cluster's own {@code client-ca},
   * paired with its own {@code server-ca} chain (both already in the entry). A workload CA
   * hierarchy is a SIBLING of the mgmt one, so nothing else would verify or be accepted there.
   *
   * <p>ADDITIVE, so the operator's kubeconfig does not churn a fresh certificate every run — but
   * the keep is GATED on the credentials still matching the cluster: an entry is kept only while
   * its {@code caCertPem} is byte-identical to the cluster's current {@code serverCa} chain. That
   * is the check rather than bookkeeping because it is the very thing that must hold — the chain
   * and the client-ca come out of ONE generated bundle, so a differing chain means the CA was
   * re-minted and the kept admin leaf is signed by a CA the apiserver no longer knows. Re-derive
   * instead of carrying a credential that would fail at the first {@code kubectl}.
   */
  public WorkloadAdminCredentials sealWorkloadAdmins(
      WorkloadClusterCas cas, Optional<WorkloadAdminCredentials> existing) {
    if (cas.entries().isEmpty()) {
      return new WorkloadAdminCredentials(List.of());
    }
    final Map<String, AdminCredentials> kept =
        existing.map(WorkloadAdminCredentials::entries).orElseGet(List::of).stream()
            .collect(
                Collectors.toMap(
                    WorkloadAdminCredentials.Entry::clusterName,
                    WorkloadAdminCredentials.Entry::credentials,
                    (a, b) -> a,
                    LinkedHashMap::new));
    final ClusterCaGenerator generator = new ClusterCaGenerator();
    final List<WorkloadAdminCredentials.Entry> entries = new ArrayList<>();
    for (final WorkloadClusterCas.Entry cluster : cas.entries()) {
      final String serverChain = cluster.serverCa().certChainPem();
      final AdminCredentials keptAdmin = kept.get(cluster.clusterName());
      if (keptAdmin != null && serverChain.equals(keptAdmin.caCertPem())) {
        entries.add(new WorkloadAdminCredentials.Entry(cluster.clusterName(), keptAdmin));
        continue;
      }
      final ClusterCaGenerator.AdminLeaf admin =
          generator.mintAdminClient(cluster.clientCa().certChainPem(), cluster.clientCa().keyPem());
      entries.add(
          new WorkloadAdminCredentials.Entry(
              cluster.clusterName(),
              new AdminCredentials(admin.certPem(), admin.keyPem(), serverChain)));
    }
    return new WorkloadAdminCredentials(entries);
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
    try {
      return CODEC.reading().readYaml(yaml, new TypeReference<LinkedHashMap<String, String>>() {});
    } catch (Exception unreadable) {
      // No cause: the parser's message quotes its source, and the source is the decrypted bundle
      // with its private keys.
      throw new IllegalStateException("failed to parse the decrypted cluster CA bundle YAML");
    }
  }

  /**
   * Render {@code fileName -> PEM} to the flat YAML the node's {@code sops.nix} declares — literal
   * block scalars so each PEM stays readable and its trailing newline is preserved.
   */
  private static String renderYaml(LinkedHashMap<String, String> bundle) {
    try {
      return CODEC.canonical().writeYaml(bundle);
    } catch (Exception unwritable) {
      // No cause, as in parseBundleYaml: a serializer's message can name what it was writing, and
      // the bundle holds the CA private keys.
      throw new IllegalStateException("failed to render the cluster CA bundle YAML");
    }
  }
}
