package io.seedmatic.rke2lab.plan.cli;

import io.seedmatic.rke2lab.dataplan.ingress.DataplanIngressCoordinate;
import io.seedmatic.rke2lab.netplan.ingress.NetplanIngressCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The planes the {@code plan} CLI exports — the genus/species split that motivates the unified CLI:
 * {@code plan} is the genus, {@link #NETWORK} (netplan) and {@link #DATASET} (dataplan) the
 * species. Each plane names the broker coordinate its {@code export} sows, the HARVEST its
 * in-container scion files in the run's cellar, and how that host-neutral JSON is rendered to
 * stdout.
 *
 * <p>The sow coordinate is a plain string, NOT the domain's {@code *Coordinate.DOMAIN} constant:
 * those live in {@code type=contract} bundles the flat host cannot reference (the realm-boundary
 * law). The harvest is the opposite case and is named by its coordinate, never a slug: a value the
 * host fetches has its coordinate in a dual-realm {@code *-ingress-contract} for exactly that
 * reason, so the host can reach it.
 */
public enum Plane {
  NETWORK("netplan", NetplanIngressCoordinate.PROJECTION, Format.YAML),
  DATASET("dataplan", DataplanIngressCoordinate.LAYOUT, Format.JSON);

  /** How the reaped host-neutral JSON is rendered to stdout. */
  public enum Format {
    YAML,
    JSON
  }

  private final String coordinate;
  private final SeedCoordinate harvest;
  private final Format format;

  Plane(String coordinate, SeedCoordinate harvest, Format format) {
    this.coordinate = coordinate;
    this.harvest = harvest;
    this.format = format;
  }

  /** The broker coordinate slug this plane's {@code export} sows through the gardening. */
  public String coordinate() {
    return coordinate;
  }

  /** Where the in-container scion files what it derived, read back by the CLI within the run. */
  public SeedCoordinate harvest() {
    return harvest;
  }

  /** The stdout rendering of the reaped export. */
  public Format format() {
    return format;
  }

  /** Resolve a plane from its CLI token; an unknown token is a usage error. */
  public static Plane parse(String token) {
    return switch (token) {
      case "network" -> NETWORK;
      case "dataset" -> DATASET;
      default ->
          throw new IllegalArgumentException(
              "unknown plane '" + token + "' — supported: network, dataset");
    };
  }
}
