// @codebase
package io.seedmatic.rke2lab.manifests.contract.profiles;

import java.util.Objects;

/**
 * The REALISED node-base image, published to synth-time layers via {@link
 * io.seedmatic.rke2lab.manifests.ManifestSynthesisContext} and rendered into the {@code NodeImage}
 * CR that describes it to the cluster depending on it.
 *
 * <p>It is one object because the image is one artifact. That was not always so: its identity used
 * to be SHREDDED — the fingerprint embedded inside each pool's spec, the baked RKE2 version beside
 * it as a sibling scalar re-normalised at two render sites, the incus project inside an identity
 * Secret, and the alias / build checksum / source remote written into a {@code
 * <cluster>-image-state} ConfigMap that NOTHING ever read. Because no object owned the image, the
 * runtime contract of that image had nowhere to live, so it was restated on both sides of the seam
 * and the two diverged (see {@link NodeRuntime}).
 *
 * <p>Field notes:
 *
 * <ul>
 *   <li>{@code imageFingerprint} is the immutable content hash — {@code sha256(metadata.tar.xz ++
 *       rootfs.squashfs)}, how incus derives a SPLIT image's fingerprint. An {@code
 *       LXCMachineTemplate} pins THIS, not the re-pointable {@code imageAlias}, so a pool launches
 *       from the exact image the grow built.
 *   <li>{@code imageBuildChecksum} is the SHA-256 of the build INPUTS (as opposed to the
 *       fingerprint, which hashes the output) — the basis for drift detection. Carried as part of
 *       the artifact's identity; no consumer compares it yet.
 *   <li>{@code rke2Version} is the RKE2 release BAKED INTO the image, read from the {@code
 *       rke2.version} artifact the nix build emits ({@code nixosConfigurations.rke2-node-base …
 *       rke2.package.version}) — never a hand-pinned literal, and the source for {@code
 *       RKE2ControlPlane.spec.version}. NORMALISED to a leading {@code v} by the compact
 *       constructor, so no consumer re-normalises (two render sites used to do it each).
 *   <li>{@code runtime} is the contract an instance needs to RUN this image.
 * </ul>
 *
 * <p>Values are COMPUTED by the incus scion from the freshly-built node-base artifacts and
 * forwarded as the {@code IMAGE_STATE} amendment (never a Pulumi {@code getImagePlain} round trip).
 * Absence — a run that built no image (unit tests, ephemeral or survey synth) — is carried as an
 * empty {@code Optional<ImageState>} on the synthesis request, never a placeholder instance: a
 * present {@code ImageState} always holds a real identity, so the units render it unconditionally.
 */
public record ImageState(
    String imageAlias,
    String imageFingerprint,
    String imageBuildChecksum,
    String incusProject,
    String incusRemoteAddress,
    String rke2Version,
    NodeRuntime runtime) {

  public ImageState {
    imageAlias = Objects.requireNonNull(imageAlias, "imageAlias");
    imageFingerprint = Objects.requireNonNull(imageFingerprint, "imageFingerprint");
    imageBuildChecksum = Objects.requireNonNull(imageBuildChecksum, "imageBuildChecksum");
    incusProject = Objects.requireNonNull(incusProject, "incusProject");
    incusRemoteAddress = Objects.requireNonNull(incusRemoteAddress, "incusRemoteAddress");
    rke2Version = Objects.requireNonNull(rke2Version, "rke2Version");
    runtime = Objects.requireNonNull(runtime, "runtime");
    // Normalise ONCE, here: the nix artifact emits a bare "1.34.1+rke2r1" while CAPRKE2 wants a
    // leading "v". Both render units used to test-and-prefix, which is the kind of duplication that
    // drifts.
    rke2Version = rke2Version.startsWith("v") ? rke2Version : "v" + rke2Version;
  }
}
