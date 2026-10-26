package io.seedmatic.rke2lab.incus.edge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The edge-owned image-build recipe — the {@code build-node-base-image.sh} nix driver (a bundle
 * resource). The single owner of the recipe bytes: the {@link #digest()} folds it, so a change to
 * the driver invalidates the host's image cache, and it is the SAME digest whether the run
 * cultivates (builds for real) or surveys (plans only) — the host cache key must not move between
 * the two. Shared by the {@code Cultivating}/{@code Surveying} builder pair so neither owns the
 * resource plumbing alone.
 *
 * <p>The digest captures the build METHOD (how nix is invoked), not the image CONTENT: what {@code
 * nixosConfigurations.rke2-node-server} evaluates to is determined by {@code flake.lock} + the
 * {@code nixos/} modules, which the scion folds into the {@code buildChecksum} separately (it holds
 * the worktree; the edge only holds bundle resources).
 */
final class BuildRecipe {

  static final String NIX_BUILD_SCRIPT_RESOURCE =
      "io/seedmatic/rke2lab/incus/edge/build-node-base-image.sh";

  /** A stable SHA-256 over the nix build script — the host's image-cache key, mode-invariant. */
  String digest() {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(load(NIX_BUILD_SCRIPT_RESOURCE));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new ImageBuildException("SHA-256 is not available", ex);
    }
  }

  byte[] load(String resource) {
    try (var in = getClass().getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new ImageBuildException("Bundle resource not found: " + resource);
      }
      return in.readAllBytes();
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to load bundle resource: " + resource, ex);
    }
  }
}
