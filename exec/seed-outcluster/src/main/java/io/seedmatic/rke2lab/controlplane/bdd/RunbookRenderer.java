package io.seedmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.report.asciidoc.AsciiDocReportConfig;
import com.tngtech.jgiven.report.asciidoc.AsciiDocReportGenerator;
import com.tngtech.jgiven.report.json.ScenarioJsonWriter;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.ScenarioModel;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Renders the played {@link ReportModel} into the run's staging slot — the runbook, in the two
 * renderings the model already affords (§ host-cellar-realisation, dogfooding — ONE model, two
 * renderings): {@code runbook.json} ({@link ScenarioJsonWriter} — the raw serialisation the node
 * reads at runtime) and an {@code adoc/} directory ({@link AsciiDocReportGenerator} — the operator
 * narration). Both travel inside {@code host.N.staging.d}, so the runbook rides the slot's
 * lifecycle.
 *
 * <p>Best-effort: rendering NEVER throws back into the pipeline — a runbook that fails to render
 * must not fail the provisioning it documents (that failure is exactly what the runbook would
 * narrate). It only renders; the doctor-diagnosis injection the pre-vision renderer carried is gone
 * (the consultations are grafted nodes now, not a side-channel to weave in).
 */
public final class RunbookRenderer {

  /** Entry point of the rendered AsciiDoc report directory. */
  public static final String INDEX_FILE = "index.asciidoc";

  /** The feature the runbook's scenarios group under (AsciiDoc aggregates by class name). */
  private static final String RUNBOOK_FEATURE = "Runbook";

  private final Path stagingRoot;
  private final Consumer<String> logger;

  public RunbookRenderer(Path stagingRoot, Consumer<String> logger) {
    this.stagingRoot = stagingRoot;
    this.logger = logger;
  }

  /**
   * Render {@code model} into {@code stagingRoot/runbook/{json,adoc}}. Best-effort — logs and
   * swallows any failure so provisioning is never broken by its own narration.
   */
  public void render(ReportModel model) {
    try {
      final Path runbookRoot = stagingRoot.resolve("runbook");
      final Path jsonDir = runbookRoot.resolve("json");
      final Path adocDir = runbookRoot.resolve("adoc");
      recreate(jsonDir);
      recreate(adocDir);

      normalize(model);
      new ScenarioJsonWriter(model).write(jsonDir.resolve("runbook.json").toFile());

      final AsciiDocReportGenerator generator = new AsciiDocReportGenerator();
      final AsciiDocReportConfig config =
          generator.createReportConfig(
              "--sourceDir=" + jsonDir.toAbsolutePath(), "--targetDir=" + adocDir.toAbsolutePath());
      generator.setConfig(config);
      generator.loadReportModel();
      generator.generate();

      logger.accept("runbook rendered → " + adocDir.resolve(INDEX_FILE).toAbsolutePath());
    } catch (Exception cause) {
      logger.accept("runbook render skipped (" + cause.getMessage() + ")");
    }
  }

  /**
   * Name the model and its scenarios. The scenario plays standalone through the launcher, so the
   * class name AsciiDoc aggregates features by is unset — an unnamed model renders zero scenarios.
   * Naming it here keeps that rendering detail out of the scenario.
   */
  private void normalize(ReportModel model) {
    if (model.getName() == null || model.getName().isBlank()) {
      model.setName(RUNBOOK_FEATURE);
    }
    if (model.getClassName() == null || model.getClassName().isBlank()) {
      model.setClassName(RUNBOOK_FEATURE);
    }
    for (ScenarioModel scenario : model.getScenarios()) {
      if (scenario.getClassName() == null || scenario.getClassName().isBlank()) {
        scenario.setClassName(RUNBOOK_FEATURE);
      }
    }
  }

  private void recreate(Path dir) throws IOException {
    deleteRecursively(dir);
    Files.createDirectories(dir);
  }

  private void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
  }
}
