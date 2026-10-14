package io.seedmatic.rke2lab.seed.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ScenarioState;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.ScenarioModel;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioGraft;
import io.seedmatic.rke2lab.seed.bdd.sow.Gardening;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import java.util.Map;

/**
 * The gardening gesture behind every crossing step: sow a soil's runbook through the open
 * gardening, and graft the reaped scion under this step. It COMPOSES the two halves of the
 * crossing, which live in different realms by design:
 *
 * <ul>
 *   <li>SOW — {@link Gardening#sow} (package {@code seed.bdd.sow}, EXPORTED): realm-agnostic, it
 *       grows {@code soil}'s scenario through the gardener and reaps the runbook JSON. The driver
 *       holds the open gardening today; a scion-peer could hold one in-container tomorrow (remote).
 *   <li>GRAFT — {@link ScenarioGraft} (host-flat): builds a host-realm {@link ReportModel} from
 *       that JSON and grafts the scion's steps under the root step executing this crossing,
 *       propagating the scion verdict (fail-fast across the frontier). The graft is LOCAL to
 *       whoever owns the runbook tree, so it stays in this non-exported base package.
 * </ul>
 *
 * <p>The root scenario passes TWO of its own live handles: its current {@link ScenarioModel}
 * ({@code getScenario().getScenarioModel()}) — the trunk the scion steps graft into, the ONLY one
 * that carries the rootstock step mid-run (jGiven appends the scenario to its {@link ReportModel}
 * only once it FINISHES, so {@code getModel().getScenarios()} is empty here) — and its {@link
 * ReportModel} ({@code getScenario().getModel()}), whose live tag map receives the scion's
 * within-run tags. The collaborators (the open {@link Gardening}, the two host handles, the soil)
 * are handed in through a {@link Hidden} step, the way a domain scenario receives its contact — no
 * injection machinery. See docs/architecture/osgi/seed-broker-spec.adoc (§ playing a scion scenario
 * is a sow).
 */
public class SowAndGraftStage extends Stage<SowAndGraftStage> {

  private final ScenarioGraft graft = new ScenarioGraft();

  @ScenarioState private String soil;
  @ScenarioState private Gardening gardening;
  @ScenarioState private ScenarioModel hostScenario;
  @ScenarioState private ReportModel hostTree;
  @ScenarioState private Map<String, JsonNode> amendments;

  // The run's working cellar — the root's own ScenarioCellar (the seam type Cellar here), published
  // by the root GIVEN and carried on every sow, so a launched scion inherits its txId + in-flight
  // entries (§ cellar-transactional). The RunbookHandler casts to ScenarioCellar at the launcher
  // boundary. Resolved by TYPE: the durable backend is a distinct type (PulumiCellar), no clash.
  @ScenarioState private Cellar cellar;

  /**
   * Hand in the crossing's collaborators: the {@code soil} name to sow toward, the open {@link
   * Gardening}, and the root scenario's two live handles — its current {@link ScenarioModel} (the
   * trunk the scion steps graft into) and its {@link ReportModel} (the tag map the scion's
   * within-run tags merge into). Hidden — it carries wiring, not narration. The crossing sows with
   * no amendment (the scion falls back to its own defaults); a crossing that must fill an amendment
   * role uses {@link #sowing(String, Gardening, ScenarioModel, ReportModel, Map)}.
   */
  @Hidden
  public SowAndGraftStage sowing(
      String soil, Gardening gardening, ScenarioModel hostScenario, ReportModel hostTree) {
    return sowing(soil, gardening, hostScenario, hostTree, Map.<String, JsonNode>of());
  }

  /**
   * The amending variant: {@code amendments} is a {@code {role → value}} map the host holds under
   * neutral {@link io.seedmatic.rke2lab.seed.broker.port.Amendment} roles (e.g. the incus crossing
   * fills {@code worktree} with the flat provisioning scalars the scion rebuilds its topology
   * from), each value a {@link JsonNode} — a flat scalar or a sub-record. Empty behaves exactly
   * like {@link #sowing(String, Gardening, ScenarioModel, ReportModel)}.
   */
  @Hidden
  public SowAndGraftStage sowing(
      String soil,
      Gardening gardening,
      ScenarioModel hostScenario,
      ReportModel hostTree,
      Map<String, JsonNode> amendments) {
    this.soil = soil;
    this.gardening = gardening;
    this.hostScenario = hostScenario;
    this.hostTree = hostTree;
    this.amendments = amendments;
    return self();
  }

  /**
   * Sow the soil's runbook through the gardening, graft the reaped scion under the host step
   * executing this crossing, then PROPAGATE its verdict: a FAILED scion THROWS, failing the sower
   * (fail-fast across the frontier — the honest default, so a failed crossing never reads as a
   * green run and {@code pulumi up} exits non-zero). A sower that wants the crossings AFTER this
   * one to still run and aggregate their failures in one runbook (systemd AND cluster) keeps the
   * hand with {@link #the_scion_is_sown_and_grafted_tolerating_failure}, and enforces the overall
   * verdict from its own closing gate ({@link ScenarioGraft#assertNoCrossingFailed}).
   */
  public SowAndGraftStage the_scion_is_sown_and_grafted() {
    final ReportModel scion = sown();
    graft.assertPassed(scion, graft.graftUnder(hostScenario, hostTree, soil, scion));
    return self();
  }

  /**
   * The tolerating variant — the sower KEEPS THE HAND. Graft the scion's verdict into the runbook
   * (its steps, its FAILED status, its error text carried onto the host case) but do NOT throw, so
   * the crossings that follow still run and their failures ACCUMULATE in the one runbook. For the
   * terminal, independent crossings a sower wants to report together; the sower fails the run
   * overall from its closing gate, not here. NOT the default — a crossing whose failure makes the
   * rest meaningless uses {@link #the_scion_is_sown_and_grafted}.
   */
  public SowAndGraftStage the_scion_is_sown_and_grafted_tolerating_failure() {
    graft.graftUnder(hostScenario, hostTree, soil, sown());
    return self();
  }

  /** Sow the soil through the gardening and rebuild the reaped scion in this realm. */
  private ReportModel sown() {
    return graft.rebuild(gardening.sow(soil, amendments, cellar));
  }
}
