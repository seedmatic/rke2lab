package io.seedmatic.rke2lab.netplan.contract;

import io.seedmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.ShapeCoordinate;

/**
 * The netplan domain's seed coordinates, declared and owned in ONE place — the single-source
 * discipline its siblings {@code ManifestsCoordinate} / {@code IncusCoordinate} hold, and, like
 * {@code ManifestsCoordinate}, an EMPTY {@code enum}: netplan SYNTHESISES-and-MATERIALISES (the
 * blueprint export writes {@code blueprint.json} into the soil). The one value it harvests behind
 * the cellar, its projection, is keyed by {@code NetplanIngressCoordinate} in the dual-realm {@code
 * netplan-ingress-contract}, because the host fetches it. It enumerates nothing, holding instead,
 * as static constants, the shared META coordinates netplan is addressed through — {@link #AMEND}
 * (the reflector serves it, the assembler gathers on it), {@link #SHAPE} (the schema projection),
 * {@link #RUNBOOK} (the export trigger) — all keyed by one {@link #DOMAIN}, so the growers never
 * diverge as raw literals.
 */
public enum NetplanCoordinate implements SeedCoordinate {
  ;

  /** The domain slug every netplan coordinate is keyed by — the single source. */
  public static final String DOMAIN = "netplan";

  /** The fill-by-role coordinate: the reflector serves it, the assembler gathers on it. */
  public static final AmendCoordinate AMEND = new AmendCoordinate(DOMAIN);

  /** The schema-projection coordinate: a sower learns the runbook input's shape through it. */
  public static final ShapeCoordinate SHAPE = new ShapeCoordinate(DOMAIN);

  /** The activation coordinate: a sower plays the blueprint export through it. */
  public static final RunbookCoordinate RUNBOOK = new RunbookCoordinate(DOMAIN);

  @Override
  public String slug() {
    throw new UnsupportedOperationException(
        "NetplanCoordinate is an empty enum with no slug of its own — use its AMEND / SHAPE /"
            + " RUNBOOK meta-coordinate constants");
  }

  @Override
  public String domain() {
    return DOMAIN;
  }
}
