package io.seedmatic.rke2lab.incus.contract;

/**
 * The flat coordinates an {@link ImageBuilder} needs to build the seed image's Incus artifacts,
 * projected by the scion. A seam record — no host config type crosses; every field is a scalar.
 *
 * <p>{@code flakeRef} names a REVISION, never a working tree: {@code
 * git+file://<repository>?rev=<sha>}, the worktree's commit read through its common dir, so nix
 * builds exactly what was committed and no working file (decrypted or not) can reach the store.
 * {@code sourceDigest} is the content digest of that revision's build inputs, the script's
 * freshness gate. {@code artifactDir} is absolute.
 *
 * <p>The build SCRIPT is NOT a coordinate here: the edge owns it as a bundle resource (the single
 * source of the recipe, folded into {@code recipeDigest()}).
 */
public record ImageBuildRequest(
    String builderBinary, String flakeRef, String sourceDigest, String artifactDir) {

  public ImageBuildRequest {
    builderBinary = normalize(builderBinary);
    flakeRef = normalize(flakeRef);
    sourceDigest = normalize(sourceDigest);
    artifactDir = normalize(artifactDir);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
