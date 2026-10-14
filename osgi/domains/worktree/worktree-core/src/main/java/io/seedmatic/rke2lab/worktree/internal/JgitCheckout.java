package io.seedmatic.rke2lab.worktree.internal;

import io.seedmatic.rke2lab.worktree.BuildRevision;
import io.seedmatic.rke2lab.worktree.CommitSpec;
import io.seedmatic.rke2lab.worktree.GitIdentity;
import io.seedmatic.rke2lab.worktree.Provenance;
import io.seedmatic.rke2lab.worktree.TreeEdit;
import io.seedmatic.rke2lab.worktree.WorkingState;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.attributes.Attributes;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;

/**
 * A jgit view of ONE git worktree at a path — the SOLE holder of jgit in this domain, and the
 * instance the seat composes rather than a static utility: {@link JgitWorktree} (the seed the
 * process runs inside) holds a checkout of its self-located root, and {@link
 * JgitTransientWorktrees} fetches through it. Each opens its repository on demand — provenance and
 * working state change as the tree is edited, so they are never cached. jgit stays sealed here;
 * nothing jgit crosses back to a caller.
 *
 * <p>Works equally on a main worktree and a LINKED one: a linked worktree's {@code .git} file
 * points at the shared common dir, which {@link FileRepositoryBuilder#findGitDir} resolves, so
 * provenance and commits act on the linked HEAD over the shared object database.
 */
final class JgitCheckout {

  /** Where a fabricated build revision is pinned — written here only, never pushed. */
  static final String GROW_REFS = "refs/rke2lab/grow/";

  private static final Duration BUILD_REF_RETENTION = Duration.ofDays(7);
  private static final String GROW_BOT_NAME = "rke2lab:grow";
  private static final String GROW_BOT_EMAIL = "rke2lab+grow@rke2lab.invalid";

  private final Path worktree;
  private final ContentGuards guards = new ContentGuards();

  JgitCheckout(Path worktree) {
    this.worktree = worktree;
  }

  Path root() {
    return worktree;
  }

  Provenance provenance() {
    try (Repository repository = open()) {
      final ObjectId head = repository.resolve(Constants.HEAD);
      if (head == null) {
        return new Provenance("", false);
      }
      return new Provenance(head.name(), !status(repository).isClean());
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read the provenance of " + worktree, ex);
    }
  }

  WorkingState workingState() {
    try (Repository repository = open()) {
      final Status status = status(repository);
      if (status.isClean()) {
        return new WorkingState(true, List.of());
      }
      return new WorkingState(false, uncommittedPaths(status));
    }
  }

  boolean flakeLockCoherent() {
    try (Repository repository = open()) {
      final ObjectId oldTree = repository.resolve("HEAD~1^{tree}");
      final ObjectId newTree = repository.resolve("HEAD^{tree}");
      if (oldTree == null || newTree == null) {
        return true; // no prior commit to diff against — nothing to judge, so coherent.
      }
      return violatingFlakeDirs(repository, oldTree, newTree).isEmpty();
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read the flake-lock coherence of " + worktree, ex);
    }
  }

  Path repository() {
    try (Repository repository = open()) {
      return repository.getCommonDirectory().toPath().toAbsolutePath().normalize();
    }
  }

