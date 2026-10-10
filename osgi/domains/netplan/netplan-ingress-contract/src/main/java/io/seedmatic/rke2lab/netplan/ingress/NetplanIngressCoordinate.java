package io.seedmatic.rke2lab.netplan.ingress;

import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The netplan domain's DUAL-REALM seed coordinate — the one document the host fetches from it. It
 * lives HERE, not in {@code NetplanCoordinate}: that one is {@code netplan-contract} ({@code
 * type=contract}, OSGi-only), so the host cannot compile against it, and spelling the slug as a
 * string host-side is the very mismatch the single-source-of-truth discipline forbids. Both realms
 * reference this enum — the netplan scion to {@code store}, the host to {@code fetch}.
 *
 * <p>Domain is {@code "netplan"}, the same as {@code NetplanCoordinate}: the netplan domain
 * speaking to itself across the realm boundary, not a second domain. The value is the projection
 * the scion DERIVES (the same tree the plan CLI exports and fabric/plan delivers as {@code
 * netplan.json}); the host reads it as JSON, never as a netplan type.
 */
public enum NetplanIngressCoordinate implements SeedCoordinate {
  PROJECTION("netplan-projection");

  private static final String DOMAIN = "netplan";

  private final String slug;

  NetplanIngressCoordinate(String slug) {
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
