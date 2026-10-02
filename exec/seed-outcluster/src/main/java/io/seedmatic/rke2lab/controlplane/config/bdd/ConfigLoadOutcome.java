package io.seedmatic.rke2lab.controlplane.config.bdd;

import io.seedmatic.rke2lab.controlplane.config.Rke2labConfig;
import java.util.List;
import java.util.Optional;

/**
 * The outcome of playing the configuration entry gate: either the configuration is ready (a DTO was
 * built) or mandatory inputs are missing (the accumulated keys). Exactly one is present.
 *
 * <p>This is what the gate reports — the doctor (Increment 2) consults a missing-inputs outcome to
 * route each key to its domain specialist for a prescription.
 */
public record ConfigLoadOutcome(Optional<Rke2labConfig> config, List<String> missingKeys) {

  public static ConfigLoadOutcome ready(Rke2labConfig config) {
    return new ConfigLoadOutcome(Optional.of(config), List.of());
  }

  public static ConfigLoadOutcome missing(List<String> missingKeys) {
    return new ConfigLoadOutcome(Optional.empty(), List.copyOf(missingKeys));
  }

  public boolean isReady() {
    return config.isPresent();
  }
}
