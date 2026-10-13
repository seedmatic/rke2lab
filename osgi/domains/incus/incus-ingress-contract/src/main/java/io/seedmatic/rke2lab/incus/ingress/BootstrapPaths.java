package io.seedmatic.rke2lab.incus.ingress;

import java.nio.file.Path;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * The provisioning topology of the control node — the roots the bootstrap resolves once from the
 * worktree scalars and carries through the run. The NixOS {@code node-base} substrate bakes the
 * node's config, systemd units and scripts into the image, so the former {@code /srv/host} mount
 * catalog and the host-asset materialisation roots are gone; and the rendered-branch model moved
 * the manifests delivery to git, so the staging-slot rebase and its {@code manifestsRoot}/{@code
 * assetsRoot} went with it. What remains is the worktree base, the per-node state tree ({@code
 * stateRoot}, from which {@link #liveRoot} derives). Felix is embedded in the host JVM, so the
 * scion computes the whole topology OSGi-side, here. {@link #fromLocalWorktree} builds it.
 */
public record BootstrapPaths(Path worktreeRoot, Path stateRoot) {

  private static Builder builder() {
    return new Builder();
  }

  /**
   * The per-run state dir under the worktree root — {@code .local.d}, the DARWIN-local convention
   * this class lays out. Named once here (the single source) rather than spelled as a literal at
   * every site that reaches into the state tree (the host log file, the kubeconfig ref, …).
   */
  public static final String STATE_DIR = ".local.d";

  /**
   * The DARWIN-local layout: {@code .local.d/} owns the whole state tree at the root. There is
   * exactly ONE management cluster (single-node, it travels between bare-metals), so the state root
   * carries no {@code <cluster>/<node>} segment — one short {@code cd .local.d} lands in it, and no
   * {@code var/{run,lib}/incus/...} split to translate.
   */
  public static BootstrapPaths fromLocalWorktree(Path worktreeRoot) {
    return builder().worktreeRoot(worktreeRoot).stateRoot(worktreeRoot.resolve(STATE_DIR)).build();
  }

  /**
   * The fixed {@code host.live.d} off {@code stateRoot} the host writes the run's narration (the
   * runbook) into, post-run. A fixed path derived from the flat scalars alone.
   */
  public Path liveRoot() {
    return stateRoot.resolve("host.live.d");
  }

  private static final class Builder {
    @MonotonicNonNull private Path worktreeRoot;
    @MonotonicNonNull private Path stateRoot;

    private Builder worktreeRoot(Path value) {
      this.worktreeRoot = value;
      return this;
    }

    private Builder stateRoot(Path value) {
      this.stateRoot = value;
      return this;
    }

    private BootstrapPaths build() {
      return new BootstrapPaths(
          Objects.requireNonNull(worktreeRoot), Objects.requireNonNull(stateRoot));
    }
  }
}
