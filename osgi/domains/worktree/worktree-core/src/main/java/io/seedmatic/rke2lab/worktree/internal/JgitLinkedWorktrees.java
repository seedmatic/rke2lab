package io.seedmatic.rke2lab.worktree.internal;

import io.seedmatic.rke2lab.worktree.LinkedWorktree;
import io.seedmatic.rke2lab.worktree.LinkedWorktrees;
import io.seedmatic.rke2lab.worktree.Worktree;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The jgit/{@code git worktree}-backed {@link LinkedWorktrees} — the factory that makes a linked
 * worktree of a branch. It takes the seed {@link Worktree} by {@code @Reference} to learn the repo
 * ROOT (instance-passing: the root flows in, it is not re-located here), builds a {@link GitCli}
 * bound to that root, adds the linked worktree, and wires the returned {@link JgitLinkedWorktree}
 * from that same {@code GitCli} plus a {@link JgitCheckout} of the new path. Stateless and
 * domain-neutral: the branch name and the worktree path are the caller's, so no consumer vocabulary
 * enters. {@code git worktree} and jgit stay sealed in the collaborators it composes.
 */
@Component(service = LinkedWorktrees.class)
public final class JgitLinkedWorktrees implements LinkedWorktrees {

  private final Worktree worktree;

  @Activate
  public JgitLinkedWorktrees(@Reference Worktree worktree) {
    this.worktree = worktree;
  }

  @Override
  public LinkedWorktree prepare(Path worktreePath, String branch) {
    final Path path = worktreePath.toAbsolutePath().normalize();
    final GitCli gitCli = new GitCli(worktree.root());
    gitCli.worktreeAdd(path, branch);
    // Canonicalise now that the worktree exists (git records and reports the real, symlink-resolved
    // path — e.g. /private/var over macOS's /var link), so path() agrees with git and with the seed
    // Worktree's own toRealPath()-canonicalised root.
    return new JgitLinkedWorktree(gitCli, new JgitCheckout(realPath(path)), branch);
  }

  private static Path realPath(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot canonicalise the linked worktree at " + path, ex);
    }
  }
}
