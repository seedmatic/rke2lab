package io.seedmatic.rke2lab.manifests.bdd;

import io.seedmatic.rke2lab.manifests.contract.NodeBootstrapArtifact;
import io.seedmatic.rke2lab.worktree.TransientWorktree;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A head the render commits onto, with the in-memory plot it materialises that head's tree into.
 * The tree the commit carries is the plot's files minus what a worktree keeps local; the head's
 * {@code reflections/} subtree is carried over as committed (the reflector owns it).
 */
record RenderSoil(TransientWorktree head, Path root) {

  /** What a worktree of this project keeps for itself, out of every commit it makes. */
  static final List<String> KEPT_LOCAL = List.of(NodeBootstrapArtifact.LOCAL_DIR, ".scratchpad.d");

  Optional<String> readAtHead(String path) {
    return head.readAtHead(path);
  }

  /** The plot's files as the commit's tree: worktree-relative path → bytes, local dirs left out. */
  Map<String, byte[]> files() {
    final Map<String, byte[]> files = new LinkedHashMap<>();
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
        final String path = root.relativize(file).toString().replace('\\', '/');
        if (KEPT_LOCAL.stream().noneMatch(dir -> path.equals(dir) || path.startsWith(dir + "/"))) {
          files.put(path, Files.readAllBytes(file));
        }
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read the rendered tree at " + root, ex);
    }
    return files;
  }
}
