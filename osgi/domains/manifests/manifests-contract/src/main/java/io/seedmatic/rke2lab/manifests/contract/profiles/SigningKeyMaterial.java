// @codebase
package io.seedmatic.rke2lab.manifests.contract.profiles;

import java.util.Objects;

/**
 * The commit-signing SSH private key ({@code rke2lab-bot-signing}) published to synth-time layers
 * via {@link io.seedmatic.rke2lab.manifests.ManifestSynthesisContext}. Backs the {@code
 * manifests-render-signing} Secret (see {@code RenderSigningSecretManifestsUnit}) that the
 * in-cluster Tekton {@code render-publish} step mounts as {@code RKE2LAB_SIGNING_KEY} to sign the
 * rendered commit — the twin of {@link SopsAgeMaterial}.
 *
 * <p>The synthesis service reads it from its ndh key-store in the pre-synthesis step, ONLY as
 * OPERATOR (the enclosure gate): the operator's grow emits the Secret so the future in-cluster
 * render can sign, exactly as it emits {@code sops-age} for Flux. IN_CLUSTER the key-store is
 * sops-encrypted at rest and there is nothing to re-emit, so the material is absent and the unit
 * skips. The value is RAW (the PEM key text); base64 is the unit's Kubernetes-encoding concern.
 *
 * <p>Absence — no key-store (a bare survey / test), or an in-cluster render — is carried as an
 * empty {@code Optional<SigningKeyMaterial>} on the context, never a placeholder.
 */
public record SigningKeyMaterial(String sshPrivate) {

  public SigningKeyMaterial {
    sshPrivate = Objects.requireNonNull(sshPrivate, "sshPrivate");
  }
}
