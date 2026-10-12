package io.seedmatic.rke2lab.incus.bdd;

import io.seedmatic.rke2lab.worktree.Worktree;
import java.util.List;

/**
 * What the node-base image is built FROM: the seat's HEAD as a nix flake ref read through the
 * seat's repository ({@code git+file://<repository>?rev=<sha>}, so a local commit builds with no
 * push and no working file reaches the store), and the content digest of that revision's build
 * inputs — ONE digest, read by the build script's freshness gate and folded into the plan's {@code
 * buildChecksum}.
 *
 * <p>The build is a REVISION, so an uncommitted edit to a build input would silently not be built:
 * {@link #of} refuses instead, naming the paths to commit.
 */
record NodeBaseSource(String flakeRef, String digest) {

  /** The paths {@code nixosConfigurations.rke2-node-base} evaluates from. */
  static final List<String> BUILD_INPUTS = List.of("flake.lock", "flake.nix", "nixos");

  static NodeBaseSource of(Worktree worktree) {
    final List<String> uncommitted =
        worktree.workingState().uncommittedPaths().stream()
            .filter(NodeBaseSource::isBuildInput)
            .toList();
    if (!uncommitted.isEmpty()) {
      throw new IllegalStateException(
          "the node-base image is built from the committed HEAD, and these build inputs are not"
              + " committed: "
              + uncommitted
              + " — commit them, then grow");
    }
    final String sha = worktree.provenance().sha();
    if (sha.isBlank()) {
      throw new IllegalStateException("the seat has no HEAD to build the node-base image from");
    }
    return new NodeBaseSource(
        "git+file://" + worktree.repository() + "?rev=" + sha,
        worktree.treeDigestAtHead(BUILD_INPUTS));
  }

  private static boolean isBuildInput(String path) {
    return BUILD_INPUTS.stream()
        .anyMatch(input -> path.equals(input) || path.startsWith(input + "/"));
  }
}
