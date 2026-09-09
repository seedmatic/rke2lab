package io.seedmatic.rke2lab.manifests.bdd;

import io.seedmatic.rke2lab.manifests.contract.IncusIdentitySealInput;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.GenericRunbookHandler;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.port.SeedHandler;
import java.util.function.Consumer;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;
import org.osgi.service.component.annotations.Component;

/**
 * The incus-identity seal runbook handler — the OSGi-side grower behind the broker's {@code
 * incus-identity} host→scion door. Decodes the {@link IncusIdentitySealInput} (the host-world creds
 * the INCUS_IDENTITY amendment bound) off {@code trigger.payload()} and routes it through {@link
 * IncusIdentitySealScenario#INPUT}. Sown with no amendment (an empty trigger) → a blank input, so
 * the scion files nothing.
 */
@Component(service = SeedHandler.class)
public final class IncusIdentitySealRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = new RunbookCoordinate("incus-identity");

  private final SeedCodec codec = new SeedCodec();

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return IncusIdentitySealScenario.class;
  }

  @Override
  public Consumer<NamespacedHierarchicalStore<Namespace>> seedFrom(SeedEnvelope trigger) {
    // The incus identity is mandatory: decode straight through, so an absent amendment fails loud
    // (the record's required host) rather than seeding a fallback.
    return IncusIdentitySealScenario.INPUT.into(
        codec.decode(trigger.payload(), IncusIdentitySealInput.class));
  }
}
