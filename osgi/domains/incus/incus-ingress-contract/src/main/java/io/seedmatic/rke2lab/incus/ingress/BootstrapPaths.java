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
 * stateRoot}, from which {@link #renderRoot} and {@link #liveRoot} derive), and the {@code
 * secretsFile}. Felix is embedded in the host JVM, so the scion computes the whole topology
 * OSGi-side, here. {@link #fromLocalWorktree} builds the DARWIN-local view; {@link
 * #asAutomountView} rebases onto the automount view the remote NixOS host reads through.
 */
public record BootstrapPaths(Path worktreeRoot, Path stateRoot, Path secretsFile) {

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
    return builder()
        .worktreeRoot(worktreeRoot)
        .stateRoot(worktreeRoot.resolve(STATE_DIR))
        .secretsFile(worktreeRoot.resolve(".secrets"))
        .build();
  }

  /**
   * The root the per-cluster RENDERED trees live under — {@code .local.d/render}. Each cluster gets
   * an orphan {@code manifests/<cluster>} worktree at {@code renderRoot()/<cluster>} that the
   * manifests scion prepares, materialises the rendered YAML into, and force-pushes from. It
   * replaces the old rotating staging slot: a rendered branch is regenerated in place and delivered
   * through git, so there is no slot rotation and no {@code host.live.d} promotion to size it for.
   */
  public Path renderRoot() {
    return stateRoot.resolve("render");
  }

  /**
   * The fixed {@code host.live.d} off {@code stateRoot} the host writes the run's narration (the
   * runbook) into, post-run. A fixed path derived from the flat scalars alone.
   */
  public Path liveRoot() {
    return stateRoot.resolve("host.live.d");
  }

  /**
   * The layout rebased onto the automount view the remote NIXOS host mounts the assets from.
   * Provisioning is BI-MACHINE: Felix (on the Mac) WRITES the assets under the DARWIN-local paths,
   * the remote NIXOS host reads them over its automount. When {@code automount} is off the paths
   * are unchanged (same machine); otherwise each absolute path is rebased under {@code netPrefix}
   * (e.g. {@code /net/<cluster>.<tailnet>}, the tailscale MagicDNS FQDN).
   */
  public BootstrapPaths asAutomountView(boolean automount, String netPrefix) {
    return builder()
        .worktreeRoot(automountPath(worktreeRoot, automount, netPrefix))
        .stateRoot(automountPath(stateRoot, automount, netPrefix))
        .secretsFile(automountPath(secretsFile, automount, netPrefix))
        .build();
  }

  /**
   * Rebase one absolute path onto the automount view. With automount off (or a path already under
   * {@code /net/}) the path is returned unchanged; any absolute path is rebased under {@code
   * netPrefix} (host-agnostic, the input root is already canonicalised at the source via {@link
   * io.seedmatic.rke2lab.worktree.Worktree}).
   */
  Path automountPath(Path rawPath, boolean automount, String netPrefix) {
    final Path normalized = rawPath.toAbsolutePath().normalize();
    if (!automount) {
      return normalized;
    }
    final String path = normalized.toString();
    if (path.startsWith("/net/")) {
      return normalized;
    }
    return Path.of(netPrefix + path).normalize();
  }

  private static final class Builder {
    @MonotonicNonNull private Path worktreeRoot;
    @MonotonicNonNull private Path stateRoot;
    @MonotonicNonNull private Path secretsFile;

    private Builder worktreeRoot(Path value) {
      this.worktreeRoot = value;
      return this;
    }

    private Builder stateRoot(Path value) {
      this.stateRoot = value;
      return this;
    }

    private Builder secretsFile(Path value) {
      this.secretsFile = value;
      return this;
    }

    private BootstrapPaths build() {
      return new BootstrapPaths(
          Objects.requireNonNull(worktreeRoot),
          Objects.requireNonNull(stateRoot),
          Objects.requireNonNull(secretsFile));
    }
  }
}
