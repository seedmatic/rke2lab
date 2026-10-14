package io.seedmatic.rke2lab.manifests.contract;

import java.nio.file.Path;

/**
 * The node-side bootstrap artifact the exploder writes and the synthesis scion reads back — an enum
 * so its location is one instance both ends resolve through, never a static path helper (the
 * instance-discipline law: pass instances, not static behaviour). It sits in the exploded tree's
 * {@link #LOCAL_DIR}, like the consolidated {@code manifests.yaml}: inside the render's own
 * worktree but ignored by git, the way any worktree keeps what is its own (dog-fooding the worktree
 * convention), so it is never part of the committed/applied per-resource branch tree.
 */
public enum NodeBootstrapArtifact {

  /**
   * The single multi-doc file the exploder collects the {@link ManifestAnnotation#NODE_BOOTSTRAP}
   * resources into, for the host to seed onto the node over devlxd (see the {@code
   * rke2lab-server-manifests} guest unit).
   */
  MANIFESTS(".bootstrap", "rke2lab-bootstrap.yaml");

  /**
   * The rendered tree's local directory — the render's intermediates (this artifact, the
   * consolidated {@code manifests.yaml}). Ignored by the {@code .gitignore} the render commits into
   * its branch, so a render in-cluster, with no operator-side ignore, keeps them out as well.
   */
  public static final String LOCAL_DIR = ".local.d";

  private final String dir;
  private final String file;

  NodeBootstrapArtifact(String dir, String file) {
    this.dir = dir;
    this.file = file;
  }

  /**
   * This artifact's path for a given exploded tree — under its {@link #LOCAL_DIR}, so each render
   * keeps its own (two passes never share it) and git never tracks it.
   */
  public Path in(final Path explodedTargetDir) {
    return explodedTargetDir.resolve(LOCAL_DIR).resolve(dir).resolve(file);
  }
}
