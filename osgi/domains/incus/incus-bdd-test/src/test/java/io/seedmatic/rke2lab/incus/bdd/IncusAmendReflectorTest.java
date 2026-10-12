package io.seedmatic.rke2lab.incus.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.seedmatic.rke2lab.incus.contract.IncusRunbookInput;
import io.seedmatic.rke2lab.incus.contract.IncusRunbookInput.Facet;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.Amendment;
import io.seedmatic.rke2lab.seed.broker.port.AmendmentAssembler;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.testkit.RefusingCellar;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the first link of the facet wiring: the incus amend reflector binds the neutral {@code
 * facet} role onto {@code IncusRunbookInput.facet}. The host does NOT sow the facet per-consult —
 * it contributes the stable provisioning identity AMBIENT (an {@code AmendmentContributor} the
 * {@code AmendmentAssembler} gathers at the amend door), so the reflector must {@code gather} that
 * ambient role, not read it off the trigger payload. The regression this pins: the reflector once
 * ignored the assembler, so the ambient facet was silently dropped and the scion ran unamended (no
 * staging, no grow plan, no runbook). A pure-JSON reflector, so no container is needed; the
 * in-container proof of the scenario itself lives in {@code IncusBddInContainerTest}.
 */
class IncusAmendReflectorTest {

  private static final SeedCodec CODEC = new SeedCodec();

  /** The empty per-consult trigger the host sows the incus amend with (it carries no facet). */
  private static SeedEnvelope emptyTrigger() {
    return new SeedEnvelope("incus", "runbook", CODEC.encode(Map.of()));
  }

  /** An assembler that gathers the given ambient roles for any coordinate. */
  private static AmendmentAssembler ambient(Map<String, String> roles) {
    return coordinate -> roles;
  }

  @Test
  void it_binds_the_ambient_facet_role_onto_the_facet_scalars() {
    final Facet scalars = new Facet("bioskop", "bioskop-master", "rke2lab");
    final IncusAmendReflector reflector =
        new IncusAmendReflector(ambient(Map.of(Amendment.FACET, CODEC.encode(scalars))));

    final SeedEnvelope amended = reflector.handle(RefusingCellar.INSTANCE, emptyTrigger());

    assertEquals("runbook", amended.coordinate(), "the amended payload is ready to sow at runbook");
    final IncusRunbookInput bound = CODEC.decode(amended.payload(), IncusRunbookInput.class);
    assertEquals(
        Optional.of(scalars), bound.facet(), "the ambient facet role landed on the facet scalars");
  }

  @Test
  void no_ambient_facet_keeps_the_default_empty_facet() {
    final IncusAmendReflector reflector = new IncusAmendReflector(ambient(Map.of()));

    final IncusRunbookInput bound =
        CODEC.decode(
            reflector.handle(RefusingCellar.INSTANCE, emptyTrigger()).payload(),
            IncusRunbookInput.class);

    assertEquals(
        Optional.empty(), bound.facet(), "an unamended input keeps its empty facet amendment");
  }

  @Test
  void it_rejects_a_coordinate_it_does_not_amend() {
    final IncusAmendReflector reflector = new IncusAmendReflector(ambient(Map.of()));
    final SeedEnvelope unknown =
        new SeedEnvelope("incus", "not-a-bearer", CODEC.encode(Map.of(Amendment.FACET, "/x")));

    assertThrows(
        IllegalArgumentException.class, () -> reflector.handle(RefusingCellar.INSTANCE, unknown));
  }

  @Test
  void it_serves_the_incus_amend_coordinate() {
    final IncusAmendReflector reflector = new IncusAmendReflector(ambient(Map.of()));
    assertEquals(IncusScenario.PROVISION.amend(), reflector.serves());
  }
}
