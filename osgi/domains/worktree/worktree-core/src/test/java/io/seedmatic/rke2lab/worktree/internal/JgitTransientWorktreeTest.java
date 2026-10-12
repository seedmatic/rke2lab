package io.seedmatic.rke2lab.worktree.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.seedmatic.rke2lab.worktree.CommitSpec;
import io.seedmatic.rke2lab.worktree.GitIdentity;
import io.seedmatic.rke2lab.worktree.TransientWorktree;
import io.seedmatic.rke2lab.worktree.TreeEdit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A head committed onto in memory, on throwaway repositories built by jgit — a bare origin and a
 * seat cloned from it, no git binary anywhere.
 */
class JgitTransientWorktreeTest {

  private static final String BRANCH = "manifests/test-mgmt";
  private static final CommitSpec RENDER =
      new CommitSpec(
          "render test-mgmt",
          new GitIdentity("rke2lab:manifests", "rke2lab+manifests@example.invalid"),
          Optional.empty());
  private static final Optional<String> TOKEN = Optional.of("unused-by-file-transport");

  @TempDir Path origin;
  @TempDir Path seatDir;
  @TempDir Path noHooks;
  private Git seat;
  private JgitCheckout checkout;

  @BeforeEach
  void an_origin_and_a_seat() throws Exception {
    Git.init()
        .setBare(true)
        .setDirectory(origin.toFile())
        .setInitialBranch("develop")
        .call()
        .close();
    final Path upstreamDir = Files.createTempDirectory(noHooks, "upstream");
    try (Git upstream =
        Git.init().setDirectory(upstreamDir.toFile()).setInitialBranch("develop").call()) {
      noHooksIn(upstream);
      Files.writeString(upstreamDir.resolve("flake.nix"), "{ }");
      upstream.add().addFilepattern(".").call();
      commit(upstream, "seat base");
      upstream
          .push()
          .setRemote(origin.toUri().toString())
          .setRefSpecs(new RefSpec("refs/heads/develop:refs/heads/develop"))
          .call();
    }
    seat =
        Git.cloneRepository()
            .setURI(origin.toUri().toString())
            .setDirectory(seatDir.toFile())
            .call();
    noHooksIn(seat);
    checkout = new JgitCheckout(seatDir);
  }

  @AfterEach
  void close() {
    if (seat != null) {
      seat.close();
    }
  }

  @Test
  void a_missing_branch_starts_on_a_readme_base_and_publishes_locally_without_a_token()
      throws Exception {
    final TransientWorktree head = open();
    final String base = head.head();
    assertTrue(head.readAtHead("README.md").orElseThrow().startsWith("# " + BRANCH));
    assertNull(localTip(), "opening moves no ref");

    final String sha = head.commit(replace(Map.of("a.yaml", "a"), List.of()), RENDER);
    assertEquals(base, parentOf(sha));
    assertNull(localTip(), "committing moves no ref");

    head.publish(Optional.empty());
    assertEquals(sha, localTip());
    assertNull(originTip(), "no token, no push");
  }

  @Test
  void a_token_pushes_first_then_advances_the_local_branch() throws Exception {
    final TransientWorktree head = open();
    final String sha = head.commit(replace(Map.of("a.yaml", "a"), List.of()), RENDER);
    head.publish(TOKEN);
    assertEquals(sha, originTip());
    assertEquals(sha, localTip());
  }

  @Test
  void a_replace_carries_its_subtrees_and_drops_the_rest() throws Exception {
    final TransientWorktree first = open();
    first.commit(
        replace(
            Map.of("old.yaml", "o", "reflections/r.yaml", "r", "manifest.yaml", "m1"), List.of()),
        RENDER);
    first.publish(TOKEN);

    final TransientWorktree again = open();
    assertEquals(originTip(), again.head(), "it builds on the pushed tip");
    assertEquals("m1", again.readAtHead("manifest.yaml").orElseThrow());
    again.commit(replace(Map.of("manifest.yaml", "m2"), List.of("reflections")), RENDER);
    assertEquals("r", again.readAtHead("reflections/r.yaml").orElseThrow());
    assertEquals("m2", again.readAtHead("manifest.yaml").orElseThrow());
    assertEquals(Optional.empty(), again.readAtHead("old.yaml"));
    assertEquals(Optional.empty(), again.readAtHead("README.md"), "the base marker goes");
  }

  @Test
  void an_overlay_keeps_every_other_path() throws Exception {
    final TransientWorktree head = open();
    head.commit(replace(Map.of("netplan.json", "n1", "dataplan.json", "d1"), List.of()), RENDER);
    head.commit(new TreeEdit.Overlay(Map.of("netplan.json", bytes("n2")), List.of()), RENDER);
    assertEquals("n2", head.readAtHead("netplan.json").orElseThrow());
    assertEquals("d1", head.readAtHead("dataplan.json").orElseThrow());
  }

