package io.seedmatic.rke2lab.manifests.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.manifests.contract.IncusIdentitySealInput;
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
 * The incus-identity seal contribution of the amend verb: it serves {@code
 * AmendCoordinate("incus-identity")} so the sower — holding the host-world creds under the NEUTRAL
 * {@code incus-identity} role — can fill the {@link IncusIdentitySealInput} without naming the
 * field. Mirrors {@code ClusterPkiSealAmendReflector}: it hands the gathered + offered roles to the
 * foundation {@link AmendmentBinder}, which places each role's value in its {@code @Amendment}
 * field, and returns the amended node under the {@code runbook} coordinate.
 */
@Component(service = SeedHandler.class)
public final class IncusIdentitySealAmendReflector implements SeedHandler {

  private static final String SOIL = "incus-identity";

  private static final Map<String, Class<?>> AMEND_BEARERS = index(IncusIdentitySealInput.class);

  private final SeedCodec codec = new SeedCodec();
  private final AmendmentBinder binder = new AmendmentBinder();
  private final AmendmentAssembler assembler;

  @Activate
  public IncusIdentitySealAmendReflector(@Reference AmendmentAssembler assembler) {
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
          "incus-identity amends no input for coordinate '" + seed.coordinate() + "'");
    }
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
