package io.seedmatic.rke2lab.pulumi.edge;

import com.pulumi.automation.LocalWorkspace;
import com.pulumi.automation.LocalWorkspaceOptions;
import com.pulumi.automation.ProjectBackend;
import com.pulumi.automation.ProjectRuntimeName;
import com.pulumi.automation.ProjectSettings;
import com.pulumi.automation.WorkspaceStack;
import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.OpaqueCellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.RunGate;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.port.Trail;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * The Pulumi realisation of the {@link Cellar} conservation role — the commissioner's neutral store
 * over the Pulumi file backend, fusing the three former doctor-named host journals ({@code
 * MedicalRecordJournal}, {@code InterventionJournal}, {@code InterventionLedgerWriter}) into one.
 * It holds nothing but sealed {@link SeedEnvelope}s addressed by a {@link Parcel}; it never names a
 * doctor type and never opens a payload.
 *
 * <p><b>The durable coquille.</b> Every entry is persisted as a self-characterising CLEAR shell of
 * five flat fields around the opaque payload — {@code {domain, trail, tombstone, mac, payload}} —
 * registered as the entry's Pulumi output (keyed by the coordinate slug, the shelf label). The
 * {@code domain} and the {@code trail} (the value's fil d'Ariane, § fil-d-ariane) thus SURVIVE the
 * durable round-trip; the {@code payload} rides as a STRING field, so a SEALED {@code
 * cellar:sealed:v1:…} value is just a field value, no longer a Map the store must parse (the
 * blocker that made durable sealing impossible). The {@code mac} is an HMAC over the clear
 * characterisation, keyed by the cellar's own passphrase — a property of the CELLAR, not the seal
 * (decision A): every coquille carries one, so a fetch can trust ANY value's lineage and any tamper
 * of the clear shell OR the sealed payload fails closed. The host never opens the payload — it MACs
 * and carries the opaque bytes — so the {@link OpaqueCellar} contract ("never opens a payload")
 * holds literally.
 *
 * <p><b>Two soils, one verb.</b> The cellar detects whether a {@link Parcel} is the RUN's OWN
 * stack, but by a DIFFERENT signal for a write than for a read. A WRITE asks the live Pulumi
 * deployment installed on THIS thread (§ {@link PulumiDeploymentSeed#targets}) — staging into the
 * one deployment needs it here. A READ asks the run stack's IDENTITY (§ {@link
 * PulumiDeploymentSeed#runStack}, captured host-side at construction), thread-INDEPENDENT — so a
 * scion reading off the deployment thread still reads the run stack's current state, not the
 * side-stack history-walk. That discriminant routes {@code store} / {@code fetch} / {@code
 * withdraw} down one of two paths; the scion never picks — each verb stays neutral.
 *
 * <p><b>store</b> — the RUN's own stack STAGES the coquille and {@link #conserve} re-declares the
 * whole live set as {@code CellarEntry} resources INTO the run's single deployment: the harvest
 * folds into the ONE history alongside the infra (incus, cluster), with no nested {@code up} and no
 * lock contention (the fatal collision of the old separate-{@code up} design). A SIDE stack (the
 * doctor's ledger, its own project) has no live deployment, so it files EAGERLY: an out-of-run
 * {@code up()} routed by the injected {@link RunGate} CONSERVES it (a stable resource name per
 * coordinate makes each append a history entry); a {@code preview()} PRE-RESERVES it, touching no
 * state.
 *
 * <p><b>fetch</b> — rebuilds one {@link SeedEnvelope} per stored coquille, VERIFYING its MAC and
 * restoring {@code domain} + {@code trail} + opaque {@code payload}, keyed by the output's NATIVE
 * name (the coordinate). The read is FILTERED to the {@code rke2lab:cellar:Entry} type, so the
 * resources the run's own stack co-hosts (the incus provider, the root Stack) are ignored rather
 * than mistaken for malformed coquilles. The run's stack (told by IDENTITY, so any thread reads it
 * alike) reads its CURRENT state (every live coquille sits in the one checkpoint); a side stack
 * walks its history append-log. The typed overloads ({@code fetch(parcel, type)}, {@code
 * fetch(parcel, coordinate, type)}) DELEGATE the decode to the {@link SeedCodec} — the host decodes
 * any type ITS classpath holds; a type it lacks (a bundle type) is the codec's throw, not a cellar
 * rule.
 *
 * <p><b>withdraw</b> — the RUN's own stack STAGES a removal: the coordinate is simply not
 * re-declared by {@code conserve}, so the run's authoritative {@code up} reaps it — no in-band
 * tombstone. A side stack files a TOMBSTONE coquille ({@code tombstone=true}, a first-class CLEAR
 * shell flag) so the current-state fold reads the case empty while history keeps the trace.
 *
 * <p><b>conserve</b> — the end-of-drain flush for the run's own stack: desired = the live coquilles
 * carried forward (re-declared so the authoritative {@code up} does not reap them) merged with this
 * run's staged changes. A no-op for the eager side-stack path (already committed per store).
 *
 * <p><b>neighbours</b> — the sibling parcels under the same backend soil (the parcel's own first).
 *
 * <p>An absent file backend yields an empty neighbourhood (just the parcel) and empty fetches; a
 * present-but-unreadable state or history propagates (corruption is not absence); a coquille whose
 * MAC does not verify is tamper, and fails closed rather than degrading to a skip.
 */
public final class PulumiCellar implements OpaqueCellar {

  private static final String BACKEND_URL_ENV = "PULUMI_BACKEND_URL";
  private static final String FILE_SCHEME = "file://";

  // The cellar's own (mono) identity — the same value the codec's PassphraseCellarCipher seals
  // with,
  // doubling here as the file-backend secrets-provider passphrase AND the key of the coquille's
  // MAC.
  // The MAC is a property of the CELLAR, not the seal (decision A): every coquille carries one,
  // sealed or clear, so the doctor can trust ANY value's lineage without a per-seal key.
  private static final String PASSPHRASE = "rke2lab-cellar";

  // The five CLEAR fields of the durable coquille — the self-characterising shell around the opaque
  // payload. domain + trail + tombstone restore the envelope characterisation the old (coordinate →
  // payload) shell dropped; mac binds the clear layer to the sealed payload; payload rides as a
  // STRING field (sealed or clear), so a "cellar:sealed:v1:…" value is just a field value, no
  // longer
  // a Map the store must parse (the blocker that made durable sealing impossible).
  private static final String KEY_DOMAIN = "domain";
  private static final String KEY_TRAIL = "trail";
  private static final String KEY_TOMBSTONE = "tombstone";
  private static final String KEY_MAC = "mac";
  private static final String KEY_PAYLOAD = "payload";

  private static final String MAC_ALGORITHM = "HmacSHA256";

  // The trail (de)serialization DELEGATES to the SeedCodec, the sole holder of the sealed
  // Breadcrumb's polymorphic type-info (§ SeedCodec). The MAC's input goes through wire(): compact,
  // nothing reordered or dropped — the bytes past runs MAC'd, which a stored entry must still
  // match.
  private final SeedCodec codec = new SeedCodec();
  private final Optional<Path> backendDir;
  private final RunGate gate;
  private final Consumer<String> logger;

  // The RUN's own stack identity, captured host-side at construction (in the GIVEN, where the live
  // deployment is installed). READS use it to tell the run stack from a side stack by IDENTITY —
  // thread-INDEPENDENT — so a scion reading off the deployment thread still reads the run stack's
  // CURRENT state, not the side-stack history-walk (which would resurrect a coquille a prior reap
  // dropped: a run-stack delete is a tombstone-less omission the fold cannot honour). Empty for a
  // standalone/preview run (or a test): reads then fall back to the thread-local isRunStack.
  private final Optional<Parcel> runStack;

  // Per-parcel staged current-state for the RUN's own stack (the "one history" path): a store
  // stages its shell, a withdraw stages an empty (an omission). Flushed by conserve() at the drain,
  // where the full live set is re-declared into the run's single deployment. Empty for the eager
  // side-stack path (the doctor's ledger), which files each store as its own out-of-run up.
  private final Map<Parcel, Map<String, Optional<Map<String, Object>>>> stagedByParcel =
      new LinkedHashMap<>();

  public PulumiCellar(Optional<Path> backendDir, RunGate gate, Consumer<String> logger) {
    this(backendDir, gate, logger, Optional.empty());
  }

  public PulumiCellar(
      Optional<Path> backendDir, RunGate gate, Consumer<String> logger, Optional<Parcel> runStack) {
    this.backendDir = backendDir;
    this.gate = gate;
    this.logger = logger;
    this.runStack = runStack;
  }

  public static PulumiCellar fromEnvironment(RunGate gate, Consumer<String> logger) {
    // The run stack identity is read HERE — the GIVEN runs on the worker where PulumiDeploymentSeed
    // installed the deployment, so a reader on any thread later tells the run stack by identity.
    return new PulumiCellar(
        backendDirFromUrl(System.getenv(BACKEND_URL_ENV)),
        gate,
        logger,
        PulumiDeploymentSeed.runStack());
  }

  static Optional<Path> backendDirFromUrl(@Nullable String pulumiBackendUrl) {
    return Optional.ofNullable(pulumiBackendUrl)
        .filter(url -> url.startsWith(FILE_SCHEME))
        .map(url -> Path.of(url.substring(FILE_SCHEME.length())));
  }

  /** The file-backend root this cellar reads from, or empty when no file:// backend is set. */
  public Optional<Path> backendDir() {
    return backendDir;
  }

  /**
   * The stack's CURRENT snapshot — the last-committed deployment state. During the run's own {@code
   * up} this is the state BEFORE this up commits (the prior state), so a host reader sees the world
   * as it was at grow entry. Exposed for a resource the cellar does not model as a coquille (the
   * grow reads {@code seed-instance}'s prior running state to tell WARM from COLD). Empty when no
   * file:// backend is configured or the stack has no state yet; a present-but-unreadable state is
   * corruption, propagated.
   */
  public Optional<StackSnapshot> currentSnapshot(Parcel parcel) {
    if (backendDir.isEmpty()) {
      return Optional.empty();
    }
    final StackHandle handle =
        StackHandle.forBackend(backendDir.orElseThrow(), parcel.project(), parcel.stack());
    try {
      return handle.currentSnapshot();
    } catch (StackException e) {
      throw new RuntimeException("stack state present but unreadable under " + backendDir, e);
    }
  }

  @Override
  public void store(Parcel parcel, SeedEnvelope vegetal) {
    // A store files a self-characterising coquille: the CLEAR shell (domain, trail, tombstone)
    // around the OPAQUE payload, MAC-bound so a later fetch can trust the whole. tombstone is false
    // — a store fills the case; a withdrawal empties it.
    final Map<String, Object> shell =
        shell(vegetal.domain(), vegetal.coordinate(), vegetal.trail(), false, vegetal.payload());
    if (isRunStack(parcel)) {
      // The run's OWN stack: stage the shell; conserve() re-declares the full live set into the
      // run's single deployment (one history, no nested up, no lock).
      stage(parcel, vegetal.coordinate(), Optional.of(shell));
    } else {
      // A side stack (the doctor's ledger): file it eagerly as its own out-of-run up.
      writeShell(parcel, vegetal.coordinate(), shell);
    }
  }

  /** Whether {@code parcel} is the RUN's own stack for a WRITE — the live deployment targets it. */
  private boolean isRunStack(Parcel parcel) {
    return PulumiDeploymentSeed.targets(parcel.project(), parcel.stack());
  }

  /**
   * Whether {@code parcel} is the RUN's own stack for a READ — decided by the run stack's IDENTITY
   * (captured host-side at construction), NOT the thread-local deployment. So a scion reading off
   * the deployment thread still reads the run stack's CURRENT state — the same view the host grow
   * sees — instead of forking to the side-stack history-walk, which would resurrect a coquille a
   * prior reap dropped (a run-stack delete is a tombstone-less omission the history fold cannot
   * honour). Falls back to {@link #isRunStack} when no identity was captured (a standalone/preview
   * run or a test), preserving the prior behaviour exactly.
   */
  private boolean readsRunStack(Parcel parcel) {
    return runStack.map(parcel::equals).orElseGet(() -> isRunStack(parcel));
  }

  /** Stage one run-stack op: a present shell (a store) or an empty (a withdrawal). */
  private void stage(Parcel parcel, String coordinate, Optional<Map<String, Object>> op) {
    stagedByParcel.computeIfAbsent(parcel, p -> new LinkedHashMap<>()).put(coordinate, op);
  }

  /**
   * Persist one coquille under {@code coordinate} — the sole Pulumi writer, shared by {@link
   * #store} (a fill) and {@link #withdraw} (a tombstone). The shell is registered verbatim as the
   * entry's output; the history fold is the audit, one entry per {@code up()}.
   */
  @SuppressWarnings("try") // WorkspaceStack.close() declares InterruptedException; handled in catch
  private void writeShell(Parcel parcel, String coordinate, Map<String, Object> shell) {
    if (backendDir.isEmpty()) {
      throw new IllegalStateException(
          "cannot store to parcel "
              + parcel.project()
              + "/"
              + parcel.stack()
              + ": no file:// PULUMI_BACKEND_URL configured");
    }
    final Path backend = backendDir.orElseThrow();

    final ProjectSettings projectSettings =
        ProjectSettings.builder(parcel.project(), ProjectRuntimeName.JAVA)
            .backend(ProjectBackend.builder().url(FILE_SCHEME + backend).build())
            .build();

    final LocalWorkspaceOptions options =
        LocalWorkspaceOptions.builder()
            .projectSettings(projectSettings)
            .program(ctx -> new CellarEntry(coordinate, shell))
            .environmentVariables(
                Map.of(
                    "PULUMI_BACKEND_URL",
                    FILE_SCHEME + backend,
                    "PULUMI_CONFIG_PASSPHRASE",
                    PASSPHRASE))
            .build();

    try (WorkspaceStack stack =
        LocalWorkspace.createOrSelectStack(
            parcel.project(), parcel.stack(), options.program(), options)) {
      // The cellar consults the gate: cultivating conserves (up), surveying pre-reserves (preview —
      // the plan is computed, the state left intact). preview() returns a change summary the caller
      // has no channel for yet (store is void); the runbook narrates the plan, so we only log it.
      if (gate.cultivating()) {
        stack.up();
      } else {
        logger.accept(
            "cellar pre-reserve (preview) for "
                + parcel.project()
                + "/"
                + parcel.stack()
                + " at "
                + coordinate
                + ": "
                + stack.preview().changeSummary());
      }
    } catch (Exception e) {
      throw new RuntimeException(
          "failed to store to parcel "
              + parcel.project()
              + "/"
              + parcel.stack()
              + " under "
              + backend,
          e);
    }
  }

  @Override
  public List<SeedEnvelope> fetch(Parcel parcel) {
    return reap(parcel).stream().map(Shelved::envelope).toList();
  }

  /**
   * The parcel's timeline as {@link Shelved} coquilles — each carrying its rebuilt {@link
   * SeedEnvelope} (domain + trail + payload restored from the CLEAR shell) and its first-class
   * tombstone flag, so the fold ({@link #currentAt}) reads the case's state without opening the
   * opaque payload. Oldest first. A present-but-unreadable history is corruption (propagates); a
   * single entry that cannot be materialised degrades to a skip. A coquille whose MAC does NOT
   * verify is NOT a skip — it is tamper, and {@link #collectEntry} throws it closed.
   */
  private List<Shelved> reap(Parcel parcel) {
    if (backendDir.isEmpty()) {
      logger.accept(
          "cellar empty for "
              + parcel.project()
              + "/"
              + parcel.stack()
              + ": no file:// PULUMI_BACKEND_URL configured");
      return List.of();
    }
    final Path root = backendDir.orElseThrow();
    final StackHandle handle = StackHandle.forBackend(root, parcel.project(), parcel.stack());
    final List<Shelved> reaped = new ArrayList<>();

    if (readsRunStack(parcel)) {
      // The run's OWN stack holds EVERY live coquille as a resource in ONE current state (conserve
      // re-declares them each up). Read that current state, filtered to our own CellarEntry type so
      // co-resident resources (the incus provider, the root Stack) are ignored rather than mistaken
      // for malformed coquilles.
      final Optional<StackSnapshot> snapshot;
      try {
        snapshot = handle.currentSnapshot();
      } catch (StackException e) {
        // A present-but-unreadable state is corruption, not absence: propagate rather than mask.
        throw new RuntimeException("cellar state present but unreadable under " + root, e);
      }
      snapshot.ifPresent(s -> collectEntry(s, reaped));
      return reaped;
    }

    // An eager side stack (the doctor's ledger): each store is its own up, so the append-log lives
    // in the stack HISTORY — walk it, oldest first.
    final List<StackHistory.Entry> entries;
    try {
      entries = handle.history().entries();
    } catch (StackException e) {
      // A present-but-unreadable history is corruption, not absence: propagate rather than mask an
      // empty store (the dishonesty the ledger exists to kill).
      throw new RuntimeException("cellar history present but unreadable under " + root, e);
    }
    for (StackHistory.Entry entry : entries) {
      try {
        collectEntry(handle.snapshotOf(entry), reaped);
      } catch (StackException e) {
        // A present entry that cannot be materialised degrades to a skip with a reason — the fetch
        // continues on the readable prefix rather than throwing.
        logger.accept(
            "cellar entry skipped for "
                + parcel.project()
                + "/"
                + parcel.stack()
                + ": version="
                + entry.version()
                + " at "
                + entry.when()
                + " unreadable — "
                + e.getMessage());
      }
    }
    return reaped;
  }

  @Override
  public Optional<SeedEnvelope> fetch(Parcel parcel, SeedCoordinate coordinate) {
    // Peek ONE case OPAQUE: the last-wins fold of the timeline at this coordinate. An empty case
    // (never stored, or withdrawn — a tombstone won) is Optional.empty(), a legitimate state.
    return currentAt(parcel, coordinate.slug());
  }

  @Override
  public Optional<SeedEnvelope> withdraw(Parcel parcel, SeedCoordinate coordinate) {
    // Take the case out OPAQUE: hand back its current envelope, then empty the case.
    final Optional<SeedEnvelope> current = fetch(parcel, coordinate);
    if (isRunStack(parcel)) {
      // The run's OWN stack: stage a removal. The coordinate is simply not re-declared in
      // conserve(), so the run's authoritative up reaps it — no in-band tombstone shell needed.
      stage(parcel, coordinate.slug(), Optional.empty());
    } else if (current.isPresent()) {
      // Eager side stack: file a first-class TOMBSTONE coquille (tombstone=true) so a later
      // history fold reads the case empty. History keeps the trace (the audit does not lie).
      writeShell(parcel, coordinate.slug(), shell("", coordinate.slug(), Trail.empty(), true, ""));
    }
    return current;
  }

  @Override
  public void conserve(Parcel parcel) {
    // Flush the staged current-state. Only the run's OWN stack stages (the eager side-stack path
    // already committed each store via its own up). The desired live set = the coquilles carried
    // forward from the current state (re-declared so Pulumi's one authoritative up does NOT reap
    // them) merged with this run's staged changes (a store overrides, a withdrawal omits). Each
    // shell is rebuilt from its read-back envelope — deterministic, same MAC. Then one CellarEntry
    // per coordinate is registered into the run's live deployment: stable-named, so the single up
    // folds the whole harvest into ONE history alongside the infra.
    final Map<String, Optional<Map<String, Object>>> ops = stagedByParcel.remove(parcel);
    if (!isRunStack(parcel)) {
      return;
    }
    final Map<String, Map<String, Object>> desired = new LinkedHashMap<>();
    for (Shelved shelved : reap(parcel)) {
      if (!shelved.tombstone()) {
        final SeedEnvelope e = shelved.envelope();
        desired.put(
            e.coordinate(), shell(e.domain(), e.coordinate(), e.trail(), false, e.payload()));
      }
    }
    if (ops != null) {
      ops.forEach(
          (coordinate, op) -> {
            if (op.isPresent()) {
              desired.put(coordinate, op.get());
            } else {
              desired.remove(coordinate);
            }
          });
    }
    desired.forEach((coordinate, shell) -> new CellarEntry(coordinate, shell));
  }

  /**
   * The CURRENT coquille at {@code coordinate} — the last-wins fold of the parcel's timeline, or
   * empty if the case was never filled or its most-recent state is a tombstone (a {@link
   * #withdraw}). Reads the tombstone off the CLEAR shell flag, never the opaque payload.
   */
  private Optional<SeedEnvelope> currentAt(Parcel parcel, String coordinate) {
    final Map<String, SeedEnvelope> current = new LinkedHashMap<>();
    for (Shelved shelved : reap(parcel)) {
      if (!shelved.envelope().coordinate().equals(coordinate)) {
        continue;
      }
      if (shelved.tombstone()) {
        current.remove(coordinate);
      } else {
        current.put(coordinate, shelved.envelope());
      }
    }
    return Optional.ofNullable(current.get(coordinate));
  }

  @Override
  public List<Parcel> neighbours(Parcel parcel) {
    if (backendDir.isEmpty()) {
      return List.of(parcel);
    }
    final Path stacksDir =
        PulumiBackendLayout.stacksDir(backendDir.orElseThrow(), parcel.project());
    if (!Files.isDirectory(stacksDir)) {
      return List.of(parcel);
    }
    try (Stream<Path> entries = Files.list(stacksDir)) {
      return entries
          .filter(Files::isDirectory)
          .map(dir -> dir.getFileName().toString())
          .map(stack -> new Parcel(parcel.project(), stack))
          .sorted(
              Comparator.comparing((Parcel p) -> p.stack().equals(parcel.stack()) ? 0 : 1)
                  .thenComparing(Parcel::stack))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot enumerate neighbours under " + stacksDir, e);
    }
  }

  /**
   * Rebuild one {@link Shelved} coquille per stored output in the entry's snapshot, keyed by the
   * output's native name (the coordinate the store filed it under). Each output value is a CLEAR
   * shell {@code {domain, trail, tombstone, mac, payload}}: its MAC is VERIFIED first (over the
   * same canonical characterisation the store bound), then the {@link SeedEnvelope} is rebuilt from
   * the shell's own {@code domain} + {@code trail} + opaque {@code payload} — the characterisation
   * the old {@code (coordinate → payload)} shell dropped. The host never opens the payload: it MACs
   * and carries the opaque bytes, so the {@link OpaqueCellar} contract holds. A MAC mismatch is
   * tamper and throws (fail-closed).
   */
  private void collectEntry(StackSnapshot snapshot, List<Shelved> into) {
    snapshot
        .outputsOfType(CellarEntry.TYPE_TOKEN)
        .forEach(
            (coordinate, values) ->
                values.forEach(value -> into.add(unshell(coordinate, asShell(value)))));
  }

  /** One rebuilt durable coquille: its restored envelope and its first-class tombstone flag. */
  record Shelved(SeedEnvelope envelope, boolean tombstone) {}

  /**
   * Build a CLEAR shell around an opaque {@code payload}: the five flat fields, MAC-bound. The
   * trail is stored as its literal JSON STRING (a clear field, readable without the passphrase), so
   * the MAC covers exactly the bytes that ride — no re-serialisation drift, no dependence on the
   * map key order Pulumi may reshuffle. The MAC is the LAST field added; it binds the four that
   * precede it.
   */
  Map<String, Object> shell(
      String domain, String coordinate, Trail trail, boolean tombstone, String payload) {
    final String trailJson = codec.encode(trail);
    final Map<String, Object> shell = new LinkedHashMap<>();
    shell.put(KEY_DOMAIN, domain);
    shell.put(KEY_TRAIL, trailJson);
    shell.put(KEY_TOMBSTONE, tombstone);
    shell.put(KEY_PAYLOAD, payload);
    shell.put(KEY_MAC, mac(domain, coordinate, tombstone, trailJson, payload));
    return shell;
  }

  /**
   * Verify the coquille's MAC and rebuild its {@link Shelved} form. The {@code coordinate} (the
   * output name) is part of the bound characterisation, so a coquille moved to another case fails
   * the MAC too.
   */
  Shelved unshell(String coordinate, Map<String, Object> shell) {
    final String domain = stringField(shell, KEY_DOMAIN);
    final String trailJson = stringField(shell, KEY_TRAIL);
    final boolean tombstone = Boolean.TRUE.equals(shell.get(KEY_TOMBSTONE));
    final String payload = stringField(shell, KEY_PAYLOAD);
    final String stored = stringField(shell, KEY_MAC);

    final String expected = mac(domain, coordinate, tombstone, trailJson, payload);
    if (!MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8))) {
      throw new SecurityException(
          "cellar coquille MAC mismatch at "
              + coordinate
              + " — the clear shell or payload was tampered");
    }
    final Trail trail = codec.decode(trailJson, Trail.class);
    return new Shelved(new SeedEnvelope(domain, coordinate, payload, trail), tombstone);
  }

  /**
   * The coquille's MAC — an HMAC over the CLEAR characterisation {@code (domain, coordinate,
   * tombstone, trail, payload)}, keyed by the cellar's own {@link #PASSPHRASE}. A property of the
   * CELLAR (decision A): every coquille carries one, sealed or clear, so the trail's lineage is
   * trustworthy and any tamper of the clear shell OR the sealed payload fails at fetch. Cipher-
   * agnostic (a proper MAC, not AES-GCM's AAD), so it works identically for the passphrase (mono)
   * and age (multi) seals. The fields are HMAC'd as a JSON array — jackson's string escaping
   * delimits them unambiguously, so no field's content can be confused with the separator.
   */
  private String mac(
      String domain, String coordinate, boolean tombstone, String trailJson, String payload) {
    try {
      final Mac hmac = Mac.getInstance(MAC_ALGORITHM);
      hmac.init(new SecretKeySpec(PASSPHRASE.getBytes(StandardCharsets.UTF_8), MAC_ALGORITHM));
      final String canonical =
          writeString(List.of(domain, coordinate, tombstone, trailJson, payload));
      return Base64.getEncoder()
          .encodeToString(hmac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("cellar coquille MAC failed", e);
    }
  }

  private static String stringField(Map<String, Object> shell, String key) {
    final Object value = shell.get(key);
    if (!(value instanceof String s)) {
      throw new IllegalArgumentException(
          "malformed cellar coquille: missing string field '" + key + "'");
    }
    return s;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> asShell(Object value) {
    if (!(value instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("malformed cellar coquille: output is not a shell map");
    }
    return (Map<String, Object>) value;
  }

  private String writeString(Object blob) {
    return codec.wire().writeJson(blob);
  }

  /**
   * An inert component resource that persists one stored envelope's payload as a per-resource
   * output under the coordinate-slug name. It touches no real infrastructure — the store IS the
   * history fold: one entry per {@code up()}, the sequence lives in history, not in distinct
   * resource names (so the name is the stable coordinate slug, not the payload).
   */
  private static final class CellarEntry extends ComponentResource {
    private static final String TYPE_TOKEN = "rke2lab:cellar:Entry";

    CellarEntry(String coordinate, Map<String, Object> payload) {
      super(TYPE_TOKEN, coordinate);
      registerOutputs(Map.of(coordinate, Output.of(payload)));
    }
  }
}
