// @codebase
/**
 * Tailscale domain manifest units: the per-cluster tailnet SUBSTRATE every cluster carries — the
 * Tailscale operator, the subnet-router Connector, and the funnel machinery that exposes a
 * cluster's OWN endpoints publicly (the Flux receiver, the PaC/Tekton webhook). Broader than
 * ingress: the operator also advertises cluster subnets to the tailnet (the Connector) and joins
 * the tailnet itself; the funnel is one capability it provides. Distinct from the mesh domain
 * (Headscale/Headplane, the self-hosted control service).
 *
 * <ul>
 *   <li>{@link
 *       io.seedmatic.rke2lab.manifests.units.tailscale.TailscaleSystemNamespaceManifestsUnit} — the
 *       {@code tailscale-system} namespace.
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.tailscale.TailscaleManifestsUnit} — the
 *       Tailscale operator + subnet-router Connector.
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.tailscale.FunnelCertRestoreManifestsUnit} /
 *       {@link io.seedmatic.rke2lab.manifests.units.tailscale.FunnelStatePersistenceManifestsUnit}
 *       — the durable-funnel cert restore + state persistence.
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.tailscale.TailnetPurgeManifestsUnit} — the
 *       stale-device GC that clears the tailnet before the operator provisions any proxy.
 * </ul>
 *
 * <p>Registered by {@link io.seedmatic.rke2lab.manifests.domain.TailscaleDomainRegistrar}.
 */
@org.jspecify.annotations.NullMarked
package io.seedmatic.rke2lab.manifests.units.tailscale;
