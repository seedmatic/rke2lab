package io.seedmatic.rke2lab.dataplan.contract;

import io.seedmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.ShapeCoordinate;

/**
 * The dataplan domain's seed coordinates, declared and owned in ONE place — the single-source
 * discipline its sibling {@code NetplanCoordinate} holds. It carries ONE value, {@link #LAYOUT},
 * and the shared META coordinates dataplan is addressed through as static constants: {@link #AMEND}
 * (the reflector serves it, the assembler gathers on it), {@link #SHAPE} (the schema projection),
 * {@link #RUNBOOK} (the export trigger) — all keyed by one {@link #DOMAIN}, so the growers never
 * diverge as raw literals.
 *
 * <p>{@link #LAYOUT} lives HERE and not in a dual-realm ingress module, which is the whole
 * difference with {@code NetplanIngressCoordinate}: that one is staged in both realms "because the
 * host fetches it" — {@code ClusterSeedScenario} reads netplan's addressing for the operator's
 * kubeconfig contexts. NOTHING host-side reads the dataset tree (measured: zero references to
 * {@code DataplanLayout} outside the bundle realm); its readers are the three storage manifests
 * units and the fabric delivery, all OSGi. A dual-realm module would stage a bundle flat host-side
 * for no consumer.
 */
public enum DataplanCoordinate implements SeedCoordinate {
  /**
   * The layout the export DERIVES ({@code DataplanLayout.canonical()}, the {@code tank/rke2lab/*}
   * dataset tree) — stored behind the cellar when the run publishes a {@code Parcel}, so the fabric
   * delivery reads the tree from the run instead of re-reading the soil file. The same derivation
   * the export materialises as {@code dataplan.json} and fabric/plan delivers.
   */
  LAYOUT("dataplan-layout");

  /** The domain slug every dataplan coordinate is keyed by — the single source. */
  public static final String DOMAIN = "dataplan";

  /** The fill-by-role coordinate: the reflector serves it, the assembler gathers on it. */
  public static final AmendCoordinate AMEND = new AmendCoordinate(DOMAIN);

  /** The schema-projection coordinate: a sower learns the runbook input's shape through it. */
  public static final ShapeCoordinate SHAPE = new ShapeCoordinate(DOMAIN);

  /** The activation coordinate: a sower plays the layout export through it. */
  public static final RunbookCoordinate RUNBOOK = new RunbookCoordinate(DOMAIN);

  private final String slug;

  DataplanCoordinate(String slug) {
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
