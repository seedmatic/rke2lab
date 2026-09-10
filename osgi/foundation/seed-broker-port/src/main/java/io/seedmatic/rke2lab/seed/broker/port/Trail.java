package io.seedmatic.rke2lab.seed.broker.port;

import java.util.ArrayList;
import java.util.List;

/**
 * A path-to-origin as an ordered trail of {@link Breadcrumb}s, ROOT-FIRST — ONE mechanism serving
 * two lineages (§ fil-d-ariane): a value's fil d'Ariane (a trail of {@link SourceCrumb}s, root the
 * git commit it descends from) and a grafted failure's crossing path (a trail of {@link Crossing}s,
 * root the outermost crossing). Rides CLEAR on the {@link SeedEnvelope}, OUTSIDE the sealed
 * payload, so a SEALED value's lineage is traceable back to its source commit WITHOUT the
 * passphrase — secured-value traceability. Immutable: both {@link #push} and {@link #prepend}
 * return a NEW trail, the receiver unchanged. The two directions serve the two builders: the cellar
 * {@link #push}es as it DESCENDS (root filed first), a grafted failure {@link #prepend}s as it
 * RETURNS up the chain (leaf grew first) — both land ROOT-FIRST.
 *
 * <p>Beyond the breadcrumbs (origin), the trail carries a producer-declared {@link Reach}
 * (destination/audience) — orthogonal metadata, also CLEAR, so a consumer filters SEALED entries by
 * reach without the passphrase. See {@link Reach}.
 */
public record Trail(List<Breadcrumb> breadcrumbs, Reach reach) {

  public Trail {
    breadcrumbs = List.copyOf(breadcrumbs);
    // Absent on coquilles filed before reach existed → the fail-closed default (never in-cluster).
    reach = reach == null ? Reach.OPERATOR_ONLY : reach;
  }

  /**
   * Backward-compatible form — {@code reach} defaults to {@link Reach#OPERATOR_ONLY}. Keeps every
   * lineage-only call site (which never declares reach) building unchanged.
   */
  public Trail(List<Breadcrumb> breadcrumbs) {
    this(breadcrumbs, Reach.OPERATOR_ONLY);
  }

  /** The empty trail — an unstamped value, or a store filed before any provenance root is known. */
  public static Trail empty() {
    return new Trail(List.of(), Reach.OPERATOR_ONLY);
  }

  /** A new trail with {@code crumb} appended (leaf-ward); this trail is left unchanged. */
  public Trail push(Breadcrumb crumb) {
    final List<Breadcrumb> appended = new ArrayList<>(breadcrumbs);
    appended.add(crumb);
    return new Trail(appended, reach);
  }

  /** A new trail with {@code crumb} at the head (root-ward); this trail is left unchanged. */
  public Trail prepend(Breadcrumb crumb) {
    final List<Breadcrumb> rooted = new ArrayList<>(breadcrumbs.size() + 1);
    rooted.add(crumb);
    rooted.addAll(breadcrumbs);
    return new Trail(rooted, reach);
  }

  /**
   * A new trail carrying {@code reach}; breadcrumbs unchanged (the seal stamps its destination).
   */
  public Trail withReach(Reach reach) {
    return new Trail(breadcrumbs, reach);
  }
}
