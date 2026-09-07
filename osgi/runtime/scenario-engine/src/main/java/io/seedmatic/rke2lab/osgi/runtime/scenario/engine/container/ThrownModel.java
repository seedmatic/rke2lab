package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The STRUCTURED wire form of a failure — a POJO built DIRECTLY from a live {@link Throwable} (its
 * type, message, stack {@link Frame}s, and {@code cause} chain, recursively), so the crossing's
 * reason travels as JSON via the codec and NOT as a printed-then-reparsed string. This is the
 * answer to "the frames must come from the mapper fed a POJO, not from parsing a printStackTrace
 * dump": the capture happens where the live exception still exists ({@link ScenarioPlayer},
 * in-container, with both the model and the {@code Throwable} in hand), reads the real {@link
 * StackTraceElement}s, and lets {@code SeedCodec} serialise the graph. On the host it is rebuilt
 * into a real {@link Throwable} ({@link #toThrowable}) — full cause chain, native {@code
 * printStackTrace} — with no string parsing.
 *
 * @param type the exception's class name (its {@code toString} prefix, for a faithful re-render)
 * @param message the exception's message (absent — a bare throw)
 * @param frames its own stack frames (NOT the cause's — the cause carries its own)
 * @param cause the next link of the {@code getCause} chain, absent at the root
 */
public record ThrownModel(
    String type, Optional<String> message, List<Frame> frames, Optional<ThrownModel> cause) {

  /** One structured stack frame — a {@link StackTraceElement}, flat for the mapper. */
  public record Frame(
      String declaringClass, String methodName, Optional<String> fileName, int lineNumber) {

    StackTraceElement toElement() {
      return new StackTraceElement(declaringClass, methodName, fileName.orElse(null), lineNumber);
    }
  }

  /** Capture a live {@link Throwable} — its type, message, real frames, and cause chain. */
  public static ThrownModel of(Throwable throwable) {
    final List<Frame> frames = new ArrayList<>();
    for (final StackTraceElement element : throwable.getStackTrace()) {
      frames.add(
          new Frame(
              element.getClassName(),
              element.getMethodName(),
              Optional.ofNullable(element.getFileName()),
              element.getLineNumber()));
    }
    return new ThrownModel(
        throwable.getClass().getName(),
        Optional.ofNullable(throwable.getMessage()),
        frames,
        Optional.ofNullable(throwable.getCause()).map(ThrownModel::of));
  }

  public ThrownModel {
    frames = List.copyOf(frames);
  }

  /** This model's own frames as real {@link StackTraceElement}s (no parsing — a direct map). */
  StackTraceElement[] framesAsElements() {
    return frames.stream().map(Frame::toElement).toArray(StackTraceElement[]::new);
  }

  /**
   * Rebuild a host-side {@link Throwable} carrying this model's message, real frames, and its whole
   * cause chain — so {@code printStackTrace} renders the faithful {@code Caused by:} tree natively,
   * with no line reconstructed from a string.
   */
  public Throwable toThrowable() {
    return new ReconstructedThrowable(this);
  }
}
