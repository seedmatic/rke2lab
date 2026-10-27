package io.seedmatic.rke2lab.incus.core;

import io.seedmatic.rke2lab.incus.ingress.GrowIdentityView;
import io.seedmatic.rke2lab.incus.ingress.GrowImageView;
import io.seedmatic.rke2lab.incus.ingress.GrowNetworkView;
import io.seedmatic.rke2lab.incus.ingress.InstanceGrowPlan;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Assembles the ONE immutable {@link InstanceGrowPlan} the host GROW fetches — the sealing act of
 * the incus PREPARE (§ host-cellar-realisation, the scion-projects/host-actualises rule). Every
 * value it carries is computed OSGi-side so the host actualises without computing anything of the
 * domain: the image {@code buildChecksum} (the edge {@code recipeDigest} folded with the image
 * scalars + the nix source digest) and the two readable artifact paths. The NixOS {@code node-base}
 * substrate bakes the node's config, so the plan carries no host mounts and no cloud-init seed.
 *
 * <p>Instance-passing: the caller hands the {@link GrowNetworkView} the network beat already
 * resolved and the flat image scalars; this assembler folds them into the plan. No static helper —
 * its inputs are its constructor arguments, its one act is {@link #assemble}.
 */
public final class GrowPlanAssembler {

  private static final String METADATA_FILENAME = "incus.tar.xz";
  private static final String ROOTFS_FILENAME = "rootfs.squashfs";

  private final String imageAlias;
  private final String builderBinary;
  private final String recipeDigest;
  private final String imageSourceDigest;
  private final Path sharedFolder;

  public GrowPlanAssembler(
      String imageAlias,
      String builderBinary,
      String recipeDigest,
      String imageSourceDigest,
      Path sharedFolder) {
    this.imageAlias = imageAlias;
    this.builderBinary = builderBinary;
    this.recipeDigest = recipeDigest;
    this.imageSourceDigest = imageSourceDigest;
    this.sharedFolder = sharedFolder;
  }

  /**
   * Seal the network view, this image (the server's, the one the instance clones), the agent's
   * image and the identity into the immutable plan. The agent's view comes from its own assembler,
   * keyed by its alias.
   */
  public InstanceGrowPlan assemble(
      GrowNetworkView network, GrowImageView agentImage, GrowIdentityView identity) {
    return new InstanceGrowPlan(network, imageView(), agentImage, identity);
  }

  /**
   * Where an image's artifacts live: one dir per alias under the shared folder, with the same file
   * names, so the server's and the agent's never overwrite each other. The build request writes
   * here, the plan reads here.
   */
  public Path artifactDir() {
    return sharedFolder.resolve(imageAlias).toAbsolutePath().normalize();
  }

  /**
   * The built image's view — alias, the two readable artifact paths, and the OSGi-computed {@code
   * buildChecksum}. Public because the incus scion also folds this identity (plus the split-image
   * fingerprint over the two paths and the emitted {@code rke2.version}) into the {@code
   * IMAGE_STATE} amendment it forwards to the manifests synthesis, so the workload CRs pin the same
   * image the plan does — the one computation, two consumers.
   */
  public GrowImageView imageView() {
    final Path artifactDir = artifactDir();
    return new GrowImageView(
        imageAlias,
        artifactDir.resolve(METADATA_FILENAME).toString(),
        artifactDir.resolve(ROOTFS_FILENAME).toString(),
        buildChecksum());
  }

  /**
   * The image-cache key the host poses on {@code user.rke2lab.imageBuildChecksum}: SHA-256 of the
   * edge {@code recipeDigest} (the build METHOD — how nix is invoked) folded with the two image
   * scalars AND the {@code imageSourceDigest} (the build CONTENT — the committed tree of what
   * {@code nixosConfigurations.rke2-node-server} evaluates, the SAME digest the build script's
   * freshness gate reads). Committing an edit to {@code node-base.nix} or a {@code flake.lock} bump
   * moves the checksum and {@code replaceOnChanges} recreates the instance onto the new image.
   */
  private String buildChecksum() {
    final MessageDigest digest = sha256();
    digest.update(recipeDigest.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) '\n');
    digest.update(imageAlias.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) '\n');
    digest.update(builderBinary.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) '\n');
    digest.update(imageSourceDigest.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest.digest());
  }

  private MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
