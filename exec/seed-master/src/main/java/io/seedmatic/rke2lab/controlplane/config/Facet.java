package io.seedmatic.rke2lab.controlplane.config;

/**
 * A config coordinate that contributes a FACET payload at the amend door. The uniform contract for
 * every facet-bearing config record: the host caches ONE opaque JSON payload — the operator subtree
 * (manifests {@code {publish, debug}}), possibly joined with a secret it owns (see {@code
 * SecretJoin}) — and contributes it VERBATIM under {@link
 * io.seedmatic.rke2lab.seed.broker.port.Amendment#FACET}, naming no domain vocabulary. The
 * payload's structure — its decode into {@code Router}, into {@code Facets} — is the consuming
 * scion's, never the host's.
 *
 * <p>The payload is materialised in the config DAG (visible in the step debugger); the facet
 * contract exposes ONLY {@link #facetJson()}. A blank payload means the host offers no FACET for
 * this coordinate (the scion keeps its defaults), so a survey with no {@code .secrets} and an
 * absent config section behave alike.
 */
public interface Facet {

  /** The JSON payload contributed verbatim under the FACET role; blank ⇒ no contribution. */
  String facetJson();
}
