package io.seedmatic.rke2lab.controlplane.bdd;

import io.seedmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.seedmatic.rke2lab.pulumi.edge.RunMode;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The commissioner's request — the run's facts, captured by {@code Main} INSIDE {@code Pulumi.run}
 * and carried to the scenario's GIVEN through the launcher session store. These are exactly the
 * facts only the Pulumi envelope can know: the {@link RunMode} (live vs preview, from {@code
 * isDryRun}), the {@link Parcel} (project/stack, from the Pulumi context), and the derived {@link
 * BootstrapConfig} (from the Pulumi {@code Config}), plus whether a clean worktree is required (the
 * entry-gate policy). The GIVEN bootstraps the open gardening from them; everything else it builds
 * itself. See docs/architecture/osgi/seed-bdd-module-spec.adoc (§ the amorce).
 *
 * <p>{@code txId} is the run's transaction id (a root-minted UUID) — carried on every {@code sow}
 * so a launched scion inherits it, for AUDIT correlation across the crossing (§
 * cellar-transactional). It is not the drain discriminant (the {@code RunRole} is); it is the
 * observability thread that ties a scion's work back to the run that sowed it.
 *
 * <p>{@code facets} maps a coordinate slug ({@code "manifests"}, {@code "bbox"}) to that domain's
 * raw config subtree, serialized verbatim as a JSON {@code String} — Pulumi-config facts only the
 * envelope can read, carried so the GIVEN can publish each as its domain's {@code FACET} amendment
 * (the host names the neutral coordinate + role, never a domain type). A map rather than a field
 * per domain: a new FACET domain is one entry, and it mirrors the amendment channel's {@code
 * coordinate → value} shape. The {@code "bbox"} subtree is the JOIN of the stack config and {@code
 * .secrets:lan.bbox} (the router {@code uri} + {@code password}), reconciled by {@code
 * ConfigLoader}'s {@code secret:} meta. A coordinate absent from the map (or an empty subtree)
 * falls to the scion's defaults at the amend door.
 */
public record SeedRun(
    RunMode runMode,
    Parcel parcel,
    BootstrapConfig config,
    boolean cleanWorktreeRequired,
    List<String> toleratedWorktreePaths,
    boolean flakeLockRequired,
    String txId,
    Map<String, String> facets) {

  /**
   * The raw config subtree the host read for {@code coordinate} — present iff the domain carried a
   * facet (the KEY is in the map), so consumers decide the fallback rather than inheriting a {@code
   * ""} sentinel. Presence is the deterministic signal (key membership), not the value's shape. The
   * explicit {@code <String>} witness keeps NullAway from widening the nullable {@code get} into
   * the type argument.
   */
  public Optional<String> facet(String coordinate) {
    return Optional.<String>ofNullable(facets.get(coordinate));
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * The recommended construction path: several components of the same type (two {@code boolean}s, a
   * {@code String} txId beside the facet map) are easy to transpose positionally, so {@code Main}
   * names each through this builder rather than the canonical constructor. Facets accumulate by
   * coordinate through {@link #facet}, so a new FACET domain is one call. Nested in the record (a
   * record's canonical constructor cannot be made less visible than the public record, so the
   * builder is recommended-not-enforced); the single {@code Main} construction site routes through
   * it.
   */
  public static final class Builder {
    @Nullable private RunMode runMode;
    @Nullable private Parcel parcel;
    @Nullable private BootstrapConfig config;
    private boolean cleanWorktreeRequired;
    private List<String> toleratedWorktreePaths = List.of();
    private boolean flakeLockRequired;
    @Nullable private String txId;
    private final Map<String, String> facets = new LinkedHashMap<>();

    private Builder() {}

    public Builder runMode(RunMode runMode) {
      this.runMode = runMode;
      return this;
    }

    public Builder parcel(Parcel parcel) {
      this.parcel = parcel;
      return this;
    }

    public Builder config(BootstrapConfig config) {
      this.config = config;
      return this;
    }

    public Builder cleanWorktreeRequired(boolean cleanWorktreeRequired) {
      this.cleanWorktreeRequired = cleanWorktreeRequired;
      return this;
    }

    public Builder toleratedWorktreePaths(List<String> toleratedWorktreePaths) {
      this.toleratedWorktreePaths = toleratedWorktreePaths;
      return this;
    }

    public Builder flakeLockRequired(boolean flakeLockRequired) {
      this.flakeLockRequired = flakeLockRequired;
      return this;
    }

    public Builder txId(String txId) {
      this.txId = txId;
      return this;
    }

    /** Carry {@code coordinate}'s raw config subtree as its FACET; one call per FACET domain. */
    public Builder facet(String coordinate, String subtreeJson) {
      this.facets.put(coordinate, subtreeJson);
      return this;
    }

    public SeedRun build() {
      return new SeedRun(
          Objects.requireNonNull(runMode, "runMode"),
          Objects.requireNonNull(parcel, "parcel"),
          Objects.requireNonNull(config, "config"),
          cleanWorktreeRequired,
          toleratedWorktreePaths,
          flakeLockRequired,
          Objects.requireNonNull(txId, "txId"),
          Map.copyOf(facets));
    }
  }
}
