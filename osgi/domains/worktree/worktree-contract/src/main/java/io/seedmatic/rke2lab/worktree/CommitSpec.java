package io.seedmatic.rke2lab.worktree;

import java.util.Optional;

/**
 * Who commits an edit, and how: the {@code message}, the {@code identity} authored AND committed as
 * (an automated commit carries its per-tool bot identity, never the ambient {@code user.name}), and
 * the OpenSSH private key it is SSH-signed with ({@code git} namespace), or empty to commit
 * unsigned.
 */
public record CommitSpec(String message, GitIdentity identity, Optional<String> sshSigningKey) {}
