package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.report.model.ReportModel;
import io.seedmatic.rke2lab.seed.broker.codec.PassphraseCellarCipher;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.CellarCipher;
import io.seedmatic.rke2lab.seed.broker.port.CellarCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.Persistence;
import io.seedmatic.rke2lab.seed.broker.port.Reach;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.port.Sensitivity;
import io.seedmatic.rke2lab.seed.broker.port.SourceCrumb;
import io.seedmatic.rke2lab.seed.broker.port.Trail;
import io.seedmatic.rke2lab.seed.broker.port.TransactionalCellar;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The UNIVERSAL transactional cellar: the single {@link Cellar} every scenario — host root AND
 * in-container scion — is handed, and the SOLE writer of a tag on the run's {@link ReportModel}.
 * {@code store} does NOT touch the durable backend: it posts the codec-encoded value as a {@link
 * tag} on the model (the within-run write set). The graft folds a scion's tags into the host trunk,
 * and at the run boundary the root's {@code ScenarioCellarExtension} DRAINS the folded tags to the
 * durable {@link io.seedmatic.rke2lab.seed.broker.port.OpaqueCellar} — so a fragment never
 * persists, only the root does, atomically (§ seed-broker-spec, cellar-transactional).
 *
 * <p>The reads are an OVERLAY: the run's own read-write set (its {@code store}s and {@code
 * withdraw} tombstones on the model) takes precedence, the durable {@link Cellar} is the fallback.
 * So a scenario reads back what it just wrote (read-your-writes) before the drain has made anything
 * durable, and a {@code withdraw} shadows the durable case for the rest of the run. {@code
 * neighbours} alone is pure delegation — it names parcels (topology), not values, so the set never
 * shadows it. A scenario with no durable read side (a pure in-container fragment whose reads are
 * mocked) is handed a delegate for the fallback; the overlay itself needs only the model.
 *
 * <p>The {@link ReportModel} is read LAZILY (a {@link Supplier}, resolved at the first {@code
 * store}), so the extension can inject this cellar before jGiven has bound the model to the
 * scenario — the order between the two post-processors is a non-issue.
 */
public final class ScenarioCellar implements TransactionalCellar {

  private final Supplier<ReportModel> model;
  private final Supplier<Cellar> durableReads;
  private final Optional<String> txId;
  private final SeedCodec codec = new SeedCodec();
  // The clean/smudge filter (§ cellar-secrets). A SEALED store is sealed HERE, so its sealed
  // payload
  // is what rides the run's write-set tag across the graft and drains to the durable backend — the
  // harvest plaintext never crosses the seam. The cipher is the injected CellarCipher (age) the
  // extension resolves when its bundle is provisioned; where it is absent the mono passphrase
  // stand-in fills in (the three-arg constructor the isolated tests use).
  private final CellarCipher cipher;

  /**
   * The passphrase-default construction — the mono clean/smudge filter for a run with no injected
   * cipher (an isolated test, or a world where {@code cellar-cipher-age} is not provisioned).
   *
   * @param model the run's {@link ReportModel} (read lazily, but live already when the extension
   *     constructs this — jGiven binds it before the body)
   * @param durableReads the durable read side, resolved on first fetch
   * @param txId the run's transaction id, or {@link Optional#empty()} for a run outside a
   *     transaction — the cellar is its lifecycle-mate, so it POSTS the tx-id tag here (the sole
   *     writer of a tag on the model), and a scion sowing a sub-scion reads it back via {@link
   *     #transactionId()}
   */
  public ScenarioCellar(
      Supplier<ReportModel> model, Supplier<Cellar> durableReads, Optional<String> txId) {
    this(model, durableReads, txId, new PassphraseCellarCipher());
  }

  /**
   * As {@link #ScenarioCellar(Supplier, Supplier, Optional)}, with the {@link CellarCipher} the
   * {@code ScenarioCellarExtension} resolved from the registry (the age impl where provisioned) —
   * so a SEALED store is sealed with the real cellar cipher, not the passphrase stand-in.
   */
  public ScenarioCellar(
      Supplier<ReportModel> model,
      Supplier<Cellar> durableReads,
      Optional<String> txId,
      CellarCipher cipher) {
    this.model = model;
    this.durableReads = durableReads;
    this.txId = txId;
    this.cipher = cipher;
    txId.ifPresent(id -> model.get().addTag(Tag.TRANSACTION.of(id)));
  }

