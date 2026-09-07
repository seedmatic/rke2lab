package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

/**
 * A host-side stand-in for ONE link of a captured failure's cause chain (see {@link ThrownModel}) —
 * the real domain exception is bundle-private and unloadable on the flat host loader, so a faithful
 * carrier stands in for its type, message, frames, and cause. It exists so {@code printStackTrace}
 * walks a REAL {@code getCause} tree and prints a faithful {@code Caused by:} chain, with no line
 * parsed from a printed dump.
 *
 * <p>{@link #toString} re-prints the original type + message (so a frame reads {@code
 * pkg.OriginalError: message}, not {@code ReconstructedThrowable: …}); {@link #fillInStackTrace} is
 * a no-op — the useful stack is the captured one, set via {@link #setStackTrace}.
 */
final class ReconstructedThrowable extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String type;

  ReconstructedThrowable(ThrownModel model) {
    super(model.message().orElse(null), model.cause().map(ThrownModel::toThrowable).orElse(null));
    this.type = model.type();
    setStackTrace(model.framesAsElements());
  }

  @Override
  public synchronized Throwable fillInStackTrace() {
    return this;
  }

  @Override
  public String toString() {
    return getMessage() == null ? type : type + ": " + getMessage();
  }
}
