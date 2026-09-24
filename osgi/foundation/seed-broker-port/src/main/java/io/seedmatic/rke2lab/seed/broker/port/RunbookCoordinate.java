package io.seedmatic.rke2lab.seed.broker.port;

/**
 * The "play this soil's scenario and hand me its runbook" coordinate. Like {@link SplitCoordinate},
 * a VALUE coordinate, not a per-domain enum constant: the host holds only a soil name (a {@code
 * String} — "worktree", "cluster") and sows {@code new RunbookCoordinate(soil)}; a domain
 * contributes a runbook handler serving {@code new RunbookCoordinate(itsDomain)}. Two equal records
 * route to the same handler ({@code DefaultSeedBroker} keys on {@code equals}), so the host names
 * NO domain's coordinate type — the gardening register knows a parcel by its soil name, never a
 * domain's vocabulary.
 *
 * <p>This is what keeps the sow-and-graft caller pure jardinerie: the SOW half is {@code
 * broker.sow(new RunbookCoordinate(soil), trigger)} and reaps the runbook as an opaque {@link
 * SeedEnvelope} (the serialized {@code RunbookEnvelope}); the handler plays its OWN scenario
 * in-container behind the door, so the reflection into a {@code *BddScenarios} front-door stays
 * OSGi-side, in the domain's realm. The host neither names nor reaches a domain type. See
 * docs/architecture/osgi/seed-broker-spec.adoc (§ playing a scion scenario is a sow).
 */
public record RunbookCoordinate(String domain) implements ValueCoordinate {

  /** The single wire slug of the play-scenario verb, across every domain. */
  public static final String SLUG = "runbook";

  @Override
  public String slug() {
    return SLUG;
  }
}
