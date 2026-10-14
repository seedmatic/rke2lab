package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.report.json.ScenarioJsonReader;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.ScenarioCaseModel;
import com.tngtech.jgiven.report.model.ScenarioModel;
import com.tngtech.jgiven.report.model.StepModel;
import com.tngtech.jgiven.report.model.StepStatus;
import com.tngtech.jgiven.report.model.Tag;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.Crossing;
import io.seedmatic.rke2lab.seed.broker.port.Trail;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The cross-world graft: the host-side mechanism that folds a scenario played in ANOTHER world (an
 * OSGi scion, played in-container) into the host's own runbook. A {@code @NestedSteps} call
 * composes within ONE jGiven engine; a dependency that crosses the world boundary cannot be a
 * nested step — two engines, two {@link ReportModel}s, and a live jGiven object would {@code
 * ClassCastException} on the flat host loader. So the scion world serializes its scenario to JSON
 * (a {@code ScenarioJsonWriter} String — the only thing that crosses the seam), the host {@link
 * #rebuild}s a model of ITS OWN realm from that String, and {@link #graftUnder grafts} the scion
 * steps as the nested children of a host rootstock step.
 *
 * <p>Generic by construction — it speaks only jGiven's report model, never a domain vocabulary — so
 * EVERY domain whose scenario blooms in-container reuses it: cluster, systemd, and the rest. The
 * observability twin of the seed-broker (which carries the crossing's EXECUTION); this carries the
 * crossing's NARRATION. In the engine's EXPORTED {@code .container} package because the graft runs
 * wherever a runbook is owned: host-side ({@code Main} folds the scions into the root runbook on
 * the flat classpath) AND in-container (a scion that itself consults a sub-scion — the incus
 * provisioning scion grafts the manifests scion under its own step — folds it in-world). Stateless,
 * and only flat JSON crosses the realm, so no live jGiven object is ever shared between loaders.
 *
 * <p>The vocabulary is horticultural, one register end to end: the host sows a seed toward the
 * other world (the seed-broker's {@code sow}), it grows there into a scion (played in-container),
 * and the scion is GRAFTED onto the host tree — onto the host's <em>rootstock step</em>, the step
 * executing that crossing, which receives the graft.
 *
 * <p>The verdict travels IN the model: if the scion scenario is {@link ExecutionStatus#FAILED}, the
 * rootstock step is marked FAILED and the scion's failure text (the case errorMessage + stackTrace
 * — jGiven holds it at case level, never on a step) is carried onto the host case so the reason
 * survives the crossing. The fail-fast is the grafter's: it {@link #assertPassed asserts} the scion
 * it grafted, and jGiven skips the host steps after a throwing step as it does after any failed
 * one. One serialized scenario suffices: it carries the narration (its steps → the sub-tree), the
 * verdict (its execution status → the propagation), and the reason (its case failure → the host
 * case).
 */
public final class ScenarioGraft {

  private static final SeedCodec CODEC = new SeedCodec();

  /**
   * Rebuild a host-realm {@link ReportModel} from the serialized runbook JSON a scion world's
   * front-door produced ({@code ScenarioJsonWriter(model).toString()}). {@link ScenarioJsonReader}
   * reads a file, so the String is spilled to a throwaway temp file and read back — no jGiven type
   * crossed live, only the flat JSON. The rebuilt model belongs to THIS classloader, so its steps
   * are graftable into a host runbook.
   */
  public ReportModel rebuild(String runbookJson) {
    try {
      final File tmp = Files.createTempFile("grafted-runbook", ".json").toFile();
      tmp.deleteOnExit();
      Files.writeString(tmp.toPath(), runbookJson);
      return new ScenarioJsonReader().apply(tmp);
    } catch (IOException cause) {
      throw new UncheckedIOException("cannot rebuild the scion runbook model", cause);
    }
  }

  /**
   * Graft {@code scion}'s scenario under the host step that is EXECUTING — the crossing step the
   * caller runs in: the scion scenario's steps become that step's nested children (one continuous
   * tree — the operator descends from the seed to the scion root cause), and the scion verdict
   * propagates. When the scion scenario is FAILED, the rootstock step is marked FAILED. Returns the
   * rootstock's name, the label the caller's {@link #assertPassed} names the crossing with.
   *
   * <p>The rootstock is FOUND, never named: jGiven records a step when it is invoked and does not
   * record the stage calls nested in it, so mid-step the executing step is the live case's last
   * top-level step. A name handed in beside it could disagree with the step it stands for, and
   * would only fail once a run reached it.
   *
   * <p>The host side is given as TWO live handles, not one: the crossing happens MID-run (inside a
   * host WHEN step), and jGiven does not append the current {@link ScenarioModel} to its {@link
   * ReportModel#getScenarios()} until the scenario FINISHES — so mid-run that list is still empty.
   * The steps therefore graft into {@code hostScenario} (the live current model, reached by the
   * caller via {@code getScenario().getScenarioModel()}), while the scion's tag map merges into
   * {@code hostTree} (the {@link ReportModel}, whose tag map IS live and which the host reads back
   * through {@link #graftedValue}). Fishing the host scenario out of {@code
   * hostTree.getScenarios()} was the "no scenario to graft" defect — it read empty every live run.
   *
   * @throws IllegalArgumentException if the host case has no step yet — a graft from outside any
   *     host step — or the scion model carries no scenario: a wiring bug, surfaced loudly rather
   *     than silently grafting nothing.
   */
  public String graftUnder(
      ScenarioModel hostScenario, ReportModel hostTree, String soil, ReportModel scion) {
    final ScenarioModel scionScenario = firstScenarioOf(scion);
    final StepModel rootstock = executingStepOf(hostScenario);
    final String rootstockStepName = rootstock.getName();

    scionScenario.getScenarioCases().get(0).getSteps().forEach(rootstock::addNestedStep);

    // The scion's tag map rides up with the graft — the ephemeral cellar (§ seed-broker-spec, two
    // cellars). addNestedStep carries only the STEPS, so a within-run fact the scion posed as a tag
    // (e.g. GraftTag.LIVE_ROOT) would be lost; merge it so the host reads it back via graftedValue.
    // GRAFT_FAILURE tags are the EXCEPTION to the blanket merge: they are re-posed below with THIS
    // crossing prepended to their path, so merging them raw here too would double them.
    scion.getTagMap().values().stream()
        .filter(tag -> !GraftTag.GRAFT_FAILURE.type().equals(tag.getType()))
        .forEach(hostTree::addTag);

    if (scionScenario.getExecutionStatus() == ExecutionStatus.FAILED) {
      rootstock.setStatus(StepStatus.FAILED);
      // jGiven stores the failure text (errorMessage + stackTrace) only on the CASE, never on a
      // step — so the FAILED status alone would reach the host runbook with no reason. Carry the
      // scion case's failure onto the host case so the operator descends from the seed to the
      // scion's actual error. ACCUMULATE, not first-wins: sibling crossings fail INDEPENDENTLY (the
      // graft does not abort the host — systemd AND cluster can both fail in one run), and jGiven's
      // case holds a single error slot, so each failed crossing's cause is APPENDED under a header
      // naming the crossing. First-wins would keep only systemd and silently drop cluster, leaving
      // its ❌ icon with no reason — the very "two conditions failed, one stacktrace" defect.
      final ScenarioCaseModel scionCase = scionScenario.getScenarioCases().get(0);
      final ScenarioCaseModel hostCase = hostScenario.getScenarioCases().get(0);
      if (scionCase.getErrorMessage() != null) {
        // The HUMAN errorMessage the closing gate rethrows: one header per crossing + the scion's
        // own message (ScenarioPlayer already set it to the live exception's toString — clean, no
        // frames, so no parse). ACCUMULATE, not first-wins: sibling crossings fail INDEPENDENTLY
        // (systemd AND cluster in one run), so each cause is APPENDED under a header naming it. The
        // case STACKTRACE is left to ScenarioOutcomeExtension's foldGraftedReasons (from the
        // structured tags below), so it is not folded here.
        final String header = "═══ " + rootstockStepName + " ═══";
        final boolean firstFailure = hostCase.getErrorMessage() == null;
        hostCase.setErrorMessage(
            (firstFailure ? "" : hostCase.getErrorMessage() + "\n\n")
                + header
                + "\n"
                + scionCase.getErrorMessage());
        // The STRUCTURED channel: gather the scion's OWN grafted failures (a nested chain it folded
        // in-container), or its structured self-report (the SCENARIO_FAILURE tag ScenarioPlayer
        // captured at the source from the live exception) if it grafted none, then PREPEND this
        // crossing to each path — so as the failure RETURNS up the chain, the tag accrues the full
        // crossing Trail from the root down to the leaf where it grew (the cellar's path-to-origin,
        // at the scenario level). Each tag is a hidden JSON carrier the host reloads into a
        // GraftThrowable — the frames rebuild from the POJO, never a printStackTrace re-parse.
        final Crossing here = new Crossing(soil, rootstockStepName);
        final List<GraftFailure> inner = graftedFailurePayloads(scion);
        final List<GraftFailure> leaves =
            inner.isEmpty()
                ? scenarioFailureReason(scion)
                    .map(reason -> List.of(new GraftFailure(Trail.empty(), reason)))
                    .orElseGet(List::of)
                : inner;
        for (final GraftFailure leaf : leaves) {
          final Tag failureTag =
              GraftTag.GRAFT_FAILURE.of(
                  CODEC.encode(new GraftFailure(leaf.path().prepend(here), leaf.reason())));
          failureTag.setShowInNavigation(false);
          hostTree.addTag(failureTag);
        }
      }
    }
    return rootstockStepName;
  }

  /**
   * Assert a reaped scion runbook PASSED, else throw its failure reason. For a NON-grafting reaper
   * — a CLI root that sows a scion and has no host tree to graft the verdict into (unlike {@link
   * #graftUnder}, which propagates a FAILED scion onto the host runbook and carries its error text
   * up). The scion's failure crossed the realm as JSON, so jGiven holds only the case {@code
   * errorMessage} + {@code stackTrace} STRINGS (no live Throwable to chain) — both are folded into
   * the thrown {@link AssertionError} so the operator sees the reason AND the scion's frames, not a
   * one-line summary.
   *
   * @param label how the operator names the sow (e.g. {@code "the manifests synthesis"})
   */
  public void assertPassed(String runbookJson, String label) {
    assertPassed(rebuild(runbookJson), label);
  }

  /**
   * The model overload — assert an already-rebuilt scion {@code model} PASSED, else throw its
   * failure reason (the case errorMessage + stackTrace, folded into one {@link AssertionError}).
   * The grafting sower uses this on the scion it just grafted to PROPAGATE a FAILED verdict as a
   * throw — the honest fail-fast default of {@code the_scion_is_sown_and_grafted}.
   */
  public void assertPassed(ReportModel model, String label) {
    if (model.getScenarios().isEmpty()) {
      throw new AssertionError(label + " reaped a runbook with no scenario");
    }
    final ScenarioModel scenario = model.getScenarios().get(0);
    if (scenario.getExecutionStatus() != ExecutionStatus.FAILED) {
      return;
    }
    final ScenarioCaseModel failed = scenario.getScenarioCases().get(0);
    // ScenarioPlayer set the case errorMessage to the live exception's toString (clean, no inlined
    // frames), so no parse is needed to name the reason. Carry the scion's STRUCTURED failure (its
    // full cause chain, rebuilt from the SCENARIO_FAILURE tag) as a SUPPRESSED exception, so the
    // fail-fast verdict is the SAME shape as the closing gate's — one exception with the scion's
    // reason hanging off it.
    final AssertionError verdict =
        new AssertionError(label + " failed in-container: " + failed.getErrorMessage());
    scenarioFailureReason(model).map(ThrownModel::toThrowable).ifPresent(verdict::addSuppressed);
    throw verdict;
  }

  /**
   * The sower's CLOSING GATE — fail the host run if any TOLERATED crossing ended FAILED, and build
   * the run's VERDICT here (not in the driver — the verdict is not exe-specific). A crossing
   * grafted with the tolerating sow ({@code the_scion_is_sown_and_grafted_tolerating_failure})
   * folds its FAILED verdict + error text onto the host case WITHOUT throwing, so its siblings run
   * and aggregate. Called from the root's closing THEN, this throws an {@link AssertionError} whose
   * message is a HEADLINE (which crossings failed, by name — NOT their reasons, so nothing repeats)
   * and which carries the per-crossing {@link GraftThrowable}s as SUPPRESSED exceptions (from the
   * {@code hostTree}'s GRAFT_FAILURE tags) — so the throw is ONE exception, ROOT-anchored (its
   * stack is the root scenario's THEN), and each scion's full reason + cause chain hangs off it
   * ONCE, in its suppressed. The driver only re-surfaces this captured throwable ({@code pulumi up}
   * exits non-zero); it does not construct the verdict. A no-op when every crossing passed (the
   * host case carries no error).
   */
  public void assertNoCrossingFailed(ScenarioModel hostScenario, ReportModel hostTree) {
    final ScenarioCaseModel hostCase = hostScenario.getScenarioCases().get(0);
    if (hostCase.getErrorMessage() == null) {
      return;
    }
    final List<GraftThrowable> failures = graftedFailures(hostTree);
    final AssertionError verdict =
        failures.isEmpty()
            ? new AssertionError(hostCase.getErrorMessage())
            : new AssertionError(
                failures.size() + " crossing(s) did not complete: " + crossingNames(failures));
    failures.forEach(verdict::addSuppressed);
    throw verdict;
  }

  /** The crossing paths of the failed crossings, by name — the verdict headline (no reasons). */
  private static String crossingNames(List<GraftThrowable> failures) {
    final List<String> names = new ArrayList<>();
    for (final GraftThrowable failure : failures) {
      final List<String> path = new ArrayList<>();
      failure.path().breadcrumbs().forEach(crumb -> path.add(crumb.coordinate()));
      names.add(String.join(" / ", path));
    }
    return String.join("; ", names);
  }

  /**
   * Read a within-run fact a scion posed on its model and the graft merged into {@code hostTree} —
   * the ephemeral cellar's read side. Returns the single tag value of {@code kind}, or empty if the
   * scion posed none (a scion that did not run, or a probe with nothing to bring to the terrain).
   * The host asks the graft mechanism, never hand-filters a tag map — the tag structure stays the
   * mechanism's own.
   */
  public Optional<String> graftedValue(ReportModel hostTree, GraftTag kind) {
    return hostTree.getTagMap().values().stream()
        .filter(tag -> kind.type().equals(tag.getType()))
        .map(Tag::getValues)
        .filter(values -> values != null && !values.isEmpty())
        .map(values -> String.valueOf(values.get(0)))
        .findFirst();
  }

  /**
   * Reload the grafted failures the crossings posed at reception (the {@link
   * GraftTag#GRAFT_FAILURE} tags, one per failed scion) into host-side {@link GraftThrowable}s —
   * the "the reason crossed as JSON, rebuild the POJO host-side" step. Each carries the crossing
   * (context), and — from the structured {@link ThrownModel} — the scion's message, its REAL
   * frames, and its whole cause chain (no printStackTrace re-parse). The driver ({@code Main})
   * attaches them as suppressed on the run's verdict, so the operator reads one ordinary {@code
   * Suppressed:} per failed crossing.
   */
  private List<GraftThrowable> graftedFailures(ReportModel hostTree) {
    return graftedFailurePayloads(hostTree).stream().map(ScenarioGraft::toThrowable).toList();
  }

  /**
   * Restore the crossing REASONS onto a runbook's root case for the RENDER — the report-model twin
   * of {@link #graftedFailures}. {@code graftUnder} folds each scion's frames onto the host case,
   * but the scenario then THROWS its closing gate ({@code assertNoCrossingFailed}) or fail-fast,
   * and jGiven OVERWRITES the case {@code stackTrace} with that throw's own stack — so the rendered
   * runbook would show the gate boilerplate, not the crossings that failed. Called post-run from
   * {@link ScenarioOutcomeExtension} (the universal {@code @SeedScenario} chokepoint, firing after
   * jGiven has finished, so nothing overwrites it again), this rewrites the case {@code stackTrace}
   * from the {@link GraftTag#GRAFT_FAILURE} tags: each rebuilt {@link GraftThrowable} (its path
   * header + real frames + {@code Caused by:} chain) rendered by {@code printStackTrace} — the same
   * reasons the operator reads as {@code Suppressed:} on the console verdict, and the SAME
   * structured source, so the runbook and the console never diverge. Uniform across EVERY root
   * scenario (the cluster seed, the CLI scenarios), not just the host driver. A no-op when there
   * are no grafted failures (a non-crossing failure keeps jGiven's real throw site — a leaf scion
   * that grafted nothing) or the runbook carries no scenario.
   */
  public void foldGraftedReasons(ReportModel runbook) {
    final List<GraftThrowable> failures = graftedFailures(runbook);
    if (failures.isEmpty() || runbook.getScenarios().isEmpty()) {
      return;
    }
    final ScenarioCaseModel hostCase = runbook.getScenarios().get(0).getScenarioCases().get(0);
    final List<String> stack = new ArrayList<>();
    for (final GraftThrowable failure : failures) {
      if (!stack.isEmpty()) {
        stack.add("");
      }
      final StringWriter rendered = new StringWriter();
      failure.printStackTrace(new PrintWriter(rendered));
      stack.addAll(List.of(rendered.toString().split("\\R")));
    }
    hostCase.setStackTrace(stack);
  }

  /** Decode the {@link GraftTag#GRAFT_FAILURE} tags on a model into their {@link GraftFailure}s. */
  private static List<GraftFailure> graftedFailurePayloads(ReportModel model) {
    return model.getTagMap().values().stream()
        .filter(tag -> GraftTag.GRAFT_FAILURE.type().equals(tag.getType()))
        .map(Tag::getValues)
        .filter(values -> values != null && !values.isEmpty())
        .flatMap(List::stream)
        .map(String::valueOf)
        .map(json -> CODEC.decode(json, GraftFailure.class))
        .toList();
  }

  private static GraftThrowable toThrowable(GraftFailure failure) {
    return new GraftThrowable(failure.path(), failure.reason());
  }

  /**
   * The scion's OWN structured self-report — the {@link ThrownModel} it posed as a {@link
   * GraftTag#SCENARIO_FAILURE} tag at the source ({@code ScenarioPlayer}, from the live exception).
   * A failed leaf scion (one that grafted no sub-scenario) has exactly this; {@code graftUnder}
   * wraps it in a {@link GraftFailure} with the crossing path.
   */
  private Optional<ThrownModel> scenarioFailureReason(ReportModel scion) {
    return scion.getTagMap().values().stream()
        .filter(tag -> GraftTag.SCENARIO_FAILURE.type().equals(tag.getType()))
        .map(Tag::getValues)
        .filter(values -> values != null && !values.isEmpty())
        .flatMap(List::stream)
        .map(String::valueOf)
        .map(json -> CODEC.decode(json, ThrownModel.class))
        .findFirst();
  }

  private ScenarioModel firstScenarioOf(ReportModel model) {
    if (model.getScenarios().isEmpty()) {
      throw new IllegalArgumentException("the scion model carries no scenario to graft");
    }
    return model.getScenarios().get(0);
  }

  private StepModel executingStepOf(ScenarioModel hostScenario) {
    final List<StepModel> steps = hostScenario.getScenarioCases().get(0).getSteps();
    if (steps.isEmpty()) {
      throw new IllegalArgumentException(
          "no host step is executing to graft under — a crossing grafts from inside its host step");
    }
    return steps.get(steps.size() - 1);
  }
}
