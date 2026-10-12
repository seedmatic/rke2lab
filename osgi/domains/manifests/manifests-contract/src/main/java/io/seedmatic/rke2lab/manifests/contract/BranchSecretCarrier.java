package io.seedmatic.rke2lab.manifests.contract;

import java.nio.file.Path;

/**
 * A Secret the synthesis scion itself writes into a rendered branch, beside the exploded resources
 * — an enum so its name is one instance the writer, the reader and the seal's proof all resolve
 * through. Dot-named, so Flux never applies it; a Secret all the same, so the render seals it like
 * any other before the branch is committed.
 */
public enum BranchSecretCarrier {
  /**
   * The in-cluster cellar asset: the sealed materials that must reach an in-cluster render, read
   * back from the branch HEAD by the next render (§ in-cluster-cellar-asset).
   */
  IN_CLUSTER_CELLAR(".secret-in-cluster-cellar.yml");

  private final String file;

  BranchSecretCarrier(String file) {
    this.file = file;
  }

  /** The carrier's path relative to the rendered tree's root, as git names it. */
  public String file() {
    return file;
  }

  /** The carrier's path in a given rendered tree. */
  public Path in(final Path renderedTree) {
    return renderedTree.resolve(file);
  }
}
