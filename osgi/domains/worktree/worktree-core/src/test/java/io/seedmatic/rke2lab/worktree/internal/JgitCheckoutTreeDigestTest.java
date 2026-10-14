package io.seedmatic.rke2lab.worktree.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The seat's HEAD read for the node-base image: its repository and its build-input digest. */
class JgitCheckoutTreeDigestTest {

  private static final List<String> BUILD_INPUTS = List.of("flake.lock", "flake.nix", "nixos");
  private static final PersonIdent BOT = new PersonIdent("test", "test@example.invalid");

  @TempDir Path dir;

  @Test
  void the_digest_follows_the_committed_build_inputs_only() throws Exception {
    try (Git git = init()) {
      write("flake.nix", "{ }");
      write("flake.lock", "{}");
      write("nixos/a.nix", "a");
      write("docs/x.md", "x");
      commit(git, "base");
      final JgitCheckout seat = new JgitCheckout(dir);
      final String base = seat.treeDigest("HEAD", BUILD_INPUTS);

      assertEquals(base, seat.treeDigest("HEAD", BUILD_INPUTS), "the same tree digests the same");

      write("docs/x.md", "y");
      commit(git, "a doc moves");
      assertEquals(
          base, seat.treeDigest("HEAD", BUILD_INPUTS), "a path outside the inputs is blind");

      write("nixos/a.nix", "edited, not committed");
      assertEquals(base, seat.treeDigest("HEAD", BUILD_INPUTS), "the working tree is blind");

      commit(git, "a module moves");
      assertNotEquals(base, seat.treeDigest("HEAD", BUILD_INPUTS), "a committed input moves it");
    }
  }

  @Test
  void the_repository_is_the_common_dir() throws Exception {
    try (Git git = init()) {
      write("flake.nix", "{ }");
      commit(git, "base");
      assertEquals(
          dir.resolve(".git").toRealPath(), new JgitCheckout(dir).repository().toRealPath());
    }
  }

  @Test
  void no_head_refuses_to_digest() throws Exception {
    try (Git git = init()) {
      final JgitCheckout seat = new JgitCheckout(dir);
      assertThrows(IllegalStateException.class, () -> seat.treeDigest("HEAD", BUILD_INPUTS));
    }
  }

  private Git init() throws GitAPIException {
    return Git.init().setDirectory(dir.toFile()).setInitialBranch("develop").call();
  }

  private void write(String path, String content) throws IOException {
    final Path file = dir.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  private void commit(Git git, String message) throws GitAPIException {
    git.add().addFilepattern(".").call();
    git.commit()
        .setMessage(message)
        .setAuthor(BOT)
        .setCommitter(BOT)
        .setSign(false)
        .setNoVerify(true)
        .call();
  }
}
