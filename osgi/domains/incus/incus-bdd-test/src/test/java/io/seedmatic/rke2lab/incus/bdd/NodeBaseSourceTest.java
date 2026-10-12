package io.seedmatic.rke2lab.incus.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.worktree.GitIdentity;
import io.seedmatic.rke2lab.worktree.Provenance;
import io.seedmatic.rke2lab.worktree.WorkingState;
import io.seedmatic.rke2lab.worktree.Worktree;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The node-base image builds the seat's committed HEAD, and refuses uncommitted build inputs. */
class NodeBaseSourceTest {

  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

  @Test
  void a_clean_seat_builds_its_head_by_revision() {
    final NodeBaseSource source = NodeBaseSource.of(seat(SHA, List.of()));
    assertEquals("git+file:///bare/rke2lab.git?rev=" + SHA, source.flakeRef());
    assertEquals("digest:" + NodeBaseSource.BUILD_INPUTS, source.digest());
  }

  @Test
  void an_uncommitted_build_input_is_refused_by_name() {
    for (String dirty : List.of("flake.lock", "flake.nix", "nixos/rke2.nix")) {
      final IllegalStateException refused =
          assertThrows(
              IllegalStateException.class,
              () -> NodeBaseSource.of(seat(SHA, List.of("docs/x.adoc", dirty))));
      assertTrue(refused.getMessage().contains(dirty), refused.getMessage());
    }
  }

  @Test
  void an_uncommitted_path_outside_the_inputs_does_not_block() {
    NodeBaseSource.of(seat(SHA, List.of("docs/x.adoc", "nixos-notes/y.md", "flake.nix.orig")));
  }

  @Test
  void a_seat_without_head_is_refused() {
    assertThrows(IllegalStateException.class, () -> NodeBaseSource.of(seat("", List.of())));
  }

  private static Worktree seat(String sha, List<String> uncommitted) {
    return new Worktree() {
      @Override
      public Path root() {
        return Path.of("/seat");
      }

      @Override
      public Provenance provenance() {
        return new Provenance(sha, !uncommitted.isEmpty());
      }

      @Override
      public WorkingState workingState() {
        return new WorkingState(uncommitted.isEmpty(), uncommitted);
      }

      @Override
      public Optional<String> readAtHead(String path) {
        throw new UnsupportedOperationException();
      }

      @Override
      public boolean flakeLockCoherent() {
        throw new UnsupportedOperationException();
      }

      @Override
      public Path repository() {
        return Path.of("/bare/rke2lab.git");
      }

      @Override
      public String treeDigestAtHead(List<String> paths) {
        return "digest:" + paths;
      }

      @Override
      public void stage(List<Path> paths) {
        throw new UnsupportedOperationException();
      }

      @Override
      public String commit(String message, GitIdentity identity, Optional<String> sshSigningKey) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
