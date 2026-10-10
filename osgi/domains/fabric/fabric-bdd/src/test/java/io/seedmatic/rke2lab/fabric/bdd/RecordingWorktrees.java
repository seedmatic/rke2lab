package io.seedmatic.rke2lab.fabric.bdd;

import io.seedmatic.rke2lab.worktree.LinkedWorktree;
import io.seedmatic.rke2lab.worktree.LinkedWorktrees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Makes one recording worktree and keeps it, so a test can read what the delivery did. */
final class RecordingWorktrees implements LinkedWorktrees {
  RecordingWorktree made;

  @Override
  public LinkedWorktree prepare(Path worktreePath, String branch) {
    try {
      Files.createDirectories(worktreePath);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    this.made = new RecordingWorktree(worktreePath, branch);
    return made;
  }
}
