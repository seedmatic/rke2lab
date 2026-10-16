package io.seedmatic.rke2lab.worktree.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.worktree.BuildRevision;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A dirty worktree builds a FABRICATED revision — one test per condition security set (2026-10-12):
 * the allow-list, gitignored files never captured, untracked ones listed, the sops attribute and
 * envelope refusals, the content guards, the symlink refusal, the ref lifecycle.
 */
class JgitCheckoutBuildRevisionTest {

  private static final List<String> INPUTS = List.of("flake.lock", "flake.nix", "nixos");
  private static final PersonIdent BOT = new PersonIdent("test", "test@example.invalid");

  @TempDir Path dir;
  private Git git;
  private JgitCheckout worktree;
  private String head;

  @BeforeEach
  void a_committed_worktree() throws Exception {
    git = Git.init().setDirectory(dir.toFile()).setInitialBranch("develop").call();
    write(".gitignore", "nixos/local.d/\n");
    write("flake.nix", "{ }");
    write("flake.lock", "{}");
    write("nixos/a.nix", "a");
    write("docs/x.md", "x");
    head = commit("base");
    worktree = new JgitCheckout(dir);
  }

  @AfterEach
  void close() {
    git.close();
  }

  @Test
  void clean_inputs_build_head_and_pin_nothing() throws Exception {
    write("docs/x.md", "a doc edit is not a build input");
    final BuildRevision revision = worktree.buildRevision(INPUTS);
    assertEquals(new BuildRevision(head, false, List.of(), List.of()), revision);
    assertEquals(List.of(), growRefs());
  }

  @Test
  void a_dirty_input_builds_a_fabricated_child_of_head_pinned_under_the_namespace()
      throws Exception {
    write("nixos/a.nix", "edited");
    write("docs/x.md", "not captured");
    final BuildRevision revision = worktree.buildRevision(INPUTS);

    assertTrue(revision.fabricated());
    assertEquals(List.of("nixos/a.nix"), revision.changed());
    assertEquals(List.of(JgitCheckout.GROW_REFS + revision.sha()), growRefs());
    final Repository repository = git.getRepository();
    try (RevWalk walk = new RevWalk(repository)) {
      final RevCommit commit = walk.parseCommit(ObjectId.fromString(revision.sha()));
      assertEquals(head, commit.getParent(0).name());
      assertEquals("grow: dirty nixos/a.nix", commit.getShortMessage());
      assertEquals("rke2lab:grow", commit.getAuthorIdent().getName());
      assertNull(commit.getRawGpgSignature(), "a fabricated revision is not signed");
      assertEquals("edited", read(repository, commit, "nixos/a.nix"));
      assertEquals("x", read(repository, commit, "docs/x.md"), "outside the allow-list: HEAD's");
    }
  }

  @Test
  void an_untracked_input_is_captured_and_listed() throws Exception {
    write("nixos/new.nix", "new");
    final BuildRevision revision = worktree.buildRevision(INPUTS);
    assertEquals(List.of("nixos/new.nix"), revision.untracked());
    assertEquals(List.of("nixos/new.nix"), revision.changed());
  }

  @Test
  void a_gitignored_file_is_never_captured() throws Exception {
    write("nixos/local.d/key.nix", "local material");
    assertFalse(worktree.buildRevision(INPUTS).fabricated(), "an ignored file alone is not dirt");

    write("nixos/a.nix", "edited");
    final BuildRevision revision = worktree.buildRevision(INPUTS);
    final Repository repository = git.getRepository();
    try (RevWalk walk = new RevWalk(repository)) {
      final RevCommit commit = walk.parseCommit(ObjectId.fromString(revision.sha()));
      assertNull(
          TreeWalk.forPath(repository, "nixos/local.d/key.nix", commit.getTree()),
          "the ignored file is absent from the fabricated tree");
    }
  }

  @Test
  void a_removed_input_is_removed_from_the_fabricated_tree() throws Exception {
    Files.delete(dir.resolve("nixos/a.nix"));
    final BuildRevision revision = worktree.buildRevision(INPUTS);
    assertEquals(List.of("nixos/a.nix"), revision.changed());
    final Repository repository = git.getRepository();
    try (RevWalk walk = new RevWalk(repository)) {
      assertNull(
          TreeWalk.forPath(
              repository,
              "nixos/a.nix",
              walk.parseCommit(ObjectId.fromString(revision.sha())).getTree()));
    }
  }

