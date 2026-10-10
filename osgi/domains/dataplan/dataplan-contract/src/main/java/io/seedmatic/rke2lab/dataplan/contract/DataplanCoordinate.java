package io.seedmatic.rke2lab.dataplan.contract;

import io.seedmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.ShapeCoordinate;

/**
 * The dataplan domain's seed coordinates, declared and owned in ONE place — the single-source
 * discipline its sibling {@code NetplanCoordinate} holds, and like it an EMPTY {@code enum}: it
 * enumerates nothing, holding as static constants the shared META coordinates dataplan is addressed
 * through — {@link #AMEND} (the reflector serves it, the assembler gathers on it), {@link #SHAPE}
 * (the schema projection), {@link #RUNBOOK} (the export trigger) — all keyed by one {@link
 * #DOMAIN}, so the growers never diverge as raw literals.
 *
 * <p>The layout's value coordinate is {@code DataplanIngressCoordinate.LAYOUT}, in the dual-realm
 * {@code dataplan-ingress-contract}, NOT here: the host fetches it (the plan CLI's export renders
 * the dataset tree from the harvest), and a value coordinate earns a dual-realm module iff the host
 * fetches it. This contract is OSGi-only, so a host could not name a coordinate declared in it.
 */
public enum DataplanCoordinate implements SeedCoordinate {
  ;

  /** The domain slug every dataplan coordinate is keyed by — the single source. */
  public static final String DOMAIN = "dataplan";

  /** The fill-by-role coordinate: the reflector serves it, the assembler gathers on it. */
  public static final AmendCoordinate AMEND = new AmendCoordinate(DOMAIN);

  /** The schema-projection coordinate: a sower learns the runbook input's shape through it. */
  public static final ShapeCoordinate SHAPE = new ShapeCoordinate(DOMAIN);

  /** The activation coordinate: a sower plays the layout export through it. */
  public static final RunbookCoordinate RUNBOOK = new RunbookCoordinate(DOMAIN);

  @Override
  public String slug() {
    throw new UnsupportedOperationException(
        "DataplanCoordinate is an empty enum with no slug of its own — use its AMEND / SHAPE /"
            + " RUNBOOK meta-coordinate constants");
  }

  @Override
  public String domain() {
    return DOMAIN;
  }
}
