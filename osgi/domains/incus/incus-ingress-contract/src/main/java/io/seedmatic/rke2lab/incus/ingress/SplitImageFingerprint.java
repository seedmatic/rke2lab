package io.seedmatic.rke2lab.incus.ingress;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * The content fingerprint of a SPLIT incus image — {@code sha256(metadata.tar.xz ++
 * rootfs.squashfs)}, metadata first, exactly as incus derives a split image's fingerprint (verified
 * against the live daemon). A pure leaf util SHARED by the two parties that must agree on the
 * identity of the one node-base image: the host GROW references the image by this fingerprint (no
 * alias — see {@code InstanceGrow.ensureImage}), and the incus scion folds it into the {@code
 * IMAGE_STATE} amendment so the workload {@code LXCMachineTemplate} pins the exact same image the
 * master booted. Both compute it from the same two artifacts, so the algorithm lives once here
 * rather than twice.
 */
public final class SplitImageFingerprint {

  private SplitImageFingerprint() {}

  /** The hex sha256 of {@code metadata} then {@code rootfs}, concatenated (metadata first). */
  public static String of(final Path metadata, final Path rootfs) {
    try {
      final MessageDigest sha = MessageDigest.getInstance("SHA-256");
      final byte[] buffer = new byte[1 << 16];
      for (final Path path : List.of(metadata, rootfs)) {
        try (InputStream in = Files.newInputStream(path)) {
          int read;
          while ((read = in.read(buffer)) > 0) {
            sha.update(buffer, 0, read);
          }
        }
      }
      return HexFormat.of().formatHex(sha.digest());
    } catch (IOException | NoSuchAlgorithmException ex) {
      throw new IllegalStateException(
          "cannot compute the split-image fingerprint from " + metadata + " + " + rootfs, ex);
    }
  }
}
