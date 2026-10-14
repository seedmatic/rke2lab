package io.seedmatic.rke2lab.worktree;

import java.util.List;
import java.util.Map;

/**
 * What a commit changes in a worktree's tree, held in memory: the ONE edit model every writer of a
 * worktree uses, so a writer hands bytes and never touches files itself. Paths are
 * worktree-relative, {@code /}-separated.
 */
public sealed interface TreeEdit {

  /** Whether the edit changes nothing. */
  boolean isEmpty();

  /**
   * Lay {@code files} over the head's tree and drop {@code removed}; every other path keeps its
   * head content — a version bump, a delivery of a few files.
   */
  record Overlay(Map<String, byte[]> files, List<String> removed) implements TreeEdit {

    public Overlay {
      files = Map.copyOf(files);
      removed = List.copyOf(removed);
    }

    @Override
    public boolean isEmpty() {
      return files.isEmpty() && removed.isEmpty();
    }
  }

  /**
   * Replace the head's whole tree with {@code files}, keeping the head's {@code carried} subtrees
   * as they are — a render, which owns its branch's tree except what it carries over.
   */
  record Replace(Map<String, byte[]> files, List<String> carried) implements TreeEdit {

    public Replace {
      files = Map.copyOf(files);
      carried = List.copyOf(carried);
    }

    @Override
    public boolean isEmpty() {
      return false;
    }
  }
}
