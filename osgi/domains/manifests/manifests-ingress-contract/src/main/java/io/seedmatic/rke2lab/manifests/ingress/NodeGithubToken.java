package io.seedmatic.rke2lab.manifests.ingress;

import io.seedmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The node's ephemeral {@code contents:read} github token as one string — a {@code type=dual-realm}
 * record twinning {@link ServerManifestsBundle}. The synthesis scion (OSGi) mints it (from the
 * durable App creds) and files it SEALED + TRANSIENT under {@link
 * NodeGithubTokenCoordinate#NODE_GITHUB_TOKEN}; the pure-host GROW fetches it (revealed) and poses
 * it in the instance's cloud-init as {@code /run/rke2lab/nix-github.conf}, where the {@code
 * rke2lab-rke2-config} oneshot reads it (nix {@code access-tokens}) to fetch the private branch,
 * then erases. {@link SeedContract} binds it to the {@code node-github-token} coordinate for the
 * codec's decode guard.
 */
@SeedContract("node-github-token")
public record NodeGithubToken(String token) {}
