package io.seedmatic.rke2lab.clusterpki.bdd;

import io.seedmatic.rke2lab.clusterpki.contract.ClusterPkiSealInput;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.GenericRunbookHandler;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.port.SeedHandler;
import java.util.List;
import java.util.function.Consumer;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;
import org.osgi.service.component.annotations.Component;

/**
 * The cluster-pki runbook handler — the OSGi-side grower behind the broker's one host→scion door.
 * Extends {@link GenericRunbookHandler} and supplies the coordinate it serves ({@code
 * RunbookCoordinate("cluster-pki")}) and the bundle-private scenario the launcher plays on THIS
 * bundle's loader.
 *
 * <p>The seal reads the operator's key-store + {@code .sops.yaml} in-container, so its ONLY
 * host-held input is the {@link ClusterPkiSealInput} the WORKLOAD_TARGETS amendment binds (the
 * workload cluster names the mgmt will greenfield, so the seal mints their BYO-CA sets too). {@link
 * #seedFrom} decodes it off {@code trigger.payload()} and routes it through the scenario's {@link
 * ClusterPkiSealScenario#INPUT} channel. Sown with no amendment (an empty trigger) → an empty
 * input, so the scion mints only the mgmt CA.
 */
@Component(service = SeedHandler.class)
public final class ClusterPkiRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = new RunbookCoordinate("cluster-pki");

  private final SeedCodec codec = new SeedCodec();

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return ClusterPkiSealScenario.class;
  }

  @Override
  public Consumer<NamespacedHierarchicalStore<Namespace>> seedFrom(SeedEnvelope trigger) {
    final String payload = trigger.payload();
    final ClusterPkiSealInput input =
        payload == null || payload.isBlank()
            ? new ClusterPkiSealInput(List.of())
            : codec.decode(payload, ClusterPkiSealInput.class);
    return ClusterPkiSealScenario.INPUT.into(input);
  }
}
