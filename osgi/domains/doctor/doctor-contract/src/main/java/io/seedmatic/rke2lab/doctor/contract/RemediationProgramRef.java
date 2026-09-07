package io.seedmatic.rke2lab.doctor.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * The closed set of remediation programs a {@link Prescription} can be addressed to — a typed
 * catalog reference, never a magic string (the single-source-of-truth discipline; the {@code
 * clusterApi} bug taught why string addressing fails silently). Today the operator _is_ the
 * remediation program (the runbook renders the prescription as prose); a real executor is a future
 * concern, but the reference is typed from the start so it can be dispatched on, not parsed.
 */
public enum RemediationProgramRef {
  INSTALL_PACKAGE("install-package"),
  RESTART_UNIT("restart-systemd-unit"),
  CHECK_CONNECTIVITY("check-connectivity");

  private final String id;

  RemediationProgramRef(String id) {
    this.id = id;
  }

  /** The kebab-case id used in rendered runbooks and (future) executor dispatch. */
  @JsonValue
  public String id() {
    return id;
  }

  public static Optional<RemediationProgramRef> parse(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    final String normalized = value.trim().toLowerCase();
    for (RemediationProgramRef ref : values()) {
      if (ref.id.equals(normalized) || ref.name().equalsIgnoreCase(normalized)) {
        return Optional.of(ref);
      }
    }
    return Optional.empty();
  }

  /**
   * The codec's {@code @JsonCreator}: rejects an unknown/blank slug with an {@link
   * IllegalArgumentException} — the wire only ever carries slugs we emit.
   */
  @JsonCreator
  static RemediationProgramRef fromWire(String value) {
    return parse(value)
        .orElseThrow(
            () -> new IllegalArgumentException("unknown remediation program ref: '" + value + "'"));
  }
}
