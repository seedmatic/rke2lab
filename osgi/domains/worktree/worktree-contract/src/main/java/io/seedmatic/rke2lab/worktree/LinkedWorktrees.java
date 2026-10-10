package io.seedmatic.rke2lab.worktree;

import java.nio.file.Path;

/**
 * Fabricates a LINKED git worktree of a branch — the delivery channel for a rendered tree. Where
 * {@link Worktree} is "the worktree the seed IS" (it self-locates and reports its own facts),
 * {@code LinkedWorktrees} is "a worktree the seed MAKES": a transient checkout of a target branch
 * at a caller-chosen path, into which a producer materialises a rendered tree, then commits and
 * force-pushes it. A rendered branch is ephemeral desired-state, not history — a force-push is
 * expected and correct (see docs/architecture/cluster-api/manifests-rendered-branches.adoc).
 *
 * <p>Domain-neutral, exactly like {@link Worktree} and {@link GatePolicy}: it names no {@code
 * manifests/<host>-<role>} convention and no {@code .local.d} path. The caller supplies the branch
 * ref and the worktree path; the worktree domain owns only the git MECHANISM (add / commit / push /
 * remove), so no consumer vocabulary leaks in. jgit and {@code git worktree} stay sealed behind the
 * implementation — only JDK types cross this interface.
 */
public interface LinkedWorktrees {

  /**
   * Prepare a linked worktree checked out at {@code worktreePath} on {@code branch}, on a STABLE
   * null-commit base. The first time {@code branch} is seen it is seeded with an empty root commit
   * — a shared base a GitOps reader (Flux) can point at even before a full render, and that every
   * render commits ON TOP of (accretion, one commit per render, fast-forward pushes — not
   * orphan-per-render force-pushes). On a re-run the branch is reused: its tip is checked out (the
   * accretion parent) and the working tree emptied, so the caller materialises the rendered YAML
   * alone (never the source) and a manifest dropped since the previous render is staged as a
   * deletion. Idempotent: an existing worktree at {@code worktreePath} is removed first. Returns a
   * handle the caller materialises into, {@link LinkedWorktree#stageAll stages}, {@link
   * LinkedWorktree#commit commits}, {@link LinkedWorktree#push pushes}, and {@link
   * LinkedWorktree#close removes}. {@code git worktree} is shelled (jgit exposes no worktree
   * porcelain), sealed behind the implementation.
   */
  LinkedWorktree prepare(Path worktreePath, String branch);
}
