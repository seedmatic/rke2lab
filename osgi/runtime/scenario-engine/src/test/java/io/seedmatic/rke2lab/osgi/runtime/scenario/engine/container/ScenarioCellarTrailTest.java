package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.jgiven.report.model.ReportModel;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.CellarCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.Sensitivity;
import io.seedmatic.rke2lab.seed.broker.port.SourceCrumb;
import io.seedmatic.rke2lab.seed.broker.port.Trail;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The fil d'Ariane's CROSSING PATH (§ fil-d-ariane) — the intra-run accumulation, proven on the
 * transactional cellar directly (a bare {@link ReportModel}, no launcher, no live sow). It locks
 * the two pieces the sow relies on: {@link ScenarioCellar#store} stamps {@code path.push(here)} off
 * the {@code RUN_PROVENANCE} {@link Trail}, and {@link
 * ScenarioCellar#entriesEncoded(SeedCoordinate)} EXTENDS that path with the sown child's crossing
 * crumb as the entries descend — so a value stored one sow deep reads the full route {@code root →
 * crossing → here}, not just {@code [root, here]}.
 */
class ScenarioCellarTrailTest {

  private static final Parcel PARCEL = new Parcel("bioskop", "dev");
  private static final String SHA = "abc123";

  private enum Value implements SeedCoordinate {
    INCUS_FACTS("incus", "incus-facts"),
    MANIFESTS_TREE("manifests", "manifests-tree");

    private final String domain;
    private final String slug;

    Value(String domain, String slug) {
      this.domain = domain;
      this.slug = slug;
    }

    @Override
    public String slug() {
      return slug;
    }

    @Override
    public String domain() {
      return domain;
    }
  }

  /**
   * A ScenarioCellar over a fresh model, its durable side a no-op (every read here hits the
   * overlay).
   */
  private static ScenarioCellar overFreshModel() {
    final ReportModel model = new ReportModel();
    return new ScenarioCellar(() -> model, ScenarioCellarTrailTest::noDurable, Optional.empty());
  }

  private static Cellar noDurable() {
    return new Cellar() {
      @Override
      public <T> void store(Parcel parcel, SeedCoordinate coordinate, T value, Sensitivity s) {}

      @Override
      public <T> List<T> fetch(Parcel parcel, Class<T> type) {
        return List.of();
      }

      @Override
      public <T> Optional<T> fetch(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
        return Optional.empty();
      }

      @Override
      public <T> Optional<T> withdraw(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
        return Optional.empty();
      }

      @Override
      public List<Parcel> neighbours(Parcel parcel) {
        return List.of(parcel);
      }
    };
  }

  private static SourceCrumb gitRoot() {
    return new SourceCrumb("worktree", "worktree-facts", SHA, false);
  }

  @Test
  void aStoreStampsTheProvenancePathPlusItsOwnLink() {
    final ScenarioCellar cellar = overFreshModel();
    cellar.store(PARCEL, CellarCoordinate.RUN_PROVENANCE, new Trail(List.of(gitRoot())));

    cellar.store(PARCEL, Value.INCUS_FACTS, "harvest");

    assertEquals(
        Optional.of(
            new Trail(List.of(gitRoot(), new SourceCrumb("incus", "incus-facts", SHA, false)))),
        cellar.trailOf(PARCEL, Value.INCUS_FACTS),
        "the trail is the root path pushed with this coordinate's link");
  }

  @Test
  void aSownScionInheritsTheExtendedPathAndAccumulatesTheFullRoute() {
    // Parent (e.g. incus): plant the root, then hand its entries DOWN toward a "manifests" sub-sow
    // —
    // the handler's gesture, transaction.entriesEncoded(coordinate()).
    final ScenarioCellar parent = overFreshModel();
    parent.store(PARCEL, CellarCoordinate.RUN_PROVENANCE, new Trail(List.of(gitRoot())));
    final List<String> descending = parent.entriesEncoded(new RunbookCoordinate("manifests"));

    // Child (manifests): inherit the descended entries, then store its own value.
    final ScenarioCellar child = overFreshModel();
    child.inheritEntries(descending);
    child.store(PARCEL, Value.MANIFESTS_TREE, "synthesized");

    assertEquals(
        Optional.of(
            new Trail(
                List.of(
                    gitRoot(),
                    new SourceCrumb("manifests", "runbook", SHA, false),
                    new SourceCrumb("manifests", "manifests-tree", SHA, false)))),
        child.trailOf(PARCEL, Value.MANIFESTS_TREE),
        "the child accumulates root → crossing → here (the full route)");
  }
}
