package io.seedmatic.rke2lab.doctor.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * A typed readiness-failure kind. The probe classifies its own failure and records the symptom in
 * the snapshot envelope (under {@link #ENVELOPE_KEY}) — it is never reverse-engineered from an
 * assertion message. Typed from day one so the data source is correct before the doctor exists: in
 * Increment A nothing routes on it (the canned fault-simulation probe simply emits one); the
 * Generalist's symptom→specialist routing arrives in Increment B.
 */
public enum Symptom {
  CONNECTION_REFUSED("connection-refused"),
  TIMEOUT("timeout"),
  KUBECONFIG_MISSING("kubeconfig-missing"),
  API_NOT_READY("api-not-ready"),
  CONTROLLER_NOT_READY("controller-not-ready"),
  RESERVATION_REFUSED("reservation-refused"),
  IMAGE_BUILD_FAILED("image-build-failed"),
  INSTANCE_UNREACHABLE("instance-unreachable"),
  BLUEPRINT_EXPORT_FAILED("blueprint-export-failed"),
  SYNTHESIS_INCOMPLETE("synthesis-incomplete");

  /** Envelope key under which a probe records its symptom on a non-ok result. */
  public static final String ENVELOPE_KEY = "symptom";

  private final String id;

  Symptom(String id) {
    this.id = id;
  }

  /** The kebab-case id used in config (e.g. the {@code policy.preview.simulate} sub-map). */
  @JsonValue
  public String id() {
    return id;
  }

  /**
   * Parses a config failure-kind ({@code "connection-refused"}) or enum name; blank/unknown empty.
   */
  public static Optional<Symptom> parse(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    final String normalized = value.trim().toLowerCase();
    for (Symptom symptom : values()) {
      if (symptom.id.equals(normalized) || symptom.name().equalsIgnoreCase(normalized)) {
        return Optional.of(symptom);
      }
    }
    return Optional.empty();
  }

  /**
   * The codec's {@code @JsonCreator}: decodes a slug to the enum, rejecting an unknown/blank slug
   * with an {@link IllegalArgumentException} — the wire only ever carries slugs we emit, so a
   * malformed value is a decode error, not a value to absorb. (An absent optional part is never
   * routed here: the mapper omits it and never invokes the creator.)
   */
  @JsonCreator
  static Symptom fromWire(String value) {
    return parse(value)
        .orElseThrow(() -> new IllegalArgumentException("unknown symptom slug: '" + value + "'"));
  }
}
