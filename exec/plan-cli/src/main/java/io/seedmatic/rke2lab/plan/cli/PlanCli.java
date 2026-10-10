package io.seedmatic.rke2lab.plan.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.seedmatic.rke2lab.osgi.runtime.junit.launcher.JUnitLauncherCore;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.LogFileSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRole;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRoleSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioOutcomeSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.TxIdSeed;
import io.seedmatic.rke2lab.plan.cli.bdd.PlanCliRun;
import io.seedmatic.rke2lab.plan.cli.bdd.PlanCliScenario;
import io.seedmatic.rke2lab.plan.cli.bdd.PublishPlanRun;
import io.seedmatic.rke2lab.plan.cli.bdd.PublishPlanScenario;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line interface for the cross-repo plan — the unified {@code plan} north-adapter. It
 * multiplexes two {@link Plane}s over one door, {@code plan network export} (the network blueprint)
 * and {@code plan dataset export} (the ZFS dataset layout), and DELIVERS both with {@code plan
 * publish}: {@link PublishPlanScenario} derives them and the fabric scion pushes {@code
 * fabric/plan}. {@code plan} is the genus; the planes are the species.
 *
 * <p>Each {@code export} verb drives {@link PlanCliScenario} on the embedded JUnit launcher — the
 * SAME BDD-as-engine machinery {@code seed-outcluster} and {@code manifests-cli} use. The scenario
 * sows the plane's coordinate through the broker; that grows the domain scion in-container ({@code
 * NetplanBlueprintScenario} for {@code network}, {@code DataplanScenario} for {@code dataset}),
 * where the {@code type=contract} bundle record (which the flat host cannot reference) is
 * reachable. The scion files what it derived in the run's cellar; the scenario reads that harvest
 * back within the run and hands it to this CLI, which renders it (YAML for network, which nix
 * re-parses via {@code yq -o=json}; raw JSON for dataset, which ndh reads via {@code fromJSON}).
 * The CLI renders what the scion DERIVED, never a file it wrote. No contract type ever crosses to
 * the host.
 *
 * <p>The domains stay separate (each its own coordinate + scion); only this ingress is shared.
 */
public final class PlanCli {

  private static final Logger LOG = LoggerFactory.getLogger(PlanCli.class);

  private PlanCli() {}

  public static void main(String[] args) {
    new PlanCli().run(args);
  }

  private void run(String[] args) {
    final String planeToken = args.length > 0 ? args[0] : "";
    final String verb = args.length > 1 ? args[1] : "";

    if (planeToken.equals("publish")) {
      publish();
      return;
    }

    final Plane plane;
    try {
      plane = Plane.parse(planeToken);
    } catch (IllegalArgumentException ex) {
      LOG.error("{} — usage: plan <network|dataset> export | plan publish", ex.getMessage());
      System.exit(1);
      return;
    }

    switch (verb) {
      case "export" -> export(plane);
      default -> {
        LOG.error(
            "specify a verb — supported: export (got: '{}'); usage: plan {} export",
            verb,
            planeToken);
        System.exit(1);
      }
    }
  }

  /**
   * Drive {@link PlanCliScenario} to grow the plane's export in-container, take the harvest the
   * scenario hands back, and stream it to stdout in the plane's format. The SOIL temp dir is still
   * given to the scion, which writes its own export file there, and deleted after — nothing reads
   * it; it only keeps the scion from leaving a temp dir of its own behind. The scenario noise stays
   * off stdout at its own source — {@code ScenarioOutcomeExtension} silences jGiven's console
   * report (the outcome is the harvested runbook), and the framework log rides its file appender —
   * so stdout carries only the export.
   */
  private void export(Plane plane) {
    final Path soil = freshDir("rke2lab-" + plane.coordinate() + "-export-");
    try {
      final AtomicReference<JsonNode> harvest = new AtomicReference<>();
      play(
          PlanCliScenario.class,
          PlanCliScenario.SEED.into(
              new PlanCliRun(plane, Optional.of(soil.toString()), harvest::set)),
          "the plan export");
      final JsonNode reaped =
          Optional.ofNullable(harvest.get())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "the " + plane.coordinate() + " export handed back no harvest"));
      switch (plane.format()) {
        case YAML -> writeYaml(reaped, System.out);
        case JSON -> writeJson(reaped, System.out);
      }
    } finally {
      deleteRecursively(soil);
    }
  }

  /**
   * Derive the plan and deliver it: {@link PublishPlanScenario} sows and grafts ghapp, netplan,
   * dataplan and fabric, and the fabric scion pushes {@code fabric/plan}. Run from the repository's
   * root: the delivery's linked worktree is made under its {@code .local.d/worktrees}.
   */
  private void publish() {
    final Path soil = freshDir("rke2lab-plan-publish-");
    try {
      play(
          PublishPlanScenario.class,
          PublishPlanScenario.SEED.into(
              new PublishPlanRun(
                  soil.toString(),
                  Path.of(".local.d", "worktrees").toAbsolutePath().normalize().toString())),
          "the plan publish");
    } finally {
      deleteRecursively(soil);
    }
  }

  /** Play {@code scenario} on the embedded launcher, seeded with {@code seed}, failing loud. */
  private void play(
      Class<?> scenario, Consumer<NamespacedHierarchicalStore<Namespace>> seed, String label) {
    final String txId = UUID.randomUUID().toString();
    try {
      final ReportModel runbook =
          new JUnitLauncherCore<ReportModel>()
              .run(
                  PlanCli.class.getClassLoader(),
                  JupiterTestEngine.class,
                  wiring -> List.of(DiscoverySelectors.selectClass(scenario)),
                  (launcher, request, sessionStore) -> {
                    final SummaryGeneratingListener listener = new SummaryGeneratingListener();
                    launcher.execute(request, listener);
                    final var summary = listener.getSummary();
                    if (summary.getTotalFailureCount() > 0) {
                      final var first = summary.getFailures().get(0);
                      throw new IllegalStateException(
                          label + " failed: " + first.getTestIdentifier().getDisplayName(),
                          first.getException());
                    }
                    return new ScenarioOutcomeSeed().read(sessionStore).runbook();
                  },
                  seed.andThen(RunRoleSeed.into(RunRole.ROOT))
                      .andThen(TxIdSeed.into(txId))
                      .andThen(LogFileSeed.into(".local.d/plan-cli.log")));
      final List<?> broken =
          runbook.getScenariosWithStatus(ExecutionStatus.FAILED, ExecutionStatus.ABORTED);
      if (!broken.isEmpty()) {
        throw new IllegalStateException(
            label + " did not complete (" + broken.size() + " failed/aborted)");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(label + " was interrupted", interrupted);
    }
  }

  private void writeYaml(JsonNode export, PrintStream out) {
    final YAMLFactory yamlFactory =
        YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build();
    try {
      new ObjectMapper(yamlFactory).writerWithDefaultPrettyPrinter().writeValue(out, export);
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot render the plan export as YAML", ex);
    }
  }

  private void writeJson(JsonNode export, PrintStream out) {
    try {
      new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out, export);
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot render the plan export as JSON", ex);
    }
  }

  private Path freshDir(String prefix) {
    try {
      return Files.createTempDirectory(prefix).toAbsolutePath().normalize();
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot create the plan export dir", ex);
    }
  }

  private void deleteRecursively(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ex) {
                  throw new UncheckedIOException("cannot clean the export dir " + path, ex);
                }
              });
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot walk the export dir " + root, ex);
    }
  }
}
