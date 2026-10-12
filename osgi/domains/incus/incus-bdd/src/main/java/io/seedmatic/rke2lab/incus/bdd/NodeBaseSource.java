package io.seedmatic.rke2lab.incus.bdd;

import io.seedmatic.rke2lab.worktree.BuildRevision;
import io.seedmatic.rke2lab.worktree.Worktree;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the node-base image is built FROM: a REVISION of the seat, as a nix flake ref read through
 * the seat's repository ({@code git+file://<repository>?rev=<sha>}, so a local commit builds with
 * no push and no working file reaches the store), and the content digest of that revision's build
 * inputs — ONE digest, read by the build script's freshness gate and folded into the plan's {@code
 * buildChecksum}.
 *
 * <p>The revision is the seat's HEAD when the build inputs are clean; when they are dirty, the seat
 * fabricates one ({@link Worktree#buildRevision}) — the grow tests what is on disk, still by
 * revision. The rule is enforced here: a source whose flake ref names anything but a revision does
 * not exist.
 */
record NodeBaseSource(BuildRevision revision, String flakeRef, String digest) {

  /** The paths {@code nixosConfigurations.rke2-node-base} evaluates from. */
  static final List<String> BUILD_INPUTS = List.of("flake.lock", "flake.nix", "nixos");

  private static final Pattern BY_REVISION =
      Pattern.compile("git\\+file://[^?#]+\\?rev=[0-9a-f]{40}");

  NodeBaseSource {
    if (!BY_REVISION.matcher(flakeRef).matches()) {
      throw new IllegalStateException(
          "refusing to build the node-base image from "
              + flakeRef
              + ": nix builds only a revision, git+file://<repository>?rev=<40-hex sha>");
    }
  }

  static NodeBaseSource of(Worktree seat) {
    final BuildRevision revision = seat.buildRevision(BUILD_INPUTS);
    return new NodeBaseSource(
        revision,
        "git+file://" + seat.repository() + "?rev=" + revision.sha(),
        seat.treeDigest(revision.sha(), BUILD_INPUTS));
  }

  /** The revision as the runbook names it: its sha and, when fabricated, what made it dirty. */
  String provenance() {
    final String sha = revision.sha().substring(0, 12);
    if (!revision.fabricated()) {
      return sha;
    }
    final String untracked =
        revision.untracked().isEmpty()
            ? ""
            : " — untracked: " + String.join(", ", revision.untracked());
    return sha + " (dirty: " + String.join(", ", revision.changed()) + untracked + ")";
  }
}
