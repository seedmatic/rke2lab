package io.seedmatic.rke2lab.ghapp.contract;

import io.seedmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.Objects;

/**
 * The one org-owned GitHub App's identity + private key, as a SEALED cellar case — filed ONCE by
 * the registration (the manifest-flow conversion returns the private key exactly once) and revealed
 * by every consumer that must authenticate AS the App: the host writer mint, and the render of the
 * {@code githubapp} Secret Flux consumes.
 *
 * <p>{@code appId} / {@code installationId} are identifiers, not secrets; {@code privateKeyPem} is
 * the crown material — so the whole record is sealed ({@code Sensitivity.SEALED}, {@code
 * CellarCipher} at rest). Holding this record is what lets us "be the App": a JWT signed with
 * {@code privateKeyPem} proves the identity, and the App id + installation id address the
 * installation-token endpoint. Knowing the App merely EXISTS on GitHub is not enough — the private
 * key is the proof.
 *
 * <p>{@link SeedContract} binds it to the {@code github-app} coordinate for the codec's decode
 * guard.
 */
@SeedContract("github-app")
public record GithubAppCredentials(String appId, String installationId, String privateKeyPem) {

  // A sealed case missing a field fails at its decode, not downstream: the manifests units embed
  // this record as it is, and the token mints hand it to GitHub.
  public GithubAppCredentials {
    Objects.requireNonNull(appId, "appId");
    Objects.requireNonNull(installationId, "installationId");
    Objects.requireNonNull(privateKeyPem, "privateKeyPem");
  }
}
