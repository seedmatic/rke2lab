package io.seedmatic.rke2lab.osgi.runtime.scenario.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.NestedSteps;
import com.tngtech.jgiven.annotation.ScenarioStage;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.json.ScenarioJsonWriter;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.ScenarioModel;
import com.tngtech.jgiven.report.model.StepModel;
import com.tngtech.jgiven.report.model.StepStatus;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.GraftTag;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioGraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link ScenarioGraft} — the cross-world graft — in production, not in the bench spike. Two
 * scenarios are played to yield real {@link ReportModel}s: a HOST runbook whose crossing step
 * grafts, and a REMOTE rootstock. The scion's model crosses as a {@code ScenarioJsonWriter} String
 * (the only thing the seam carries), the graft {@link ScenarioGraft#rebuild rebuilds} it in this
 * realm and {@link ScenarioGraft#graftUnder grafts} it under the host step that is executing. Every
 * graft here runs INSIDE a host step, mid-run, as production does: that is the only condition under
 * which the executing step is the one the graft finds. What is asserted is the spec's E5 shape (one
 * continuous tree) + the fail-fast across the frontier (P2).
 */
class ScenarioGraftTest {

  private static final String ROOTSTOCK = "the scion world is consulted";
  private static final String ROOTSTOCK2 = "the second scion world is consulted";
  private static final String NESTED_ROOTSTOCK = "the nested crossing is consulted";
  private static final String STAGED_ROOTSTOCK = "the staged crossing is consulted";
  private static final String NESTED_STEP = "a nested step is recorded";
  private static final String GRAFT_STEP = "the graft runs";
  private static final String SCION_STEP = "the scion scenario runs green";
  private static final String SOIL = "scion";

  private final ScenarioGraft graft = new ScenarioGraft();

  /** The two live handles a crossing step holds mid-run. */
  private record Live(ScenarioModel scenario, ReportModel tree) {}

  @Test
  void a_healthy_scion_grafts_under_the_executing_step_and_the_host_continues() {
    final ReportModel scion =
        play(ScionStage.class, (r, live) -> r.the_scion_scenario_runs_green());

    // What actually crosses is a String; rebuild it in this realm before grafting.
    final String crossed = new ScenarioJsonWriter(scion).toString();
    assertFalse(crossed.isBlank(), "the scion scenario serializes to cross the seam");

    final List<String> graftedUnder = new ArrayList<>();
    final List<List<String>> stepsAtGraft = new ArrayList<>();
    final ReportModel host =
        playHost(
            live -> {
              stepsAtGraft.add(names(live.scenario().getScenarioCases().get(0).getSteps()));
              graftedUnder.add(
                  graft.graftUnder(live.scenario(), live.tree(), SOIL, graft.rebuild(crossed)));
            });

    assertEquals(List.of(ROOTSTOCK), graftedUnder, "the graft names the step it ran in");
    assertEquals(
        List.of(List.of(ROOTSTOCK)),
        stepsAtGraft,
        "while the graft runs, the rootstock is the last step: no host step after it exists yet");
    assertFalse(
        stepNamed(host, ROOTSTOCK).getNestedSteps().isEmpty(),
        "the scion steps grafted under the rootstock");
    assertEquals(
        StepStatus.PASSED, stepAfter(host, ROOTSTOCK).getStatus(), "the host phase after ran");
  }

  @Test
  void a_failing_scion_fails_its_rootstock_and_the_assert_skips_the_host_downstream() {
    final ReportModel scion = play(ScionStage.class, (r, live) -> r.the_scion_scenario_fails());
    assertEquals(
        ExecutionStatus.FAILED,
        scion.getScenarios().get(0).getExecutionStatus(),
        "the scion scenario failed");
    final ReportModel rebuilt = crossed(scion);

    final ReportModel host =
        playHost(
            live ->
                graft.assertPassed(
                    rebuilt, graft.graftUnder(live.scenario(), live.tree(), SOIL, rebuilt)));

    assertEquals(
        StepStatus.FAILED, stepNamed(host, ROOTSTOCK).getStatus(), "the scion failure propagates");
    assertEquals(
        StepStatus.SKIPPED,
        stepAfter(host, ROOTSTOCK).getStatus(),
        "the asserted graft throws, so the host phase after it is skipped (fail-fast across the"
            + " frontier)");
  }

  @Test
  void two_failing_scions_each_surface_their_own_error_under_their_crossing() {
    // Two sibling crossings fail INDEPENDENTLY in one run (the tolerating graft does not abort the
    // host between them). Each cause is ACCUMULATED under a header naming its crossing, not
    // first-wins — otherwise the later ❌ would carry no reason.
    final ReportModel first =
        crossed(play(ScionStage.class, (r, l) -> r.the_scion_scenario_fails()));
    final ReportModel second =
        crossed(play(ScionStage.class, (r, l) -> r.the_scion_scenario_fails()));

    final ReportModel host =
        play(
            HostStage.class,
            (h, live) ->
                h.the_scion_is_grafted(
                        () -> graft.graftUnder(live.scenario(), live.tree(), SOIL, first))
                    .the_second_scion_is_grafted(
                        () -> graft.graftUnder(live.scenario(), live.tree(), SOIL, second))
                    .the_host_finishes());

    assertEquals(
        StepStatus.FAILED, stepNamed(host, ROOTSTOCK).getStatus(), "the first crossing failed");
    assertEquals(
        StepStatus.FAILED,
        stepNamed(host, ROOTSTOCK2).getStatus(),
        "the second crossing failed too, grafted under its own step");

    final String error = host.getScenarios().get(0).getScenarioCases().get(0).getErrorMessage();
    assertTrue(error.contains(ROOTSTOCK), "the first crossing's error section is present");
    assertTrue(
        error.contains(ROOTSTOCK2),
        "the SECOND crossing's error survives too — not dropped by first-wins");
  }

  @Test
  void a_scion_tag_rides_up_with_the_graft_and_the_host_reads_it_back() {
    // The ephemeral cellar: a scion poses a within-run fact on its model; the graft merges its tag
    // map into the host tree, and the host reads it back through the mechanism — not by hand.
    final ReportModel scion =
        play(ScionStage.class, (r, live) -> r.the_scion_scenario_runs_green());
    scion.addTag(GraftTag.LIVE_ROOT.of("/x/.local.d/bioskop/master/host.live.d"));
    // Round-trip across the seam (serialize → rebuild) before grafting, as production does.
    final ReportModel rebuilt = crossed(scion);

    final ReportModel host =
        playHost(live -> graft.graftUnder(live.scenario(), live.tree(), SOIL, rebuilt));

    assertEquals(
        Optional.of("/x/.local.d/bioskop/master/host.live.d"),
        graft.graftedValue(host, GraftTag.LIVE_ROOT),
        "the scion's tag survived the seam and merged into the host tree");
  }

  @Test
  void a_missing_scion_tag_reads_back_empty() {
    final ReportModel rebuilt =
        crossed(play(ScionStage.class, (r, live) -> r.the_scion_scenario_runs_green()));

    final ReportModel host =
        playHost(live -> graft.graftUnder(live.scenario(), live.tree(), SOIL, rebuilt));

    assertEquals(
        Optional.empty(),
        graft.graftedValue(host, GraftTag.LIVE_ROOT),
        "a scion that posed no tag reads back empty, not a crash");
  }

  @Test
  void a_graft_with_no_host_step_executing_is_a_loud_wiring_bug() {
    final ReportModel rebuilt =
        crossed(play(ScionStage.class, (r, live) -> r.the_scion_scenario_runs_green()));

    // The host scenario has started but no step has been invoked: there is no rootstock at all.
    play(
        HostStage.class,
        (h, live) -> {
          final IllegalArgumentException thrown =
              assertThrows(
                  IllegalArgumentException.class,
                  () -> graft.graftUnder(live.scenario(), live.tree(), SOIL, rebuilt),
                  "grafting with no host step executing fails loudly, not silently");
          assertTrue(
              thrown.getMessage().contains("no host step is executing"),
              "the failure says why: " + thrown.getMessage());
        });
  }

  @Test
  void a_scion_grafts_into_the_live_scenario_while_the_report_model_has_no_scenario_yet() {
    // jGiven appends the current scenario to its ReportModel only when the scenario FINISHES, so
    // mid-step getModel().getScenarios() is empty while the live ScenarioModel already carries the
    // executing step. The graft must target that live ScenarioModel, not the empty ReportModel;
    // fishing the host scenario out of the ReportModel was the live "no scenario to graft".
    final ReportModel rebuilt =
        crossed(play(ScionStage.class, (r, live) -> r.the_scion_scenario_runs_green()));
    final List<Integer> reportScenariosAtGraft = new ArrayList<>();

    final ReportModel host =
        playHost(
            live -> {
              reportScenariosAtGraft.add(live.tree().getScenarios().size());
              graft.graftUnder(live.scenario(), live.tree(), SOIL, rebuilt);
            });

    assertEquals(
        List.of(0), reportScenariosAtGraft, "the ReportModel held no scenario when the graft ran");
    assertFalse(
        stepNamed(host, ROOTSTOCK).getNestedSteps().isEmpty(),
        "the scion grafted into the live ScenarioModel all the same");
  }

  @Test
  void under_nested_steps_the_graft_lands_on_the_parent_not_on_a_step_it_recorded() {
    // A @NestedSteps step makes jGiven RECORD the stage calls made in its body — as nested steps
    // of that parent, which stays the case's last top-level step. Here the parent records a nested
    // step FIRST, then grafts: the rootstock must still be the parent.
    final ReportModel rebuilt =
        crossed(play(ScionStage.class, (r, live) -> r.the_scion_scenario_runs_green()));
    final List<String> graftedUnder = new ArrayList<>();

    final ReportModel host =
        play(
            HostStage.class,
            (h, live) ->
                h.the_nested_crossing_is_grafted(
                        () ->
                            graftedUnder.add(
                                graft.graftUnder(live.scenario(), live.tree(), SOIL, rebuilt)))
                    .the_host_finishes());

    assertEquals(List.of(NESTED_ROOTSTOCK), graftedUnder, "the graft names the parent");
    assertEquals(
        List.of(NESTED_ROOTSTOCK, "the host finishes"),
        names(topLevelSteps(host)),
        "the nested step stayed under its parent; the parent is the first top-level step");
    assertEquals(
        List.of(NESTED_STEP, SCION_STEP),
        names(stepNamed(host, NESTED_ROOTSTOCK).getNestedSteps()),
        "the scion grafted under the parent, after the nested step the parent recorded");
    assertTrue(
        nestedStepNamed(host, NESTED_ROOTSTOCK, NESTED_STEP).getNestedSteps().isEmpty(),
        "the nested step the parent recorded is not the rootstock");
  }

  @Test
  void under_nested_steps_a_graft_run_from_another_stage_step_lands_on_the_parent() {
    // The production shape: the @NestedSteps crossing step calls SowAndGraftStage's step, which
    // jGiven records as a nested step and which is EXECUTING when the graft runs. The rootstock is
    // still the parent, not that executing nested step.
    final ReportModel rebuilt =
        crossed(play(ScionStage.class, (r, live) -> r.the_scion_scenario_runs_green()));
    final List<String> graftedUnder = new ArrayList<>();

    final ReportModel host =
        play(
            HostStage.class,
            (h, live) ->
                h.the_staged_crossing_is_grafted(
                        () ->
                            graftedUnder.add(
                                graft.graftUnder(live.scenario(), live.tree(), SOIL, rebuilt)))
                    .the_host_finishes());

    assertEquals(List.of(STAGED_ROOTSTOCK), graftedUnder, "the graft names the parent");
    assertEquals(
        List.of(STAGED_ROOTSTOCK, "the host finishes"),
        names(topLevelSteps(host)),
        "the stage step stayed under its parent; the parent is the first top-level step");
    assertEquals(
        List.of(NESTED_STEP, GRAFT_STEP, SCION_STEP),
        names(stepNamed(host, STAGED_ROOTSTOCK).getNestedSteps()),
        "the scion grafted under the parent, beside the stage step that ran the graft");
    assertTrue(
        nestedStepNamed(host, STAGED_ROOTSTOCK, GRAFT_STEP).getNestedSteps().isEmpty(),
        "the executing stage step is not the rootstock");
  }

  @Test
  void the_default_propagates_a_failed_scion_as_a_throw_and_a_green_one_passes() {
    // assertPassed(model, label) is what the DEFAULT sow calls to PROPAGATE the scion verdict: a
    // FAILED scion throws its reason (fail-fast across the frontier); a green scion is a no-op.
    final ReportModel failing = play(ScionStage.class, (r, live) -> r.the_scion_scenario_fails());
    final AssertionError thrown =
        assertThrows(
            AssertionError.class,
            () -> graft.assertPassed(failing, ROOTSTOCK),
            "a failed scion propagates as a throw");
    assertTrue(thrown.getMessage().contains(ROOTSTOCK), "the throw names the crossing");

    final ReportModel green =
        play(ScionStage.class, (r, live) -> r.the_scion_scenario_runs_green());
    graft.assertPassed(green, ROOTSTOCK); // a green scion does not throw
  }

  @Test
  void the_closing_gate_fails_on_a_tolerated_failure_and_passes_when_clean() {
    // assertNoCrossingFailed is the sower's CLOSING GATE: after a TOLERATED crossing grafted a
    // FAILED verdict onto the host case WITHOUT throwing (so its siblings ran), the gate throws the
    // accumulated reason so the run fails overall. A clean host passes the gate.
    final ReportModel cleanHost = playHost(live -> {});
    graft.assertNoCrossingFailed(cleanHost.getScenarios().get(0), cleanHost); // nothing failed

    final ReportModel failing =
        crossed(play(ScionStage.class, (r, live) -> r.the_scion_scenario_fails()));
    final ReportModel host =
        playHost(live -> graft.graftUnder(live.scenario(), live.tree(), SOIL, failing));

    final AssertionError thrown =
        assertThrows(
            AssertionError.class,
            () -> graft.assertNoCrossingFailed(host.getScenarios().get(0), host),
            "the gate throws when a tolerated crossing left an error on the host case");
    assertTrue(thrown.getMessage().contains(ROOTSTOCK), "the gate carries the crossing's reason");
  }

  /** The host runbook: a crossing step that runs {@code crossing}, then a downstream phase. */
  private static ReportModel playHost(Consumer<Live> crossing) {
    return play(
        HostStage.class,
        (h, live) -> h.the_scion_is_grafted(() -> crossing.accept(live)).the_host_finishes());
  }

  /** Serialize a played scion across the seam and rebuild it in this realm, as production does. */
  private ReportModel crossed(ReportModel scion) {
    return graft.rebuild(new ScenarioJsonWriter(scion).toString());
  }

  /**
   * Play a standalone jGiven scenario to its {@link ReportModel}, the way a checkpoint does (raw
   * {@code Scenario.create}, not the JUnit runner), handing the body the scenario's live handles.
   * {@code finished()} throws on the failing path but has already flushed the FAILED scenario into
   * the model, so it is swallowed.
   */
  private static <T extends Stage<T>> ReportModel play(
      Class<T> stageType, BiConsumer<T, Live> body) {
    final ReportModel model = new ReportModel();
    model.setClassName(stageType.getSimpleName());
    final Scenario<T, T, T> scenario = Scenario.create(stageType);
    scenario.setModel(model);
    scenario.startScenario("scenario");
    body.accept(scenario.getGivenStage(), new Live(scenario.getScenarioModel(), model));
    try {
      scenario.finished();
    } catch (Throwable diagnosed) {
      // the failing path throws; finished() has already flushed the FAILED scenario into the model.
    }
    return model;
  }

  private static List<StepModel> topLevelSteps(ReportModel model) {
    return model.getScenarios().get(0).getScenarioCases().get(0).getSteps();
  }

  private static List<String> names(List<StepModel> steps) {
    return steps.stream().map(StepModel::getName).toList();
  }

  private static StepModel stepNamed(ReportModel model, String name) {
    return topLevelSteps(model).stream()
        .filter(s -> name.equals(s.getName()))
        .findFirst()
        .orElseThrow();
  }

  private static StepModel nestedStepNamed(ReportModel model, String parent, String name) {
    return stepNamed(model, parent).getNestedSteps().stream()
        .filter(s -> name.equals(s.getName()))
        .findFirst()
        .orElseThrow();
  }

  private static StepModel stepAfter(ReportModel model, String name) {
    final var steps = model.getScenarios().get(0).getScenarioCases().get(0).getSteps();
    for (int i = 0; i < steps.size() - 1; i++) {
      if (name.equals(steps.get(i).getName())) {
        return steps.get(i + 1);
      }
    }
    throw new IllegalStateException("no step after " + name);
  }

  /** The host-world stages: crossing steps that run the graft handed to them, then a phase. */
  public static class HostStage extends Stage<HostStage> {
    @ScenarioStage GraftingStage grafting;

    @NestedSteps
    @As("the nested crossing is consulted")
    public HostStage the_nested_crossing_is_grafted(@Hidden Runnable crossing) {
      a_nested_step_is_recorded();
      crossing.run();
      return self();
    }

    @NestedSteps
    @As("the staged crossing is consulted")
    public HostStage the_staged_crossing_is_grafted(@Hidden Runnable crossing) {
      a_nested_step_is_recorded();
      grafting.the_graft_runs(crossing);
      return self();
    }

    @As("a nested step is recorded")
    public HostStage a_nested_step_is_recorded() {
      return self();
    }

    @As("the scion world is consulted")
    public HostStage the_scion_is_grafted(@Hidden Runnable crossing) {
      crossing.run();
      return self();
    }

    @As("the second scion world is consulted")
    public HostStage the_second_scion_is_grafted(@Hidden Runnable crossing) {
      crossing.run();
      return self();
    }

    @As("the host finishes")
    public HostStage the_host_finishes() {
      return self();
    }
  }

  /** A second stage whose step runs the graft, the way SowAndGraftStage's step does. */
  public static class GraftingStage extends Stage<GraftingStage> {
    @As("the graft runs")
    public GraftingStage the_graft_runs(@Hidden Runnable crossing) {
      crossing.run();
      return self();
    }
  }

  /** The scion-world stage: the rootstock, green or failing. */
  public static class ScionStage extends Stage<ScionStage> {
    @As("the scion scenario runs green")
    public ScionStage the_scion_scenario_runs_green() {
      return self();
    }

    @As("the scion scenario fails")
    public ScionStage the_scion_scenario_fails() {
      throw new AssertionError("the scion rootstock diagnosed a symptom");
    }
  }
}
