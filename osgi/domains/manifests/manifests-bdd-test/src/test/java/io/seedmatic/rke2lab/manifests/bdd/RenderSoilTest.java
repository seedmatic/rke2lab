package io.seedmatic.rke2lab.manifests.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.marschall.memoryfilesystem.MemoryFileSystemBuilder;
import io.seedmatic.rke2lab.manifests.contract.NodeBootstrapArtifact;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The tree a render commits: the plot's files on the in-memory soil, local dirs left out. */
class RenderSoilTest {

  @Test
  void the_commit_carries_the_rendered_files_and_never_the_local_dirs() throws Exception {
    try (FileSystem soil = MemoryFileSystemBuilder.newEmpty().build()) {
      final Path root = soil.getPath("/.local.d/worktrees/manifests/test-mgmt");
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

  private static void write(Path root, String path, String content) throws Exception {
    final Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }
}
