// @codebase
/**
 * Mesh domain manifest units: the self-hosted control SERVICE (Headscale + Headplane). The
 * Tailscale operator + funnel machinery live in the sibling {@code units.tailscale} package (the
 * per-cluster tailnet substrate, both roles); mesh owns its OWN {@code mesh-system} namespace and
 * no longer shares one with it.
 *
 * <ul>
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.mesh.MeshSystemNamespaceManifestsUnit} — the
 *       {@code mesh-system} namespace.
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.mesh.HeadscaleManifestsUnit} — Headscale
 *       control server.
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.mesh.HeadplaneManifestsUnit} — Headplane UI.
 * </ul>
 *
 * <p>Registered by {@link io.seedmatic.rke2lab.manifests.domain.MeshDomainRegistrar}.
 *
 * <h2>Related documentation</h2>
 *
 * <ul>
 *   <li><a href="../../../../../../../../../../docs/manifests-architecture.adoc">Manifests
 *       Architecture</a> — the unit model and synthesis flow.
 * </ul>
 */
@org.jspecify.annotations.NullMarked
package io.seedmatic.rke2lab.manifests.units.mesh;