  /**
   * The run's transaction id, or empty when this cellar is not transactional (a scenario played
   * outside a run — its own isolated test). A scion sowing a sub-scion passes this straight to the
   * sow: present ⇒ the sub-scion inherits the tx; empty ⇒ a non-transactional play, legitimately no
   * correlation. So "error if transactional, tolerated otherwise" is encoded by the value itself —
   * no ad-hoc emptiness check at the call site.
   */
  @Override
  public Optional<String> transactionId() {
    return txId;
  }

  /**
   * This run's entries as flat encoded strings, handed DOWN to the {@code sownChild} sub-scion so
   * it inherits the transaction's in-flight stores (the ALLER sense, § seed-broker-spec, the
   * entries descend); the sub-scion's extension re-posts them via {@link #inheritEntries}. As they
   * descend, the run's provenance PATH — the {@link Trail} carried by the {@link
   * CellarCoordinate#RUN_PROVENANCE} entry — is EXTENDED with {@code sownChild}'s crossing crumb,
   * so a value the child stores carries the full route {@code root → … → child → here}, not just
   * {@code [root, here]}. The cellar is the sole reader/writer of its {@link Entry} format, so the
   * rewrite lives here. Flat by construction, so nothing live crosses the launcher membrane.
   */
  @Override
  public List<String> entriesEncoded(SeedCoordinate sownChild) {
    return entriesOf(model.get()).stream()
        .map(entry -> isRunProvenanceEntry(entry) ? extendPath(entry, sownChild) : entry)
        .map(codec::encode)
        .toList();
  }

  /** Whether {@code entry} carries the run's provenance path (the {@link Trail}-bearing root). */
  private boolean isRunProvenanceEntry(Entry entry) {
    return entry.envelope().domain().equals(CellarCoordinate.RUN_PROVENANCE.domain())
        && entry.envelope().coordinate().equals(CellarCoordinate.RUN_PROVENANCE.slug());
  }

  /**
   * Extend the provenance-path entry with a crossing crumb for the {@code sownChild} being
   * launched: a {@link SourceCrumb} under the sown coordinate, carrying the run's git source (the
   * path root's {@code sha}/{@code dirty}) so each link stays a complete source coordinate. The
   * child inherits this longer path and stamps its own stores {@code path.push(here)} — the
   * accumulation IS the route. RUN_PROVENANCE is never sealed, so its payload decodes straight to a
   * {@link Trail}.
   */
  private Entry extendPath(Entry entry, SeedCoordinate sownChild) {
    final Trail path = codec.decode(entry.envelope().payload(), Trail.class);
    final SourceCrumb root =
        path.breadcrumbs().isEmpty() ? null : (SourceCrumb) path.breadcrumbs().get(0);
    final SourceCrumb crossing =
        new SourceCrumb(
            sownChild.domain(),
            sownChild.slug(),
            root == null ? "" : root.sha(),
            root != null && root.dirty());
    final SeedEnvelope env = entry.envelope();
    final SeedEnvelope extended =
        new SeedEnvelope(
            env.domain(), env.coordinate(), codec.encode(path.push(crossing)), env.trail());
    return new Entry(
        entry.parcel(), extended, entry.tombstone(), entry.inherited(), entry.persistence());
  }

