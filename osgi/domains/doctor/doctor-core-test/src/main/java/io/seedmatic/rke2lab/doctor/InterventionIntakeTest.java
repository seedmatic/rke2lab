package io.seedmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.seedmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.seedmatic.rke2lab.doctor.contract.InterventionRequest;
import io.seedmatic.rke2lab.doctor.contract.ReadinessVerdict;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.diagnostic.ScrDiagnostics;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.port.SeedHandler;
import io.seedmatic.rke2lab.seed.broker.testkit.RefusingCellar;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

/**
 * The in-container proof of the ingress canonicalization seam: SCR activates {@code
 * DefaultInterventionIntake} on its own — it declares NO references, the whole point of growing
 * intervention canonicalization as a standalone {@link SeedHandler} {@code @Component} rather than
 * on {@code HealthSystem} (which would demand the EHR + ledger the CLI has no reason to publish). A
 * published {@code SeedHandler} serving {@code intervention} IS that proof. It runs here, not from
 * the bare JVM, because the canonicalizer builds the {@code Intervention} from the doctor
 * vocabulary that never leaves the OSGi world.
 */
class InterventionIntakeTest {

  private static final SeedCodec CODEC = new SeedCodec();
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  /** The {@link SeedHandler} SCR publishes for the {@code intervention} coordinate. */
  private static SeedHandler interventionHandler(BundleContext context) throws Exception {
    final var references = context.getServiceReferences(SeedHandler.class, null);
    assertNotNull(
        references,
        "SCR must publish a SeedHandler for intervention — DefaultInterventionIntake activates"
            + " with NO references, so no EHR/ledger need be published for canonicalization."
            + ScrDiagnostics.of(context).map(ScrDiagnostics::report).orElse(""));
    for (var reference : references) {
      final SeedHandler handler = context.getService(reference);
      if (handler != null && handler.serves() == DoctorCoordinate.INTERVENTION) {
        return handler;
      }
    }
    throw new AssertionError(
        "no SeedHandler serves the intervention coordinate"
            + ScrDiagnostics.of(context).map(ScrDiagnostics::report).orElse(""));
  }

  @Test
  void scrPublishesInterventionIntakeAndItCanonicalizesRawFacts() throws Exception {
    final BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
    final SeedHandler intake = interventionHandler(context);

    final InterventionRequest request =
        new InterventionRequest(
            "systemd-adapter/connection-refused",
            "nft delete ...",
            Optional.empty(),
            Optional.empty(),
            Instant.parse("2026-06-14T09:30:00Z"));
    final SeedEnvelope rawFacts =
        new SeedEnvelope(
            "doctor", DoctorCoordinate.INTERVENTION_REQUEST.slug(), CODEC.encode(request));

    final SeedEnvelope canonical = intake.handle(RefusingCellar.INSTANCE, rawFacts);
    assertEquals(
        DoctorCoordinate.INTERVENTION.slug(),
        canonical.coordinate(),
        "valid facts must canonicalize, not return an error verdict");
    final Map<String, Object> payload = CODEC.reading().readJson(canonical.payload(), MAP);
    assertEquals("operator-manual", payload.get("provenance"));
    assertEquals("systemd-adapter/connection-refused", payload.get("problem"));
    assertEquals("nft delete ...", payload.get("what"));
  }

  @Test
  void anUnparseableProblemComesBackAsAnErrorVerdict() throws Exception {
    final BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
    final SeedHandler intake = interventionHandler(context);

    final InterventionRequest request =
        new InterventionRequest(
            "no-such-checkpoint/whatever",
            "something",
            Optional.empty(),
            Optional.empty(),
            Instant.parse("2026-06-14T09:30:00Z"));
    final SeedEnvelope rawFacts =
        new SeedEnvelope(
            "doctor", DoctorCoordinate.INTERVENTION_REQUEST.slug(), CODEC.encode(request));

    final SeedEnvelope verdict = intake.handle(RefusingCellar.INSTANCE, rawFacts);
    assertEquals(
        DoctorCoordinate.READINESS_VERDICT.slug(),
        verdict.coordinate(),
        "a bad reference must return an error verdict, not throw across the seam");
    final ReadinessVerdict decoded = CODEC.decode(verdict, ReadinessVerdict.class);
    assertTrue(
        decoded.reason().contains("no-such-checkpoint"),
        () -> "the verdict reason must name the bad reference: " + decoded);
  }
}
