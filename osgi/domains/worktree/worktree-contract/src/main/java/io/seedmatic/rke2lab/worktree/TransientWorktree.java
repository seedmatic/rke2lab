package io.seedmatic.rke2lab.worktree;

import java.time.Duration;
import java.util.Optional;

/**
 * A head the seed commits onto WITHOUT materializing it: the branch's tip read from the worktree's
 * repository, edited in memory, committed as objects, then published. No working file, no linked
 * worktree, nothing to release — the transient realization of {@link Worktree}, twin of the {@link
 * DurableWorktree}.
 *
 * <p>jgit-free — only JDK types and the {@code worktree} records cross it.
 */
public interface TransientWorktree extends Worktree {

  /** The branch this head belongs to, e.g. {@code manifests/<cluster>}. */
  String branch();

  /**
   * The commit the next edit builds on: the branch's tip when it exists, else a base commit
   * carrying only a README (jgit cannot pack the empty tree), then the last commit this head made.
   */
  String head();

  /**
   * Commit {@code edit} on {@link #head()} as {@code spec} says, writing objects only: no ref moves
   * until {@link #publish}. When the resulting tree is the head's, nothing is committed and the
   * head is returned. Returns the new head.
   */
  String commit(TreeEdit edit, CommitSpec spec);

  /**
   * Land {@link #head()} on the branch. With a {@code pushToken}: fast-forward push to origin
   * first, and only once origin accepted it advance the local branch — never a local ref ahead of a
   * failed push. Without: advance the local branch only (a render whose push is not armed). {@code
   * timeout} bounds an IDLE connection, not the whole push.
   */
  void publish(Optional<String> pushToken, Duration timeout);

  /** {@link #publish(Optional, Duration)} with {@link #DEFAULT_PUSH_TIMEOUT}. */
  default void publish(Optional<String> pushToken) {
    publish(pushToken, DEFAULT_PUSH_TIMEOUT);
  }

  /** The idle bound of a publish: a stalled connection fails rather than blocks the seed. */
  Duration DEFAULT_PUSH_TIMEOUT = Duration.ofSeconds(30);
}
