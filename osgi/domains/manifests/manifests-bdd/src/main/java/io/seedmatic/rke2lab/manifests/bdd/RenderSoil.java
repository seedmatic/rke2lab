package io.seedmatic.rke2lab.manifests.bdd;

import io.seedmatic.rke2lab.manifests.contract.KeptLocalDirs;
import io.seedmatic.rke2lab.worktree.TransientWorktree;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A head the render commits onto, with the in-memory plot it materialises that head's tree into.
 * The tree the commit carries is the plot's files minus what a worktree keeps local; the head's
 * {@code reflections/} subtree is carried over as committed (the reflector owns it).
 */
record RenderSoil(TransientWorktree head, Path root) {

  Optional<String> readAtHead(String path) {
    return head.readAtHead(path);
  }

  /**
   * The plot's files as the commit's tree: worktree-relative path → bytes. A kept-local directory
   * at the plot root (the render's intermediates, the scratchpad) is left out — the seal skips it
   * too, so what sits there stays in the clear and must never be committed. One BELOW the root is
   * refused, not skipped: nothing renders there, and committing it would push those clear files. A
   * {@code .git} segment, which the seal skips too, is refused the same way: what the seal does not
   * see never reaches a commit.
   */
  Map<String, byte[]> files() {
    final Map<String, byte[]> files = new LinkedHashMap<>();
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
        final Path relative = root.relativize(file);
        if (KeptLocalDirs.OF_A_WORKTREE.atRoot(relative)) {
          continue;
        }
        if (KeptLocalDirs.OF_A_WORKTREE.holds(relative) || hasGitSegment(relative)) {
          throw new IllegalStateException(
              "refusing to commit "
                  + relative
                  + ": a kept-local or .git directory below the plot root holds what the seal left"
                  + " in the clear");
        }
        files.put(relative.toString().replace('\\', '/'), Files.readAllBytes(file));
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read the rendered tree at " + root, ex);
    }
    return files;
  }

  private static boolean hasGitSegment(Path relative) {
    for (Path segment : relative) {
      if (".git".equals(segment.toString())) {
        return true;
      }
    }
    return false;
  }
}
