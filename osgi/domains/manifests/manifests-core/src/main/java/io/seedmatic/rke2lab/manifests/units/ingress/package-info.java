// @codebase
/**
 * Ingress domain manifest units: the public-door CAPABILITY every cluster carries — the Tailscale
 * operator plus the funnel machinery that exposes a cluster's OWN endpoints publicly (the Flux
 * receiver, the PaC/Tekton webhook). Distinct from the mesh domain (Headscale/Headplane, the
 * self-hosted control service).
 *
 * <ul>
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.ingress.IngressSystemNamespaceManifestsUnit} —
 *       the shared {@code ingress-system} namespace the mesh units also depend on.
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.ingress.TailscaleManifestsUnit} — the Tailscale
 *       operator + connector.
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.ingress.FunnelCertRestoreManifestsUnit} /
 *       {@link io.seedmatic.rke2lab.manifests.units.ingress.FunnelStatePersistenceManifestsUnit} —
 *       the durable-funnel cert restore + state persistence.
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.ingress.TailnetPurgeManifestsUnit} — the
 *       stale-device GC that clears the tailnet before the operator provisions any proxy.
 * </ul>
 *
 * <p>Registered by {@link io.seedmatic.rke2lab.manifests.domain.IngressDomainRegistrar}.
 */
@org.jspecify.annotations.NullMarked
package io.seedmatic.rke2lab.manifests.units.ingress;
