package io.seedmatic.rke2lab.worktree;

import java.util.Optional;

/**
 * A git worktree: a head and the tree a commit edits. Two realizations — the durable worktree,
 * {@link DurableWorktree}, on disk and durable; a head the seed commits onto, {@link
 * TransientWorktree}, in memory and transient. Both read the committed state the same way and take
 * the same edit model ({@link TreeEdit}, {@link CommitSpec}); what differs is where the tree lives.
 *
 * <p>jgit-free — only JDK types and the {@code worktree} records cross it.
 */
public interface Worktree {

  /**
   * The content of {@code path} as committed at the head ({@code path} worktree-relative), or empty
   * when there is no head or the path is absent from its tree. Reads the COMMITTED blob, never a
   * working file — so a caller reads the branch's recorded state even when it is about to be
   * overwritten (the render reads its recorded facet before re-materialising the tree).
   */
  Optional<String> readAtHead(String path);

  /**
   * Commit {@code edit} on the head as {@code spec} says. When the result matches the head nothing
   * is committed — no empty commit — and the head's sha is returned. Returns the commit sha.
   */
  String commit(TreeEdit edit, CommitSpec spec);
}
