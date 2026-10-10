package io.seedmatic.rke2lab.fabric.bdd;

import io.seedmatic.rke2lab.worktree.LinkedWorktree;
import io.seedmatic.rke2lab.worktree.LinkedWorktrees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Makes one recording worktree and keeps it, so a test can read what the delivery did. */
final class RecordingWorktrees implements LinkedWorktrees {
  RecordingWorktree made;
  Optional<String> fetchedWith = Optional.empty();

  @Override
  public LinkedWorktree prepare(Path worktreePath, String branch, Optional<String> fetchToken) {
    try {
      Files.createDirectories(worktreePath);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    this.fetchedWith = fetchToken;
    this.made = new RecordingWorktree(worktreePath, branch);
    return made;
  }
}
