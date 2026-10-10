package io.seedmatic.rke2lab.dataplan.ingress;

import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The dataplan domain's DUAL-REALM seed coordinate — the one document the host fetches from it: the
 * plan CLI's {@code export} reads the dataset tree here and renders it. It lives in this module and
 * not in {@code DataplanCoordinate} by the rule a value coordinate's placement follows: it earns a
 * dual-realm module IFF THE HOST FETCHES IT. {@code dataplan-contract} is {@code type=contract},
 * OSGi-only, so the host cannot compile against it, and spelling the slug as a string host-side is
 * the very mismatch the single-source-of-truth discipline forbids. Both realms reference this enum
 * — the dataplan scion to {@code store}, the host to {@code fetch}.
 *
 * <p>Domain is {@code "dataplan"}: the dataplan domain speaking to itself across the realm
 * boundary, not a second domain. The value is the layout the scion DERIVES ({@code
 * DataplanLayout.canonical()}, the {@code tank/rke2lab/*} dataset tree); the host reads it as JSON,
 * never as a dataplan type.
 */
public enum DataplanIngressCoordinate implements SeedCoordinate {
  LAYOUT("dataplan-layout");

  private static final String DOMAIN = "dataplan";

  private final String slug;

  DataplanIngressCoordinate(String slug) {
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