  /**
   * One accumulated cellar operation as it rides the model: the {@link Parcel} it is filed under,
   * the {@link SeedEnvelope} (its {@code coordinate} always meaningful; its {@code payload} the
   * encoded value for a store, empty for a tombstone), and whether it is a {@code tombstone} — a
   * {@code withdraw} that empties the case. Flat (the codec serialises it to the tag's value
   * String). Entries ride in store ORDER, so the drain replays each in turn: a store re-stores the
   * envelope, a tombstone withdraws the case (a store→withdraw→store sequence lands the case
   * present, as it must). Its {@code persistence} is the drain verdict: a {@link
   * Persistence#TRANSIENT} entry rides the overlay and inheritance like any other but the drain
   * SKIPS it (the within-run bus, § cellar-transactional), so it never reaches the durable backend.
   */
  public record Entry(
      Parcel parcel,
      SeedEnvelope envelope,
      boolean tombstone,
      boolean inherited,
      Persistence persistence) {

    /**
     * The same operation seen from a CHILD crossing — marked {@code inherited} so the overlay reads
     * it (read-your-parent's-writes) but the child's graft does NOT fold it back up into the trunk
     * (the trunk already holds it; the extension strips it first). Orthogonal to {@code tombstone}:
     * a parent's WITHDRAW descends as an inherited tombstone (else the child re-reads a durable
     * case the parent emptied in-flight). Persistence rides down unchanged — a transient store the
     * parent made is transient for the child too (the child reads it, the drain still skips it).
     */
    Entry asInherited() {
      return new Entry(parcel, envelope, tombstone, true, persistence);
    }
  }

  /**
   * The accumulated {@link Entry entries} on a {@code model}, decoded from the {@link Tag#ENTRY}
   * tags — the cellar is the SOLE writer of that tag, so it is also the sole READER of its format.
   * Package-private: the run-boundary DRAIN (its lifecycle-mate {@code ScenarioCellarExtension},
   * same package) consumes it, and the overlay reads use it internally; the format stays the
   * cellar's private concern (a test reads back through the generic {@link #fetch} API, not this).
   * Order is the model's tag order, i.e. store order.
   */
  static List<Entry> entriesOf(ReportModel model) {
    final SeedCodec codec = new SeedCodec();
    return model.getTagMap().values().stream()
        .filter(tag -> Tag.ENTRY.type().equals(tag.getType()))
        .flatMap(tag -> tag.getValues().stream())
        .map(value -> codec.decode(value, Entry.class))
        .toList();
  }

  /**
   * Re-post a parent transaction's entries onto THIS run's model, each marked {@code inherited} —
   * the ALLER sense of the transaction (the child reads its parent's in-flight stores as its own
   * overlay). Called by the extension BEFORE the body, so the inherited entries precede the child's
   * own stores in tag order: on a shared coordinate the child's own store (posted later) wins,
   * giving own {@literal >} inherited {@literal >} durable for free. The cellar is the SOLE tag
   * writer, so this re-posting lives here, not in the extension.
   */
  public void inheritEntries(List<String> encodedEntries) {
    for (String encoded : encodedEntries) {
      append(codec.decode(encoded, Entry.class).asInherited());
    }
  }

  /**
   * Remove the inherited entries from {@code model}'s tag map — the child's extension calls this
   * AFTER the body, before the graft folds the model up into the trunk, so an inherited entry
   * (which the trunk already holds from the parent crossing) is not folded up a SECOND time and
   * drained twice. Clean on jGiven 2.0.3: a cellar tag is added via {@code ReportModel.addTag} (the
   * tag map alone) and never referenced by a {@code ScenarioModel.tagIds}, so removing it leaves no
   * dangling id. Each {@link Tag#ENTRY} tag holds exactly one entry (a distinct {@code
   * toIdString}), so the per-tag test is unambiguous.
   */
  public static void stripInherited(ReportModel model) {
    final SeedCodec codec = new SeedCodec();
    model
        .getTagMap()
        .values()
        .removeIf(
            tag ->
                Tag.ENTRY.type().equals(tag.getType())
                    && tag.getValues().stream()
                        .anyMatch(value -> codec.decode(value, Entry.class).inherited()));
  }

  /**
   * The cellar's tags on a scenario's {@code ReportModel} — nested here because the cellar is their
   * SOLE writer (§ seed-broker-spec cellar-transactional). Referenced qualified ({@code
   * ScenarioCellar.Tag}), so it does not clash with jGiven's {@code
   * com.tngtech.jgiven.report.model.Tag}. The graft folds them into the root tree; the drain reads
   * {@link #ENTRY}, a scion sowing a sub-scion reads {@link #TRANSACTION}.
   */
  public enum Tag implements ScenarioTag {

    /** An accumulated store — the within-run write set the root drains to the durable backend. */
    ENTRY("cellar-entry"),

