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
 * .secrets}, the remote address from {@code ~/.config/incus/}. The OSGi unit must NOT reach across
 * the world frontier to read them itself; it receives them here and only renders the Secret. Values
 * are RAW (PEM text, plain address) — base64 is a Kubernetes Secret encoding concern, applied by
 * the unit at render time, not baked into this port type.
 *
 * <p>★ There is no {@code serverCert}, and its absence is the point. It carried the Incus
 * listener's LEAF, which CAPN pinned as {@code TLSServerCert} — so the listener certificate could
 * not be replaced without invalidating every copy, and the pin silently skipped name verification.
 * CAPN now trusts the listener by CA ({@code TlsAuthorityCaMaterial}), so nothing reads a server
 * cert and seed-master no longer needs {@code ~/.config/incus/servercerts/} at all. Do not
 * reintroduce it to "be safe": with the leaf present the pin wins, the CA path goes untested, and a
 * certificate nobody can replace comes back.
 *
 * <p>Absence — a run that supplied no Incus identity (unit tests, ephemeral synth) — is carried as
 * an empty {@code Optional<IncusIdentityMaterial>} on the synthesis request, never a placeholder
 * instance: a present material always holds real PEM/address blobs, so the unit renders the
 * identity Secret unconditionally.
 */
public record IncusIdentityMaterial(String serverAddress, String clientCert, String clientKey) {

  public IncusIdentityMaterial {
    serverAddress = Objects.requireNonNull(serverAddress, "serverAddress");
    clientCert = Objects.requireNonNull(clientCert, "clientCert");
    clientKey = Objects.requireNonNull(clientKey, "clientKey");
  }
}
