package io.seedmatic.rke2lab.worktree.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The {@code git} binary for ONE repository's LOCAL operations — an instance bound to that repo's
 * directory. Shelled where jgit cannot do the job: it exposes no worktree porcelain (verified
 * against 7.7.x: it can OPEN a linked worktree but not CREATE the {@code .git/worktrees/<name>}
 * administrative files), so {@code worktree add / remove / prune / list} run here; and a {@link
 * #restoreFromHead checkout} must run the configured {@code sops-yaml} smudge filter, which only
 * the binary does. The other verbs it carries — emptying a linked worktree, seeding its null base,
 * staging it whole — sit with the worktree add they follow, and are local too.
 *
 * <p>Nothing here reaches a remote. The binary reads the machine's git config, so a fetch through
 * it would answer an authentication challenge with whatever credential helper that config names —
 * on an operator's machine, a personal token. The domain's one network read, fetching a branch from
 * origin, is jgit's ({@link JgitCheckout#fetchBranch}) with an explicit credential, as its push is.
 *
 * <p>The single spot that runs {@code git} as a subprocess in this domain, the way {@link
 * SshCommitSigner} is the single spot that shells {@code ssh-keygen}. Constructed by {@link
 * JgitLinkedWorktrees} from the seed's root and threaded into the {@link JgitLinkedWorktree} it
 * makes, so add (at prepare) and remove (at close) run against the same repo.
 */
final class GitCli {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final Path repoDir;

  GitCli(Path repoDir) {
    this.repoDir = repoDir;
  }

  /**
   * Free {@code branch} for a checkout at {@code worktreePath}. Idempotent: any prior worktree at
   * the path is removed and pruned. A branch can live in only ONE worktree, so we also INTROSPECT
   * where it is currently checked out and release that worktree if it sits elsewhere — a render
   * leaf renamed out from under us (e.g. the branch renamed on the remote, leaving an old path
   * holding it) would otherwise make {@code worktree add} fail {@code "already used by worktree at
   * …"}. The branch itself survives (worktree remove ≠ branch delete), so its accretion history is
   * reused; only the stale checkout is dropped. Run before the branch is fetched, so the fetch
   * never targets a checked-out ref.
   */
  void release(Path worktreePath, String branch) {
    final String path = worktreePath.toString();
    worktreeOf(branch)
        .filter(existing -> !existing.equals(worktreePath))
        .ifPresent(
            existing -> {
              run(false, "worktree", "remove", "--force", existing.toString());
              run(false, "worktree", "prune");
            });
    run(false, "worktree", "remove", "--force", path);
    run(false, "worktree", "prune");
  }

  /**
   * Add a linked worktree at {@code worktreePath} on the EXISTING {@code branch} — its tip is
   * checked out as the accretion parent — and {@link #empty} it.
   */
  void worktreeAdd(Path worktreePath, String branch) {
    run(true, "worktree", "add", worktreePath.toString(), branch);
    empty(worktreePath);
  }

  /**
   * Add a linked worktree at {@code worktreePath} on a NEW {@code branch}, seeded with a {@link
   * #seedNullBase base commit} — a shared root Flux can point at and every later render commits ON
   * TOP of (accretion, not orphan-per-render) — and {@link #empty} it.
   */
  void worktreeAddOnNullBase(Path worktreePath, String branch) {
    run(true, "worktree", "add", "--orphan", "-b", branch, worktreePath.toString());
    seedNullBase(worktreePath, branch);
    empty(worktreePath);
  }

  /**
   * Empty the working tree (keep HEAD) so the render starts clean and a manifest dropped since the
   * previous render stages as a deletion.
   */
  private void empty(Path worktreePath) {
    run(false, "-C", worktreePath.toString(), "rm", "-rf", "--quiet", "--ignore-unmatch", ".");
  }

  /**
   * Seed {@code branch}'s stable base commit with a one-file marker — NOT git's implicit empty
   * tree. jgit's {@code PackWriter} opens a commit's tree PHYSICALLY when preparing a push and
   * throws "Missing tree 4b825dc…" on the empty tree (git keeps it implicit), so an empty base
   * would break EVERY push of a branch grown on it, GitHub included. A README keeps the base
   * packable; the render empties it, so the marker lives only in this base commit. Its identity is
   * immaterial — the render commits on top carry the real bot identity + SSH signature.
   */
  private void seedNullBase(Path worktreePath, String branch) {
    final String readme =
        "# "
            + branch
            + "\n\nMachine-rendered branch — accreted and force-advanced by the rke2lab manifests"
            + " render. Do not edit by hand.\n";
    try {
      Files.writeString(worktreePath.resolve("README.md"), readme, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot seed the base of " + branch, ex);
    }
    final String path = worktreePath.toString();
    run(true, "-C", path, "add", "README.md");
    run(
        true,
        "-C",
        path,
        "-c",
        "user.name=rke2lab",
        "-c",
        "user.email=rke2lab@localhost",
        "commit",
        "--no-gpg-sign",
        "-m",
        "init " + branch);
  }

  /**
   * Stage EVERYTHING in the worktree — additions, modifications, AND deletions ({@code add -A}).
   */
  void addAll(Path worktreePath) {
    run(true, "-C", worktreePath.toString(), "add", "-A");
  }

  /**
   * Restore {@code relPath} from HEAD into the working tree ({@code git checkout HEAD -- <path>}) —
   * a checkout, so it runs the configured {@code sops-yaml} smudge filter and the file lands
   * DECRYPTED. The caller has already confirmed the path is committed at HEAD (jgit read its blob),
   * so a non-zero exit is a real defect, not the missing-path case.
   */
  void restoreFromHead(Path worktreePath, String relPath) {
    run(true, "-C", worktreePath.toString(), "checkout", "HEAD", "--", relPath);
  }

  /**
   * Restore {@code pathspec} from HEAD into the working tree, TOLERANT of an unmatched pathspec
   * (the path absent at HEAD → a no-op). For preserving a whole directory (e.g. the reflector's
   * {@code reflections/}) across the wholesale render's {@code rm -rf .}, where the directory may
   * not exist yet on a first render.
   */
  void restoreFromHeadTolerant(Path worktreePath, String pathspec) {
    run(false, "-C", worktreePath.toString(), "checkout", "HEAD", "--", pathspec);
  }

  /** Remove the linked worktree at {@code worktreePath} — tolerant of a path already gone. */
  void worktreeRemove(Path worktreePath) {
    run(false, "worktree", "remove", "--force", worktreePath.toString());
  }

  /**
   * The worktree {@code branch} is currently checked out in, if any — parsed from {@code git
   * worktree list --porcelain} (blocks of {@code worktree <path>} … {@code branch
   * refs/heads/<name>}). A branch lives in at most one worktree, so the first match is
   * authoritative; empty when none holds it (never materialised, or only the bare ref).
   */
  private Optional<Path> worktreeOf(String branch) {
    final String marker = "branch refs/heads/" + branch;
    Path current = null;
    for (final String line : capture("worktree", "list", "--porcelain").split("\n")) {
      final String entry = line.strip();
      if (entry.startsWith("worktree ")) {
        current = Path.of(entry.substring("worktree ".length()));
      } else if (entry.equals(marker) && current != null) {
        return Optional.of(current.toAbsolutePath().normalize());
      }
    }
    return Optional.empty();
  }

  private int run(boolean check, String... args) {
    final Result result = exec(args);
    if (check && result.exit() != 0) {
      throw new IllegalStateException(
          "git "
              + String.join(" ", args)
              + " failed ("
              + result.exit()
              + "): "
              + result.output().trim());
    }
    return result.exit();
  }

  /** Run git and return its stdout (stderr merged), throwing on a non-zero exit. */
  private String capture(String... args) {
    final Result result = exec(args);
    if (result.exit() != 0) {
      throw new IllegalStateException(
          "git "
              + String.join(" ", args)
              + " failed ("
              + result.exit()
              + "): "
              + result.output().trim());
    }
    return result.output();
  }

  private Result exec(String... args) {
    final List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(args));
    try {
      final Process process =
          new ProcessBuilder(command).directory(repoDir.toFile()).redirectErrorStream(true).start();
      final byte[] output = process.getInputStream().readAllBytes();
      if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException(String.join(" ", command) + " timed out after " + TIMEOUT);
      }
      return new Result(process.exitValue(), new String(output, StandardCharsets.UTF_8));
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot run " + String.join(" ", command), ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while running " + String.join(" ", command), ex);
    }
  }

  private record Result(int exit, String output) {}
}
