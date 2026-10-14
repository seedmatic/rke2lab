package io.seedmatic.rke2lab.clusterpki.contract;

/**
 * Seam toward the {@code sops} external tool, the inverse of {@link SopsEncryptor}: open a
 * sops-encrypted YAML with an age identity we hold, returning the plaintext YAML. Same ownership as
 * its twin — a pure point of contact the cluster-pki seal binds and the top-level {@code sops-edge}
 * realises.
 *
 * <p>Why the seal needs it: adding a NEW sealed case (e.g. the webhook serving cert) to an EXISTING
 * cluster must NOT regenerate the CAs (random keygen — a re-seal would break the live cluster). The
 * additive path instead re-opens the already-sealed {@link ClusterCaBundle} — the CA material we
 * already possess — with the cluster age identity the seal itself derives, mints only the missing
 * leaf from the recovered {@code server-ca}, and files it. Decryption is thus not an operator
 * secret we lack; it is a key we hold, and this is the door we had not yet built.
 *
 * <p>A failure of the tool is a {@link SopsEncryptionException}, whose message never quotes sops'
 * stderr.
 */
public interface SopsDecryptor {

  /**
   * Open {@code sopsYaml} with {@code ageIdentity} (an {@code AGE-SECRET-KEY-…} private key),
   * returning the plaintext YAML.
   *
   * <p>Throws an unchecked failure if the tool is absent, the identity cannot decrypt, or it exits
   * non-zero — a failed open is a defect to surface fast, not a recoverable outcome.
   *
   * @param sopsYaml the sops-encrypted YAML document (piped through the tool, never touches disk)
   * @param ageIdentity the age private key that is one of the document's recipients
   * @return the plaintext YAML document
   */
  String decryptYaml(String sopsYaml, String ageIdentity);

  /**
   * Open {@code sopsYaml} with whatever identity sops finds on its own ({@code SOPS_AGE_KEY},
   * {@code SOPS_AGE_KEY_FILE}, the default key file). The manifests render reads back the carrier
   * it sealed on a previous run this way: in-cluster the cluster key is injected into its
   * environment, on the operator's host the operator key is in place, and neither is ever held by
   * the render itself.
   *
   * @param sopsYaml the sops-encrypted YAML document (piped through the tool, never touches disk)
   * @return the plaintext YAML document
   */
  String decryptYaml(String sopsYaml);
}
