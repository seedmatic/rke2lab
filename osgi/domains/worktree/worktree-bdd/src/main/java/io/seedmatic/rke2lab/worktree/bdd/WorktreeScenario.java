package io.seedmatic.rke2lab.worktree.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.InputReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioInputSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.CellarCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.SourceCrumb;
import io.seedmatic.rke2lab.seed.broker.port.Trail;
import io.seedmatic.rke2lab.worktree.DurableWorktree;
import io.seedmatic.rke2lab.worktree.GatePolicy;
import io.seedmatic.rke2lab.worktree.WorkingState;
import io.seedmatic.rke2lab.worktree.WorktreeCoordinate;
import io.seedmatic.rke2lab.worktree.WorktreeFacts;
import io.seedmatic.rke2lab.worktree.WorktreeRunbookInput;
import java.util.List;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The worktree soil — a production jGiven scenario told in the WORKTREE DOMAIN's own vocabulary,
 * played IN-CONTAINER by the seeding engine like every other crossing. Its whole job is to HARVEST
 * the worktree's git facts into the cellar: it reads the {@link DurableWorktree} {@code @Component}
 * (which self-locates its root and knows its HEAD provenance + working state) and stores a {@link
 * WorktreeFacts} at the {@link WorktreeCoordinate#FACTS} coordinate. The flat host then FETCHES
 * that harvest from the cellar — the root for the GROW mounts, the working state for the entry gate
 * — the same fetch-not-push discipline the incus {@code InstanceGrowPlan} rides (a domain-owned
 * coordinate keyed by its wire slug, so it crosses the realm through the cellar without the broker
 * ever routing a domain enum).
 *
 * <p>Its collaborator, the {@link DurableWorktree} service, is INJECTED from the framework registry
 * by the {@link OsgiService} bridge — {@code worktree-core}'s {@code JgitWorktree} live, or a mock
 * a test seeds. NOT survey-inert: reading git facts is a pure, deterministic read (no live system
 * contacted), so the harvest runs in BOTH modes — the host needs the root to project the GROW
 * mounts at preview too, exactly as the incus grow plan is projected in both.
 */
@SeedScenario
public class WorktreeScenario
    extends ScenarioTestBase<WorktreeScenario.Given, WorktreeScenario.When, WorktreeScenario.Then>
    implements CellarReceiver<Cellar>,
        InputReceiver<WorktreeRunbookInput>,
        ScenarioPlayer.Playable {

  /**
   * The inbound channel the {@code WorktreeRunbookHandler.seedFrom} seeds the {@link
   * WorktreeRunbookInput} through and this scenario receives it from (via {@link InputReceiver}) —
   * the entry-gate {@link GatePolicy} the host amended at the {@code worktree} door. Registered as
   * a {@link RegisterExtension} so its {@code TestInstancePostProcessor} fires before the body
   * reads {@link #input}, the way the incus scion's channel does.
   */
  @RegisterExtension
  public static final ScenarioInputSeed<WorktreeRunbookInput> INPUT =
      new ScenarioInputSeed<>(WorktreeRunbookInput.class, "worktree-runbook-input");

  private final Scenario<Given, When, Then> scenario = createScenario();

  // The transactional cellar the extension injects before the body — the harvest is stored into it,
  // and the host reads it back within the run (read-your-writes). Null until receiveCellar sets it.
  private Cellar cellar;

  // The activation input the front-door seeds before the body (InputReceiver) — it carries the
  // entry-gate FACET. Null until receiveInput sets it; an unamended crossing falls to defaults().
  @MonotonicNonNull private WorktreeRunbookInput input;

  // The current parcel the host publishes at the GIVEN; the soil files its harvest under it.
  @OsgiService private Optional<Parcel> parcel = Optional.empty();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveCellar(Cellar cellar) {
    this.cellar = cellar;
  }

  @Override
  public void receiveInput(WorktreeRunbookInput input) {
    this.input = input;
  }

  @Test
  void the_worktree_facts_are_harvested() {
    final WorktreeRunbookInput activation = input != null ? input : WorktreeRunbookInput.defaults();
    given().the_worktree("seed");
    when().the_git_facts_are_read();
    then()
        .the_facts_are_harvested(cellar, parcel.orElseThrow())
        .and()
        .the_entry_gate_is_enforced(activation.gate().orElseGet(GatePolicy::defaults));
  }

  /** Given: the worktree the seed cultivates — a name, for the readable line. */
  public static class Given extends Stage<Given> {

    public Given the_worktree(@Quoted String name) {
      return self();
    }
  }

  /**
   * When: read the worktree's git facts off the {@link DurableWorktree} service and fabricate the
   * {@link WorktreeFacts} snapshot (WHEN fabricates). The service is injected straight from the
   * bundle registry by the {@link OsgiService} bridge — {@code JgitWorktree} live, jgit sealed
   * behind it.
   */
  public static class When extends Stage<When> {

    @OsgiService private Optional<DurableWorktree> worktree = Optional.empty();

    @ProvidedScenarioState WorktreeFacts facts;

    public When the_git_facts_are_read() {
      final DurableWorktree resolved = worktree.orElseThrow();
      this.facts =
          new WorktreeFacts(
              resolved.root().toString(),
              resolved.provenance(),
              resolved.workingState(),
              resolved.flakeLockCoherent());
      return self();
    }
  }

  /**
   * Then: plant the run's fil d'Ariane root, then file the harvest at {@link
   * WorktreeCoordinate#FACTS} under the current parcel (THEN seals). As the FIRST crossing, the
   * worktree soil projects its git HEAD provenance into a foundation {@link SourceCrumb} filed at
   * {@link CellarCoordinate#RUN_PROVENANCE} — the root every later value's {@link
   * io.seedmatic.rke2lab.seed.broker.port.Trail} descends from (it reaches sibling crossings by the
   * ordinary transactional inheritance). The store is unconditional on the gate — the cellar routes
   * conserve vs pre-reserve itself — so a preview run still records the facts the host reads back
   * for the grow projection.
   */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState WorktreeFacts facts;

    public Then the_facts_are_harvested(@Hidden Cellar cellar, @Hidden Parcel parcel) {
      // Plant the run's provenance PATH with its ROOT crumb — the git HEAD source. It is a
      // one-crumb
      // Trail, not a bare Breadcrumb: each crossing sown below appends its own crumb as the path
      // descends (§ fil-d-ariane, the crossing path), so every later value's Trail is the full
      // route
      // root → … → here.
      cellar.store(
          parcel,
          CellarCoordinate.RUN_PROVENANCE,
          new Trail(
              List.of(
                  new SourceCrumb(
                      WorktreeCoordinate.FACTS.domain(),
                      WorktreeCoordinate.FACTS.slug(),
                      facts.provenance().sha(),
                      facts.provenance().dirty()))));
      cellar.store(parcel, WorktreeCoordinate.FACTS, facts);
      return self();
    }

    /**
     * Enforce the run's entry {@link GatePolicy} against the harvested {@link WorkingState} — the
     * gate the host used to run flat-side, now OSGi-side against the freshly-read fact. It throws
     * when the ground is unclean beyond tolerance, failing this first crossing before any effectful
     * sow. An unamended crossing gets {@link GatePolicy#defaults()} (no requirement) — a pure
     * survey harvests without gating.
     */
    public Then the_entry_gate_is_enforced(@Hidden GatePolicy gate) {
      gate.enforce(facts);
      return self();
    }
  }
}
