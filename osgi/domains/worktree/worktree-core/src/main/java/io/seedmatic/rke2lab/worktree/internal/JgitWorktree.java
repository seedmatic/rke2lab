package io.seedmatic.rke2lab.worktree.internal;

import io.seedmatic.rke2lab.worktree.BuildRevision;
import io.seedmatic.rke2lab.worktree.GitIdentity;
import io.seedmatic.rke2lab.worktree.Provenance;
import io.seedmatic.rke2lab.worktree.WorkingState;
import io.seedmatic.rke2lab.worktree.Worktree;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

/**
 * The {@link Worktree} the seed IS — a thin {@code @Component} that self-locates its root once at
 * activation (walking up from the process directory to the enclosing {@code .git}) and holds a
 * {@link JgitCheckout} of it, to which every git fact and write verb delegates. The self-location
 * is the only thing this component adds over a plain checkout: the seed process runs INSIDE its
 * worktree, so its root is discovered, not supplied. jgit stays sealed inside the checkout
 * instance; the exported {@link Worktree} interface carries only JDK types and the contract's
 * records.
 */
@Component(service = Worktree.class)
public final class JgitWorktree implements Worktree {

  private final JgitCheckout checkout;

  @Activate
  public JgitWorktree() {
    try {
      this.checkout = new JgitCheckout(locateFrom(Path.of("").toAbsolutePath()).toRealPath());
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot canonicalise the worktree root", ex);
    }
  }

  @Override
  public Path root() {
    return checkout.root();
  }

  @Override
  public Provenance provenance() {
    return checkout.provenance();
  }

  @Override
  public WorkingState workingState() {
    return checkout.workingState();
  }

  @Override
  public Optional<String> readAtHead(String path) {
    return checkout.readAtHead(path);
  }

  @Override
  public boolean flakeLockCoherent() {
    return checkout.flakeLockCoherent();
  }

  @Override
  public Path repository() {
    return checkout.repository();
  }

  @Override
  public BuildRevision buildRevision(List<String> paths) {
    return checkout.buildRevision(paths);
  }

  @Override
  public void releaseBuildRevision(BuildRevision revision) {
    checkout.releaseBuildRevision(revision);
  }

  @Override
  public String treeDigest(String revision, List<String> paths) {
    return checkout.treeDigest(revision, paths);
  }

  @Override
  public void stage(List<Path> paths) {
    checkout.stage(paths);
  }

  @Override
  public String commit(String message, GitIdentity identity, Optional<String> sshSigningKey) {
    return checkout.commit(message, identity, sshSigningKey);
  }

  /** Walk up from {@code startDir} to the enclosing {@code .git} and report its working tree. */
  private static Path locateFrom(Path startDir) {
    final FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(startDir.toFile());
    if (builder.getGitDir() == null) {
      throw new IllegalStateException("no git worktree encloses " + startDir);
    }
    try (Repository repository = builder.build()) {
      final File workTree = repository.getWorkTree();
      if (workTree == null) {
        throw new IllegalStateException("no worktree for the git dir found from " + startDir);
      }
      return workTree.toPath().toAbsolutePath().normalize();
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot locate the worktree enclosing " + startDir, ex);
    }
  }
}
