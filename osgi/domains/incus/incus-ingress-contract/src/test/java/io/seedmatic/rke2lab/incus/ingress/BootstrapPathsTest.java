package io.seedmatic.rke2lab.incus.ingress;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BootstrapPathsTest {

  private final BootstrapPaths bootstrapPaths =
      BootstrapPaths.fromLocalWorktree(Path.of("/tmp/worktree"));

  // --- automountPath: rebase an absolute path under the automount prefix (host-agnostic) ---

  @Test
  void automountPath_rebases_the_bioskop_private_layout_under_the_prefix() {
    final Path bioskopRoot = Path.of("/private/var/lib/git/nxmatic/rke2lab");
    assertEquals(
        Path.of("/net/bioskop.local/private/var/lib/git/nxmatic/rke2lab"),
        bootstrapPaths.automountPath(bioskopRoot, true, "/net/bioskop.local"));
  }

  @Test
  void automountPath_rebases_the_nikopol_volumes_layout_under_the_prefix() {
    final Path nikopolRoot = Path.of("/Volumes/git-worktree-store/nxmatic/rke2lab");
    assertEquals(
        Path.of("/net/nikopol.local/Volumes/git-worktree-store/nxmatic/rke2lab"),
        bootstrapPaths.automountPath(nikopolRoot, true, "/net/nikopol.local"));
  }

  @Test
  void automountPath_preserves_the_path_when_automount_is_disabled() {
    final Path path = Path.of("/private/var/lib/git/nxmatic/rke2lab");
    assertEquals(
        path.toAbsolutePath().normalize(),
        bootstrapPaths.automountPath(path, false, "/net/bioskop.local"));
  }

  @Test
  void automountPath_is_idempotent_for_paths_already_under_net() {
    final Path netPath = Path.of("/net/nikopol.local/Volumes/git-worktree-store/nxmatic/rke2lab");
    assertEquals(netPath, bootstrapPaths.automountPath(netPath, true, "/net/nikopol.local"));
  }

  // --- asAutomountView: every root rebased under the prefix (or left as-is when disabled) ---

  @Test
  void asAutomountView_rebases_every_root_under_the_prefix() {
    final BootstrapPaths view = bootstrapPaths.asAutomountView(true, "/net/nikopol.local");
    assertEquals(Path.of("/net/nikopol.local/tmp/worktree"), view.worktreeRoot());
    assertEquals(Path.of("/net/nikopol.local/tmp/worktree/.local.d"), view.stateRoot());
  }

  @Test
  void asAutomountView_leaves_roots_untouched_when_automount_is_disabled() {
    final BootstrapPaths view = bootstrapPaths.asAutomountView(false, "/net/nikopol.local");
    assertEquals(bootstrapPaths.worktreeRoot(), view.worktreeRoot());
  }
}
