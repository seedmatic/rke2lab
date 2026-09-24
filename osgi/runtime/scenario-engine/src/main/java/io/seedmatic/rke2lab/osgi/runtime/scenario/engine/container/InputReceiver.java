package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

/**
 * A scenario that RECEIVES its activation input from the driver before the body — the INBOUND
 * counterpart of {@link ConsultationSource} (which is pulled OUT). It replaces the static {@code
 * INPUT} holder + {@code seedInput(...)} setter the two input-bearing scenarios (incus-provision,
 * manifests) used to expose: the front-door seeds the input into the launcher session store ({@link
 * ScenarioInputSeed}), and {@link ScenarioInputSeed} (a post-processor) reads it back and hands it
 * here before the GIVEN runs.
 *
 * <p>The in-container twin of {@code seed.bdd.SeedReceiver} (which lives in an un-exported
 * foundation package the host root uses); a scion cannot import that, so the same shape lives here
 * in the exported {@code .container} package the scions already depend on. Opt-in by implementing
 * it — a scenario that reads no input (cluster, systemd, incus-reconcile) does not.
 *
 * @param <T> the activation input's type (e.g. {@code IncusRunbookInput}, {@code
 *     ManifestsRunbookInput}) — a bundle type carried in-realm (the handler and scenario share the
 *     bundle loader), so no codec crossing
 */
@FunctionalInterface
public interface InputReceiver<T> {

  /** Receive the driver's activation input, before the GIVEN runs. */
  void receiveInput(T input);
}
