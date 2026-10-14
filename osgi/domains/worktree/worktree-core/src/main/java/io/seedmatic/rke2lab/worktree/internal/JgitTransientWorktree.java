package io.seedmatic.rke2lab.worktree.internal;

import io.seedmatic.rke2lab.worktree.CommitSpec;
import io.seedmatic.rke2lab.worktree.TransientWorktree;
import io.seedmatic.rke2lab.worktree.TreeEdit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.CanceledException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.UnsupportedSigningFormatException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.GpgConfig;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * One head, in memory: its tip read from the repository's objects, edits written as objects, the
 * branch moved only by {@link #publish}. Holds the repository's path and the current head sha, and
 * opens the repository per act, as {@link JgitCheckout} does.
 */
final class JgitTransientWorktree implements TransientWorktree {

  private final Path repositoryDir;
  private final String branch;
  private String head;

  private JgitTransientWorktree(Path repositoryDir, String branch, String head) {
    this.repositoryDir = repositoryDir;
    this.branch = branch;
    this.head = head;
  }

  /** The branch's local tip, or a fresh README-only base when the branch does not exist yet. */
  static JgitTransientWorktree open(Path repositoryDir, String branch) {
    try (Repository repository = openRepository(repositoryDir)) {
      final Ref tip = repository.exactRef(Constants.R_HEADS + branch);
      final String head =
          tip != null ? tip.getObjectId().name() : nullBase(repository, branch).name();
      return new JgitTransientWorktree(repositoryDir, branch, head);
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot open " + branch + " in " + repositoryDir, ex);
    }
  }

  @Override
  public String branch() {
    return branch;
  }

  @Override
  public String head() {
    return head;
  }

  @Override
  public Optional<String> readAtHead(String path) {
    try (Repository repository = openRepository(repositoryDir);
        RevWalk walk = new RevWalk(repository)) {
      final RevCommit commit = walk.parseCommit(ObjectId.fromString(head));
      try (TreeWalk entry = TreeWalk.forPath(repository, path, commit.getTree())) {
        if (entry == null) {
          return Optional.empty();
        }
        return Optional.of(
            new String(repository.open(entry.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read " + path + " at " + branch, ex);
    }
  }

  @Override
  public String commit(TreeEdit edit, CommitSpec spec) {
    try (Repository repository = openRepository(repositoryDir);
        RevWalk walk = new RevWalk(repository);
        ObjectInserter inserter = repository.newObjectInserter()) {
      final RevCommit parent = walk.parseCommit(ObjectId.fromString(head));
      final Map<String, DirCacheEntry> tree = entriesOf(repository, parent.getTree());
      final Map<String, byte[]> files =
          switch (edit) {
            case TreeEdit.Overlay overlay -> {
              overlay.removed().forEach(tree::remove);
              yield overlay.files();
            }
            case TreeEdit.Replace replace -> {
              tree.keySet().removeIf(path -> !isUnder(path, replace.carried()));
              yield replace.files();
            }
          };
      for (Map.Entry<String, byte[]> file : files.entrySet()) {
        final DirCacheEntry entry = new DirCacheEntry(file.getKey());
        entry.setFileMode(FileMode.REGULAR_FILE);
        entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, file.getValue()));
        tree.put(file.getKey(), entry);
      }
      final DirCache index = DirCache.newInCore();
      final DirCacheBuilder builder = index.builder();
      tree.values().forEach(builder::add);
      builder.finish();
      final ObjectId treeId = index.writeTree(inserter);
      if (treeId.equals(parent.getTree())) {
        return head;
      }
      final PersonIdent identity = new PersonIdent(spec.identity().name(), spec.identity().email());
      final CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(treeId);
      commit.setParentId(parent);
      commit.setAuthor(identity);
      commit.setCommitter(identity);
      commit.setMessage(spec.message());
      final Optional<String> key = spec.sshSigningKey().filter(value -> !value.isBlank());
      if (key.isPresent()) {
        new SshCommitSigner(key.get())
            .signObject(
                repository,
                new GpgConfig(repository.getConfig()),
                commit,
                identity,
                null,
                CredentialsProvider.getDefault());
      }
      final ObjectId id = inserter.insert(commit);
      inserter.flush();
      head = id.name();
      return head;
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot commit on " + branch + " in " + repositoryDir, ex);
    } catch (CanceledException | UnsupportedSigningFormatException ex) {
      throw new IllegalStateException("cannot sign the commit on " + branch, ex);
    }
  }

  @Override
  public void publish(Optional<String> pushToken, Duration timeout) {
    final String ref = Constants.R_HEADS + branch;
    try (Repository repository = openRepository(repositoryDir)) {
      if (pushToken.isPresent()) {
        push(repository, ref, pushToken.get(), timeout);
      }
      final RefUpdate update = repository.updateRef(ref);
      update.setNewObjectId(ObjectId.fromString(head));
      final RefUpdate.Result result = update.update();
      switch (result) {
        case NEW, FAST_FORWARD, NO_CHANGE -> {}
        default -> throw new IllegalStateException("cannot advance " + ref + ": " + result);
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot publish " + branch + " in " + repositoryDir, ex);
    }
  }

  private void push(Repository repository, String ref, String token, Duration timeout) {
    try (Git git = new Git(repository)) {
      final Iterable<PushResult> results =
          git.push()
              .setRemote(Constants.DEFAULT_REMOTE_NAME)
              .setRefSpecs(new RefSpec(head + ":" + ref))
              .setForce(false)
              .setTimeout((int) timeout.toSeconds())
              .setCredentialsProvider(
                  new UsernamePasswordCredentialsProvider("x-access-token", token))
              .call();
      for (PushResult result : results) {
        final RemoteRefUpdate update = result.getRemoteUpdate(ref);
        if (update == null
            || (update.getStatus() != RemoteRefUpdate.Status.OK
                && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE)) {
          throw new IllegalStateException(
              "origin refused "
                  + ref
                  + " at "
                  + head
                  + ": "
                  + (update == null ? "no update" : update.getStatus()));
        }
      }
    } catch (GitAPIException ex) {
      throw new IllegalStateException("cannot push " + ref + " from " + repositoryDir, ex);
    }
  }

  /**
   * The base a new branch grows on: one commit carrying a README — NOT the empty tree, which jgit's
   * PackWriter cannot pack, so every push of a branch grown on it would fail. A render replaces it.
   */
  private static ObjectId nullBase(Repository repository, String branch) throws IOException {
    final String readme =
        "# "
            + branch
            + "\n\nMachine-rendered branch — accreted and force-advanced by the rke2lab manifests"
            + " render. Do not edit by hand.\n";
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      final DirCache index = DirCache.newInCore();
      final DirCacheBuilder builder = index.builder();
      final DirCacheEntry entry = new DirCacheEntry("README.md");
      entry.setFileMode(FileMode.REGULAR_FILE);
      entry.setObjectId(
          inserter.insert(Constants.OBJ_BLOB, readme.getBytes(StandardCharsets.UTF_8)));
      builder.add(entry);
      builder.finish();
      final CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(index.writeTree(inserter));
      final PersonIdent base = new PersonIdent("rke2lab", "rke2lab@localhost");
      commit.setAuthor(base);
      commit.setCommitter(base);
      commit.setMessage("init " + branch + "\n");
      final ObjectId id = inserter.insert(commit);
      inserter.flush();
      return id;
    }
  }

  private static Map<String, DirCacheEntry> entriesOf(Repository repository, ObjectId tree)
      throws IOException {
    final Map<String, DirCacheEntry> entries = new TreeMap<>();
    try (TreeWalk walk = new TreeWalk(repository)) {
      walk.addTree(tree);
      walk.setRecursive(true);
      while (walk.next()) {
        final DirCacheEntry entry = new DirCacheEntry(walk.getPathString());
        entry.setFileMode(walk.getFileMode(0));
        entry.setObjectId(walk.getObjectId(0));
        entries.put(walk.getPathString(), entry);
      }
    }
    return entries;
  }

  private static boolean isUnder(String path, List<String> roots) {
    return roots.stream().anyMatch(root -> path.equals(root) || path.startsWith(root + "/"));
  }

  private static Repository openRepository(Path repositoryDir) throws IOException {
    return new FileRepositoryBuilder().setGitDir(repositoryDir.toFile()).build();
  }
}
