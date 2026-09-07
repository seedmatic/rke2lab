// @codebase
package io.seedmatic.rke2lab.manifests.contract.profiles;

import java.util.Objects;

/**
 * Stage A → Stage B image-identity slice published to synth-time layers via {@link
 * io.seedmatic.rke2lab.manifests.ManifestSynthesisContext}. Backs the {@code <cluster>-image-state}
 * ConfigMap (see {@code ImageStateConfigMapManifestsUnit}) that hands the control-node image's
 * identity to the in-cluster Cluster API / CAPN provider, which has no access to Stage A's Pulumi
 * outputs and can only read Kubernetes objects.
 *
 * <p>The {@code imageFingerprint} is the immutable content hash Incus assigns; {@code
 * LXCMachineTemplate} should pin it (not the re-pointable {@code imageAlias}) so peers launch from
 * the exact image Stage A built. {@code imageBuildChecksum} is the SHA-256 of the build inputs,
 * used for drift detection. {@code rke2Version} is the RKE2 version baked into the image, resolved
 * from nix at image-build time ({@code nixosConfigurations.rke2-node-base … rke2.package.version},
 * emitted as the {@code rke2.version} artifact) — the source for {@code
 * RKE2ControlPlane.spec.version}, never a hand-pinned literal.
 *
 * <p>Values are COMPUTED by the incus scion from the freshly-built node-base artifacts and
 * forwarded as the {@code IMAGE_STATE} amendment (never a Pulumi {@code getImagePlain} round trip):
 * the fingerprint is {@code sha256(incus.tar.xz ++ rootfs.squashfs)}, the {@code rke2Version} is
 * read from the emitted {@code rke2.version} artifact, and the checksum/remote/project come from
 * the bootstrap config the scion holds. Absence — a run that built no image (unit tests, ephemeral
 * or survey synth) — is carried as an empty {@code Optional<ImageState>} on the synthesis request,
 * never a placeholder instance: a present {@code ImageState} always holds a real identity, so the
 * unit renders it unconditionally.
 */
public record ImageState(
    String imageAlias,
    String imageFingerprint,
    String imageBuildChecksum,
    String incusProject,
    String incusRemoteAddress,
    String rke2Version) {

  public ImageState {
    imageAlias = Objects.requireNonNull(imageAlias, "imageAlias");
    imageFingerprint = Objects.requireNonNull(imageFingerprint, "imageFingerprint");
    imageBuildChecksum = Objects.requireNonNull(imageBuildChecksum, "imageBuildChecksum");
    incusProject = Objects.requireNonNull(incusProject, "incusProject");
    incusRemoteAddress = Objects.requireNonNull(incusRemoteAddress, "incusRemoteAddress");
    rke2Version = Objects.requireNonNull(rke2Version, "rke2Version");
  }
}
