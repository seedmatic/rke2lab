package io.seedmatic.rke2lab.fabric.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.fabric.contract.FabricCoordinate;
import io.seedmatic.rke2lab.fabric.contract.FabricRunbookInput;
import io.seedmatic.rke2lab.seed.broker.codec.AmendmentBinder;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
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
 * Fabric's contribution of the amend verb: it serves {@code AmendCoordinate("fabric")} so a sower —
 * holding a value under a NEUTRAL role ({@code soil}) — can fill the fabric runbook input without
 * naming the field ({@code worktreesRoot}). Mirrors {@code NetplanAmendReflector}: it hands the
 * gathered + offered roles to the foundation {@link AmendmentBinder}, which places each role's
 * value in its {@code @Amendment} field ({@code worktreesRoot} is {@code Optional} — absent binds
 * empty, no door default). The amended node is returned under the {@code runbook} coordinate, ready
 * to sow at {@link FabricCoordinate#RUNBOOK}. So the vocabulary reconciliation lives at the door,
 * never in the runbook handler.
 */
@Component(service = SeedHandler.class)
public final class FabricAmendReflector implements SeedHandler {

  private static final String DOMAIN = FabricCoordinate.DOMAIN;

  /** The fabric input wire-records that bear amendments, indexed by {@code @SeedContract} slug. */
  private static final Map<String, Class<?>> AMEND_BEARERS = index(FabricRunbookInput.class);

  private final SeedCodec codec = new SeedCodec();
  private final AmendmentBinder binder = new AmendmentBinder();
  private final AmendmentAssembler assembler;

  @Activate
  public FabricAmendReflector(@Reference AmendmentAssembler assembler) {
    this.assembler = assembler;
  }

  @Override
  public SeedCoordinate serves() {
    return FabricCoordinate.AMEND;
  }

  @Override
  public SeedEnvelope handle(Cellar cellar, SeedEnvelope seed) {
    final Class<?> bearer = AMEND_BEARERS.get(seed.coordinate());
    if (bearer == null) {
      throw new IllegalArgumentException(
          "fabric amends no input for coordinate '" + seed.coordinate() + "'");
    }
    // Ambient roles gathered at the door and merged UNDER the roles the sower offered — a sower's
    // per-consult value (SOIL) wins over an ambient contribution on the same role.
    final Map<String, JsonNode> roleValues = new LinkedHashMap<>();
    assembler
        .gather(FabricCoordinate.AMEND)
        .forEach((role, json) -> roleValues.put(role, codec.decode(json)));
    roleValues.putAll(roleValues(codec.decode(seed.payload())));
    final JsonNode amended = binder.bind(bearer, roleValues);
    return new SeedEnvelope(DOMAIN, seed.coordinate(), codec.encode(amended));
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