    /** The run's transaction id (a root-minted UUID) — audit correlation across the crossing. */
    TRANSACTION("tx-id");

    private final String type;

    Tag(String type) {
      this.type = type;
    }

    @Override
    public String type() {
      return type;
    }
  }

  @Override
  public <T> void store(
      Parcel parcel, SeedCoordinate coordinate, T value, Sensitivity sensitivity) {
    storeCore(parcel, coordinate, value, sensitivity, Persistence.DURABLE, Reach.OPERATOR_ONLY);
  }

  @Override
  public <T> void store(
      Parcel parcel,
      SeedCoordinate coordinate,
      T value,
      Sensitivity sensitivity,
      Persistence persistence) {
    storeCore(parcel, coordinate, value, sensitivity, persistence, Reach.OPERATOR_ONLY);
  }

  @Override
  public <T> void store(
      Parcel parcel, SeedCoordinate coordinate, T value, Sensitivity sensitivity, Reach reach) {
    storeCore(parcel, coordinate, value, sensitivity, Persistence.DURABLE, reach);
  }

  private <T> void storeCore(
      Parcel parcel,
      SeedCoordinate coordinate,
      T value,
      Sensitivity sensitivity,
      Persistence persistence,
      Reach reach) {
    final String encoded = codec.encode(value);
    final String payload = sensitivity == Sensitivity.SEALED ? cipher.seal(encoded) : encoded;
    final SeedEnvelope envelope =
        SeedEnvelope.of(coordinate, payload)
            .withTrail(trailFor(parcel, coordinate).withReach(reach));
    append(new Entry(parcel, envelope, false, false, persistence));
  }

  /** A portable bundle of SEALED envelopes — the wire shape {@link #exportReaching} serialises. */
  private record CellarAsset(List<SeedEnvelope> envelopes) {}

  /**
   * The overlay's current SEALED envelopes at {@code parcel} whose producer-declared {@link Reach}
   * is {@code reach}, serialised to a portable JSON asset — the in-cluster render EXTRACTS the
   * {@link Reach#IN_CLUSTER} set to a sops-encrypted branch file so a later secret-blind render
   * rehydrates it (§ in-cluster-cellar-asset). Reads the OVERLAY only (the run's own/inherited
   * seals on a grow, the {@link #importSealed rehydrated} envelopes on a publish); payloads stay
   * SEALED — the passphrase never leaves the run, the sops layer on the asset file is the real
   * protection. Empty when nothing reaches {@code reach} (a mgmt-only grow, a survey).
   */
  public Optional<String> exportReaching(Parcel parcel, Reach reach) {
    final List<SeedEnvelope> reaching =
        currentSetEntries(parcel).stream()
            .map(Entry::envelope)
            .filter(envelope -> envelope.trail().reach() == reach)
            .toList();
    return reaching.isEmpty()
        ? Optional.empty()
        : Optional.of(codec.encode(new CellarAsset(reaching)));
  }

  /**
   * Rehydrate the SEALED envelopes of a branch asset (produced by {@link #exportReaching}) onto
   * this run's overlay — the read-back a secret-blind in-cluster render needs: a publish sows no
   * seal, so the reveals would find nothing and Flux would prune the branch secrets; rehydrating
   * the asset's envelopes makes the reveals resolve as if the operator's seal had run. Each is
   * filed {@link Persistence#TRANSIENT} (a read-side rehydration, never re-drained) and SKIPPED
   * when the coordinate already carries an overlay entry, so a re-grow's FRESH seal always wins
   * over a stale asset (own {@literal >} rehydrated). The payload stays sealed; a reveal opens it
   * with the same cellar cipher that sealed it.
   */
  public void importSealed(Parcel parcel, String assetJson) {
    for (SeedEnvelope sealed : codec.decode(assetJson, CellarAsset.class).envelopes()) {
      final boolean present =
          setEntriesFor(parcel).stream()
              .anyMatch(entry -> entry.envelope().coordinate().equals(sealed.coordinate()));
      if (!present) {
        append(new Entry(parcel, sealed, false, false, Persistence.TRANSIENT));
      }
    }
  }

