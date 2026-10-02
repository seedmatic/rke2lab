package io.seedmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.ScenarioModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins I5: the driver renders the played model into the staging slot as BOTH renderings — {@code
 * runbook/json/runbook.json} (the runtime serialisation) and {@code runbook/adoc/index.asciidoc}
 * (the operator narration). A minimal one-scenario model suffices; the point is that both artefacts
 * land under the slot and the render never throws.
 */
class RunbookRendererTest {

  private static ReportModel oneScenarioModel() {
    final ReportModel model = new ReportModel();
    final ScenarioModel scenario = new ScenarioModel();
    scenario.setDescription("the cluster seed grows to a ready cluster");
    model.setScenarios(new ArrayList<>(List.of(scenario)));
    return model;
  }

  @Test
  void renders_both_json_and_adoc_into_the_slot(@TempDir Path slot) {
    final List<String> logged = new ArrayList<>();
    new RunbookRenderer(slot, logged::add).render(oneScenarioModel());

    assertTrue(
        Files.exists(slot.resolve("runbook/json/runbook.json")),
        "the runtime json rendering lands in the slot");
    assertTrue(
        Files.exists(slot.resolve("runbook/adoc/" + RunbookRenderer.INDEX_FILE)),
        "the operator adoc rendering lands in the slot");
    assertTrue(
        logged.stream().anyMatch(line -> line.contains("runbook rendered")),
        "a successful render logs where it landed");
  }

  @Test
  void a_render_failure_never_throws(@TempDir Path parent) {
    // Point the renderer at a path whose parent is a FILE, so directory creation fails — the render
    // must swallow it (best-effort) and log a skip, never throw into the provisioning.
    final List<String> logged = new ArrayList<>();
    final Path fileNotDir = parent.resolve("occupied");
    try {
      Files.writeString(fileNotDir, "x");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }

    new RunbookRenderer(fileNotDir, logged::add).render(oneScenarioModel());

    assertTrue(
        logged.stream().anyMatch(line -> line.contains("runbook render skipped")),
        "a render that cannot write logs a skip and does not throw");
  }
}