  @Test
  void a_sops_attribute_is_refused() throws Exception {
    write(".gitattributes", "nixos/s.nix filter=sops-yaml diff=sops-yaml\n");
    write("nixos/s.nix", "plain");
    assertRefused("nixos/s.nix: a sops filter/diff attribute", "plain");
  }

  @Test
  void a_sops_envelope_is_refused() throws Exception {
    write("nixos/e.yaml", "data: x\nsops:\n  mac: y\n");
    assertRefused("nixos/e.yaml: a sops envelope", "mac: y");
  }

  @Test
  void private_key_material_is_refused_by_path_and_line() throws Exception {
    final String header = "-----BEGIN OPENSSH " + "PRIVATE" + " KEY-----";
    final String body = "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAAB";
    write("nixos/k.nix", "x\n" + header + "\n" + body + "\n");
    assertRefused("nixos/k.nix (line 3): private key material", body);
  }

  @Test
  void a_token_shape_is_refused_in_any_file() throws Exception {
    final String token = "gh" + "p_" + "A1b2C3d4E5f6G7h8I9j0K1l2";
    write("nixos/t.nix", "{ token = \"" + token + "\"; }\n");
    assertRefused("nixos/t.nix (line(s) 1): a bearer token outside ENC[…]", token);
  }

  @Test
  void a_symlink_leaving_the_repository_is_refused_and_one_inside_is_captured() throws Exception {
    Files.createSymbolicLink(dir.resolve("nixos/inside.nix"), Path.of("a.nix"));
    assertEquals(List.of("nixos/inside.nix"), worktree.buildRevision(INPUTS).untracked());

    Files.createSymbolicLink(dir.resolve("nixos/out.nix"), Path.of("../../outside.nix"));
    assertRefused("nixos/out.nix: a symlink leaving the repository", "../../outside.nix");
  }

  @Test
  void release_drops_the_pin_and_head_releases_nothing() throws Exception {
    write("nixos/a.nix", "edited");
    final BuildRevision revision = worktree.buildRevision(INPUTS);
    worktree.releaseBuildRevision(revision);
    assertEquals(List.of(), growRefs());
    worktree.releaseBuildRevision(new BuildRevision(head, false, List.of(), List.of()));
  }

  @Test
  void a_pin_older_than_seven_days_is_swept_by_the_next_build() throws Exception {
    final String stale = pinAged(Instant.now().minusSeconds(8L * 24 * 3600));
    final String fresh = pinAged(Instant.now().minusSeconds(24L * 3600));
    worktree.buildRevision(INPUTS);
    assertEquals(List.of(JgitCheckout.GROW_REFS + fresh), growRefs());
    assertNotEquals(stale, fresh);
  }

  private void assertRefused(String finding, String content) {
    final IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> worktree.buildRevision(INPUTS));
    assertTrue(refused.getMessage().contains(finding), refused.getMessage());
    assertFalse(refused.getMessage().contains(content), "the refusal never carries captured text");
  }

  private String pinAged(Instant when) throws IOException {
    final Repository repository = git.getRepository();
    try (ObjectInserter inserter = repository.newObjectInserter();
        RevWalk walk = new RevWalk(repository)) {
      final CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(walk.parseCommit(ObjectId.fromString(head)).getTree());
      commit.setParentId(ObjectId.fromString(head));
      final PersonIdent aged = new PersonIdent("rke2lab:grow", "g@x", when, ZoneOffset.UTC);
      commit.setAuthor(aged);
      commit.setCommitter(aged);
      commit.setMessage("aged " + when.getEpochSecond());
      final ObjectId id = inserter.insert(commit);
      inserter.flush();
      final RefUpdate pin = repository.updateRef(JgitCheckout.GROW_REFS + id.name());
      pin.setNewObjectId(id);
      pin.update();
      return id.name();
    }
  }

  private List<String> growRefs() throws IOException {
    return git.getRepository().getRefDatabase().getRefsByPrefix(JgitCheckout.GROW_REFS).stream()
        .map(ref -> ref.getName())
        .toList();
  }

  private static String read(Repository repository, RevCommit commit, String path)
      throws IOException {
    try (TreeWalk walk = TreeWalk.forPath(repository, path, commit.getTree())) {
      return new String(repository.open(walk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
    }
  }

  private void write(String path, String content) throws IOException {
    final Path file = dir.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  private String commit(String message) throws GitAPIException {
    git.add().addFilepattern(".").call();
    return git.commit()
        .setMessage(message)
        .setAuthor(BOT)
        .setCommitter(BOT)
        .setSign(false)
        .setNoVerify(true)
        .call()
        .name();
  }
}
