package io.seedmatic.rke2lab.manifests.contract;

import java.nio.file.Path;
import java.util.List;

/**
 * What a worktree of this project keeps for itself — never sealed, never committed: the ONE rule
 * the render's {@code .gitignore}, its Secret seal and the tree it commits all read, so they cannot
 * disagree on where clear material may sit.
 */
public record KeptLocalDirs(List<String> names) {

  /** A worktree's: the render's intermediates ({@code .local.d}) and the scratchpad. */
  public static final KeptLocalDirs OF_A_WORKTREE =
      new KeptLocalDirs(List.of(NodeBootstrapArtifact.LOCAL_DIR, ".scratchpad.d"));

  public KeptLocalDirs {
    names = List.copyOf(names);
  }

  /** Whether {@code relative} lies in a kept-local directory, at ANY depth. */
  public boolean holds(Path relative) {
    for (Path segment : relative) {
      if (names.contains(segment.toString())) {
        return true;
      }
    }
    return false;
  }

  /** Whether {@code relative} lies in a kept-local directory at the tree's root. */
  public boolean atRoot(Path relative) {
    return relative.getNameCount() > 0 && names.contains(relative.getName(0).toString());
  }

  /** The {@code .gitignore} lines that keep these directories out of a commit. */
  public String gitignore() {
    return String.join("", names.stream().map(name -> name + "/\n").toList());
  }
}
