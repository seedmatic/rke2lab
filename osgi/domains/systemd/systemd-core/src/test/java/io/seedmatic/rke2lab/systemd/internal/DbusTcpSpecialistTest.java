package io.seedmatic.rke2lab.systemd.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.doctor.contract.Assessment;
import io.seedmatic.rke2lab.doctor.contract.Observation;
import io.seedmatic.rke2lab.doctor.contract.Prescription;
import io.seedmatic.rke2lab.doctor.contract.Referral;
import io.seedmatic.rke2lab.doctor.contract.RemediationProgramRef;
import io.seedmatic.rke2lab.doctor.contract.Symptom;
import io.seedmatic.rke2lab.doctor.testkit.TestReferrals;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The systemd diagnostician's contract, as a plain JVM unit test colocated with the specialist
 * (same package, so it reads the package-private ADAPTER_UNIT / restartUnitCommand to pin the
 * contract without re-spelling them). The two acts are pure — they read facts off the OBSERVATION
 * the producer stamped, never host config — so no OSGi boot is needed here; the DS contribution
 * itself is proven generically by the doctor's HealthSystemContributionTest. The {@code Referral}
 * fixture comes from the shared {@link TestReferrals} testkit factory.
 */
class DbusTcpSpecialistTest {

  @Test
  void declined_symptom_assesses_but_does_not_prescribe() {
    final DbusTcpSpecialist specialist = new DbusTcpSpecialist();
    final Observation observation = Observation.failed(Symptom.TIMEOUT, "timed out", Map.of());
    final Referral referral = TestReferrals.of(Symptom.TIMEOUT, observation);

    final Assessment assessment = specialist.assess(referral);
    assertEquals("dbus-tcp/declined/v1", assessment.schemaRef().id());
    assertTrue(
        assessment.summary().contains("no treatment"),
        () -> "a decline must explain itself: " + assessment.summary());
    assertFalse(
        specialist.prescribe(referral, assessment).isPresent(),
        "the dbus specialist only treats connection-refused");
  }

  @Test
  void connection_refused_assesses_and_prescribes_from_the_observation() {
    final DbusTcpSpecialist specialist = new DbusTcpSpecialist();
    // The producer (the endpoint gate) stamps the endpoint + node as flat details keys; the
    // specialist reads them off the observation rather than reaching back to host config.
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED,
            "dbus refused",
            Map.of(
                "adapterHost", "10.0.0.7", "adapterPort", "55555", "nodeName", "seed-outcluster"));
    final Referral referral = TestReferrals.of(Symptom.CONNECTION_REFUSED, observation);

    final Assessment assessment = specialist.assess(referral);
    assertEquals("dbus-tcp/connection-refused/v1", assessment.schemaRef().id());
    assertTrue(
        assessment.summary().contains("10.0.0.7:55555"),
        () -> "the endpoint read off the observation must appear: " + assessment.summary());

    final Optional<Prescription> prescribed = specialist.prescribe(referral, assessment);
    assertTrue(prescribed.isPresent(), "connection-refused is the dbus specialist's treatment");
    final Prescription prescription = prescribed.orElseThrow();
    assertEquals(RemediationProgramRef.RESTART_UNIT, prescription.programRef());
    assertEquals(DbusTcpSpecialist.ADAPTER_UNIT, prescription.payload().get("unit"));

    // The humanHint is the incus egress command — built from the specialist's single format helper,
    // with the node read off the observation. Asserting via the helper pins the contract without
    // re-spelling the format here.
    final String humanHint = prescription.humanHint();
    assertEquals(
        DbusTcpSpecialist.restartUnitCommand("seed-outcluster", DbusTcpSpecialist.ADAPTER_UNIT),
        humanHint,
        "the hint is the helper's format with the node taken from the observation");
    assertFalse(
        humanHint.contains("refused"),
        () ->
            "the reasoning moved into the assessment; humanHint is the action only: " + humanHint);
  }
}
