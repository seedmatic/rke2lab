package io.seedmatic.rke2lab.manifests;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The RepoTags of the two OCI images baked into the node — the flox carrier every flox-injected pod
 * runs as its base, and the flox-controller node agent. READ from {@code /image-refs/}, which the
 * flake stages into this bundle exactly as it stages {@code /crds/} and {@code /rbac/}.
 *
 * <p>★ Read, never written down. Both tags are derived from the image's CONTENT (dockerTools'
 * output hash, the explicit tag omitted), so no literal can be right across a rebuild. They used to
 * be static — {@code rke2lab/flox-carrier:0.1.0} and {@code
 * io.seedmatic.flox-controller:0.0.0-develop} — held as constants on {@code FloxDebugPolicy} under
 * a javadoc pleading that they "MUST match the RepoTag the nix image is tagged with". The plea was
 * the design: nothing enforced it, and one of the two lived in a different repository's {@code
 * VERSION} file.
 *
 * <p>⚠️ What the static tags actually cost: the images reach a node through the air-gap path (the
 * tar is baked into the node image, rke2 auto-imports it into containerd at boot) and pods
 * reference them with {@code imagePullPolicy: IfNotPresent}. A node already holding a RepoTag
 * therefore NEVER replaces it — so a rebuilt controller shipped inside the node image while every
 * pod kept running the old binary, with nothing reporting it. A content-derived tag makes {@code
 * IfNotPresent} correct instead of a trap, and leaves the previous tag in place for a rollback.
 *
 * <p>Why this is not on {@code FloxDebugPolicy} any more: a base image is not a debug toggle. The
 * refs are facts of the BUILD, so they belong to the synthesis, reached through {@link
 * ManifestSynthesisContext#containerImages()}.
 */
public final class ContainerImageRefs {

  /**
   * Staged by {@code nix run .#stage-image-refs} (and by {@code seedOutclusterJar} for a release).
   */
  private static final String CARRIER_REF_RESOURCE = "/image-refs/flox-carrier";

  private static final String FLOX_CONTROLLER_REF_RESOURCE = "/image-refs/flox-controller";

  private static final ContainerImageRefs STAGED = new ContainerImageRefs();

  private ContainerImageRefs() {}

  /** The refs staged into this bundle by the build. */
  public static ContainerImageRefs staged() {
    return STAGED;
  }

  /**
   * The flox carrier's RepoTag — the base image of every flox-injected pod (prod AND debug share
   * one carrier: the prod/debug distinction lives in the flox ENV, not the base image).
   */
  public String carrier() {
    return read(CARRIER_REF_RESOURCE);
  }

  /** The flox-controller node-agent's RepoTag. */
  public String floxController() {
    return read(FLOX_CONTROLLER_REF_RESOURCE);
  }

  /**
   * Read on each call rather than cached at class-init, so a classpath missing the staged refs
   * fails AT THE POINT OF USE with an actionable message instead of at class load — and so the
   * failure names the step that produces them. The reads are a handful of bytes from the bundle.
   */
  private static String read(final String resource) {
    try (InputStream in = ContainerImageRefs.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException(
            "missing staged image ref "
                + resource
                + " — the build stages it from the flake (`nix run .#stage-image-refs`, or"
                + " seedOutclusterJar for a release). It cannot be defaulted: the tag is derived from"
                + " image content, so a guessed value would name an image no node holds.");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read the staged image ref " + resource, ex);
    }
  }
}
