package io.seedmatic.rke2lab.worktree.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.seedmatic.rke2lab.worktree.CommitSpec;
import io.seedmatic.rke2lab.worktree.GitIdentity;
import io.seedmatic.rke2lab.worktree.TreeEdit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The seat's one write verb: an Overlay applied on disk, staged and committed as its spec says. */
class JgitCheckoutCommitEditTest {

  private static final GitIdentity BUMPER =
      new GitIdentity("rke2lab:manifests-bumper", "rke2lab+manifests-bumper@example.invalid");
  private static final CommitSpec SPEC = new CommitSpec("bump", BUMPER, Optional.empty());

  @TempDir Path dir;
  @TempDir Path noHooks;
  private Git git;
  private JgitCheckout seat;
  private String head;

  @BeforeEach
  void a_committed_seat() throws Exception {
    git = Git.init().setDirectory(dir.toFile()).setInitialBranch("develop").call();
    final StoredConfig config = git.getRepository().getConfig();
    config.setString("core", null, "hooksPath", noHooks.toString());
    config.save();
    write("Component.java", "pin 1.0");
    write("assets/release-1.0.yaml", "old");
    git.add().addFilepattern(".").call();
    final PersonIdent bot = new PersonIdent("test", "test@example.invalid");
    head =
        git.commit()
            .setMessage("base")
            .setAuthor(bot)
            .setCommitter(bot)
            .setSign(false)
            .setNoVerify(true)
            .call()
            .name();
    seat = new JgitCheckout(dir);
  }

  @AfterEach
  void close() {
    git.close();
  }

  @Test
  void an_overlay_rewrites_adds_removes_and_commits_as_its_spec() throws Exception {
    final String sha =
        seat.commit(
            new TreeEdit.Overlay(
                Map.of(
                    "Component.java", bytes("pin 1.1"),
                    "assets/release-1.1.yaml", bytes("new")),
                List.of("assets/release-1.0.yaml")),
            SPEC);

    assertNotEquals(head, sha);
    assertEquals("pin 1.1", Files.readString(dir.resolve("Component.java")));
    assertEquals("new", Files.readString(dir.resolve("assets/release-1.1.yaml")));
    assertFalse(Files.exists(dir.resolve("assets/release-1.0.yaml")));
    final RevCommit commit = git.log().setMaxCount(1).call().iterator().next();
    assertEquals(sha, commit.name());
    assertEquals("bump", commit.getFullMessage());
    assertEquals(BUMPER.email(), commit.getAuthorIdent().getEmailAddress());
    assertEquals(BUMPER.email(), commit.getCommitterIdent().getEmailAddress());
    assertEquals(List.of(), git.status().call().getUncommittedChanges().stream().toList());
  }

  @Test
  void an_overlay_that_changes_nothing_commits_nothing() {
    assertEquals(
        head,
        seat.commit(
            new TreeEdit.Overlay(Map.of("Component.java", bytes("pin 1.0")), List.of()), SPEC));
  }

  @Test
  void a_replace_is_refused_on_the_seat() {
    assertThrows(
        IllegalArgumentException.class,
        () -> seat.commit(new TreeEdit.Replace(Map.of(), List.of()), SPEC));
  }

  @Test
  void a_path_leaving_the_worktree_is_refused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> seat.commit(new TreeEdit.Overlay(Map.of("../escape", bytes("x")), List.of()), SPEC));
    assertFalse(Files.exists(dir.getParent().resolve("escape")));
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private void write(String path, String content) throws IOException {
    final Path file = dir.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }
}
