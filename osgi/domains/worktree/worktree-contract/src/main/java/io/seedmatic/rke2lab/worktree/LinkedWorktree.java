package io.seedmatic.rke2lab.worktree;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * A prepared linked worktree — the handle {@link RenderedBranch#prepare} returns. It is the
 * transient checkout a producer materialises a rendered tree into, then seals with a SIGNED commit
 * and force-pushes to the origin the repository already knows. {@link AutoCloseable}: {@link
 * #close()} runs {@code git worktree remove --force}, so a try-with-resources leaves no linked
 * worktree behind whatever the render did.
 *
 * <p>Its {@link #stage}/{@link #commit} mirror {@link Worktree}'s verbs, but against THIS
 * worktree's path and branch, not the seed's own — the same signing mechanism (the caller's OpenSSH
 * key, git SSHSIG under the {@code git} namespace), the same bot {@link GitIdentity} discipline.
 * jgit and {@code git worktree} stay sealed behind the implementation; only JDK types cross.
 */
public interface LinkedWorktree extends AutoCloseable {

  /** The worktree root the render materialises into — absolute, normalised. */
  Path path();

  /**
   * The branch this worktree has checked out (e.g. the rendered {@code manifests/<host>-<role>}).
   */
  String branch();

  /**
   * The content of {@code path} as committed at {@code HEAD} of this checked-out branch, or empty
   * when there is no HEAD (a first render, empty branch) or the path is absent. Reads the COMMITTED
   * blob, NOT the working-tree file — so a render reads the branch's recorded state (e.g. the
   * recorded facet at {@code manifest.yaml}) before it overwrites the tree. jgit stays sealed.
   */
  Optional<String> readAtHead(String path);

  /**
   * Restore {@code path} from {@code HEAD} into the working tree THROUGH the smudge filter, then
   * return its (smudged) content — the read-back a rendered branch's sops-encrypted asset needs.
   * {@link #readAtHead} yields the COMMITTED blob (sops-ENCRYPTED, jgit runs no filter), so a
   * consumer that must read the plaintext instead checks the committed asset out with {@code git}
   * (which DOES run the {@code sops-yaml} smudge filter the environment configures), leaving the
   * decrypted file where the render then overwrites it. Empty when {@code path} is absent at HEAD
   * (a first render, an orphan base). The working-tree file is a side effect — {@code prepare}
   * emptied the tree, so restoring it is additive, and a later {@link #stageAll} re-seals it via
   * the clean filter.
   */
  Optional<String> smudgeFromHead(String path);

  /**
   * Stage the given paths for the next commit — additions/modifications for paths that exist,
   * removals for paths that no longer do. Each path may be absolute or resolved against {@link
   * #path()}. For staging a whole rendered tree (including files a re-render dropped), prefer
   * {@link #stageAll()}.
   */
  void stage(List<Path> paths);

  /**
   * Stage the ENTIRE worktree — additions, modifications, AND deletions ({@code git add -A}). The
   * render verb: a re-render rewrites the tree in place, and a manifest removed since the previous
   * render must be staged as a deletion so the commit reflects the tree exactly.
   */
  void stageAll();

  /**
   * Commit the staged tree with {@code message}, authored AND committed as {@code identity} (a bot
   * identity — a rendered branch is machine-made, attributable to the tool, never to the ambient
   * {@code user.name}). {@code sshSigningKey} is the caller's OpenSSH PRIVATE key the commit is
   * SSH-signed with (git SSHSIG, {@code git} namespace); {@link Optional#empty()} commits unsigned.
   * The commit accretes on the branch's tip (a null-commit base + one commit per render that
   * CHANGES the tree). A render that reproduces the tip byte-for-byte commits nothing — every
   * webhook/reconcile fires a render, so an unchanged one must not churn the branch with an empty
   * commit; it returns the unchanged tip sha instead, and the follow-up {@link #push} is a
   * fast-forward no-op. Returns the delivered commit sha (new, or the unchanged tip). Local only —
   * {@link #push} is the separate, credentialed act.
   */
  String commit(String message, GitIdentity identity, Optional<String> sshSigningKey);

  /**
   * Push this worktree's {@link #branch()} to the repository's {@code origin} over HTTPS,
   * authenticating as {@code x-access-token} with {@code token} (a short-lived GitHub token the
   * caller revealed from the sealed cellar). A FAST-FORWARD push, not a force: renders accrete on
   * the branch's stable null-commit base, so the remote advances, never rewrites — a divergence
   * fails loudly rather than being clobbered. The credential is held in memory for the single push,
   * never written to a config or a command line.
   *
   * <p>{@code timeout} bounds the transport so a wedged connection FAILS rather than blocks the
   * seed forever; it is the caller's to choose because only the caller knows the push's weight and
   * the link — a first full push differs from an incremental one. {@link Duration#ZERO} disables
   * the ceiling (jgit's "no timeout").
   */
  void push(String token, Duration timeout);

  /**
   * The ceiling {@link #push(String)} applies — a generous IDLE bound (jgit reads it as a
   * per-operation socket timeout, not a total deadline, so a slow-but-progressing push is
   * untouched; only a stalled connection trips it). A named default here, not a literal buried in
   * the implementation, so a caller with a heavier push overrides it via {@link #push(String,
   * Duration)}.
   */
  Duration DEFAULT_PUSH_TIMEOUT = Duration.ofSeconds(30);

  /** Convenience {@link #push(String, Duration)} with {@link #DEFAULT_PUSH_TIMEOUT}. */
  default void push(String token) {
    push(token, DEFAULT_PUSH_TIMEOUT);
  }

  /** Remove this linked worktree ({@code git worktree remove --force}). Never throws on absence. */
  @Override
  void close();
}
