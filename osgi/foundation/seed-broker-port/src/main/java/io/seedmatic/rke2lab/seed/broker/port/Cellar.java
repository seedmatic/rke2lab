package io.seedmatic.rke2lab.seed.broker.port;

import java.util.List;
import java.util.Optional;

/**
 * The TYPED cellar — the codec-aware view of the fridge, its cases peeked/taken as decoded domain
 * values and its timeline read decoded. It exists ONLY in the OSGi world (the realm that owns the
 * domain types and holds a {@code SeedCodec}); the host EDGE never references it — a host provides
 * and consumes only the opaque {@link OpaqueCellar}.
 *
 * <p>It does NOT extend {@link OpaqueCellar}: the realisation ({@code CodecCellar}) is a DECORATOR
 * that takes an {@code OpaqueCellar} by REFERENCE (an OSGi component) and adds the codec, rather
 * than an interface that inherits the opaque verbs. So the two faces stay separable — the opaque
 * one is the seam the host implements, the typed one is the OSGi-only decode layer over it.
 *
 * <p>The typed reads DELEGATE to the realm's codec, which reflects on the {@link Class} passed here
 * — no bundle type is a compile link across the seam ({@code Class<T>} carries its own loader). A
 * consumer needing per-entry tolerance uses {@link #fetch(Parcel, Class)}, which SKIPS the
 * unreadable entries (fail-at-end, the tolerance the {@code *Reader} classes had).
 */
public interface Cellar {

  /**
   * File a decoded value at its coordinate with an explicit {@link Sensitivity} — the typed twin of
   * {@link OpaqueCellar#store}: the impl encodes {@code value} under {@code coordinate} via the
   * realm's codec, SEALS it through the {@code CellarCipher} when {@code sensitivity} is {@link
   * Sensitivity#SEALED} (so its plaintext never rides the run's write set nor reaches the durable
   * backend), then files the envelope. The scion DECLARES the sensitivity here, where the harvest
   * is born (§ cellar-secrets, re-declare where born).
   */
  <T> void store(Parcel parcel, SeedCoordinate coordinate, T value, Sensitivity sensitivity);

  /**
   * File a decoded value in clear — the {@link Sensitivity#PLAIN} convenience over {@link
   * #store(Parcel, SeedCoordinate, Object, Sensitivity)}, the common case (most harvests hold
   * nothing secret). A scion with a secret harvest calls the four-arg form with {@link
   * Sensitivity#SEALED}.
   */
  default <T> void store(Parcel parcel, SeedCoordinate coordinate, T value) {
    store(parcel, coordinate, value, Sensitivity.PLAIN);
  }

  /**
   * File a decoded value with an explicit {@link Persistence} tier — the within-run bus overload.
   * {@link Persistence#DURABLE} is the ordinary store (drained and conserved); {@link
   * Persistence#TRANSIENT} rides the run's overlay and inheritance but is evicted at the drain, so
   * it never reaches the durable backend. A capability, not a core verb — hence a default: only the
   * transactional cellar has a drain to skip and overrides this; a plain durable backend has none,
   * so it honestly treats every store as durable (§ cellar-transactional, the transient tier).
   */
  default <T> void store(
      Parcel parcel,
      SeedCoordinate coordinate,
      T value,
      Sensitivity sensitivity,
      Persistence persistence) {
    store(parcel, coordinate, value, sensitivity);
  }

  /**
   * File a decoded value with an explicit {@link Reach} — the scion DECLARES, at the seal (where
   * the harvest is born), WHERE the value is consumed ({@link Reach#OPERATOR_ONLY} default vs
   * {@link Reach#IN_CLUSTER}). The transactional cellar folds it into the value's {@link Trail}
   * (CLEAR), so a consumer filters SEALED entries by reach without the passphrase (§
   * in-cluster-cellar-asset). A capability, not a core verb — hence a default that DROPS reach;
   * only the trail-stamping {@code ScenarioCellar} overrides it to honour the mark (a cellar that
   * does not stamp trails cannot carry it).
   */
  default <T> void store(
      Parcel parcel, SeedCoordinate coordinate, T value, Sensitivity sensitivity, Reach reach) {
    store(parcel, coordinate, value, sensitivity);
  }

  /**
   * File a value on the within-run bus — the {@link Persistence#TRANSIENT} convenience over the
   * five-arg form, filed {@link Sensitivity#PLAIN} (a transient fact is a produced observation, not
   * a secret at rest). Readable this run (overlay + inheritance), evicted at the drain — it never
   * reaches the durable backend. A transient SECRET, were one ever needed, uses the five-arg form.
   */
  default <T> void storeTransient(Parcel parcel, SeedCoordinate coordinate, T value) {
    store(parcel, coordinate, value, Sensitivity.PLAIN, Persistence.TRANSIENT);
  }

  /**
   * The whole timeline DECODED into {@code type} — one {@code T} per readable entry, oldest first
   * (the doctor's medical record, the ledger). Fail-at-end: an entry the codec cannot read into
   * {@code type} is SKIPPED, the fold continues on the rest. Tombstones (a withdrawn case's marker)
   * are not entries and are skipped too.
   */
  <T> List<T> fetch(Parcel parcel, Class<T> type);

  /**
   * Peek ONE case, DECODED — the current value at {@code coordinate} decoded into {@code type}, or
   * {@link Optional#empty()} if the case is empty (never stored, or withdrawn).
   */
  <T> Optional<T> fetch(Parcel parcel, SeedCoordinate coordinate, Class<T> type);

  /**
   * TAKE one case out, DECODED — hand back the withdrawn value decoded into {@code type} ({@link
   * Optional#empty()} if the case was empty) and leave the case empty until re-stored.
   */
  <T> Optional<T> withdraw(Parcel parcel, SeedCoordinate coordinate, Class<T> type);

  /**
   * The parcel's neighbouring cellars — the sibling parcels sharing the same soil (the parcel's own
   * first). Codec-NEUTRAL (it names only {@link Parcel}s, no envelope, no decode), so it rides the
   * typed face too — a consumer folding a cohort (the doctor) references one {@code Cellar}, not
   * also the opaque one. Delegated verbatim to the underlying {@link OpaqueCellar}.
   */
  List<Parcel> neighbours(Parcel parcel);

  /**
   * The {@link Trail} of the current value at {@code coordinate} — its origin lineage (breadcrumbs
   * back to the git commit it was cultivated from) AND its producer-declared {@link Reach}, both
   * readable WITHOUT decoding the (possibly SEALED) payload, since the trail rides CLEAR on the
   * {@link SeedEnvelope}. Stamped by the transactional cellar within a run AND persisted across the
   * durable round-trip (the {@code PulumiCellar} coquille carries the trail; {@code CodecCellar}
   * reads it back off the opaque envelope). Empty only when the case is empty OR when this cellar
   * tracks no trail (the default). A capability, not a core verb — hence a default, not an abstract
   * method every {@code Cellar} must realise.
   */
  default Optional<Trail> trailOf(Parcel parcel, SeedCoordinate coordinate) {
    return Optional.empty();
  }
}
