// @codebase
/**
 * Mesh domain manifest units: the self-hosted control SERVICE (Headscale + Headplane). The
 * Tailscale operator + funnel machinery and the shared system namespace live in the sibling {@code
 * units.ingress} package (the public-door capability, both roles); mesh depends on it.
 *
 * <ul>
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
