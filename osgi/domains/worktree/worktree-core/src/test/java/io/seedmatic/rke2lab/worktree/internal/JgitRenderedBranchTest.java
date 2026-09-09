package io.seedmatic.rke2lab.worktree.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.seedmatic.rke2lab.worktree.GitIdentity;
import io.seedmatic.rke2lab.worktree.LinkedWorktree;
import io.seedmatic.rke2lab.worktree.Provenance;
import io.seedmatic.rke2lab.worktree.RenderedBranch;
import io.seedmatic.rke2lab.worktree.WorkingState;
import io.seedmatic.rke2lab.worktree.Worktree;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.junit.http.AppServer;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ReceivePack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The RenderedBranch socle, proven against real git. Each test reads as a step of the one gesture —
 * {@code prepare} a linked worktree on a branch seeded with a null-commit base, materialise a tree,
 * seal it with an SSH-SIGNED commit, fast-forward push, RE-render (accretion), and {@code close} —
 * because the plumbing (a stand-in GitHub, a work repo, a signing key, every {@code git} shell-out)
 * lives in the {@link GitGround} fixture, opened per test in a try-with-resources.
 *
 * <p>The stand-in origin is served over a loopback HTTP {@code GitServlet}, NOT a {@code file://}
 * path or a {@code git://} daemon: jgit's push DEADLOCKS on those duplex transports (the push waits
 * for the status report while receive-pack waits for the pack — two ends coupled in one JVM). HTTP
 * is half-duplex — the whole request is flushed before the response is read — so it does not, and
 * it is the shape of production (HTTPS).
 *
 * <p>Needs {@code git} and {@code ssh-keygen} on PATH (the flox runtime provides both); absence
 * aborts (skips) rather than fails.
 */
class JgitRenderedBranchTest {

  private static final String CLUSTER = "nikopol-mgmt";
  private static final String BRANCH = "manifests/" + CLUSTER;
  private static final String TOKEN = "x-access-token-unused-over-anonymous-http";
  private static final GitIdentity BOT =
      new GitIdentity("rke2lab:manifests-bumper", "rke2lab+manifests-bumper@example.invalid");

  @TempDir Path tmp;

  @BeforeEach
  void requireGit() {
    assumeTrue(toolPresent("git", "--version"), "git is required");
  }

  @Test
  void prepare_seeds_a_null_commit_base() throws Exception {
    try (GitGround ground = new GitGround(tmp)) {
      final Path worktreePath = ground.renderPath(CLUSTER);

      final LinkedWorktree linked = ground.renderedBranch().prepare(worktreePath, BRANCH);

      assertEquals(worktreePath.toRealPath(), linked.path(), "checked out at the asked path");
      assertEquals(BRANCH, linked.branch());
      assertTrue(Files.isDirectory(linked.path()), "the linked worktree is on disk");
      assertTrue(ground.registersWorktree(worktreePath), "git knows the linked worktree");
      assertEquals(
          1, ground.commitCount(linked.path()), "the branch starts at its null-commit base");
    }
  }

  @Test
  void a_render_accretes_is_ssh_signed_and_pushes() throws Exception {
    try (GitGround ground = new GitGround(tmp)) {
      final Path worktreePath = ground.renderPath(CLUSTER);
      final LinkedWorktree linked = ground.renderedBranch().prepare(worktreePath, BRANCH);

      Files.writeString(linked.path().resolve("cluster.yaml"), "kind: Cluster\n");
      linked.stageAll();
      final String sha = linked.commit("render " + CLUSTER, BOT, Optional.of(ground.signingKey()));

      assertFalse(sha.isBlank(), "the commit reports its sha");
      assertTrue(ground.isSshSigned(sha), "the rendered commit is SSH-signed");
      assertEquals(2, ground.commitCount(linked.path()), "the render accretes on the null base");

      linked.push(TOKEN);
      assertEquals(sha, ground.originTip(BRANCH), "origin advanced to the pushed render");
    }
  }

  @Test
  void jgit_stage_runs_the_external_clean_filter() throws Exception {
    // LOAD-BEARING for branch+sops: the render commits its output branch via jgit
    // (JgitCheckout.stage → git.add()). If jgit's AddCommand runs the EXTERNAL clean
    // filter, the sops clean filter (same mechanism) encrypts a Secret's bytes on commit;
    // if it does NOT, a rendered Secret would be committed in PLAINTEXT — a leak. Prove it
    // by transform: a stub clean filter that uppercases stdin. The committed blob is
    // uppercased iff jgit ran the filter.
    assumeTrue(toolPresent("tr", "--version"), "tr is required");
    try (GitGround ground = new GitGround(tmp)) {
      ground.setWorkConfig("filter.stub.clean", "tr a-z A-Z");

      final Path worktreePath = ground.renderPath(CLUSTER);
      final LinkedWorktree linked = ground.renderedBranch().prepare(worktreePath, BRANCH);

      Files.writeString(linked.path().resolve(".gitattributes"), "secret.txt filter=stub\n");
      Files.writeString(linked.path().resolve("secret.txt"), "hello\n");
      linked.stageAll();
      linked.commit("render " + CLUSTER, BOT, Optional.of(ground.signingKey()));

      // cat-file shows the STORED blob (plumbing — no smudge): uppercased iff the clean
      // filter ran while jgit staged it.
      assertEquals(
          "HELLO",
          ground.showBlob(linked.path(), "HEAD:secret.txt").trim(),
          "jgit add must run the external clean filter — else a committed Secret leaks plaintext");
    }
  }

  @Test
  void re_preparing_reuses_the_branch_so_renders_accrete_as_fast_forwards() throws Exception {
    try (GitGround ground = new GitGround(tmp)) {
      final Path worktreePath = ground.renderPath(CLUSTER);
      final RenderedBranch branch = ground.renderedBranch();

      // render 1 — accretes on the null base, pushed.
      final LinkedWorktree first = branch.prepare(worktreePath, BRANCH);
      Files.writeString(first.path().resolve("cluster.yaml"), "kind: Cluster\n");
      first.stageAll();
      final String firstSha =
          first.commit("render " + CLUSTER, BOT, Optional.of(ground.signingKey()));
      first.push(TOKEN);

      // render 2 — re-prepare REUSES the branch (its history survives), a second commit whose
      // parent
      // is render 1 (a fast-forward, not a fresh orphan).
      final LinkedWorktree again = branch.prepare(worktreePath, BRANCH);
      assertEquals(2, ground.commitCount(again.path()), "re-prepare keeps the branch history");
      Files.writeString(again.path().resolve("cluster.yaml"), "kind: Cluster\nversion: 2\n");
      again.stageAll();
      final String secondSha =
          again.commit("re-render " + CLUSTER, BOT, Optional.of(ground.signingKey()));

      assertEquals(3, ground.commitCount(again.path()), "null base + two renders");
      assertEquals(
          firstSha, ground.parentSha(again.path(), secondSha), "render 2's parent is render 1");
      again.push(TOKEN);
      assertEquals(secondSha, ground.originTip(BRANCH), "origin fast-forwarded to render 2");
    }
  }

  @Test
  void re_preparing_at_a_new_path_reclaims_the_branch_from_its_stale_worktree() throws Exception {
    try (GitGround ground = new GitGround(tmp)) {
      // render 1 at the OLD leaf, committed — the branch is now checked out there.
      final Path oldPath = ground.renderPath(CLUSTER);
      final LinkedWorktree first = ground.renderedBranch().prepare(oldPath, BRANCH);
      Files.writeString(first.path().resolve("cluster.yaml"), "kind: Cluster\n");
      first.stageAll();
      first.commit("render " + CLUSTER, BOT, Optional.of(ground.signingKey()));

      // The render leaf is renamed out from under us (a branch renamed on the remote leaves the OLD
      // worktree holding it). Re-preparing at a NEW path must RECLAIM the branch — release the
      // stale
      // worktree, re-add here — not fail "already used by worktree at …". The accretion survives.
      final Path newPath = ground.renderPath("bioskop-mgmt");
      final LinkedWorktree again = ground.renderedBranch().prepare(newPath, BRANCH);

      assertEquals(
          newPath.toRealPath(),
          again.path(),
          "the branch is re-materialised at the requested path");
      assertFalse(Files.exists(oldPath), "the stale worktree checkout is released");
      assertEquals(
          2,
          ground.commitCount(again.path()),
          "the branch history (null base + render 1) survives");
    }
  }

  @Test
  void close_removes_the_linked_worktree_but_keeps_the_branch() throws Exception {
    try (GitGround ground = new GitGround(tmp)) {
      final Path worktreePath = ground.renderPath(CLUSTER);
      final LinkedWorktree linked = ground.renderedBranch().prepare(worktreePath, BRANCH);

      linked.close();

      assertFalse(Files.exists(worktreePath), "the linked worktree directory is removed");
      assertFalse(
          ground.registersWorktree(worktreePath), "git no longer lists the linked worktree");
    }
  }

  private boolean toolPresent(String... probe) {
    try {
      final Process process =
          new ProcessBuilder(probe).redirectErrorStream(true).redirectOutput(discard()).start();
      return process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (IOException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }

  private ProcessBuilder.Redirect discard() {
    return ProcessBuilder.Redirect.to(
        new java.io.File(
            System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null"));
  }

  /**
   * The inline fixture "app": a bare origin served over a loopback HTTP {@code GitServlet} (a
   * stand-in GitHub on the half-duplex transport the push needs), a work repository with one commit
   * on {@code main}, and a throwaway {@code ssh-keygen} key (the ndh {@code github-signing}
   * stand-in). It owns every {@code git} shell-out and the server lifecycle, so a test reads as
   * prepare / render / push / close, not as plumbing. {@link AutoCloseable}: {@link #close()} stops
   * the server and closes the origin repository.
   */
  static final class GitGround implements AutoCloseable {

    private final Path origin;
    private final Path work;
    private final Path renderRoot;
    private final String signingKey;
    private final Repository originRepo;
    private final AppServer server;

    GitGround(Path tmp) throws Exception {
      this.origin = tmp.resolve("origin.git");
      this.work = tmp.resolve("work");
      this.renderRoot = tmp.resolve("render");
      this.signingKey = throwawaySshKey(tmp.resolve("sign.key"));

      git(tmp, "init", "--bare", "-b", "main", origin.toString());
      this.originRepo = new FileRepositoryBuilder().setGitDir(origin.toFile()).build();
      this.server = serveOverHttp(originRepo);
      final String originUrl = server.getURI().resolve("git/origin.git").toString();

      git(tmp, "init", "-b", "main", work.toString());
      git(work, "config", "user.name", "test");
      git(work, "config", "user.email", "test@example.invalid");
      git(work, "config", "commit.gpgsign", "false");
      Files.writeString(work.resolve("README"), "source\n");
      git(work, "add", "README");
      git(work, "commit", "-m", "init");
      git(work, "remote", "add", "origin", originUrl);
      git(work, "push", "origin", "main");
    }

    /** A rendered branch cut from this ground's work repository. */
    RenderedBranch renderedBranch() {
      return new JgitRenderedBranch(new SeedWorktree(work));
    }

    /** The render-worktree path for a cluster leaf under the (would-be gitignored) render root. */
    Path renderPath(String leaf) {
      return renderRoot.resolve(leaf);
    }

    /** The throwaway OpenSSH private key renders are signed with. */
    String signingKey() {
      return signingKey;
    }

    /** The sha origin's {@code branch} points at, read straight from the bare repo. */
    String originTip(String branch) throws Exception {
      return git(origin, "rev-parse", "refs/heads/" + branch).trim();
    }

    /** Commits reachable from the worktree's HEAD — the branch's history depth. */
    int commitCount(Path worktree) throws Exception {
      return Integer.parseInt(git(worktree, "rev-list", "--count", "HEAD").trim());
    }

    /** The parent sha of {@code sha} in the worktree's repo. */
    String parentSha(Path worktree, String sha) throws Exception {
      return git(worktree, "rev-parse", sha + "^").trim();
    }

    /** Set a git config key on the work repo (shared by its linked worktrees). */
    void setWorkConfig(String key, String value) throws Exception {
      git(work, "config", key, value);
    }

    /** The STORED blob at {@code rev} (plumbing {@code cat-file -p} — no smudge applied). */
    String showBlob(Path worktree, String rev) throws Exception {
      return git(worktree, "cat-file", "-p", rev);
    }

    /** Whether {@code sha}'s commit object carries an SSH signature header. */
    boolean isSshSigned(String sha) throws Exception {
      return git(work, "cat-file", "-p", sha).contains("BEGIN SSH SIGNATURE");
    }

    /**
     * Whether git's worktree registry lists {@code worktreePath}. Canonicalises via the PARENT
     * (which outlives the leaf — {@code git worktree remove} drops only the leaf), so it answers
     * both before and after {@code close()}, matching the real path git records.
     */
    boolean registersWorktree(Path worktreePath) throws Exception {
      final String canonical =
          worktreePath.getParent().toRealPath().resolve(worktreePath.getFileName()).toString();
      return git(work, "worktree", "list", "--porcelain").contains("worktree " + canonical);
    }

    @Override
    public void close() {
      try {
        server.tearDown();
      } catch (Exception ex) {
        throw new IllegalStateException("cannot stop the test http server", ex);
      } finally {
        originRepo.close();
      }
    }

    /**
     * Serve {@code repo} over a loopback HTTP {@code GitServlet} (ephemeral port, no TLS) with
     * anonymous receive-pack. HTTP is half-duplex — the client flushes the whole request (ref
     * updates + pack) before reading the response — so jgit's push does not deadlock the way it
     * does on the duplex {@code file://} / {@code git://} transports; it is also the shape of
     * production (HTTPS). The resolver hands back the one repo (bumping its open count, since the
     * servlet closes it per request); the {@link ReceivePack} factory makes push anonymous, no
     * credential exchanged.
     */
    private AppServer serveOverHttp(Repository repo) throws Exception {
      final GitServlet gitServlet = new GitServlet();
      gitServlet.setRepositoryResolver(
          (HttpServletRequest req, String name) -> {
            repo.incrementOpen();
            return repo;
          });
      gitServlet.setReceivePackFactory(
          (HttpServletRequest req, Repository db) -> new ReceivePack(db));

      final AppServer http = new AppServer(0, -1);
      final ServletContextHandler ctx = http.addContext("/git");
      ctx.addServlet(new ServletHolder(gitServlet), "/*");
      http.setUp();
      return http;
    }

    private String throwawaySshKey(Path keyFile) throws Exception {
      final Process process =
          new ProcessBuilder(
                  "ssh-keygen",
                  "-t",
                  "ed25519",
                  "-N",
                  "",
                  "-C",
                  "render-test",
                  "-f",
                  keyFile.toString())
              .redirectErrorStream(true)
              .start();
      if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
        abort("ssh-keygen could not mint a throwaway key");
      }
      return Files.readString(keyFile, StandardCharsets.UTF_8);
    }

    /** Run {@code git <args>} in {@code dir}, returning stdout; throws on a non-zero exit. */
    private String git(Path dir, String... args) throws Exception {
      final java.util.List<String> command = new java.util.ArrayList<>();
      command.add("git");
      command.addAll(List.of(args));
      final Process process =
          new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
      final byte[] output = process.getInputStream().readAllBytes();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException(String.join(" ", command) + " timed out");
      }
      final String text = new String(output, StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        throw new IllegalStateException(String.join(" ", command) + " failed: " + text.trim());
      }
      return text;
    }
  }

  /**
   * A {@link Worktree} that knows only its root — all {@link RenderedBranch#prepare} asks of it.
   */
  private record SeedWorktree(Path root) implements Worktree {

    @Override
    public Provenance provenance() {
      throw new UnsupportedOperationException();
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
    public void stage(List<Path> paths) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String commit(String message, GitIdentity identity, Optional<String> sshSigningKey) {
      throw new UnsupportedOperationException();
    }
  }
}
