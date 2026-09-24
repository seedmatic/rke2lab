package io.seedmatic.rke2lab.manifests.contract;

import io.seedmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.ShapeCoordinate;

/**
 * The manifests domain's seed coordinates, declared and owned in ONE place — the single-source
 * discipline its sibling {@code IncusCoordinate} holds, and, like them, an {@code enum} (the {@code
 * contract-purity} law admits only records / enums / sealed ADT / interfaces from a contract module
 * — no concrete class). But where those enumerate the VALUE- coordinates their domain STORES behind
 * the cellar ({@code BBOX_RESERVATIONS}, {@code INCUS_PREP}), manifests SYNTHESISES-and-GRAFTS — it
 * materialises into the soil and stores no harvest (its conservation is delegated to the
 * consulter's plot) — so it owns no value-coordinate. This enum is therefore EMPTY: it enumerates
 * nothing, holding instead, as static constants, the shared META coordinates manifests is addressed
 * through — {@link #AMEND} (the reflector serves it, the assembler gathers on it), {@link #SHAPE}
 * (the schema projection), {@link #RUNBOOK} (the synthesis trigger) — all keyed by one {@link
 * #DOMAIN}, so the growers never diverge as raw literals.
 *
 * <p>These are the OSGi-side single source. A SOWER (incus consulting the amend) and the HOST FACET
 * contributor keep the {@code "manifests"} literal instead — both run where this bundle-only
 * contract is out of reach (incus does not depend on it; the host runs in the FLAT realm, which the
 * realm-boundary law forbids from referencing a bundle package). That seam is guarded not by a
 * shared constant but by BETA: the assembler fails loud on a contributor whose coordinate no grower
 * serves (see docs/architecture/osgi/seed-broker-spec.adoc § Amend integrity).
 */
public enum ManifestsCoordinate implements SeedCoordinate {
  ;

  /** The domain slug every manifests coordinate is keyed by — the single source. */
  public static final String DOMAIN = "manifests";

  /** The fill-by-role coordinate: the reflector serves it, the assembler gathers on it. */
  public static final AmendCoordinate AMEND = new AmendCoordinate(DOMAIN);

  /** The schema-projection coordinate: a sower learns the runbook input's shape through it. */
  public static final ShapeCoordinate SHAPE = new ShapeCoordinate(DOMAIN);

  /** The activation coordinate: a sower plays the synthesis through it. */
  public static final RunbookCoordinate RUNBOOK = new RunbookCoordinate(DOMAIN);

  /**
   * The version-bump coordinate: a sower plays the component-version bump through it. Keyed by its
   * OWN slug ({@code manifests-versions}), NOT {@link #DOMAIN} — the bump is a distinct plant from
   * the synthesis (it mutates the worktree and commits), so it routes to its own runbook handler
   * rather than colliding with {@link #RUNBOOK} on the shared {@code manifests} slug.
   */
  public static final RunbookCoordinate VERSIONS = new RunbookCoordinate("manifests-versions");

  // SeedCoordinate is implemented for family consistency, but this enum is EMPTY — it has no
  // instance, so slug()/domain() are never actually invoked. slug() has no answer (manifests owns
  // no
  // cellarable coordinate of its own; its coordinates are the AMEND/SHAPE/RUNBOOK constants), so it
  // fails loud if a future value ever reaches it; domain() answers the one slug the constants
  // share.
  @Override
  public String slug() {
    throw new UnsupportedOperationException(
        "ManifestsCoordinate is an empty enum with no slug of its own — use its AMEND / SHAPE /"
            + " RUNBOOK meta-coordinate constants");
  }

  @Override
  public String domain() {
    return DOMAIN;
  }
}
