package io.seedmatic.rke2lab.controlplane.bdd;

import io.seedmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.Amendment;
import io.seedmatic.rke2lab.seed.broker.port.AmendmentContributor;
import java.util.Map;

/**
 * A host-owned ambient FACET contributor: the operator subtree the root read for ONE coordinate,
 * published as an {@link AmendmentContributor} the assembler gathers at the amend door. The
 * generalisation of the former single {@code ManifestsFacetContributor} — the root registers one
 * instance per curated coordinate (manifests from the stack config, another from {@code .secrets},
 * …), each carrying that coordinate's merged FACET subtree as a serialized JSON String.
 *
 * <p>The coordinate is built from a literal domain slug: the flat control-plane realm may not
 * reference a bundle-only {@code *Coordinate} type (the realm boundary). A slug divergence between
 * a contributor and its serving grower is caught at runtime by the assembler's BETA orphan guard.
 */
public final class FacetContributor implements AmendmentContributor {

  private final AmendCoordinate coordinate;
  private final String facetJson;

  public FacetContributor(AmendCoordinate coordinate, String facetJson) {
    this.coordinate = coordinate;
    this.facetJson = facetJson;
  }

  @Override
  public AmendCoordinate coordinate() {
    return coordinate;
  }

  @Override
  public Map<String, String> roles() {
    return facetJson.isBlank() ? Map.of() : Map.of(Amendment.FACET, facetJson);
  }
}
