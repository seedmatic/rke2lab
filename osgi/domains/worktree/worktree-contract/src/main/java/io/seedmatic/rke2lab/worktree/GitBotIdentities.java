package io.seedmatic.rke2lab.worktree;

/**
 * Mints the per-tool bot {@link GitIdentity} rke2lab commits an automated change as — an INSTANCE
 * holding the ONE thing that varies by deployment, the tailnet {@code authorityDomain} (imported
 * from the PKI keystore, never re-typed). The single source of trust for the bot identity: {@code
 * rke2lab} and the domain are OWNED here; a tool supplies only its own discriminator, so every
 * automated commit reads {@code rke2lab:<tool>} / {@code <tool>@<domain>} and no tool can drift
 * into a bare {@code rke2lab@<domain>} or a foreign base. It lives beside {@link GitIdentity}, in
 * the worktree domain that owns the git mechanism, because a single source of trust only ONE domain
 * can reach is not one: the fabric delivery signs its own commits and would otherwise have minted a
 * second convention.
 *
 * <p>An instance, not a static factory: the domain is a value the caller resolved (from {@code
 * NdhKeystoreReader.authorityDomain}) and passes in, so minting is a method on the thing that holds
 * it — the discriminator is the only per-call input, and it is guarded to a bare suffix (no
 * {@code @}, {@code :}, or whitespace) so it can only ADD provenance, never replace the base.
 *
 * <p>A record because it is one immutable value with behaviour, which is also what lets it live in
 * a {@code type=contract} bundle: the contract-purity law exports only records / enums / sealed
 * ADTs / interfaces, and it refused the concrete class this used to be. The law and the project's
 * "immutability by default" rule asked for the same shape.
 */
public record GitBotIdentities(String authorityDomain) {

  private static final String BOT = "rke2lab";

  public GitBotIdentities {
    if (authorityDomain.isBlank()) {
      throw new IllegalArgumentException("the bot identity's authority domain is required");
    }
    authorityDomain = authorityDomain.trim();
  }

  /**
   * The bot identity for {@code discriminator} (e.g. {@code manifests-bumper}): name {@code
   * rke2lab:<discriminator>}, email {@code rke2lab+<discriminator>@<authorityDomain>}. rke2lab is
   * the committer in BOTH — the {@code +<discriminator>} is plus-addressing that records which tool
   * made the automated commit, so the mailbox stays {@code rke2lab} while the provenance is
   * legible. The discriminator is a bare suffix — it may not carry {@code @}, {@code :}, or
   * whitespace, so it cannot spoof the base identity.
   */
  public GitIdentity forTool(final String discriminator) {
    if (discriminator == null || discriminator.isBlank()) {
      throw new IllegalArgumentException("a bot identity discriminator is required");
    }
    final String suffix = discriminator.trim();
    if (suffix.chars().anyMatch(c -> c == '@' || c == ':' || Character.isWhitespace(c))) {
      throw new IllegalArgumentException(
          "the bot discriminator '"
              + suffix
              + "' must be a bare suffix — no '@', ':', or whitespace (tools only add provenance,"
              + " never replace the rke2lab base)");
    }
    return new GitIdentity(BOT + ":" + suffix, BOT + "+" + suffix + "@" + authorityDomain);
  }
}
