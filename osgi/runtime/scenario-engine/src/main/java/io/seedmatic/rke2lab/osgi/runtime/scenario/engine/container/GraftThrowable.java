package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import io.seedmatic.rke2lab.seed.broker.port.Breadcrumb;
import io.seedmatic.rke2lab.seed.broker.port.Trail;
import java.util.stream.Collectors;

/**
 * A failure GRAFTED from a scion played in ANOTHER world (in-container, under OSGi), reconstructed
 * host-side from the serialized runbook — no live {@link Throwable} crosses the realm boundary,
 * only the {@code ReportModel}'s JSON does (see {@link ScenarioGraft}). Built from the STRUCTURED
 * {@link ThrownModel} the scion captured at the source, so it carries the leaf's message, its REAL
 * frames, and its whole {@code Caused by:} chain (as reconstructed {@link ReconstructedThrowable}
 * causes) — {@code printStackTrace} renders a faithful tree natively, with no line parsed from a
 * printed dump. It also carries the {@link #path()} of crossings from the root down to the leaf
 * where it grew.
 *
 * <p>It is NOT the real domain exception ({@code ClusterNotReadyError} and friends are
 * bundle-private and unloadable on the flat host loader); it is a faithful host-side stand-in,
 * path-tagged so the operator knows where it grew. {@link #fillInStackTrace()} is a no-op — the
 * useful stack is the scion's, set via {@link #setStackTrace}, not this host-side construction
 * site.
 */
public final class GraftThrowable extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient Trail path;
  private final String type;

  GraftThrowable(Trail path, ThrownModel reason) {
    super(reason.message().orElse(null), reason.cause().map(ThrownModel::toThrowable).orElse(null));
    this.path = path;
    this.type = reason.type();
    setStackTrace(reason.framesAsElements());
  }

  /** The crossing {@link Trail} (root-first) this failure grew through — the graft path context. */
  public Trail path() {
    return path;
  }

  @Override
  public synchronized Throwable fillInStackTrace() {
    return this;
  }

  @Override
  public String toString() {
    final String crossings =
        path.breadcrumbs().stream().map(Breadcrumb::coordinate).collect(Collectors.joining(" / "));
    final String reason = getMessage() == null ? type : type + ": " + getMessage();
    return "GraftThrowable[" + crossings + "] " + reason;
  }
}
