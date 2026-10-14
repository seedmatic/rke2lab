package io.seedmatic.rke2lab.manifests.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.marschall.memoryfilesystem.MemoryFileSystemBuilder;
import io.seedmatic.rke2lab.manifests.contract.NodeBootstrapArtifact;
import io.seedmatic.rke2lab.worktree.GitIdentity;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The tree a render commits: the plot's files on the in-memory soil, local dirs left out. */
class RenderSoilTest {

  @Test
  void the_commit_carries_the_rendered_files_and_never_the_local_dirs() throws Exception {
    try (FileSystem soil = MemoryFileSystemBuilder.newEmpty().build()) {
      final Path root = soil.getPath("manifests/test-mgmt");
      write(root, "manifest.yaml", "m");
      write(root, "platform/flux/kustomization.yaml", "k");
      write(root, ".gitignore", ".local.d/\n");
      write(root, NodeBootstrapArtifact.LOCAL_DIR + "/manifests.yaml", "intermediate");
      write(root, ".scratchpad.d/note.md", "scratch");
      write(root, NodeBootstrapArtifact.LOCAL_DIR + ".yaml", "a file, not the local dir");

      final Map<String, byte[]> files = new RenderSoil(null, root).files();

      assertEquals(
          List.of(
              ".gitignore",
              NodeBootstrapArtifact.LOCAL_DIR + ".yaml",
              "manifest.yaml",
              "platform/flux/kustomization.yaml"),
          files.keySet().stream().sorted().toList());
      assertEquals(
          "k", new String(files.get("platform/flux/kustomization.yaml"), StandardCharsets.UTF_8));
    }
  }

  @Test
  void a_kept_local_dir_below_the_root_is_refused_by_name() throws Exception {
    try (FileSystem soil = MemoryFileSystemBuilder.newEmpty().build()) {
      final Path root = soil.getPath("/plot");
      write(root, "manifest.yaml", "m");
      write(root, "sub/" + NodeBootstrapArtifact.LOCAL_DIR + "/clear.yaml", "token: dummy-token");

      final IllegalStateException refused =
          assertThrows(IllegalStateException.class, () -> new RenderSoil(null, root).files());
      assertTrue(
          refused.getMessage().contains("sub/" + NodeBootstrapArtifact.LOCAL_DIR),
          refused.getMessage());
      assertFalse(refused.getMessage().contains("dummy-token"), "the refusal carries no content");
    }
  }

  @Test
  void a_git_dir_the_seal_skips_is_refused_too() throws Exception {
    try (FileSystem soil = MemoryFileSystemBuilder.newEmpty().build()) {
      final Path root = soil.getPath("/plot");
      write(root, "manifest.yaml", "m");
      write(root, "sub/.git/x", "token: dummy-token");

      final IllegalStateException refused =
          assertThrows(IllegalStateException.class, () -> new RenderSoil(null, root).files());
      assertTrue(refused.getMessage().contains("sub/.git/x"), refused.getMessage());
    }
  }

  @Test
  void a_delivery_plan_prints_neither_its_signing_key_nor_its_token() {
    final String printed =
        new RenderDelivery(
                "m",
                new GitIdentity("n", "e@example.invalid"),
                "PRIVATE-KEY-BYTES",
                true,
                Optional.of("TOKEN-BYTES"),
                null)
            .toString();
    assertFalse(printed.contains("PRIVATE-KEY-BYTES"), printed);
    assertFalse(printed.contains("TOKEN-BYTES"), printed);
  }

  private static void write(Path root, String path, String content) throws Exception {
    final Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }
}
