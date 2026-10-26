package io.seedmatic.rke2lab.clusterpki.contract;

import io.seedmatic.rke2lab.domain.annotations.Transitional;
import io.seedmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The vclusters' PKIs — one AUTONOMOUS root set per vcluster, self-signed and rooted at nothing of
 * the fleet's (the operator, 2026-10-26: a vcluster's tenant enters no fleet trust), minted by the
 * seal at grow time. Filed {@link ClusterPkiCoordinate#VCLUSTER_PKI} SEALED (it carries the CA and
 * service-account private keys), minted ADDITIVELY like {@link WorkloadClusterCas}: a re-grow keeps
 * the entries already sealed and mints only the vclusters newly declared.
 *
 * <p>Only the ROOTS: vcluster reads an existing {@code <name>-certs} Secret at start, keeps every
 * CA and service-account key it finds, and mints the leaves (apiserver, etcd, front-proxy client)
 * and their SAN itself (upstream pkg/certs/ensure.go:122-149 and constants.go:144-186, read at
 * loft-sh/vcluster e702d7ba, not measured live). The manifests scion renders {@link
 * Entry#secretData()} as that Secret on the host cluster's branch, sops-encrypted; the host adds
 * {@link Entry#admin()} as the operator's context for the vcluster.
 */
@Transitional(to = "VclusterPki", spec = "deterministic-cluster-access.adoc")
@SeedContract("vcluster-pki")
public record VclusterPki(List<Entry> entries) {
  public VclusterPki {
    entries = entries == null ? List.of() : List.copyOf(entries);
  }

  /** The PKI for {@code name}, if this case carries one. */
  public Optional<Entry> forVcluster(String name) {
    return entries.stream().filter(entry -> entry.name().equals(name)).findFirst();
  }

  /**
   * One vcluster's roots, its service-account pair and the operator's admin credential minted from
   * its {@code client-ca}. {@code ca} is the root kubeadm signs the kubelet and controller clients
   * with; {@code serverCa}/{@code clientCa} the split k3s CAs; {@code frontProxyCa} the aggregation
   * layer's; {@code etcdCa} etcd's.
   */
  public record Entry(
      String name,
      Pair ca,
      Pair serverCa,
      Pair clientCa,
      Pair frontProxyCa,
      Pair etcdCa,
      String serviceAccountKeyPem,
      String serviceAccountPubPem,
      AdminCredentials admin) {
    public Entry {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(serviceAccountKeyPem, "serviceAccountKeyPem");
      Objects.requireNonNull(serviceAccountPubPem, "serviceAccountPubPem");
      Objects.requireNonNull(admin, "admin");
    }

    /** The Secret vcluster reads at start: {@code <name>-certs} (upstream CertSecretName). */
    public String secretName() {
      return name + "-certs";
    }

    /** Its data, keyed by the file names vcluster's certMap expects. */
    public Map<String, String> secretData() {
      final Map<String, String> data = new LinkedHashMap<>();
      put(data, "ca", ca);
      put(data, "server-ca", serverCa);
      put(data, "client-ca", clientCa);
      put(data, "front-proxy-ca", frontProxyCa);
      put(data, "etcd-ca", etcdCa);
      data.put("sa.key", serviceAccountKeyPem);
      data.put("sa.pub", serviceAccountPubPem);
      return data;
    }

    private static void put(Map<String, String> data, String stem, Pair pair) {
      data.put(stem + ".crt", pair.certPem());
      data.put(stem + ".key", pair.keyPem());
    }
  }

  /** A self-signed root: its certificate and its private key, both PEM. */
  public record Pair(String certPem, String keyPem) {
    public Pair {
      Objects.requireNonNull(certPem, "certPem");
      Objects.requireNonNull(keyPem, "keyPem");
    }
  }
}
