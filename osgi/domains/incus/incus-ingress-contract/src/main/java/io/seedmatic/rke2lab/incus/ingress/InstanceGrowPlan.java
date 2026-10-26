package io.seedmatic.rke2lab.incus.ingress;

import io.seedmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The ONE immutable record the host GROW fetches to grow the instance — the incus scion PROJECTS
 * it, the pure-host GROW ACTUALISES it (the scion-projects / host-actualises rule, see
 * docs/architecture/osgi/host-cellar-realisation-spec.adoc § the-grow-anatomy). A single host
 * consumer, so a single record fetched once — assembled at the END by a sealing THEN ({@code
 * the_instance_grow_plan_is_published}), not accumulated by mutation. One store, one fetch, one
 * value-DAG. Self-contained: it carries everything the GROW poses — the host derives NO path, holds
 * no worktree root and no {@code BootstrapPaths}.
 *
 * <p>Structure: a sub-view per coherent GROUP of fields ({@link GrowNetworkView}, {@link
 * GrowImageView}, {@link GrowIdentityView}). Two image views: {@code image} is the server's, the
 * one the instance clones; {@code agentImage} is the agent's, uploaded beside it for the worker
 * pools. The NixOS {@code node-base} substrate bakes the node's config, systemd units and scripts
 * into the image, so the instance takes NO host disk mounts and NO cloud-init seed — the former
 * {@code /srv/host} delivery mechanism is dissolved. The only per-node difference is the identity
 * view, posed as {@code user.rke2lab.node-*} keys the guest reads over devlxd; the manifests
 * (server-manifests) arrive through their own channel, not this plan.
 *
 * <p>{@link SeedContract} binds it to the {@code instance-grow-plan} coordinate ({@link
 * IncusGrowCoordinate#INSTANCE_GROW_PLAN}) for the codec's decode guard. Unlike the OSGi-only
 * wire-records, this type IS held in both realms — the intra-domain dual-realm exception — so the
 * host {@code SeedCodec} decodes it against its own flat copy.
 */
@SeedContract("instance-grow-plan")
public record InstanceGrowPlan(
    GrowNetworkView network,
    GrowImageView image,
    GrowImageView agentImage,
    GrowIdentityView identity) {}
