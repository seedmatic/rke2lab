package io.seedmatic.rke2lab.clusterpki.contract;

import java.nio.file.Path;
import java.util.List;

/**
 * Seam toward the {@code sops} external tool: seal a plaintext YAML document for a set of age
 * recipients, returning the sops-encrypted YAML. A pure point of contact — it reasons over nothing,
 * it shells a tool — so it is owned by the consumer that needs it (the cluster-pki seal, so the
 * seam lives in this contract) and realised by the top-level {@code sops-edge}, which
 * {@code @Component}-provides it; the seal binds it with a mandatory {@code @Reference}.
 *
 * <p>The node consumes the result via {@code sops-nix}, which requires the true sops envelope
 * format — hence a tool, not a Java re-implementation. The recipients are the operator's own age
 * identity AND the cluster age recipient (so both {@code us} and the node can decrypt); the edge
 * forces default encrypt-all ({@code --config /dev/null}) so the repo's {@code .sops.yaml}
 * comment-regex rule cannot leave values in the clear.
 *
 * <p>A failure of the tool is a {@link SopsEncryptionException}, whose message never quotes sops'
 * stderr.
 */
public interface SopsEncryptor {

  /**
   * Seal {@code plaintextYaml} for {@code ageRecipients}, returning the sops-encrypted YAML.
   *
   * <p>Throws an unchecked failure if the tool is absent or exits non-zero — a failed seal is a
   * defect to surface fast, not a recoverable outcome, so it propagates uncaught.
   *
   * @param plaintextYaml the cleartext YAML to seal (never touches disk — piped through the tool)
   * @param ageRecipients the age public keys to seal for (operator + cluster), non-empty
   * @return the sops-encrypted YAML document
   */
  String encryptYaml(String plaintextYaml, List<String> ageRecipients);

  /**
   * Seal {@code plaintextYaml} the way {@code policy} says a file at {@code path} is sealed: the
   * creation rule whose {@code path_regex} matches {@code path} picks the recipients and the leaves
   * to encrypt. The second consumer is the manifests render, which seals each Secret it writes to a
   * rendered branch IN MEMORY, before the file exists — never through a git clean filter, which a
   * blob inserted without a checkout would skip.
   *
   * <p>A rule that matches but encrypts nothing returns a valid envelope around the clear values;
   * the caller checks the result, the tool does not.
   *
   * @param plaintextYaml the cleartext YAML to seal (piped through the tool, never touches disk)
   * @param policy the {@code .sops.yaml} whose creation rules apply
   * @param path the path the sealed document will have, relative to the policy's directory
   * @return the sops-encrypted YAML document
   */
  String encryptYaml(String plaintextYaml, Path policy, String path);
}
