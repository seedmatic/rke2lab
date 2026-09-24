package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.List;

/**
 * A scenario that RAISED doctor consultations the outbound channel harvests alongside its runbook —
 * the pull twin of {@link CellarReceiver} (which is pushed IN). A fork-B scenario (cluster ·
 * incus-provision · systemd) consults its own doctor on a refused/failed row and holds the returned
 * {@link SeedEnvelope}s; {@link ScenarioOutcomeExtension} PULLS them at the run boundary (it
 * already pulls the runbook from jGiven's {@code ScenarioHolder}), so no static holder crosses the
 * launcher membrane.
 *
 * <p>Opt-in by implementing this: a scenario that consults no one (the root, incus-reconcile,
 * manifests) does not implement it, and the outcome carries an empty consultation list. The value
 * is read AFTER the body (jGiven defers a failed-step throw to scenario-end, so the {@code @Test}
 * still reaches its {@code consultOn*} computation — the consultation survives a FAILED runbook).
 */
@FunctionalInterface
public interface ConsultationSource {

  /** The consultations this scenario raised — empty when it consulted no one (a healthy run). */
  List<SeedEnvelope> consultations();
}
