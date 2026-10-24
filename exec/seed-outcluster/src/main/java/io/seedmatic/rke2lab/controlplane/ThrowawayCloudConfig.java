package io.seedmatic.rke2lab.controlplane;

import io.seedmatic.rke2lab.controlplane.incus.NodeCloudConfig;
import io.seedmatic.rke2lab.controlplane.incus.NodeCloudConfig.WriteFile;
import java.util.List;
import java.util.Set;

/**
 * What a throwaway node's cloud-config may hold, checked before the grow poses it: an ALLOW-list,
 * so a write added to the cloud-config tomorrow is refused until someone says a throwaway node
 * takes it. It writes {@code node.env} and its one rke2 drop-in and nothing else, it masks sshd for
 * the boot, and it authorizes no ssh key. The installed command checks the same on the node
 * afterwards.
 */
public final class ThrowawayCloudConfig {

  private static final Set<String> ALLOWED_WRITES =
      Set.of(NodeCloudConfig.NODE_ENV, NodeCloudConfig.THROWAWAY_RKE2_CONFIG);

  private ThrowawayCloudConfig() {}

  /** The cloud-config itself, once it holds nothing a throwaway node may not. */
  public static NodeCloudConfig checked(NodeCloudConfig cloudConfig) {
    final List<String> writes = cloudConfig.writeFiles().stream().map(WriteFile::path).toList();
    for (final String path : writes) {
      if (!ALLOWED_WRITES.contains(path)) {
        throw new IllegalStateException(
            "a throwaway node's cloud-config writes "
                + path
                + ": it writes only "
                + ALLOWED_WRITES);
      }
    }
    if (writes.size() != writes.stream().distinct().count()) {
      throw new IllegalStateException(
          "a throwaway node's cloud-config writes a path twice: " + writes);
    }
    if (!cloudConfig.bootcmd().contains(NodeCloudConfig.SSHD_RUNTIME_MASK)) {
      throw new IllegalStateException(
          "a throwaway node's cloud-config does not mask sshd: '"
              + NodeCloudConfig.SSHD_RUNTIME_MASK
              + "' is missing from its bootcmd");
    }
    if (!cloudConfig.sshAuthorizedKeys().isEmpty()) {
      throw new IllegalStateException(
          "a throwaway node's cloud-config authorizes an ssh key: it authorizes none");
    }
    return cloudConfig;
  }
}
