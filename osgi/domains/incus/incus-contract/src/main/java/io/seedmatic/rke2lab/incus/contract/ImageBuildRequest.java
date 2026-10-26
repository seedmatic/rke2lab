package io.seedmatic.rke2lab.incus.contract;

/**
 * The flat coordinates an {@link ImageBuilder} needs to build the seed image's Incus artifacts,
 * projected by the scion. A seam record — no host config type crosses; every field is a scalar.
 *
 * <p>{@code flakeRef} names a REVISION, never a working tree: {@code
 * git+file://<repository>?rev=<sha>}, the worktree's commit read through its common dir, so nix
 * builds exactly what was committed and no working file (decrypted or not) can reach the store.
 * {@code sourceDigest} is the content digest of that revision's build inputs, the script's
 * freshness gate. {@code artifactDir} is absolute. {@code configuration} names the node image the
 * call builds ({@code rke2-node-server} or {@code rke2-node-base}, the agent): one image per
 * request, each in its own dir.
 *
 * <p>The build SCRIPT is NOT a coordinate here: the edge owns it as a bundle resource (the single
 * source of the recipe, folded into {@code recipeDigest()}).
 */
public record ImageBuildRequest(
    String builderBinary,
    String flakeRef,
    String sourceDigest,
    String artifactDir,
    String configuration) {

  /**
   * The server image (the root and every control plane): {@code
   * nixosConfigurations.rke2-node-server}.
   */
  public static final String SERVER_CONFIGURATION = "rke2-node-server";

  /** The agent image (the worker pools): {@code nixosConfigurations.rke2-node-base}. */
  public static final String AGENT_CONFIGURATION = "rke2-node-base";

  public ImageBuildRequest {
    builderBinary = normalize(builderBinary);
    flakeRef = normalize(flakeRef);
    sourceDigest = normalize(sourceDigest);
    artifactDir = normalize(artifactDir);
    configuration = normalize(configuration);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
