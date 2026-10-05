package io.seedmatic.rke2lab.ndh.contract;

/**
 * The one seam for reading the operator's ndh key inventory ({@code .ndh-ssh.d/keys.yaml}) — the
 * rke2lab ↔ nix-darwin-home boundary. A pure point of contact: it navigates the structured YAML and
 * returns fields by authority / key NAME. This interface stays name-generic — it knows HOW the
 * inventory is opened, not which entries matter (realised by {@code ndh-core}) — and the entries
 * that DO matter are named once in {@link NdhKeystoreCatalog}, which every caller reads. They were
 * each consumer's own literal until a third consumer wanted the TLS root: two authorities whose
 * names read like one is a suffix of the other, spelled in six places, is how two spellings come to
 * disagree.
 *
 * <p>{@link #present()} is the fail-soft gate (an ephemeral run has no key-store); the accessors
 * are fail-fast (a present-but-malformed store, or a missing field, raises — a defect to surface).
 */
public interface NdhKeystoreReader {

  /** Whether the key-store is present + readable (fail-soft gate; the accessors assume it is). */
  boolean present();

  /** The x509 certificate of a TLS authority — {@code authorities.<authority>.ca_crt}. */
  String authorityCert(String authority);

  /**
   * The domain a TLS authority is scoped to — {@code authorities.<authority>.domain} (e.g. {@code
   * mammoth-skate.ts.net}). The single source of truth for the tailnet/cert domain: the authority
   * signs {@code *.<domain>} certs, so the domain lives with it. Consumers (the manifests version
   * bumper's git-bot email, …) import it here rather than re-typing the literal.
   */
  String authorityDomain(String authority);

  /**
   * The private key of a TLS authority — {@code authorities.<authority>.slots.<oldest>.private}
   * (PEM or OpenSSH). The OLDEST generation, because that is the one an authority signs with: a new
   * authority is trusted one activation at a time, so its leaves are only re-signed under it once
   * every verifier knows it.
   */
  String authorityPrivate(String authority);

  /**
   * The private key of an SSH key entry — {@code keys.<keyName>.slots.<newest>.private} (OpenSSH),
   * the generation the key presents.
   */
  String sshPrivate(String keyName);

  /**
   * The public key of an SSH key entry — {@code keys.<keyName>.slots.<newest>.public}: the bare
   * base64 blob, WITHOUT the {@code ssh-ed25519} type or the comment (ndh keeps both in fields of
   * their own). The seal side of {@link #sshPrivate}: the cellar cipher seals an age SSH-recipient
   * stanza TO this public key and reveals it with the matching private, so a harvest sealed by the
   * seed is readable by whoever holds the key — no ssh-to-age, no separate age key.
   */
  String sshPublic(String keyName);
}
