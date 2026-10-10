package io.seedmatic.rke2lab.manifests.bdd;

import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The ghapp registration's {@code github-app} cellar case, addressed by its NEUTRAL wire coordinate
 * so the manifests realm reveals the sealed App credentials without a compile link to {@code
 * ghapp-contract} (naming {@code GhAppCoordinate} would drag its flat copy into the standalone
 * {@code manifests-cli} assembly). The {@code slug}/{@code domain} here MUST match {@code
 * GhAppCoordinate.GITHUB_APP}; the cellar matches a read case by slug.
 *
 * <p>Its one consumer is the SYNTHESIS: the {@code githubapp} Secret render ({@code
 * ManifestSynthesisScenario#revealGithubApp}), the same treatment every foreign sealed material the
 * synthesis renders gets. Tokens no longer come through here: {@code GithubAppTokens}
 * (auth-contract) reads the App from its owner, {@code GhAppCoordinate.GITHUB_APP}, for the
 * render's push, the node's reader token and the version bump alike.
 */
public enum GhAppCase implements SeedCoordinate {
  GITHUB_APP;

  @Override
  public String slug() {
    return "github-app";
  }

  @Override
  public String domain() {
    return "ghapp";
  }
}
