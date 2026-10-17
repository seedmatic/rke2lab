package io.seedmatic.rke2lab.manifests;

/**
 * A refusal {@link RenderedSecretSeal} raises itself: its message names paths and keys only, never
 * a value, so it may be lent to a failure reason. Only this and a {@link
 * io.seedmatic.rke2lab.clusterpki.contract.SopsEncryptionException} lend their message; any other
 * exception reaching the seal — a library's {@link IllegalStateException} among them, whose text it
 * does not control — is named by its class alone.
 */
final class RenderRefusal extends IllegalStateException {

  RenderRefusal(String message) {
    super(message);
  }
}