  String treeDigest(String revision, List<String> paths) {
    try (Repository repository = open()) {
      final ObjectId tree = repository.resolve(revision + "^{tree}");
      if (tree == null) {
        throw new IllegalStateException("no revision " + revision + " to digest in " + worktree);
      }
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (TreeWalk walk = new TreeWalk(repository)) {
        walk.addTree(tree);
        walk.setRecursive(true);
        walk.setFilter(PathFilterGroup.createFromStrings(paths));
        while (walk.next()) {
          digest.update(walk.getPathString().getBytes(StandardCharsets.UTF_8));
          digest.update((byte) 0);
          digest.update(walk.getObjectId(0).name().getBytes(StandardCharsets.US_ASCII));
          digest.update((byte) '\n');
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot digest " + paths + " at " + revision, ex);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  BuildRevision buildRevision(List<String> paths) {
    try (Repository repository = open();
        RevWalk revWalk = new RevWalk(repository);
        ObjectInserter inserter = repository.newObjectInserter()) {
      sweepBuildRefs(repository, revWalk);
      final ObjectId head = repository.resolve(Constants.HEAD);
      if (head == null) {
        throw new IllegalStateException("no HEAD to build from in " + worktree);
      }
      final RevCommit headCommit = revWalk.parseCommit(head);
      final Map<String, DirCacheEntry> tree = entriesOf(repository, headCommit.getTree());
      final Map<String, DirCacheEntry> atHead = new TreeMap<>(tree);
      tree.keySet().removeIf(path -> isUnder(path, paths));

      record Capture(String path, FileMode mode, byte[] bytes) {}
      final List<Capture> captures = new ArrayList<>();
      final List<String> refusals = new ArrayList<>();
      try (TreeWalk walk = new TreeWalk(repository)) {
        walk.addTree(new FileTreeIterator(repository));
        walk.setRecursive(true);
        walk.setFilter(PathFilterGroup.createFromStrings(paths));
        walk.setOperationType(TreeWalk.OperationType.CHECKIN_OP);
        while (walk.next()) {
          if (walk.getTree(0, WorkingTreeIterator.class).isEntryIgnored()) {
            continue;
          }
          final String path = walk.getPathString();
          final FileMode mode = walk.getFileMode(0);
          if (isSopsMarked(walk.getAttributes())) {
            refusals.add(path + ": a sops filter/diff attribute");
            continue;
          }
          final byte[] bytes = readCaptured(path, mode, refusals);
          if (bytes == null) {
            continue;
          }
          if (holdsSopsEnvelope(bytes)) {
            refusals.add(path + ": a sops envelope");
          }
          refusals.addAll(guards.inspect(path, bytes));
          captures.add(new Capture(path, mode, bytes));
        }
      }
      if (!refusals.isEmpty()) {
        throw new IllegalStateException(
            "refusing to build the dirty seat — fix or commit these paths, then grow: "
                + String.join("; ", refusals));
      }

      final List<String> untracked = new ArrayList<>();
      final List<String> changed = new ArrayList<>();
      for (Capture capture : captures) {
        final ObjectId id = inserter.idFor(Constants.OBJ_BLOB, capture.bytes());
        final DirCacheEntry entry = new DirCacheEntry(capture.path());
        entry.setFileMode(capture.mode());
        entry.setObjectId(id);
        tree.put(capture.path(), entry);
        final DirCacheEntry before = atHead.get(capture.path());
        if (before == null) {
          untracked.add(capture.path());
          changed.add(capture.path());
        } else if (!before.getObjectId().equals(id)
            || !before.getFileMode().equals(capture.mode())) {
          changed.add(capture.path());
        }
      }
      atHead.keySet().stream()
          .filter(path -> isUnder(path, paths) && !tree.containsKey(path))
          .forEach(changed::add);
      if (changed.isEmpty()) {
        return new BuildRevision(head.name(), false, List.of(), List.of());
      }

      for (Capture capture : captures) {
        inserter.insert(Constants.OBJ_BLOB, capture.bytes());
      }
      final DirCache index = DirCache.newInCore();
      final DirCacheBuilder builder = index.builder();
      tree.values().forEach(builder::add);
      builder.finish();
      final CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(index.writeTree(inserter));
      commit.setParentId(head);
      final PersonIdent bot = new PersonIdent(GROW_BOT_NAME, GROW_BOT_EMAIL);
      commit.setAuthor(bot);
      commit.setCommitter(bot);
      commit.setMessage("grow: dirty " + String.join(", ", changed) + "\n");
      final ObjectId fabricated = inserter.insert(commit);
      inserter.flush();

      final RefUpdate pin = repository.updateRef(GROW_REFS + fabricated.name());
      pin.setNewObjectId(fabricated);
      pin.setExpectedOldObjectId(ObjectId.zeroId());
      final RefUpdate.Result result = pin.update(revWalk);
      if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.NO_CHANGE) {
        throw new IllegalStateException(
            "cannot pin " + GROW_REFS + fabricated.name() + ": " + result);
      }
      return new BuildRevision(fabricated.name(), true, changed, untracked);
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot build a revision of " + paths + " in " + worktree, ex);
    }
  }

  void releaseBuildRevision(BuildRevision revision) {
    if (!revision.fabricated()) {
      return;
    }
    try (Repository repository = open()) {
      deleteRef(repository, GROW_REFS + revision.sha());
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot release " + revision.sha() + " in " + worktree, ex);
    }
  }

  private void sweepBuildRefs(Repository repository, RevWalk revWalk) throws IOException {
    final long horizon = Instant.now().minus(BUILD_REF_RETENTION).getEpochSecond();
    for (Ref ref : repository.getRefDatabase().getRefsByPrefix(GROW_REFS)) {
      if (revWalk.parseCommit(ref.getObjectId()).getCommitTime() < horizon) {
        deleteRef(repository, ref.getName());
      }
    }
  }

  private static void deleteRef(Repository repository, String name) throws IOException {
    final RefUpdate drop = repository.updateRef(name);
    drop.setForceUpdate(true);
    final RefUpdate.Result result = drop.delete();
    if (result != RefUpdate.Result.FORCED && result != RefUpdate.Result.NO_CHANGE) {
      throw new IllegalStateException("cannot delete " + name + ": " + result);
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

  private static boolean isSopsMarked(Attributes attributes) {
    return Stream.of("filter", "diff")
        .map(attributes::getValue)
        .anyMatch(value -> value != null && value.startsWith("sops"));
  }

  private static boolean holdsSopsEnvelope(byte[] bytes) {
    final String text = new String(bytes, StandardCharsets.ISO_8859_1);
    return text.contains("ENC[") || text.lines().anyMatch(line -> line.equals("sops:"));
  }

  /** The bytes a captured entry contributes, or null with a refusal recorded. */
  private byte[] readCaptured(String path, FileMode mode, List<String> refusals)
      throws IOException {
    final Path file = worktree.resolve(path);
    if (mode == FileMode.SYMLINK) {
      final Path target = Files.readSymbolicLink(file);
      final Path resolved = file.getParent().resolve(target).normalize();
      if (!resolved.startsWith(worktree.toAbsolutePath().normalize())) {
        refusals.add(path + ": a symlink leaving the repository");
        return null;
      }
      return target.toString().getBytes(StandardCharsets.UTF_8);
    }
    if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) {
      refusals.add(path + ": neither a file nor a symlink (" + mode + ")");
      return null;
    }
    return Files.readAllBytes(file);
  }

  /** The seat's write verb: apply an {@link TreeEdit.Overlay} on disk, stage it, commit it. */
  String commit(TreeEdit edit, CommitSpec spec) {
    final TreeEdit.Overlay overlay =
        switch (edit) {
          case TreeEdit.Overlay laid -> laid;
          case TreeEdit.Replace replace ->
              throw new IllegalArgumentException(
                  "a durable worktree takes an Overlay: a Replace would empty " + worktree);
        };
    final List<Path> touched = new ArrayList<>();
    try {
      for (Map.Entry<String, byte[]> file : overlay.files().entrySet()) {
        final Path target = inside(file.getKey());
        Files.createDirectories(target.getParent());
        Files.write(target, file.getValue());
        touched.add(target);
      }
      for (String removed : overlay.removed()) {
        final Path target = inside(removed);
        Files.deleteIfExists(target);
        touched.add(target);
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot apply the edit to " + worktree, ex);
    }
    stage(touched);
    return commit(spec.message(), spec.identity(), spec.sshSigningKey());
  }

  private Path inside(String path) {
    final Path root = worktree.toAbsolutePath().normalize();
    final Path target = root.resolve(path).normalize();
    if (!target.startsWith(root) || target.equals(root)) {
      throw new IllegalArgumentException("an edit path must stay inside " + root + ": " + path);
    }
    return target;
  }

  private void stage(List<Path> paths) {
    try (Repository repository = open();
        Git git = new Git(repository)) {
      for (Path path : paths) {
        final Path absolute = (path.isAbsolute() ? path : worktree.resolve(path)).normalize();
        final String relative =
            worktree.relativize(absolute).toString().replace(File.separatorChar, '/');
        // The worktree ROOT relativizes to "" — jgit reads that as the whole tree with ".", so
        // staging root() stages every rendered file (adds and, via the recursive add, the tree).
        final String pattern = relative.isEmpty() ? "." : relative;
        if (Files.exists(absolute)) {
          git.add().addFilepattern(pattern).call();
        } else {
          git.rm().setCached(true).addFilepattern(pattern).call();
        }
      }
    } catch (GitAPIException ex) {
      throw new IllegalStateException("cannot stage paths in " + worktree, ex);
    }
  }

  private String commit(String message, GitIdentity identity, Optional<String> sshSigningKey) {
    try (Repository repository = open();
        Git git = new Git(repository)) {
      final CommitCommand commit =
          git.commit()
              .setMessage(message)
              .setAuthor(identity.name(), identity.email())
              .setCommitter(identity.name(), identity.email())
              // A render that reproduces the branch tip byte-for-byte must NOT accrete an empty
              // commit: every webhook/reconcile fires a render, so unchanged renders would churn
              // the branch with empty commits. Refuse it here and fall through to the unchanged
              // tip.
              .setAllowEmpty(false);
      // Sign with the caller's OWN key when supplied (git SSHSIG via ssh-keygen — jgit ships no ssh
      // signer, so we inject one), else force signing OFF so a repo `commit.gpgsign=true` /
      // `gpg.format=ssh` does not fail an unsigned bot commit.
      sshSigningKey
          .filter(key -> !key.isBlank())
          .ifPresentOrElse(
              key -> commit.setSign(true).setSigner(new SshCommitSigner(key)),
              () -> commit.setSign(false));
      return commit.call().getName();
    } catch (EmptyCommitException unchanged) {
      // Nothing changed vs the tip: the delivered sha IS the unchanged tip, and the follow-up push
      // is a fast-forward no-op (the ref is already there) rather than an empty-commit advance.
      return headSha();
    } catch (GitAPIException ex) {
      throw new IllegalStateException("cannot commit in " + worktree, ex);
    }
  }

  /** The current HEAD sha — the tip a caller falls back to when a render committed nothing new. */
  private String headSha() {
    try (Repository repository = open()) {
      final ObjectId head = repository.resolve(Constants.HEAD);
      if (head == null) {
        throw new IllegalStateException("no HEAD to return after an empty commit in " + worktree);
      }
      return head.name();
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read HEAD of " + worktree, ex);
    }
  }

  /**
   * Fast-forward the LOCAL {@code refs/heads/<branch>} to origin's tip, so a render accretes on the
   * branch's real (pushed) history instead of orphaning it. A render cut from a fresh clone
   * (in-cluster the Tekton {@code FETCH_HEAD} checkout) or an operator checkout that never tracked
   * the branch has only a remote ref — without this the head opens on a fresh null base, LOSING the
   * facet the grow recorded at HEAD (the mesh-drop footgun) and rejecting the follow-up
   * fast-forward push. Forced ({@code +src:dst}) so a stale or diverged local ref is reset to
   * origin: the render must sit on origin's tip for that push to land.
   *
   * <p>The credential is EXPLICIT: {@code x-access-token} with {@code token} when given, else a
   * provider that answers nothing, so jgit never falls back to a default one and no credential the
   * machine holds can answer an authentication challenge. Skipped when there is no {@code origin}
   * (a pure-local repo) or origin has no such branch (the genuine first render); any other failure
   * throws, because swallowing it would orphan the base exactly as above.
   */
  void fetchBranch(String branch, Optional<String> token, Duration timeout) {
    final String ref = Constants.R_HEADS + branch;
    final CredentialsProvider credentials =
        token
            .<CredentialsProvider>map(
                value -> new UsernamePasswordCredentialsProvider("x-access-token", value))
            .orElseGet(
                () ->
                    new CredentialsProvider() {
                      @Override
                      public boolean isInteractive() {
                        return false;
                      }

                      @Override
                      public boolean supports(CredentialItem... items) {
                        return false;
                      }

                      @Override
                      public boolean get(URIish uri, CredentialItem... items) {
                        return false;
                      }
                    });
    try (Repository repository = open();
        Git git = new Git(repository)) {
      if (repository.getConfig().getString("remote", Constants.DEFAULT_REMOTE_NAME, "url")
          == null) {
        return;
      }
      final boolean published =
          git
              .lsRemote()
              .setRemote(Constants.DEFAULT_REMOTE_NAME)
              .setHeads(true)
              .setCredentialsProvider(credentials)
              .setTimeout((int) timeout.toSeconds())
              .call()
              .stream()
              .anyMatch(remote -> ref.equals(remote.getName()));
      if (!published) {
        return;
      }
      git.fetch()
          .setRemote(Constants.DEFAULT_REMOTE_NAME)
          .setRefSpecs(new RefSpec("+" + ref + ":" + ref))
          .setCredentialsProvider(credentials)
          .setTimeout((int) timeout.toSeconds())
          .call();
    } catch (GitAPIException ex) {
      throw new IllegalStateException(
          "cannot fetch " + branch + " from origin into " + worktree, ex);
    }
  }

  /**
   * The flake directories whose {@code flake.nix} {@code inputs} block changed in the latest commit
   * WITHOUT a matching {@code flake.lock} change — the incoherence a clean worktree does NOT catch
   * (a clean tree can still commit an incoherent lock). Over the {@code HEAD~1..HEAD} tree diff.
   */
  private List<String> violatingFlakeDirs(
      Repository repository, ObjectId oldTree, ObjectId newTree) {
    final LinkedHashSet<String> flakeNixDirs = new LinkedHashSet<>();
    final LinkedHashSet<String> flakeLockDirs = new LinkedHashSet<>();
    for (DiffEntry diff : diffTrees(repository, oldTree, newTree)) {
      collectFlakeDir(diff.getOldPath(), flakeNixDirs, flakeLockDirs);
      collectFlakeDir(diff.getNewPath(), flakeNixDirs, flakeLockDirs);
    }
    final List<String> violating = new ArrayList<>();
    for (String dir : flakeNixDirs) {
      if (!flakeLockDirs.contains(dir) && inputsChanged(repository, oldTree, newTree, dir)) {
        violating.add(dir);
      }
    }
    return violating;
  }

  private List<DiffEntry> diffTrees(Repository repository, ObjectId oldTree, ObjectId newTree) {
    try (Git git = new Git(repository);
        ObjectReader reader = repository.newObjectReader()) {
      final CanonicalTreeParser oldParser = new CanonicalTreeParser();
      oldParser.reset(reader, oldTree);
      final CanonicalTreeParser newParser = new CanonicalTreeParser();
      newParser.reset(reader, newTree);
      return git.diff().setOldTree(oldParser).setNewTree(newParser).call();
    } catch (GitAPIException | IOException ex) {
      throw new IllegalStateException("cannot diff HEAD~1..HEAD of " + worktree, ex);
    }
  }

  private void collectFlakeDir(String path, Set<String> flakeNixDirs, Set<String> flakeLockDirs) {
    if (path == null || path.isBlank() || DiffEntry.DEV_NULL.equals(path)) {
      return;
    }
    if (path.equals("flake.nix") || path.endsWith("/flake.nix")) {
      flakeNixDirs.add(parentDir(path));
    } else if (path.equals("flake.lock") || path.endsWith("/flake.lock")) {
      flakeLockDirs.add(parentDir(path));
    }
  }

  private String parentDir(String path) {
    final int lastSlash = path.lastIndexOf('/');
    return lastSlash < 0 ? "." : path.substring(0, lastSlash);
  }

  private boolean inputsChanged(
      Repository repository, ObjectId oldTree, ObjectId newTree, String dir) {
    final String flakePath = ".".equals(dir) ? "flake.nix" : dir + "/flake.nix";
    return !inputsBlock(readTreeFile(repository, oldTree, flakePath))
        .equals(inputsBlock(readTreeFile(repository, newTree, flakePath)));
  }

  /**
   * The content of {@code path} as committed at {@code HEAD}, or empty when there is no HEAD (a
   * fresh repo / detached with no commit) or the path is absent from the HEAD tree. Reads the
   * COMMITTED blob — not the working-tree file — so it is unaffected by a dirty tree the caller is
   * about to overwrite (the render reads the branch's recorded facet before re-materialising it).
   */
  Optional<String> readAtHead(String path) {
    try (Repository repository = open()) {
      final ObjectId tree = repository.resolve("HEAD^{tree}");
      if (tree == null) {
        return Optional.empty();
      }
      final String content = readTreeFile(repository, tree, path);
      return content.isEmpty() ? Optional.empty() : Optional.of(content);
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read " + path + " at HEAD of " + worktree, ex);
    }
  }

  private String readTreeFile(Repository repository, ObjectId treeId, String path) {
    try {
      final TreeWalk walk = TreeWalk.forPath(repository, path, treeId);
      if (walk == null) {
        return "";
      }
      try (walk) {
        final ObjectLoader loader = repository.open(walk.getObjectId(0));
        return new String(loader.getBytes(), StandardCharsets.UTF_8);
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read " + path + " from a git tree of " + worktree, ex);
    }
  }

  /**
   * The normalised {@code inputs = { … }} block of a flake.nix, empty when absent — brace-matched
   * from the {@code inputs} binding so a change to {@code outputs}/description does NOT read as an
   * inputs change.
   */
  private String inputsBlock(String flakeNix) {
    if (flakeNix.isBlank()) {
      return "";
    }
    for (int at = flakeNix.indexOf("inputs"); at >= 0; at = flakeNix.indexOf("inputs", at + 1)) {
      if (!isWordBoundary(flakeNix, at - 1) || !isWordBoundary(flakeNix, at + "inputs".length())) {
        continue;
      }
      final int eq = skipWhitespaceTo(flakeNix, at + "inputs".length(), '=');
      final int open = eq < 0 ? -1 : skipWhitespaceTo(flakeNix, eq + 1, '{');
      final int close = open < 0 ? -1 : matchingBrace(flakeNix, open);
      if (close > open) {
        return flakeNix.substring(open, close + 1).replaceAll("\\s+", " ").trim();
      }
    }
    return "";
  }

  private int skipWhitespaceTo(String value, int start, char target) {
    int i = start;
    while (i < value.length() && Character.isWhitespace(value.charAt(i))) {
      i++;
    }
    return i < value.length() && value.charAt(i) == target ? i : -1;
  }

  private int matchingBrace(String value, int open) {
    int depth = 0;
    for (int i = open; i < value.length(); i++) {
      final char ch = value.charAt(i);
      if (ch == '{') {
        depth++;
      } else if (ch == '}' && --depth == 0) {
        return i;
      }
    }
    return -1;
  }

  private boolean isWordBoundary(String value, int index) {
    if (index < 0 || index >= value.length()) {
      return true;
    }
    final char ch = value.charAt(index);
    return !(Character.isLetterOrDigit(ch) || ch == '_' || ch == '-');
  }

  private Repository open() {
    final FileRepositoryBuilder builder = new FileRepositoryBuilder();
    builder.setWorkTree(worktree.toFile());
    builder.findGitDir(worktree.toFile());
    try {
      return builder.build();
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot open the git repository at " + worktree, ex);
    }
  }

  private static Status status(Repository repository) {
    try (Git git = new Git(repository)) {
      return git.status().call();
    } catch (GitAPIException ex) {
      throw new IllegalStateException("cannot read the git status", ex);
    }
  }

  private static List<String> uncommittedPaths(Status status) {
    final LinkedHashSet<String> paths = new LinkedHashSet<>();
    paths.addAll(status.getAdded());
    paths.addAll(status.getChanged());
    paths.addAll(status.getModified());
    paths.addAll(status.getRemoved());
    paths.addAll(status.getMissing());
    paths.addAll(status.getUntracked());
    paths.addAll(status.getConflicting());
    return new ArrayList<>(paths);
  }
}
