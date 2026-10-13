package io.seedmatic.rke2lab.manifests.bdd;

import io.seedmatic.rke2lab.manifests.RenderedSecretSeal;
import io.seedmatic.rke2lab.worktree.CommitSpec;
import io.seedmatic.rke2lab.worktree.GitIdentity;
import io.seedmatic.rke2lab.worktree.TreeEdit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

/** The rendered-branch delivery plan carried from the scenario into the THEN. */
record RenderDelivery(
    String message,
    GitIdentity identity,
    String signingKey,
    boolean push,
    Optional<String> token,
    RenderedSecretSeal secrets) {

  // What a worktree keeps for itself, the same rule every worktree of this project follows. The
  // render commits it into its branch, so a render in-cluster (no operator-side ignore) keeps its
  // intermediates out of the branch too.
  static final String WORKTREE_IGNORE =
      String.join("", RenderSoil.KEPT_LOCAL.stream().map(dir -> dir + "/\n").toList());

  // The cluster→git reflector's subtree on the managing branch — a reflector-owned path the render
  // never generates but PRESERVES across its wholesale regeneration (the escape carve-out).
  static final String REFLECTIONS_DIR = "reflections";

  /**
   * The same plan for another cluster's branch — identity, signing key and token are per-RUN (one
   * bot, one keystore read, one minted token), only the commit subject names the cluster. So the
   * manager's per-target passes derive their plans from the managing one instead of re-minting a
   * token per target.
   */
  RenderDelivery forBranchOf(String message) {
    return new RenderDelivery(message, identity, signingKey, push, token, secrets);
  }

  /**
   * Seal {@code soil} with this plan: write the worktree convention's {@code .gitignore} (its
   * intermediates stay local, out of the commit), seal every rendered Secret in memory under the
   * branch's {@code .sops.yaml} — an unchanged one keeps the ciphertext committed at HEAD, and the
   * delivery is refused if one stays in the clear — commit the whole rendered tree onto the head
   * SIGNED as the rke2lab bot, carrying {@code reflections/} over, and push it fast-forward only
   * when the operator armed the push AND the token was revealed (the gardening gate); otherwise
   * only the local branch moves. Behaviour of the plan itself, so the managing branch's THEN and
   * each per-target pass deliver through ONE path — a change to the push discipline has one site.
   */
  void seal(RenderSoil soil) {
    try {
      Files.writeString(soil.root().resolve(".gitignore"), WORKTREE_IGNORE);
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot write the render's .gitignore in " + soil.root(), ex);
    }
    secrets.seal(soil.root(), soil::readAtHead);
    soil.head()
        .commit(
            new TreeEdit.Replace(soil.files(), List.of(REFLECTIONS_DIR)),
            new CommitSpec(message, identity, Optional.of(signingKey)));
    soil.head().publish(push ? token : Optional.empty());
  }
}
