package io.seedmatic.rke2lab.worktree;

import java.util.List;

/**
 * The revision a build of the worktree reads: its HEAD when the build inputs are clean, or a
 * FABRICATED commit — HEAD's tree with the dirty build inputs taken from the working tree — pinned
 * under {@code refs/rke2lab/grow/<sha>} so nix can build it by {@code ?rev=}. {@code changed} lists
 * the paths whose working-tree state differs from HEAD (edited, added or removed), {@code
 * untracked} those among them HEAD did not carry; both empty when {@code fabricated} is false.
 */
public record BuildRevision(
    String sha, boolean fabricated, List<String> changed, List<String> untracked) {

  public BuildRevision {
    changed = List.copyOf(changed);
    untracked = List.copyOf(untracked);
  }
}
