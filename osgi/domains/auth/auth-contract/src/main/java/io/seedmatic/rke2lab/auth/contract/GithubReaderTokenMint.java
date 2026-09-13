package io.seedmatic.rke2lab.auth.contract;

/**
 * The auth domain's on-demand GitHub READ-token verb — the least-privilege twin of {@link
 * GithubWriterTokenMint}: from the durable one-org-owned App credentials (revealed by a consumer
 * from its OWN cellar case), mint a FRESH {@code contents:read} installation token AT THE POINT OF
 * USE.
 *
 * <p>For a consumer that only needs to READ a private repo — e.g. a node fetching its rendered
 * {@code manifests/<cluster>} branch to install its rke2 config — a {@code contents:write} token
 * (the writer verb) is over-privileged. This mints the minimal scope.
 *
 * <p>Same contract as the writer verb otherwise: the App is the single source of trust; the token
 * is ephemeral (≈1 h) and MUST NOT be stored durably — mint it close to use and let a transient
 * cellar tier evict it at the run's drain. Pure-JDK signature (credential fields cross as plain
 * strings, no {@code ghapp-contract} type dragged into the mirroring consumer); the realised {@code
 * auth-edge} impl delegates to the ghapp minter with the {@code READER} scope.
 *
 * <p>Fail-loud at this frontier (the twin of the writer verb): an inability to produce a usable
 * token THROWS, it never returns a blank one. A caller that must not mint — a survey/preview where
 * the {@code cultivating}-gated edge is filtered out, or an enclosure with no App credentials —
 * never resolves the edge and never calls this. So consumers hold no {@code Optional} from the mint
 * itself: a valid token, or an exception.
 */
public interface GithubReaderTokenMint {

  /**
   * A fresh {@code contents:read} installation token for the one org-owned App. Throws when a
   * usable token cannot be minted (an empty or failed mint) — never returns a blank credential.
   */
  String mint(String appId, String installationId, String privateKeyPem);
}
