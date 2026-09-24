package io.seedmatic.rke2lab.osgi.runtime.scenario.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.junit5.JGivenExtension;
import com.tngtech.jgiven.report.model.ReportModel;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioOutcome;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioOutcomeExtension;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Reproduces the LIVE nesting the {@code ClusterSeedScenario} WHEN does — a scenario whose body
 * sows a sub-scenario through {@link ScenarioPlayer} — the shape no existing test exercised: {@code
 * ScenarioGraftTest} builds its two models by hand ({@code Scenario.create} + {@code setModel},
 * bypassing the launcher + {@code ScenarioHolder}), and every {@code *BddInContainerTest} plays a
 * scenario ALONE. This plays an INNER {@code ScenarioPlayer.Playable} FROM INSIDE an OUTER one's
 * body (nested launcher sessions, the {@code ScenarioOutcomeExtension} firing at two levels on the
 * same worker) and asserts the inner outcome still carries its scenario — the datum that decides
 * whether the live "no scenario to graft" is a nesting defect in the play/harvest machinery.
 */
class NestedScenarioPlayTest {

  /** Captured by the OUTER scenario's body when it plays the INNER — asserted after the play. */
  static volatile ScenarioOutcome innerOutcome;

  @Test
  void a_scenario_played_from_inside_another_still_harvests_its_own_outcome() throws Exception {
    innerOutcome = null;
    final ScenarioOutcome outer = new ScenarioPlayer().play(Outer.class, store -> {});

    assertNotNull(outer, "the outer play harvested its own outcome");
    assertEquals(
        1, outer.runbook().getScenarios().size(), "the outer runbook carries its scenario");

    assertNotNull(innerOutcome, "the inner play (sown from the outer body) harvested an outcome");
    final ReportModel inner = innerOutcome.runbook();
    assertEquals(
        1,
        inner.getScenarios().size(),
        "the INNER runbook still carries its scenario after being played nested (live object)");

    // The PROD path: the GenericRunbookHandler serialises the nested outcome and ScenarioGraft
    // rebuilds it host-side. Nesting alone passes and round-trip alone passes (the tag round-trip
    // test) — the
    // live
    // "no scenario to graft" is their COMBINATION, so assert it here: serialise the NESTED inner
    // outcome and rebuild, exactly as the sow-and-graft does.
    final String innerJson =
        new com.tngtech.jgiven.report.json.ScenarioJsonWriter(inner).toString();
    final java.io.File tmp = java.io.File.createTempFile("nested-inner-runbook", ".json");
    tmp.deleteOnExit();
    java.nio.file.Files.writeString(tmp.toPath(), innerJson);
    final ReportModel rebuilt = new com.tngtech.jgiven.report.json.ScenarioJsonReader().apply(tmp);
    assertEquals(
        1,
        rebuilt.getScenarios().size(),
        "the nested inner runbook, serialised-then-rebuilt, still carries its scenario — the live"
            + " 'no scenario to graft' reproduced here if this is 0");
  }

  /** The OUTER scenario: its body sows the INNER through ScenarioPlayer, as the host WHEN does. */
  @ExtendWith(JGivenExtension.class)
  @ExtendWith(ScenarioOutcomeExtension.class)
  public static class Outer extends ScenarioTestBase<Outer.S, Outer.S, Outer.S>
      implements ScenarioPlayer.Playable {
    private final Scenario<S, S, S> scenario = createScenario();

    @Override
    public Scenario<S, S, S> getScenario() {
      return scenario;
    }

    @Test
    void the_outer_sows_the_inner() throws Exception {
      given().the_outer_step();
      innerOutcome = new ScenarioPlayer().play(Inner.class, store -> {});
      then().the_outer_step();
    }

    public static class S extends Stage<S> {
      public S the_outer_step() {
        return self();
      }
    }
  }

  /** The INNER scenario: a plain green scenario, played nested from the outer body. */
  @ExtendWith(JGivenExtension.class)
  @ExtendWith(ScenarioOutcomeExtension.class)
  public static class Inner extends ScenarioTestBase<Inner.S, Inner.S, Inner.S>
      implements ScenarioPlayer.Playable {
    private final Scenario<S, S, S> scenario = createScenario();

    @Override
    public Scenario<S, S, S> getScenario() {
      return scenario;
    }

    @Test
    void the_inner_plays_green() {
      given().the_inner_step();
      then().the_inner_step();
    }

    public static class S extends Stage<S> {
      public S the_inner_step() {
        return self();
      }
    }
  }
}
