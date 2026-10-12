package io.seedmatic.rke2lab.worktree;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The worktree the seed cultivates, as ONE OSGi service — it KNOWS its own git facts. The seed
 * process runs inside its worktree, so the component locates its root once (walking up to the
 * {@code .git}) and answers the facts that root carries: its {@link #provenance()} (HEAD sha +
 * dirty) and its {@link #workingState()} (clean, and which paths are uncommitted). The one place
 * the "is the worktree clean?" and "what did we provision from?" knowledge lives.
 *
 * <p>Governance: the interface is jgit-FREE — only JDK types and the worktree records cross it.
 * jgit lives behind the implementation ({@code worktree-core}'s {@code JgitWorktree}) and is never
 * exposed, so a consumer couples to the worktree's facts, not to a git library. OSGi consumers
 * {@code @Reference} it directly (the incus scion); the flat host reads the same facts through the
 * cellar — the worktree soil harvests a {@link WorktreeFacts} at the {@link WorktreeCoordinate},
 * which the host fetches back.
 */
public interface Worktree {

  /** The worktree root — absolute, normalised. The anchor every provisioning path derives from. */
  Path root();

  /** The HEAD provenance of the worktree: the commit sha it sits on, and whether it is dirty. */
  Provenance provenance();

  /** The working state: whether the tree is clean, and the uncommitted paths when it is not. */
  WorkingState workingState();

  /**
   * The content of {@code path} as committed at {@code HEAD} ({@code path} worktree-relative), or
   * empty when there is no HEAD or the path is absent from the HEAD tree. Reads the COMMITTED blob,
   * NOT the working-tree file — so a caller reads the branch's recorded state even when the working
   * tree is dirty or about to be overwritten (the render reads its recorded facet before
   * re-materialising the tree). jgit stays sealed behind the implementation; only JDK types cross.
   */
  Optional<String> readAtHead(String path);

  /**
   * Whether the latest commit's flake locks are coherent: {@code false} when a {@code flake.nix}
   * {@code inputs} block changed in {@code HEAD} without a matching {@code flake.lock} change — the
   * incoherence a clean worktree does NOT catch. jgit stays sealed behind the implementation.
   */
  boolean flakeLockCoherent();

  /**
   * The repository that holds this worktree's objects and refs — the COMMON dir, which for a linked
   * worktree is the repository it was added from (the bare), not its private {@code .git} file. A
   * commit made here is readable through it at once, with no push: {@code
   * git+file://<repository>?rev=<sha>} builds that revision.
   */
  Path repository();

  /**
   * The revision a build of {@code paths} (each a file or a directory, worktree-relative) reads:
   * HEAD when those paths are clean, otherwise a commit FABRICATED from HEAD's tree with only those
   * paths taken from the working tree, pinned under {@code refs/rke2lab/grow/<sha>} and never
   * pushed. Fails closed, naming paths and never content, on a captured file that is gitignored
   * (skipped, never captured), carries a sops filter/diff attribute or a sops envelope, holds
   * private key material or a bearer token, or is a symlink leaving the repository. Sweeps the
   * fabricated refs older than seven days.
   */
  BuildRevision buildRevision(List<String> paths);

  /** Drop the ref pinning a fabricated revision once its build is done; a no-op for HEAD. */
  void releaseBuildRevision(BuildRevision revision);

  /**
   * SHA-256 over the {@code (path, blob id)} pairs of {@code revision}'s tree under {@code paths},
   * in tree order — a CONTENT digest of what a build of that revision reads, blind to the working
   * tree and to commit metadata. A path absent from the tree contributes nothing.
   */
  String treeDigest(String revision, List<String> paths);

  /**
   * Stage the given worktree paths for the next commit — additions/modifications for paths that
   * exist, removals for paths that no longer do. Each path may be absolute or worktree-relative and
   * is resolved against {@link #root()}. The first WRITE verb of this domain (survey stayed
   * read-only until a consumer needed to cultivate the tree); jgit stays sealed behind the
   * implementation, so only JDK types cross.
   */
  void stage(List<Path> paths);

  /**
   * Commit the staged changes with {@code message}, authored AND committed as {@code identity} — an
   * automated commit carries an explicit per-tool bot identity (minted by the caller from the PKI
   * keystore's tailnet domain), never the ambient {@code user.name} of whoever ran the tool. When
   * the staged tree matches HEAD nothing is committed — no empty commit — and the unchanged tip sha
   * is returned. Returns the delivered commit sha (new, or the unchanged tip). Local only — no push
   * (that is where a remote credential, and thus the auth edge, would enter; deliberately out of
   * scope here). jgit stays sealed. The worktree stays domain-neutral: the tool identity is the
   * CALLER's value.
   *
   * <p>{@code sshSigningKey} is the caller's OpenSSH PRIVATE key (e.g. the ndh {@code
   * github-signing} key) the commit is SSH-signed with (git SSHSIG, {@code git} namespace) — so the
   * bot signs with its own imported key, the same way the operator's {@code gpg.format=ssh} config
   * would. {@link Optional#empty()} commits unsigned. Only a JDK type crosses; the signing
   * mechanism (jgit + {@code ssh-keygen}) stays sealed in the implementation.
   */
  String commit(String message, GitIdentity identity, Optional<String> sshSigningKey);
}