  /**
   * Stamp the value's fil d'Ariane at {@code coordinate}: the run's provenance PATH — read back
   * from {@link CellarCoordinate#RUN_PROVENANCE} — followed by THIS coordinate's link. The path is
   * the full route the transaction took to reach here: the git root (filed by the worktree
   * crossing) and a crossing crumb per sow above this store ({@code root → … → here}), each
   * crossing appended as the entries descended (see {@link #entriesEncoded}); it reaches this
   * crossing by the ordinary transactional inheritance. {@code here} carries the same git source
   * (the path root's), so each link is a complete source coordinate. The {@code RUN_PROVENANCE}
   * declaration itself carries no lineage (it IS the root). A store filed before any provenance is
   * known yields a lone self-link with an empty sha — a legitimate pre-provenance root.
   */
  private Trail trailFor(Parcel parcel, SeedCoordinate coordinate) {
    if (isRunProvenance(coordinate)) {
      return Trail.empty();
    }
    final Trail path = runProvenance(parcel);
    final Optional<SourceCrumb> root =
        path.breadcrumbs().stream().findFirst().map(SourceCrumb.class::cast);
    final SourceCrumb here =
        new SourceCrumb(
            coordinate.domain(),
            coordinate.slug(),
            root.map(SourceCrumb::sha).orElse(""),
            root.map(SourceCrumb::dirty).orElse(false));
    return path.push(here);
  }

  /**
   * The run's provenance PATH from the OVERLAY only (the run's own store or a crossing it
   * inherited), never the durable fallback — a prior run's durable provenance must not root THIS
   * run's stores. The {@link Trail} the {@code RUN_PROVENANCE} entry carries: the git root plus a
   * crumb per crossing sown above the reader (§ fil-d-ariane, the crossing path). Empty until the
   * first crossing harvests HEAD. RUN_PROVENANCE is never sealed — the reveal is a defensive no-op.
   */
  private Trail runProvenance(Parcel parcel) {
    return latestSetEntry(parcel, CellarCoordinate.RUN_PROVENANCE)
        .filter(entry -> !entry.tombstone())
        .map(entry -> codec.decode(cipher.reveal(entry.envelope().payload()), Trail.class))
        .orElse(Trail.empty());
  }

  private boolean isRunProvenance(SeedCoordinate coordinate) {
    return coordinate.domain().equals(CellarCoordinate.RUN_PROVENANCE.domain())
        && coordinate.slug().equals(CellarCoordinate.RUN_PROVENANCE.slug());
  }

  /** Append one operation to the read-write set — a new {@link Tag#ENTRY} tag, in store order. */
  private void append(Entry entry) {
    model.get().addTag(Tag.ENTRY.of(codec.encode(entry)));
  }

  /**
   * Peek ONE case, DECODED — an OVERLAY read: the run's own read-write set (the {@link Tag#ENTRY}
   * tags on the model) takes precedence over the durable backend, so a scenario reads back what it
   * just {@code store}d (read-your-writes) before the drain has made anything durable. The set's
   * LAST entry for {@code (parcel, coordinate)} wins (a re-store overwrites); only when the set has
   * nothing for that case does it fall through to the durable read side.
   */
  @Override
  public <T> Optional<T> fetch(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
    final Optional<Entry> latest = latestSetEntry(parcel, coordinate);
    if (latest.isEmpty()) {
      return durableReads
          .get()
          .fetch(parcel, coordinate, type); // set silent here → the durable case
    }
    // The set has the last word on this case: a store → its value; a tombstone → EMPTY, and it does
    // NOT fall back to the durable (the withdraw's intent is "gone for this run", the durable is
    // shadowed, not consulted).
    return latest
        .filter(entry -> !entry.tombstone())
        .map(entry -> codec.decode(cipher.reveal(entry.envelope().payload()), type));
  }

