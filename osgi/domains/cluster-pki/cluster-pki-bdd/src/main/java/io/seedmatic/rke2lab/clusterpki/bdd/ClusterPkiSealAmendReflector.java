package io.seedmatic.rke2lab.clusterpki.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterPkiSealInput;
import io.seedmatic.rke2lab.seed.broker.codec.AmendmentBinder;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.AmendmentAssembler;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.SeedContract;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.port.SeedHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The cluster-pki seal contribution of the amend verb: it serves {@code
 * AmendCoordinate("cluster-pki")} so a sower — holding the workload cluster names under the NEUTRAL
 * {@code workload-targets} role — can fill the {@link ClusterPkiSealInput} without naming the field
 * ({@code workloadClusters}). Mirrors {@code GithubAppWebhookAmendReflector}: it hands the gathered
 * + offered roles to the foundation {@link AmendmentBinder}, which places each role's value in its
 * {@code @Amendment} field. The amended node is returned under the {@code runbook} coordinate,
 * ready to sow at {@code RunbookCoordinate("cluster-pki")}. So the vocabulary reconciliation lives
 * at the door, never in the runbook handler.
 */
@Component(service = SeedHandler.class)
public final class ClusterPkiSealAmendReflector implements SeedHandler {

  /** The seal soil this reflector fills — the amend + runbook doors share it. */
  private static final String SOIL = "cluster-pki";

  /** The seal input wire-record that bears amendments, indexed by {@code @SeedContract} slug. */
  private static final Map<String, Class<?>> AMEND_BEARERS = index(ClusterPkiSealInput.class);

  private final SeedCodec codec = new SeedCodec();
  private final AmendmentBinder binder = new AmendmentBinder();
  private final AmendmentAssembler assembler;

  @Activate
  public ClusterPkiSealAmendReflector(@Reference AmendmentAssembler assembler) {
    this.assembler = assembler;
  }

  @Override
  public SeedCoordinate serves() {
    return new AmendCoordinate(SOIL);
  }

  @Override
  public SeedEnvelope handle(Cellar cellar, SeedEnvelope seed) {
    final Class<?> bearer = AMEND_BEARERS.get(seed.coordinate());
    if (bearer == null) {
      throw new IllegalArgumentException(
          "cluster-pki amends no input for coordinate '" + seed.coordinate() + "'");
    }
    // Ambient roles gathered at the door and merged UNDER the roles the sower offered — the sower's
    // per-consult WORKLOAD_TARGETS value wins over any ambient contribution on the same role.
    final Map<String, JsonNode> roleValues = new LinkedHashMap<>();
    assembler
        .gather(new AmendCoordinate(SOIL))
        .forEach((role, json) -> roleValues.put(role, codec.decode(json)));
    roleValues.putAll(roleValues(codec.decode(seed.payload())));
    final JsonNode amended = binder.bind(bearer, roleValues);
    return new SeedEnvelope(SOIL, seed.coordinate(), codec.encode(amended));
  }

  private static Map<String, JsonNode> roleValues(JsonNode payload) {
    final Map<String, JsonNode> values = new LinkedHashMap<>();
    payload.properties().forEach(entry -> values.put(entry.getKey(), entry.getValue()));
    return values;
  }

  private static Map<String, Class<?>> index(Class<?>... bearers) {
    final LinkedHashMap<String, Class<?>> byCoordinate = new LinkedHashMap<>();
    for (Class<?> bearer : bearers) {
      final SeedContract contract = bearer.getAnnotation(SeedContract.class);
      if (contract == null) {
        throw new IllegalStateException(bearer + " bears amendments but declares no @SeedContract");
      }
      byCoordinate.put(contract.value(), bearer);
    }
    return Map.copyOf(byCoordinate);
  }
}
