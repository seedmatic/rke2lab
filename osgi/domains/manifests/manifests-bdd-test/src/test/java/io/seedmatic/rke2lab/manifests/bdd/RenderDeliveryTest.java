package io.seedmatic.rke2lab.manifests.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.marschall.memoryfilesystem.MemoryFileSystemBuilder;
import io.seedmatic.rke2lab.manifests.RenderedSecretSeal;
import io.seedmatic.rke2lab.manifests.YamlMapper;
import io.seedmatic.rke2lab.sops.edge.SopsProcessDecryptor;
import io.seedmatic.rke2lab.sops.edge.SopsProcessEncryptor;
import io.seedmatic.rke2lab.worktree.BuildRevision;
import io.seedmatic.rke2lab.worktree.CommitSpec;
import io.seedmatic.rke2lab.worktree.DurableWorktree;
import io.seedmatic.rke2lab.worktree.GitIdentity;
import io.seedmatic.rke2lab.worktree.Provenance;
import io.seedmatic.rke2lab.worktree.TreeEdit;
import io.seedmatic.rke2lab.worktree.WorkingState;
import io.seedmatic.rke2lab.worktree.internal.JgitTransientWorktrees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The render's DELIVERY end to end, on real parts and a LOCAL bare as {@code origin}: the plot on
 * an in-memory file system, the real seal (real sops, a throwaway age key), a real SSH signature (a
 * throwaway key), the jgit heads — then the branch as origin holds it. Never the real seed remote.
 */
class RenderDeliveryTest {

  private static final String BRANCH = "manifests/test-mgmt";
  private static final GitIdentity BOT =
      new GitIdentity("rke2lab:manifests-render", "rke2lab+manifests-render@example.invalid");
  private static final String SECRET =
      """
      apiVersion: v1
      kind: Secret
      metadata:
        name: probe
        namespace: probe-system
      stringData:
        token: dummy-token
      """;

  @TempDir Path dir;
  private Path origin;
  private Path worktreeDir;
  private Git worktree;
  private String recipient;
  private String signingKey;