  /**
   * The whole timeline DECODED — the OVERLAY of the read-write set over the durable timeline: the
   * run's own CURRENT values for {@code parcel} first (the last entry per coordinate; a tombstoned
   * coordinate contributes nothing), then the durable timeline. Fail-at-end: an entry the codec
   * cannot read into {@code type} is skipped, so a fold over one domain's records tolerates a
   * foreign coordinate in the set.
   */
  @Override
  public <T> List<T> fetch(Parcel parcel, Class<T> type) {
    final List<T> overlaid = new ArrayList<>();
    for (Entry entry : currentSetEntries(parcel)) {
      try {
        overlaid.add(codec.decode(cipher.reveal(entry.envelope().payload()), type));
      } catch (RuntimeException skip) {
        // not readable into type (a foreign coordinate) — skip, keep the fold going.
      }
    }
    overlaid.addAll(durableReads.get().fetch(parcel, type));
    return overlaid;
  }

  /**
   * TAKE one case out, DECODED — the OVERLAY value ({@link #fetch(Parcel, SeedCoordinate, Class)}:
   * the run's own store, else the durable one), then RECORD the withdraw as a tombstone {@link
   * Entry} in the read-write set. The tombstone empties the case within the run (a later {@code
   * fetch} sees the case gone) and is replayed at the drain as a durable {@code withdraw} — the set
   * is the transaction, so the take is deferred like every other write, not applied to the durable
   * backend now.
   */
  @Override
  public <T> Optional<T> withdraw(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
    final Optional<T> current = fetch(parcel, coordinate, type);
    append(new Entry(parcel, SeedEnvelope.of(coordinate, ""), true, false, Persistence.DURABLE));
    return current;
  }

  @Override
  public List<Parcel> neighbours(Parcel parcel) {
    return durableReads.get().neighbours(parcel);
  }

  /**
   * The current value's fil d'Ariane at {@code coordinate} — the OVERLAY of the run's write set
   * over the durable edge, the same precedence as {@link #fetch(Parcel, SeedCoordinate, Class)}:
   * the trail stamped on the last {@link Entry} filed there wins; a tombstoned case reads empty and
   * does NOT fall back (the withdraw's intent is "gone for this run"); a case the write set is
   * silent on falls through to the durable {@link Cellar#trailOf}. Reads the CLEAR trail off the
   * envelope — no decode, no reveal — so a SEALED value's lineage is traceable without the
   * passphrase, whether it was sealed THIS run (the overlay) or a PRIOR one (the durable coquille
   * now carries it, § fil-d-ariane).
   */
  @Override
  public Optional<Trail> trailOf(Parcel parcel, SeedCoordinate coordinate) {
    final Optional<Entry> latest = latestSetEntry(parcel, coordinate);
    if (latest.isEmpty()) {
      return durableReads.get().trailOf(parcel, coordinate);
    }
    return latest.filter(entry -> !entry.tombstone()).map(entry -> entry.envelope().trail());
  }

  /**
   * The LAST read-write-set entry filed at {@code (parcel, coordinate)} — store OR tombstone — or
   * empty if the set is silent on this case. The caller reads {@link Entry#tombstone()} to tell a
   * withdraw (case emptied in-run, durable shadowed) from a store (its value), and an empty result
   * (set silent) means "consult the durable".
   */
  private Optional<Entry> latestSetEntry(Parcel parcel, SeedCoordinate coordinate) {
    Entry latest = null;
    for (Entry entry : setEntriesFor(parcel)) {
      if (entry.envelope().coordinate().equals(coordinate.slug())) {
        latest = entry; // store order → the last match is the current value
      }
    }
    return Optional.ofNullable(latest);
  }

  /**
   * The read-write set's CURRENT non-empty cases under {@code parcel}: the last entry per
   * coordinate (store order, last wins), a coordinate whose last entry is a tombstone DROPPED (the
   * case is empty in-run). The overlay's live state, coordinate order preserved by first
   * appearance.
   */
  private List<Entry> currentSetEntries(Parcel parcel) {
    final Map<String, Entry> byCoordinate = new LinkedHashMap<>();
    for (Entry entry : setEntriesFor(parcel)) {
      byCoordinate.put(entry.envelope().coordinate(), entry);
    }
    return byCoordinate.values().stream().filter(entry -> !entry.tombstone()).toList();
  }

  /** The read-write-set entries filed under {@code parcel}, in store order. */
  private List<Entry> setEntriesFor(Parcel parcel) {
    return entriesOf(model.get()).stream().filter(entry -> entry.parcel().equals(parcel)).toList();
  }
}
