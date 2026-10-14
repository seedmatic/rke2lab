package io.seedmatic.rke2lab.fabric.bdd;

import io.seedmatic.rke2lab.worktree.TransientWorktree;
import io.seedmatic.rke2lab.worktree.TransientWorktrees;
import java.util.Optional;

/** Opens one recording head and keeps it, so a test can read what the delivery did. */
final class RecordingTransientWorktrees implements TransientWorktrees {
  RecordingTransientWorktree made;
  Optional<String> fetchedWith = Optional.empty();

  @Override
  public TransientWorktree open(String branch, Optional<String> fetchToken) {
    this.fetchedWith = fetchToken;
    this.made = new RecordingTransientWorktree(branch);
    return made;
  }
}
