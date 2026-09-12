package io.seedmatic.rke2lab.manifests.ingress;

import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The manifests domain's DUAL-REALM seed coordinate for the node's github fetch token — the twin of
 * {@link ServerManifestsCoordinate}: the synthesis scion (OSGi) files it and the pure-host GROW
 * fetches it. It lives HERE, in the host face of the manifests contract, so the GROW — which runs
 * outside Felix, in the FLAT realm — can reference it without touching a bundle-wired package.
 *
 * <p>Unlike the durable cases, this one is filed {@code Persistence.TRANSIENT}: the token is a
 * fresh {@code contents:read} App installation token minted at the render (which pushes the branch
 * the node will fetch), read by the GROW THIS run, and evicted at the drain — it NEVER reaches the
 * durable backend, so it cannot go stale between runs (the {@code github-token-mint-on-demand}
 * discipline: the App creds are durable, the token is not). The slug matches the
 * {@code @SeedContract} of {@link NodeGithubToken}, which {@code SeedCodec} verifies at decode.
 */
public enum NodeGithubTokenCoordinate implements SeedCoordinate {

  /**
   * The node's ephemeral {@code contents:read} github token — SEALED + TRANSIENT. The GROW poses it
   * (via {@code NodeBootstrapMaterial}) into the instance's cloud-init as the {@code
   * /run/rke2lab/nix-github.conf} the {@code rke2lab-rke2-config} oneshot reads (nix {@code
   * access-tokens}) to fetch the private {@code manifests/<cluster>} branch, then erases.
   */
  NODE_GITHUB_TOKEN("node-github-token");

  private static final String DOMAIN = "manifests";

  private final String slug;

  NodeGithubTokenCoordinate(String slug) {
    this.slug = slug;
  }

  @Override
  public String slug() {
    return slug;
  }

  @Override
  public String domain() {
    return DOMAIN;
  }
}
