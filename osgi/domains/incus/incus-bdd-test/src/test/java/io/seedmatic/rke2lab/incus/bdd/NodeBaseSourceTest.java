package io.seedmatic.rke2lab.incus.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.worktree.BuildRevision;
import io.seedmatic.rke2lab.worktree.CommitSpec;
import io.seedmatic.rke2lab.worktree.Provenance;
import io.seedmatic.rke2lab.worktree.TreeEdit;
import io.seedmatic.rke2lab.worktree.WorkingState;
import io.seedmatic.rke2lab.worktree.Worktree;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The node-base image builds a REVISION of the seat, and nothing else. */
class NodeBaseSourceTest {

  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

  @Test
  void a_clean_seat_builds_its_head_by_revision() {
    final NodeBaseSource source =
        NodeBaseSource.of(
            seat("/bare/rke2lab.git", new BuildRevision(SHA, false, List.of(), List.of())));
    assertEquals("git+file:///bare/rke2lab.git?rev=" + SHA, source.flakeRef());
    assertEquals("digest:" + SHA + ":" + NodeBaseSource.BUILD_INPUTS, source.digest());
    assertEquals("0123456789ab", source.provenance());
  }

  @Test
  void a_dirty_seat_builds_its_fabricated_revision_and_says_so() {
    final NodeBaseSource source =
        NodeBaseSource.of(
            seat(
                "/bare/rke2lab.git",
                new BuildRevision(
                    SHA, true, List.of("nixos/a.nix", "nixos/new.nix"), List.of("nixos/new.nix"))));
    assertEquals(
        "0123456789ab (dirty: nixos/a.nix, nixos/new.nix — untracked: nixos/new.nix)",
        source.provenance());
  }

  @Test
  void a_flake_ref_that_is_not_a_revision_is_refused() {
    for (Worktree seat :
        List.of(
            seat("/bare/rke2lab.git", new BuildRevision("abc", false, List.of(), List.of())),
            seat(
                "/bare/rke2lab.git?ref=develop&x=",
                new BuildRevision(SHA, false, List.of(), List.of())))) {
      final IllegalStateException refused =
          assertThrows(IllegalStateException.class, () -> NodeBaseSource.of(seat));
      assertTrue(refused.getMessage().contains("nix builds only a revision"), refused.getMessage());
    }
  }

  private static Worktree seat(String repository, BuildRevision revision) {
    return new Worktree() {
      @Override
      public Path root() {
        return Path.of("/seat");
      }

      @Override
      public Provenance provenance() {
        return new Provenance(revision.sha(), revision.fabricated());
      }

      @Override
      public WorkingState workingState() {
        throw new UnsupportedOperationException();
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
        return Path.of(repository);
      }

      @Override
      public BuildRevision buildRevision(List<String> paths) {
        return revision;
      }

      @Override
      public void releaseBuildRevision(BuildRevision built) {
        throw new UnsupportedOperationException();
      }

      @Override
      public String treeDigest(String rev, List<String> paths) {
        return "digest:" + rev + ":" + paths;
      }

      @Override
      public String commit(TreeEdit edit, CommitSpec spec) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
