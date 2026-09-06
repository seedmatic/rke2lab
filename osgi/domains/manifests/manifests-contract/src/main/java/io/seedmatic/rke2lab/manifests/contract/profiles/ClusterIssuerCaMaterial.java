// @codebase
package io.seedmatic.rke2lab.manifests.contract.profiles;

import java.util.Objects;

/**
 * The cluster-issuer CA published to synth-time layers via {@code ManifestSynthesisContext} — the
 * CA chain + private key a cert-manager {@code CA} {@code ClusterIssuer} ({@code rke2lab-ca}) signs
 * in-cluster leaves with, so every leaf (the flox webhook serving cert, CAPI, future webhooks)
 * chains to our own root. Two PEM blocks: {@code caCertChainPem} is the FULL CHAIN (cluster-issuer
 * CA + intermediate + ndh root), {@code caKeyPem} the CA private key.
 *
 * <p>The manifests-side MIRROR of the cluster-pki {@code ClusterIssuerCa} dual-realm record: the
 * manifests scion reveals it from the cellar in-container (SEALED at rest) and translates it here
 * before handing it to synthesis, so no {@code cluster-pki} type ever crosses into the manifests
 * domain — the same gate-boundary discipline {@link OperatorPkiMaterial} and {@link
 * SopsAgeMaterial} follow.
 *
 * <p>Absence — no cluster PKI sealed yet (unit tests, a bare survey) OR a secret-blind in-cluster
 * render (the {@code EphemeralCellar} returns empty) — is carried as an empty {@code
 * Optional<ClusterIssuerCaMaterial>}: the delivering unit then renders no Secret onto the branch
 * (the material rides the durable NODE_BOOTSTRAP lane, posed node-side at grow). The {@code
 * ClusterIssuer} itself carries no secret and renders unconditionally.
 */
public record ClusterIssuerCaMaterial(String caCertChainPem, String caKeyPem) {

  public ClusterIssuerCaMaterial {
    caCertChainPem = Objects.requireNonNull(caCertChainPem, "caCertChainPem");
    caKeyPem = Objects.requireNonNull(caKeyPem, "caKeyPem");
  }
}
