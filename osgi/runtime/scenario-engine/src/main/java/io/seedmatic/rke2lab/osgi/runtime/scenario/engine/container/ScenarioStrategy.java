package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.function.Consumer;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;

/**
 * The three axes on which one domain's runbook play differs from another's — the ONLY variation
 * {@link GenericRunbookHandler} parameterises over. Everything else (the cast to {@code
 * TransactionalCellar}, the {@link ScenarioPlayer} launch, the harvest of the {@link
 * ScenarioOutcome}, the encode of the {@link RunbookEnvelope}, the {@code InterruptedException}
 * dance) is copy-free in the generic handler.
 *
 * <p>A domain supplies a strategy by IMPLEMENTING it on its {@code @Component} handler (the
 * component IS the strategy — the scenario class is bundle-private, so the naming
 * {@code @Component} must live in the domain bundle anyway; making it the strategy keeps the
 * piece-count at one). The three methods are the whole of what a handler used to spell out:
 *
 * <ul>
 *   <li>{@link #coordinate()} — the {@link RunbookCoordinate} this play answers to (its {@code
 *       serves()} and the reaped envelope's coordinate).
 *   <li>{@link #scenarioClass()} — the bundle-private {@code @SeedScenario} the launcher selects;
 *       its classloader is the bundle's, so the launch runs in the domain's realm.
 *   <li>{@link #seedFrom(SeedEnvelope)} — the INPUT fork. A no-input scion (cluster, systemd,
 *       incus-reconcile) leaves the default no-op; an input-bearing scion (manifests,
 *       incus-provision) overrides it to decode its typed activation input off the trigger and seed
 *       it through the scenario's {@link ScenarioInputSeed} channel.
 * </ul>
 */
public interface ScenarioStrategy {

  /** The coordinate this play answers to — the routing key and the reaped envelope's coordinate. */
  RunbookCoordinate coordinate();

  /**
   * The bundle-private {@code @SeedScenario} class the launcher selects and plays in-container. Its
   * classloader is the domain bundle's, so the launch runs in the domain's realm. Bounded to {@link
   * ScenarioPlayer.Playable} — OUR marker every seed scenario implements — so a domain cannot hand
   * the player a non-scenario, and the bound stays in our own vocabulary (not jGiven's {@code
   * ScenarioTestBase}, which would leak a third-party type into this API).
   */
  Class<? extends ScenarioPlayer.Playable> scenarioClass();

  /**
   * The input-seeding consumer for this play, handed to the launcher's inbound channel. The default
   * seeds nothing (a scion that ignores its trigger); an input-bearing scion overrides it to decode
   * the typed activation input off {@code trigger} and route it through its {@link
   * ScenarioInputSeed} — {@code return SomeScenario.INPUT.into(decode(trigger, SomeInput.class))}.
   */
  default Consumer<NamespacedHierarchicalStore<Namespace>> seedFrom(SeedEnvelope trigger) {
    return store -> {};
  }
}
