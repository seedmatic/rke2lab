// @codebase
package io.seedmatic.rke2lab.manifests.contract.profiles;

import java.util.Objects;

/**
 * Stage A → Stage B Incus identity material published to synth-time layers via {@link
 * io.seedmatic.rke2lab.manifests.ManifestSynthesisContext}. Backs the per-remote {@code
 * <host>-incus-identity} Secret (rendered by {@code ClusterApiWorkloadManifestsUnit} on the
 * node-bootstrap lane) that hands the {@code capn-provider} identity to the in-cluster CAPN
 * provider (Stage B), which authenticates to Incus via {@code LXCCluster.spec.secretRef} and has no
 * access to Stage A's filesystem or Pulumi outputs.
 *
 * <p>seed-master (the host) owns these materials and assembles them from the host world — the
 * {@code capn-provider} client cert from the application resources, the client key from {@code
 * .secrets}, the server cert + remote address from {@code ~/.config/incus/}. The OSGi unit must NOT
 * reach across the world frontier to read them itself; it receives them here and only renders the
 * Secret. Values are RAW (PEM text, plain address) — base64 is a Kubernetes Secret encoding
 * concern, applied by the unit at render time, not baked into this port type.
 *
 * <p>Absence — a run that supplied no Incus identity (unit tests, ephemeral synth) — is carried as
 * an empty {@code Optional<IncusIdentityMaterial>} on the synthesis request, never a placeholder
 * instance: a present material always holds real PEM/address blobs, so the unit renders the
 * identity Secret unconditionally.
 */
public record IncusIdentityMaterial(
    String serverAddress, String serverCert, String clientCert, String clientKey) {

  public IncusIdentityMaterial {
    serverAddress = Objects.requireNonNull(serverAddress, "serverAddress");
    serverCert = Objects.requireNonNull(serverCert, "serverCert");
    clientCert = Objects.requireNonNull(clientCert, "clientCert");
    clientKey = Objects.requireNonNull(clientKey, "clientKey");
  }
}
