package io.seedmatic.rke2lab.fabric.bdd;

import io.seedmatic.rke2lab.worktree.CommitSpec;
import io.seedmatic.rke2lab.worktree.GitIdentity;
import io.seedmatic.rke2lab.worktree.TransientWorktree;
import io.seedmatic.rke2lab.worktree.TreeEdit;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Records what the delivery asked of the head, and keeps the committed files so they can be read.
 */
final class RecordingTransientWorktree implements TransientWorktree {
  final String branch;
  final List<String> calls = new ArrayList<>();
  final Map<String, byte[]> committed = new LinkedHashMap<>();
  Optional<GitIdentity> committedAs = Optional.empty();
  Optional<String> signedWith = Optional.empty();
  Optional<String> pushedWith = Optional.empty();
  private String head = "0000000000000000000000000000000000000000";

  RecordingTransientWorktree(String branch) {
    this.branch = branch;
  }

  @Override
  public String branch() {
    return branch;
  }

  @Override
  public String head() {
    return head;
  }

  @Override
  public Optional<String> readAtHead(String path) {
    return Optional.ofNullable(committed.get(path))
        .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
  }

  @Override
  public String commit(TreeEdit edit, CommitSpec spec) {
    calls.add("commit:" + spec.message());
    if (edit instanceof TreeEdit.Overlay overlay) {
      committed.putAll(overlay.files());
      overlay.removed().forEach(committed::remove);
    }
    this.committedAs = Optional.of(spec.identity());
    this.signedWith = spec.sshSigningKey();
    this.head = "1111111111111111111111111111111111111111";
    return head;
  }

  @Override
  public void publish(Optional<String> pushToken, Duration timeout) {
    calls.add(pushToken.isPresent() ? "push" : "publish-local");
    this.pushedWith = pushToken;
  }
}
