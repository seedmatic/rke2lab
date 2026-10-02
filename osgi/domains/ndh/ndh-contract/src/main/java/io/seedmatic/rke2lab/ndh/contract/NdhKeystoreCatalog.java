package io.seedmatic.rke2lab.ndh.contract;

/**
 * The ndh inventory entries rke2lab reads, named ONCE. {@link NdhKeystoreReader} is deliberately
 * name-generic — it knows how to open the inventory, not which entries matter — so the entry names
 * are rke2lab's semantic choice. They are that choice, stated here instead of at each reader call.
 *
 * <p>★ Why one registry and not a constant per caller: {@link #TAILNET_AUTHORITY} and {@link
 * #TLS_AUTHORITY} are TWO DIFFERENT authorities whose names read like one is a suffix of the other,
 * and they carry different fields — only the tailnet one has a {@code domain}, and it is the TLS
 * one that signs the Incus listener leaf. A caller reaching for "the mammoth-skate authority" has
 * even odds of picking the entry that lacks the field it wants, and the reader is fail-fast on a
 * missing field, so the mistake surfaces as a raise from three layers down rather than as something
 * that does not compile. Naming both here puts the pair in front of whoever chooses.
 *
 * <p>An enum rather than a class of constants: {@code ndh-contract} is a contract bundle, and the
 * staging law lets one export only records, enums, sealed ADTs and interfaces. That is the better
 * shape anyway — a caller passes a value the compiler knows, and {@link #entryName()} is the only
 * place a literal lives.
 *
 * <p>⚠️ {@code "rke2-cluster"} also spells an AS name in {@code ClusterAsn.RKE2_CLUSTER}. That is a
 * different concept sharing a spelling — it names an autonomous system, not an inventory entry — so
 * it does NOT belong here and must not be migrated to it.
 */
public enum NdhKeystoreCatalog {

  /**
   * The SSH + TLS authority that owns the tailnet DOMAIN — {@code authorities.mammoth-skate}. Read
   * for {@link NdhKeystoreReader#authorityDomain} alone (the git-bot identities, the cert domain):
   * it is the only one of the two carrying a {@code domain} field.
   */
  TAILNET_AUTHORITY("mammoth-skate"),

  /**
   * The TLS authority the fleet's certificates are rooted at — {@code
   * authorities.mammoth-skate-tls}. Both the rke2 leaf CAs minted at seal time and the Incus
   * listener leaf chain to it, which is what lets a consumer trust the listener by CA instead of
   * pinning its leaf.
   */
  TLS_AUTHORITY("mammoth-skate-tls"),

  /**
   * The SSH key entry the cluster's age identity is derived from — {@code keys.rke2-cluster}. One
   * entry, three consumers: the cellar seals to it, Flux's sops-age identity is derived from it,
   * and cluster-pki seals the node bundle for it.
   */
  CLUSTER_SSH_KEY("rke2-cluster");

  private final String entryName;

  NdhKeystoreCatalog(final String entryName) {
    this.entryName = entryName;
  }

  /** The name this entry carries in {@code keys.yaml} — what the reader is handed. */
  public String entryName() {
    return entryName;
  }
}
