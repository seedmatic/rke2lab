package io.seedmatic.rke2lab.controlplane.config.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import io.seedmatic.rke2lab.controlplane.config.ConfigLoader;
import io.seedmatic.rke2lab.controlplane.config.MissingRequiredConfiguration;
import io.seedmatic.rke2lab.controlplane.config.Rke2labConfig;
import java.util.List;

/**
 * The configuration entry gate, told as behaviour: the earliest gate in a provisioning run.
 *
 * <p>Given the operator's configuration, when it is loaded, then either the configuration is ready
 * or the missing mandatory inputs are reported. This is the doctor pattern's first use case — in
 * Increment 2 the doctor consults a missing-inputs outcome and routes each key to its domain
 * specialist. The stages live in {@code src/main} so the gate can be played at provisioning time,
 * not only in tests.
 *
 * <p>The Given/When/Then stages are nested here so the gate's behaviour reads in one place. Then
 * steps throw plain {@link AssertionError} (not JUnit) so they remain runnable from live.
 */
public final class ConfigEntryGate {

  private ConfigEntryGate() {}

  /** Given: the operator's configuration, presented as a loader over the supplied sections. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState ConfigLoader loader;

    @Hidden
    public Given the_operator_configuration(ConfigLoader loader) {
      this.loader = loader;
      return self();
    }
  }

  /** When: the configuration is loaded, capturing ready-vs-missing as the gate outcome. */
  public static class When extends Stage<When> {

    @ExpectedScenarioState ConfigLoader loader;

    @ProvidedScenarioState ConfigLoadOutcome outcome;

    public When the_configuration_is_loaded() {
      try {
        outcome = ConfigLoadOutcome.ready(Rke2labConfig.from(loader));
      } catch (MissingRequiredConfiguration missing) {
        outcome = ConfigLoadOutcome.missing(missing.keys());
      }
      return self();
    }
  }

  /** Then: assert the gate outcome only — resolved values are covered by Rke2labConfigTest. */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState ConfigLoadOutcome outcome;

    public Then the_configuration_is_ready() {
      if (!outcome.isReady()) {
        throw new AssertionError(
            "expected configuration to be ready but mandatory inputs were missing: "
                + outcome.missingKeys());
      }
      return self();
    }

    public Then the_missing_inputs_are(@Quoted String... keys) {
      if (outcome.isReady()) {
        throw new AssertionError(
            "expected missing inputs " + List.of(keys) + " but config was ready");
      }
      final List<String> expected = List.of(keys);
      if (!outcome.missingKeys().equals(expected)) {
        throw new AssertionError(
            "expected missing inputs " + expected + " but were " + outcome.missingKeys());
      }
      return self();
    }
  }
}