  @BeforeEach
  void an_origin_holding_the_branch_and_a_worktree_cloned_from_it() throws Exception {
    assumeTrue(
        onPath("sops") && onPath("age-keygen") && onPath("ssh-keygen"),
        "needs sops, age-keygen and ssh-keygen on PATH");
    origin = dir.resolve("origin.git");
    Git.init()
        .setBare(true)
        .setDirectory(origin.toFile())
        .setInitialBranch("develop")
        .call()
        .close();
    final Path upstreamDir = dir.resolve("upstream");
    try (Git upstream =
        Git.init().setDirectory(upstreamDir.toFile()).setInitialBranch("develop").call()) {
      noHooks(upstream);
      write(upstreamDir, "flake.nix", "{ }");
      commit(upstream, "worktree base");
      upstream.checkout().setOrphan(true).setName(BRANCH).call();
      upstream.rm().addFilepattern("flake.nix").call();
      write(upstreamDir, "reflections/pool.yaml", "pool: reflected\n");
      write(upstreamDir, "old.yaml", "stale: render\n");
      commit(upstream, "the previous render");
      upstream
          .push()
          .setRemote(origin.toUri().toString())
          .setRefSpecs(
              new RefSpec("refs/heads/develop:refs/heads/develop"),
              new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
          .call();
    }
    worktreeDir = dir.resolve("worktree");
    worktree =
        Git.cloneRepository()
            .setURI(origin.toUri().toString())
            .setDirectory(worktreeDir.toFile())
            .call();
    noHooks(worktree);
    recipient = ageRecipient();
    signingKey = sshKey();
  }

  @AfterEach
  void close() {
    if (worktree != null) {
      worktree.close();
    }
  }

  @Test
  void the_delivery_lands_one_signed_commit_with_the_secrets_sealed_and_reflections_carried()
      throws Exception {
    final String before = originTip();
    try (FileSystem soil = MemoryFileSystemBuilder.newEmpty().build()) {
      final RenderSoil plot = renderedPlot(soil);

      delivery().seal(plot);
    }

    final String after = originTip();
    try (Git bare = Git.open(origin.toFile());
        RevWalk walk = new RevWalk(bare.getRepository())) {
      final Repository repository = bare.getRepository();
      final RevCommit tip = walk.parseCommit(ObjectId.fromString(after));
      assertEquals(before, tip.getParent(0).name(), "one commit on top of the previous render");
      assertTrue(
          new String(tip.getRawGpgSignature(), StandardCharsets.US_ASCII)
              .contains("BEGIN SSH SIGNATURE"),
          "the render commit is SSH-signed");
      assertEquals(BOT.email(), tip.getCommitterIdent().getEmailAddress());

      final List<String> paths = paths(repository, tip);
      assertTrue(paths.contains("reflections/pool.yaml"), "the reflector's subtree is carried");
      assertEquals("pool: reflected\n", read(repository, tip, "reflections/pool.yaml"));
      assertFalse(paths.contains("old.yaml"), "a Replace drops what the render no longer produces");
      assertTrue(paths.contains(".gitignore") && paths.contains("manifest.yaml"));
      assertTrue(
          paths.stream()
              .noneMatch(p -> p.startsWith(".local.d/") || p.startsWith(".scratchpad.d/")),
          "no local dir in the commit: " + paths);
      final String secret = read(repository, tip, "probe-system/02-secret-probe.yaml");
      assertTrue(secret.contains("token: ENC["), "the Secret's value is sealed");
      assertFalse(secret.contains("dummy-token"), "no clear value reached origin");
    }
    assertEquals(after, localTip(), "the local branch follows the accepted push");
  }

  @Test
  void a_push_origin_refuses_leaves_the_local_branch_where_it_was() throws Exception {
    try (FileSystem soil = MemoryFileSystemBuilder.newEmpty().build()) {
      final RenderSoil plot = renderedPlot(soil);
      final String local = localTip();
      final String raced = raceOrigin();

      assertThrows(IllegalStateException.class, () -> delivery().seal(plot));

      assertEquals(raced, originTip(), "origin kept the other worktree's commit");
      assertEquals(local, localTip(), "never a local ref ahead of a refused push");
    }
  }

  @Test
  void a_clear_file_in_a_nested_local_dir_is_refused_and_never_reaches_origin() throws Exception {
    final String before = originTip();
    try (FileSystem soil = MemoryFileSystemBuilder.newEmpty().build()) {
      final RenderSoil plot = renderedPlot(soil);
      write(plot.root(), "probe-system/.local.d/leak.yaml", SECRET);

      assertThrows(IllegalStateException.class, () -> delivery().seal(plot));
    }
    assertEquals(before, originTip(), "nothing was pushed");
  }

  private RenderDelivery delivery() {
    return new RenderDelivery(
        "render test-mgmt manifests",
        BOT,
        signingKey,
        true,
        Optional.of("unused-by-the-file-transport"),
        new RenderedSecretSeal(
            new SopsProcessEncryptor(), new SopsProcessDecryptor(), new YamlMapper()));
  }

  /** The head opened in the worktree's repository, its plot on the in-memory soil, rendered. */
  private RenderSoil renderedPlot(FileSystem soil) throws IOException {
    final Path root = soil.getPath(BRANCH);
    write(root, ".sops.yaml", policy());
    write(root, "manifest.yaml", "kind: ConfigMap\n");
    write(root, "probe-system/02-secret-probe.yaml", SECRET);
    write(root, ".local.d/manifests.yaml", "intermediate: " + SECRET.length() + "\n");
    write(root, ".scratchpad.d/note.md", "scratch\n");
    return new RenderSoil(
        new JgitTransientWorktrees(worktree()).open(BRANCH, Optional.empty()), root);
  }

  private String policy() {
    return "creation_rules:\n"
        + "  - path_regex: '(^|/)02-secret-[^/]*\\.ya?ml$'\n"
        + "    encrypted_regex: '^(data|stringData)$'\n"
        + "    age: "
        + recipient
        + "\n";
  }

  /** Another worktree pushes onto the branch first, so this one's push is not a fast-forward. */
  private String raceOrigin() throws Exception {
    try (Git other =
        Git.cloneRepository()
            .setURI(origin.toUri().toString())
            .setDirectory(dir.resolve("other").toFile())
            .setBranch(BRANCH)
            .call()) {
      noHooks(other);
      write(other.getRepository().getWorkTree().toPath(), "raced.yaml", "first: true\n");
      commit(other, "another worktree's render");
      other
          .push()
          .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
          .call();
    }
    return originTip();
  }

  private DurableWorktree worktree() {
    final Path root = worktreeDir;
    return new DurableWorktree() {
      @Override
      public Path root() {
        return root;
      }

      @Override
      public Path repository() {
        return root.resolve(".git");
      }

      @Override
      public Provenance provenance() {
        throw new UnsupportedOperationException();
      }

      @Override
      public WorkingState workingState() {
        throw new UnsupportedOperationException();
      }

      @Override
      public boolean flakeLockCoherent() {
        throw new UnsupportedOperationException();
      }

      @Override
      public BuildRevision buildRevision(List<String> paths) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void releaseBuildRevision(BuildRevision revision) {
        throw new UnsupportedOperationException();
      }

      @Override
      public String treeDigest(String revision, List<String> paths) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Optional<String> readAtHead(String path) {
        throw new UnsupportedOperationException();
      }

      @Override
      public String commit(TreeEdit edit, CommitSpec spec) {
        throw new UnsupportedOperationException();
      }
    };
  }

  private String originTip() throws IOException {
    try (Git bare = Git.open(origin.toFile())) {
      return bare.getRepository().exactRef(Constants.R_HEADS + BRANCH).getObjectId().name();
    }
  }

  private String localTip() throws IOException {
    return worktree.getRepository().exactRef(Constants.R_HEADS + BRANCH).getObjectId().name();
  }

  private static List<String> paths(Repository repository, RevCommit commit) throws IOException {
    final List<String> paths = new ArrayList<>();
    try (TreeWalk walk = new TreeWalk(repository)) {
      walk.addTree(commit.getTree());
      walk.setRecursive(true);
      while (walk.next()) {
        paths.add(walk.getPathString());
      }
    }
    return paths;
  }

  private static String read(Repository repository, RevCommit commit, String path)
      throws IOException {
    try (TreeWalk walk = TreeWalk.forPath(repository, path, commit.getTree())) {
      assertTrue(walk != null, path + " is in the commit");
      return new String(repository.open(walk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
    }
  }

  private String ageRecipient() throws Exception {
    final Path key = dir.resolve("age.key");
    run("age-keygen", "-o", key.toString());
    final Matcher matcher = Pattern.compile("age1[a-z0-9]+").matcher(Files.readString(key));
    assertTrue(matcher.find(), "age-keygen wrote a recipient");
    return matcher.group();
  }

  private String sshKey() throws Exception {
    final Path key = dir.resolve("signing-key");
    run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", key.toString());
    return Files.readString(key);
  }

  private static void run(String... command) throws Exception {
    final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    process.getInputStream().readAllBytes();
    assertEquals(0, process.waitFor(), String.join(" ", command));
  }

  private void noHooks(Git git) throws IOException {
    final Path empty = dir.resolve("no-hooks");
    Files.createDirectories(empty);
    final StoredConfig config = git.getRepository().getConfig();
    config.setString("core", null, "hooksPath", empty.toString());
    config.save();
  }

  private static void commit(Git git, String message) throws Exception {
    git.add().addFilepattern(".").call();
    final PersonIdent author = new PersonIdent("test", "test@example.invalid");
    git.commit()
        .setMessage(message)
        .setAuthor(author)
        .setCommitter(author)
        .setSign(false)
        .setNoVerify(true)
        .call();
  }

  private static void write(Path root, String path, String content) throws IOException {
    final Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  private static boolean onPath(String tool) {
    for (String entry : System.getenv("PATH").split(":")) {
      if (Files.isExecutable(Path.of(entry, tool))) {
        return true;
      }
    }
    return false;
  }
}
