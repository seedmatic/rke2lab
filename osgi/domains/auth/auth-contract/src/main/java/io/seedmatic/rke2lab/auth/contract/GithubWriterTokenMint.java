package io.seedmatic.rke2lab.auth.contract;

import io.seedmatic.rke2lab.ghapp.contract.GithubAppCredentials;

/**
 * The auth domain's on-demand GitHub push-token verb: from the durable one-org-owned App
 * credentials (revealed by a consumer from its OWN cellar case), mint a FRESH {@code
 * contents:write} installation token AT THE POINT OF USE.
 *
 * <p>The App is the single source of trust; the token itself is ephemeral (≈1 h) and MUST NOT be
 * stored. Minting it seconds before the push and discarding it is what keeps it from going stale
 * between a mint and a much later reveal — the trap a durable seal fell into (an installation token
 * filed in the cellar before provisioning, revealed for the push after the whole cluster came up,
 * long past its 1 h life).
 *
 * <p>It takes the owner's {@link GithubAppCredentials} as a record, not its three fields: its one
 * caller, {@link GithubAppTokens}, holds that record, and the ghapp minter the {@code auth-edge}
 * impl delegates to takes it too — splitting it into strings in between would only be a second
 * spelling of the same material.
 *
 * <p>Fail-loud at this frontier: an inability to produce a usable token THROWS, it never returns a
 * blank one. A caller that must not mint — a survey/preview where the {@code cultivating}-gated
 * edge is filtered out, or an enclosure with no App credentials — simply never resolves the edge
 * and never calls this. So the token's presence is mandatory once the mint is invoked, and
 * consumers hold no {@code Optional} from the mint itself: a valid token, or an exception.
 */
public interface GithubWriterTokenMint {

  /**
   * A fresh {@code contents:write} installation token for the one org-owned App. Throws when a
   * usable token cannot be minted (an empty or failed mint) — never returns a blank credential.
   */
  String mint(GithubAppCredentials app);
}
