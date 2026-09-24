package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.report.json.ScenarioJsonWriter;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.List;

/**
 * The whole product of an in-container scenario run, serialised as one JSON String across the realm
 * boundary — the flat form the {@code GenericRunbookHandler} hands back through the broker's door.
 * It replaces the per-domain {@code *BddScenarios.RunbookEnvelope} copies (cluster, systemd, incus,
 * manifests) that were byte-for-byte identical: one record, in the engine's exported {@code
 * .container} package the domains already depend on.
 *
 * <p>Neither half can cross the realm boundary LIVE: the jGiven {@code ReportModel} is loaded by
 * the scion bundle's loader (it would {@code ClassCastException} on the flat host loader), and a
 * single reflective return forbids a live-object-plus-json mix. So the whole envelope is flat JSON
 * — the {@code runbook} is the {@link ScenarioJsonWriter} text of the played model, the {@code
 * consultations} are the doctor consultations the run raised (already flat 3-String {@link
 * SeedEnvelope}s), empty when the run consulted no one. The host reads it back with its own jackson
 * ({@code ScenarioGraft}).
 */
public record RunbookEnvelope(String runbook, List<SeedEnvelope> consultations) {

  public RunbookEnvelope {
    consultations = List.copyOf(consultations);
  }
}
