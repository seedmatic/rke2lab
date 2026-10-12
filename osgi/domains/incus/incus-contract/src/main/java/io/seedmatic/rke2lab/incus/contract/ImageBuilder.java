package io.seedmatic.rke2lab.incus.contract;

/**
 * The incus domain's image-build contact seam: produce the seed image's Incus artifacts ({@code
 * incus.tar.xz} + {@code rootfs.squashfs}) in the request's artifact directory. The {@code
 * incus-image-edge} provides it by running the bundled nix build script locally; nix offloads the
 * aarch64-linux build to its own configured builder. The host keeps the Pulumi {@code Image}
 * resource, the artifact cache, and the config-derived path translation, and composes this contact
 * only when a rebuild is actually needed.
 *
 * <p>The grain is coarse — one call runs a whole build — but it owns no caching and no policy: it
 * builds when asked and throws on failure.
 */
public interface ImageBuilder {

  /**
   * Build the artifacts described by {@code request}, blocking until they land in the artifact
   * directory. Returns normally on success; on failure it THROWS an unchecked exception carrying a
   * human message (binary missing, a non-zero build exit) AND, where the failure wraps one, the
   * cause — so the caller surfaces both the reason and the stack, rather than a bare summary
   * string. A surveying builder plans only and returns normally without touching anything.
   */
  void build(ImageBuildRequest request);

  /**
   * A stable digest of the edge-owned build recipe (the bundled build script). The host folds this
   * into its own image-cache key so that a recipe change invalidates the cache and republishes the
   * image fingerprint, without the host needing to hold the script. Deterministic across calls for
   * a given edge build.
   */
  String recipeDigest();
}
