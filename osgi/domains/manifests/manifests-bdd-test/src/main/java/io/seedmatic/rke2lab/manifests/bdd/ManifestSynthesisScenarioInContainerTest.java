package io.seedmatic.rke2lab.manifests.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.StepModel;
import io.seedmatic.rke2lab.manifests.contract.ManifestsRunbookInput;
import io.seedmatic.rke2lab.manifests.contract.SshToAgeConverter;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioOutcome;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.seed.broker.port.RunGate;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

/**
 * The in-container proof of the manifests scion, run WHERE the scenario lives (this passenger
 * shares the manifests-bdd host loader through the fragment). It resolves the REAL synthesis:
 * manifests-core's DS {@code ManifestSynthesisService} activates under SCR and the scenario drives
 * it (the real cdk8s synthesis runs inside it).
 *
 * <p>The synthesis binds ONE collaborator, {@link SshToAgeConverter} — a pure external-tool seam
 * (OpenSSH→age), mandatory so the {@code @Component} activates. The passenger registers a stub for
 * it in-container (the incus edge-mocking shape, on the shared fragment loader so no seam and no
 * system-export are needed): the scenario runs with no SSH key-store, so {@code
 * SopsAgeMaterialResolver} fail-softs and the converter is never actually reached — the stub throws
 * if it ever is, surfacing that as the defect it would be.
 *
 * <p>It proves BOTH renders, as every sibling scion does. A LIVE run (no gate — {@code
 * GardeningSelection} defaults to cultivating) plays GREEN: the operator's facet is translated and
 * synthesised into the manifest tree. A SURVEY run (a surveying {@link RunGate}) renders PENDING:
 * manifests is a MODE-BLIND materialiser with no live-mutating edge — no {@code Cultivating}/{@code
 * Surveying} pair, since its single synthesis is honest as a plan (it writes only to staging) — so
 * {@code SurveyRenderExtension} gives it a {@code PendingMarkingScenarioExecutor}: the bodies STILL
 * run, only the render is rewritten PENDING.
 *
 * <p>It plays in-container through {@link ScenarioPlayer} (the shared play recipe the production
 * {@code GenericRunbookHandler} also drives) — seeding the activation facet through the scenario's
 * own inbound {@link ManifestSynthesisScenario#INPUT} channel, exactly as the handler does — and
 * asserts on the harvested {@link ScenarioOutcome}. It reads the LIVE outcome (same in-container
 * worker), no JSON round-trip. Registrations are removed in a {@code finally} because the framework
 * is shared across the passenger's tests (an oldest-wins ranking tie would otherwise leak a mock).
 */
public class ManifestSynthesisScenarioInContainerTest {

  @Test
  void a_live_run_synthesizes_from_the_activation_facet() throws Exception {
    // The operator's usual posture (everything on except mesh, debug off) — a complete facet, the
    // same shape a sower plucks from Pulumi.dev.yaml. No gate: the run defaults to cultivating.
    final ScenarioOutcome outcome = playWith(null);
    final ReportModel runbook = outcome.runbook();

    assertNotNull(runbook, "the player harvested the played model");
    assertEquals(1, runbook.getScenarios().size(), "one scenario played");
    assertEquals(
        ExecutionStatus.SUCCESS,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "the facet was translated and synthesised into the manifest tree — plays green");
  }

  @Test
  void a_survey_run_synthesizes_but_renders_pending() throws Exception {
    // Same facet, but a surveying gate. manifests has no Cultivating/Surveying pair (nothing to
    // swap — its one contact is a read-only converter), so SurveyRenderExtension installs a
    // PendingMarkingScenarioExecutor: it STILL proceed()s every body (the WHEN synthesises to
    // staging, the THENs assert on the real result), only the render is rewritten NORMAL → PENDING.
    final ScenarioOutcome outcome = playWith(() -> false);
    final ReportModel runbook = outcome.runbook();

    // The bodies were genuinely played, not skipped: the WHEN synthesis step is in the tree...
    assertNotNull(
        stepNamed(runbook, "the manifests are synthesized"),
        "the surveyed run still played the synthesis step (a materialiser, not survey-inert)");
    // ...and the run reads PENDING, not FAILED — and that distinction IS the proof the bodies ran:
    // had synthesis produced nothing, the THEN assertion (the manifests file) would have THROWN
    // and the scenario would read FAILED. PENDING means it held, and
    // only the render was rewritten — a plan, not a result, exactly as every sibling scion proves.
    assertEquals(
        ExecutionStatus.SCENARIO_PENDING,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "a surveyed materialiser renders PENDING — the bodies ran and held, only the render flipped");
  }

