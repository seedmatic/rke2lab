// @codebase
package io.seedmatic.rke2lab.manifests.contract.profiles;

import java.util.Objects;

/**
 * The age private key published to synth-time layers via {@link
 * io.seedmatic.rke2lab.manifests.ManifestSynthesisContext}. Backs the {@code sops-age} Secret (see
 * {@code SopsAgeSecretManifestsUnit}) that Flux uses to decrypt SOPS-encrypted resources
 * in-cluster.
 *
 * <p>The synthesis service (the host-of-synthesis, an OSGi {@code @Component}) owns the assembly:
 * in a pre-synthesis step it reads the cluster's age identity the operator handed over ({@code
 * ClusterAgeIdentity}, ndh-core), then binds it here. The unit MUST NOT reach across to a file or
 * shell a tool itself; it receives this and only renders the Secret. The value is RAW (the age key
 * text) — base64 is a Kubernetes Secret encoding concern, applied by the unit at render time, not
 * baked into this port type.
 *
 * <p>Absence — no SSH key-store present (unit tests, ephemeral synth) — is carried as an empty
 * {@code Optional<SopsAgeMaterial>} on the context, never a placeholder instance: a present
 * material always holds a real age key, so the unit renders the Secret unconditionally.
 */
public record SopsAgeMaterial(String ageKey) {

  public SopsAgeMaterial {
    ageKey = Objects.requireNonNull(ageKey, "ageKey");
  }
}
