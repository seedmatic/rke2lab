package io.seedmatic.rke2lab.incus.ingress;

/**
 * The config the host fills to ACTUALISE the incus ingress flow — the flat vocabulary the Pulumi
 * actualiser ({@code incus-ingress}) needs to declare the instance graph: the incus project +
 * remote it talks to, and the node/profile/bridge names the grow poses. Host-to-host (the seed run
 * fills it from its own bootstrap config, the actualiser reads it) — it never crosses to OSGi, but
 * it lives in the contract beside {@link BootstrapPaths} (host-facing too), so the ingress
 * vocabulary has ONE authority: the contract defines what the flow needs, the actualiser only
 * consumes it.
 *
 * <p>Flat {@code String}s throughout (a URI/Path is rendered to its string form by the caller), so
 * no host config type — nor the OSGi {@code LogLevel} such a type drags — leaks into the
 * actualiser.
 */
public record IngressConfig(
    String incusProject,
    String incusDefaultRemote,
    String incusRemoteAddress,
    String incusConfigDir,
    String profileName,
    String lanBridgeParent,
    String vmnetNetworkName) {}
