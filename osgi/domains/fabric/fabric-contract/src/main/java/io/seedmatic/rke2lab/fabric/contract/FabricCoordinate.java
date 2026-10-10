package io.seedmatic.rke2lab.fabric.contract;

import io.seedmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.ShapeCoordinate;

/**
 * The fabric domain's seed coordinates, declared and owned in ONE place. An EMPTY {@code enum}, and
 * here that is the justified case rather than the default one: fabric DELIVERS. It derives nothing,
 * so it has nothing to file behind the cellar and owns no value-coordinate — where {@code
 * DataplanIngressCoordinate.LAYOUT} exists because the dataplan scion HAS a derivation to hand on,
 * fabric is the hand-on itself. It holds only the shared META coordinates it is addressed through:
 * {@link #AMEND} (the reflector serves it, the assembler gathers on it), {@link #SHAPE} (the schema
 * projection), {@link #RUNBOOK} (the delivery trigger) — all keyed by one {@link #DOMAIN}, so the
 * growers never diverge as raw literals.
 */
public enum FabricCoordinate implements SeedCoordinate {
  ;

  /** The domain slug every fabric coordinate is keyed by — the single source. */
  public static final String DOMAIN = "fabric";

  /** The fill-by-role coordinate: the reflector serves it, the assembler gathers on it. */
  public static final AmendCoordinate AMEND = new AmendCoordinate(DOMAIN);

  /** The schema-projection coordinate: a sower learns the runbook input's shape through it. */
  public static final ShapeCoordinate SHAPE = new ShapeCoordinate(DOMAIN);

  /** The activation coordinate: a sower plays the delivery through it. */
  public static final RunbookCoordinate RUNBOOK = new RunbookCoordinate(DOMAIN);

  @Override
  public String slug() {
    throw new UnsupportedOperationException(
        "FabricCoordinate is an empty enum with no slug of its own — use its AMEND / SHAPE /"
            + " RUNBOOK meta-coordinate constants");
  }

  @Override
  public String domain() {
    return DOMAIN;
  }
}