  @Test
  void an_unchanged_edit_commits_nothing() throws Exception {
    final TransientWorktree head = open();
    final String sha = head.commit(replace(Map.of("a.yaml", "a"), List.of()), RENDER);
    assertEquals(sha, head.commit(replace(Map.of("a.yaml", "a"), List.of()), RENDER));
  }

  @Test
  void a_refused_push_leaves_the_local_branch_where_it_was() throws Exception {
    final TransientWorktree first = open();
    first.commit(replace(Map.of("a.yaml", "a"), List.of()), RENDER);
    first.publish(TOKEN);
    final String landed = localTip();
    final TransientWorktree stale = open();

    // Another seat pushes in between, so origin moves while this seat's branch stays put: only the
    // push's own status can stop the local branch from advancing past what origin refused.
    try (Git other =
        Git.cloneRepository()
            .setURI(origin.toUri().toString())
            .setDirectory(Files.createTempDirectory(noHooks, "other").toFile())
            .call()) {
      noHooksIn(other);
      final JgitCheckout otherSeat = new JgitCheckout(other.getRepository().getWorkTree().toPath());
      otherSeat.fetchBranch(BRANCH, Optional.empty(), java.time.Duration.ofSeconds(10));
      final TransientWorktree racer = JgitTransientWorktree.open(otherSeat.repository(), BRANCH);
      racer.commit(replace(Map.of("a.yaml", "racer"), List.of()), RENDER);
      racer.publish(TOKEN);
    }
    final String racerTip = originTip();

    stale.commit(replace(Map.of("a.yaml", "stale"), List.of()), RENDER);
    assertThrows(IllegalStateException.class, () -> stale.publish(TOKEN));
    assertEquals(racerTip, originTip(), "origin refused the non-fast-forward");
    assertEquals(landed, localTip(), "never a local ref ahead of a failed push");
  }

  @Test
  void a_signing_key_signs_the_commit() throws Exception {
    final TransientWorktree head = open();
    final String sha =
        head.commit(
            replace(Map.of("a.yaml", "a"), List.of()),
            new CommitSpec(RENDER.message(), RENDER.identity(), Optional.of(signingKey())));
    try (RevWalk walk = new RevWalk(seat.getRepository())) {
      final RevCommit commit = walk.parseCommit(ObjectId.fromString(sha));
      assertTrue(
          new String(commit.getRawGpgSignature(), StandardCharsets.US_ASCII)
              .contains("BEGIN SSH SIGNATURE"));
      assertEquals(RENDER.identity().email(), commit.getCommitterIdent().getEmailAddress());
    }
  }

  /** A throwaway ed25519 key, generated in a temp dir; skips when ssh-keygen is not on the PATH. */
  private String signingKey() throws Exception {
    final Path key = noHooks.resolve("signing-key");
    final Process keygen;
    try {
      keygen =
          new ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", key.toString())
              .redirectErrorStream(true)
              .start();
    } catch (IOException absent) {
      assumeTrue(false, "ssh-keygen is not on the PATH");
      throw absent;
    }
    keygen.getInputStream().readAllBytes();
    assertEquals(0, keygen.waitFor());
    return Files.readString(key);
  }

  private TransientWorktree open() {
    checkout.fetchBranch(BRANCH, Optional.empty(), java.time.Duration.ofSeconds(10));
    return JgitTransientWorktree.open(checkout.repository(), BRANCH);
  }

  private String localTip() throws IOException {
    final Ref ref = seat.getRepository().exactRef(Constants.R_HEADS + BRANCH);
    return ref == null ? null : ref.getObjectId().name();
  }

  private String originTip() throws Exception {
    try (Git bare = Git.open(origin.toFile())) {
      final Ref ref = bare.getRepository().exactRef(Constants.R_HEADS + BRANCH);
      return ref == null ? null : ref.getObjectId().name();
    }
  }

  private String parentOf(String sha) throws IOException {
    try (RevWalk walk = new RevWalk(seat.getRepository())) {
      return walk.parseCommit(ObjectId.fromString(sha)).getParent(0).name();
    }
  }

  private static TreeEdit.Replace replace(Map<String, String> files, List<String> carried) {
    final Map<String, byte[]> bytes = new java.util.LinkedHashMap<>();
    files.forEach((path, text) -> bytes.put(path, bytes(text)));
    return new TreeEdit.Replace(bytes, carried);
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private void noHooksIn(Git git) throws IOException {
    final StoredConfig config = git.getRepository().getConfig();
    config.setString("core", null, "hooksPath", noHooks.toString());
    config.save();
  }

  private static void commit(Git git, String message) throws Exception {
    final PersonIdent bot = new PersonIdent("test", "test@example.invalid");
    git.commit()
        .setMessage(message)
        .setAuthor(bot)
        .setCommitter(bot)
        .setSign(false)
        .setNoVerify(true)
        .call();
  }
}
