package io.seedmatic.rke2lab.doctor.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * The problem-oriented join key shared by three actors: the generalist opens a problem when a
 * symptom appears at a checkpoint, the operator declares interventions against it, and the drift
 * specialist infers/explains resolutions for it.
 *
 * <p>A problem is identified by a checkpoint and an optional symptom. Checkpoint-only references
 * explain every symptom at that checkpoint; symptom-specific references explain only their own.
 */
public record ProblemRef(Checkpoint checkpoint, Optional<Symptom> symptom) {

  public ProblemRef {
    if (checkpoint == null) {
      throw new IllegalArgumentException("checkpoint must not be null");
    }
    symptom = symptom == null ? Optional.empty() : symptom;
  }

  /**
   * Renders the problem reference as a string: {@code "checkpoint/symptom"} or {@code
   * "checkpoint"}. Also the codec's {@code @JsonValue} — a ProblemRef serializes as this single
   * string, decoded back via {@link #fromWire}.
   */
  @JsonValue
  public String toRef() {
    return checkpoint.slug() + symptom.map(s -> "/" + s.id()).orElse("");
  }

  /**
   * The codec's {@code @JsonCreator}: parses the {@code "checkpoint/symptom"} string, rejecting an
   * unknown/blank reference with an {@link IllegalArgumentException} — the wire only ever carries
   * refs we emit, so a malformed ref is a decode error, not a value to absorb.
   */
  @JsonCreator
  static ProblemRef fromWire(String value) {
    return parse(value)
        .orElseThrow(() -> new IllegalArgumentException("unknown problem ref: '" + value + "'"));
  }

  /**
   * Parses a problem reference from a string. Returns empty if the string is blank, if the
   * checkpoint is unknown, or if a symptom part exists but is unknown.
   */
  public static Optional<ProblemRef> parse(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }

    final int slashIndex = value.indexOf('/');
    if (slashIndex == -1) {
      return Checkpoint.fromSlug(value).map(cp -> new ProblemRef(cp, Optional.empty()));
    }

    final String checkpointPart = value.substring(0, slashIndex);
    final String symptomPart = value.substring(slashIndex + 1);

    final Optional<Checkpoint> checkpointOpt = Checkpoint.fromSlug(checkpointPart);
    if (checkpointOpt.isEmpty()) {
      return Optional.empty();
    }

    final Optional<Symptom> symptomOpt = Symptom.parse(symptomPart);
    if (symptomOpt.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(new ProblemRef(checkpointOpt.get(), symptomOpt));
  }

  /**
   * Returns true if this reference explains the target. A checkpoint-only reference explains every
   * symptom at that checkpoint; a symptom-specific reference explains only its own symptom. Refs
   * from different checkpoints never explain each other.
   */
  public boolean explains(ProblemRef target) {
    if (!checkpoint.equals(target.checkpoint)) {
      return false;
    }
    return symptom.isEmpty() || symptom.equals(target.symptom);
  }

  /**
   * Returns true if this reference explains the given symptom (checkpoint-agnostic). Used for
   * efficacy joins where the symptom is the key.
   */
  public boolean explainsSymptom(Symptom that) {
    return symptom.isEmpty() || symptom.get() == that;
  }

  /** Creates a problem reference with a checkpoint and symptom. */
  public static ProblemRef of(Checkpoint checkpoint, Symptom symptom) {
    if (checkpoint == null) {
      throw new IllegalArgumentException("checkpoint must not be null");
    }
    if (symptom == null) {
      throw new IllegalArgumentException("symptom must not be null");
    }
    return new ProblemRef(checkpoint, Optional.of(symptom));
  }

  /** Creates a checkpoint-only problem reference. */
  public static ProblemRef of(Checkpoint checkpoint) {
    if (checkpoint == null) {
      throw new IllegalArgumentException("checkpoint must not be null");
    }
    return new ProblemRef(checkpoint, Optional.empty());
  }
}
