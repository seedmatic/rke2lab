package io.seedmatic.rke2lab.auth.contract;

import java.util.Optional;

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
 * <p>{@link Optional#empty()} when the token cannot be minted (the {@code cultivating}-gated edge
 * is filtered out of a survey/preview, or its mint dependency is absent) — the caller then files no
 * token, honest inertness rather than a fabricated credential.
 */
public interface GithubReaderTokenMint {

  /** A fresh {@code contents:read} installation token for the one org-owned App, or empty. */
  Optional<String> mint(String appId, String installationId, String privateKeyPem);
}
