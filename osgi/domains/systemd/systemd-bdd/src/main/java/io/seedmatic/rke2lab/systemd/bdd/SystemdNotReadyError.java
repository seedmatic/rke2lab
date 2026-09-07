package io.seedmatic.rke2lab.systemd.bdd;

import io.seedmatic.rke2lab.doctor.contract.SymptomKind;
import io.seedmatic.rke2lab.doctor.contract.Symptomatic;
import io.seedmatic.rke2lab.systemd.contract.SystemdStatusSnapshot;
import java.util.Map;
import java.util.Optional;

/**
 * A systemd readiness facet is not ready. An {@link AssertionError} (so jGiven marks the step
 * FAILED) that is {@link Symptomatic}: it carries the whole {@link SystemdStatusSnapshot} as a
 * typed member on the reachable-but-not-ready path (the mandatory target and its state, the failed
 * units), and only the cause on the unreachable path (the endpoint threw before a snapshot could be
 * read). A consumer reads the full snapshot off {@link #snapshot()}, or the symptom + context off
 * the {@link Symptomatic} capability uniformly.
 */
public final class SystemdNotReadyError extends AssertionError implements Symptomatic {

  // Rides only in-realm (the runbook carries the message; the checkpoint carries the wire copy), so
  // neither field need survive serialization. The snapshot is absent on the unreachable path.
  private final transient Optional<SystemdStatusSnapshot> snapshot;
  private final transient Map<String, Object> recoveryContext;

  /** Reachable but not ready — the endpoint answered and the snapshot explains why. */
  public SystemdNotReadyError(String facet, SystemdStatusSnapshot snapshot) {
    super(facet + ": not ready — " + snapshot.summary());
    this.snapshot = Optional.of(snapshot);
    this.recoveryContext = Map.of("facet", facet, "snapshot", snapshot.summary());
  }

  /**
   * Unreachable — the probe threw before any snapshot could be read; the cause carries the reason.
   */
  public SystemdNotReadyError(String facet, Throwable unreachable) {
    super(facet + ": " + unreachable.getMessage(), unreachable);
    this.snapshot = Optional.empty();
    this.recoveryContext =
        Map.of("facet", facet, "reason", String.valueOf(unreachable.getMessage()));
  }

  @Override
  public SymptomKind symptom() {
    return SymptomKind.CONNECTION_REFUSED;
  }

  @Override
  public Map<String, Object> recoveryContext() {
    return recoveryContext;
  }

  /** The snapshot on the reachable path, or empty when the endpoint was unreachable. */
  public Optional<SystemdStatusSnapshot> snapshot() {
    return snapshot;
  }
}
