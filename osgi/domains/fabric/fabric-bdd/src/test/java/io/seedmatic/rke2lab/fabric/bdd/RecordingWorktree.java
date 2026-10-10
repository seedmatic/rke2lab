package io.seedmatic.rke2lab.fabric.bdd;

import io.seedmatic.rke2lab.worktree.GitIdentity;
import io.seedmatic.rke2lab.worktree.LinkedWorktree;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Records what the delivery asked of the worktree, and keeps the files so they can be read. */
final class RecordingWorktree implements LinkedWorktree {
  final Path path;
  final String branch;
  final List<String> calls = new ArrayList<>();
  Optional<GitIdentity> committedAs = Optional.empty();
  Optional<String> signedWith = Optional.empty();
  Optional<String> pushedWith = Optional.empty();

  RecordingWorktree(Path path, String branch) {
    this.path = path;
    this.branch = branch;
  }

  @Override
  public Path path() {
    return path;
  }

  @Override
  public String branch() {
    return branch;
  }

  @Override
  public Optional<String> readAtHead(String file) {
    // The double commits nothing, so "at HEAD" is what the delivery staged — enough to prove the
    // committed tree carries both files; that a commit records its tree is jgit's own contract.
    final Path staged = path.resolve(file);
    try {
      return Files.exists(staged) ? Optional.of(Files.readString(staged)) : Optional.empty();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  @Override
  public Optional<String> smudgeFromHead(String file) {
    return readAtHead(file);
  }

  @Override
  public void restoreFromHead(String pathspec) {
    calls.add("restoreFromHead");
  }

  @Override
  public void stage(List<Path> paths) {
    calls.add("stage");
  }

  @Override
  public void stageAll() {
    calls.add("stageAll");
  }

  @Override
  public String commit(String message, GitIdentity identity, Optional<String> sshSigningKey) {
    calls.add("commit:" + message);
    this.committedAs = Optional.of(identity);
    this.signedWith = sshSigningKey;
    return "0000000000000000000000000000000000000000";
  }

  @Override
  public void push(String token, Duration timeout) {
    calls.add("push");
    this.pushedWith = Optional.of(token);
  }

  @Override
  public void close() {
    calls.add("close");
  }
}
