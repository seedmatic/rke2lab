package io.seedmatic.rke2lab.worktree;

/**
 * The HEAD provenance of a worktree — the commit {@code sha} it sits on and whether it is {@code
 * dirty}. The sha is the KEY (the rest is recoverable via {@code git show}); dirty is the one bit
 * the sha cannot carry. An empty sha is a legitimate first run (no {@code .git}, or no commit yet),
 * not a failure. Dual-realm: the {@code DurableWorktree} service returns it OSGi-side, and it rides
 * inside {@link WorktreeFacts} across the broker for the flat host to decode into its own copy.
 */
public record Provenance(String sha, boolean dirty) {}
