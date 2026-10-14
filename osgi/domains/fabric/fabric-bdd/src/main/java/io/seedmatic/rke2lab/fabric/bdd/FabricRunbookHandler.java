package io.seedmatic.rke2lab.fabric.bdd;

import io.seedmatic.rke2lab.fabric.contract.FabricCoordinate;
import io.seedmatic.rke2lab.fabric.contract.FabricRunbookInput;
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
 * The fabric domain's runbook handler — the OSGi-side grower behind the broker's host→scion door.
 * Extends {@link GenericRunbookHandler} and supplies the coordinate it serves ({@code fabric}) and
 * its scenario. It READS the trigger: {@link #seedFrom} decodes the {@link FabricRunbookInput} (the
 * plot the linked worktree is made under) off {@code trigger.payload()} and routes it through the
 * scenario's {@link FabricDeliveryScenario#INPUT} channel.
 */
@Component(service = SeedHandler.class)
public final class FabricRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = FabricCoordinate.RUNBOOK;

  private final SeedCodec codec = new SeedCodec();

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return FabricDeliveryScenario.class;
  }

  @Override
  public Consumer<NamespacedHierarchicalStore<Namespace>> seedFrom(SeedEnvelope trigger) {
    return FabricDeliveryScenario.INPUT.into(
        codec.decode(trigger.payload(), FabricRunbookInput.class));
  }
}