  /**
   * Register the synthesis collaborator (the ssh-to-age edge stub) — and, when {@code gate} is
   * non-null, an ambient {@link RunGate} selecting the mode — into THIS bundle's registry, play the
   * scenario in-container through the shared {@link ScenarioPlayer}, and return its live {@link
   * ScenarioOutcome}. Registrations are removed in the {@code finally} so each test plays against
   * exactly its own services.
   */
  private static ScenarioOutcome playWith(RunGate gate) throws Exception {
    final BundleContext context =
        FrameworkUtil.getBundle(ManifestsBddTests.class).getBundleContext();
    final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    registrations.add(
        context.registerService(
            SshToAgeConverter.class, unreachableConverter(), new Hashtable<>()));
    // The synthesis component now also binds NdhKeystoreReader (mandatory) — seed a stub reporting
    // NO key-store, so SopsAgeMaterialResolver fail-softs exactly as before (the test seeds none).
    registrations.add(
        context.registerService(NdhKeystoreReader.class, absentKeystore(), new Hashtable<>()));
    if (gate != null) {
      registrations.add(context.registerService(RunGate.class, gate, new Hashtable<>()));
    }
    try {
      return new ScenarioPlayer()
          .play(
              ManifestSynthesisScenario.class,
              ManifestSynthesisScenario.INPUT.into(ManifestsRunbookInput.defaults()));
    } finally {
      registrations.forEach(ServiceRegistration::unregister);
    }
  }

  /** The named top-level step of the played scenario. */
  private static StepModel stepNamed(ReportModel runbook, String name) {
    return runbook.getScenarios().get(0).getScenarioCases().get(0).getSteps().stream()
        .filter(step -> name.equals(step.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no step named '" + name + "' in the runbook"));
  }

  /**
   * A converter the test never expects to reach — the scenario runs with no SSH key-store, so
   * {@code SopsAgeMaterialResolver} fail-softs before any conversion. If synthesis ever DID call
   * it, that is a defect the test must surface, so the stub throws rather than fabricating an age
   * key.
   */
  private static SshToAgeConverter unreachableConverter() {
    return sshPrivateKey -> {
      throw new AssertionError(
          "the in-container synthesis reached the ssh-to-age edge, but the test seeds no key-store");
    };
  }

  /**
   * A key-store reader reporting ABSENCE — the test seeds no ndh inventory, so {@code
   * SopsAgeMaterialResolver} fail-softs on {@code present() == false} and never reaches an
   * accessor. The accessors throw for the same reason {@link #unreachableConverter()} does:
   * reaching one is a defect the test must surface, not fabricate around.
   */
  private static NdhKeystoreReader absentKeystore() {
    return new NdhKeystoreReader() {
      @Override
      public boolean present() {
        return false;
      }

      @Override
      public String authorityCert(String authority) {
        throw new AssertionError(
            "the in-container synthesis read the ndh key-store, but none seeded");
      }

      @Override
      public String authorityDomain(String authority) {
        throw new AssertionError(
            "the in-container synthesis read the ndh key-store, but none seeded");
      }

      @Override
      public String authorityPrivate(String authority) {
        throw new AssertionError(
            "the in-container synthesis read the ndh key-store, but none seeded");
      }

      @Override
      public String sshPrivate(String keyName) {
        throw new AssertionError(
            "the in-container synthesis read the ndh key-store, but none seeded");
      }

      @Override
      public String sshPublic(String keyName) {
        throw new AssertionError(
            "the in-container synthesis read the ndh key-store, but none seeded");
      }
    };
  }
}
