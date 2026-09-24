package io.seedmatic.rke2lab.controlplane;

import com.pulumi.Pulumi;
import com.pulumi.deployment.Deployment;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.seedmatic.rke2lab.controlplane.bdd.ClusterSeedScenario;
import io.seedmatic.rke2lab.controlplane.bdd.RunbookRenderer;
import io.seedmatic.rke2lab.controlplane.bdd.SeedRun;
import io.seedmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.seedmatic.rke2lab.controlplane.config.ConfigLoader;
import io.seedmatic.rke2lab.controlplane.config.GithubAppCli;
import io.seedmatic.rke2lab.controlplane.config.Rke2labConfig;
import io.seedmatic.rke2lab.incus.ingress.BootstrapPaths;
import io.seedmatic.rke2lab.osgi.runtime.junit.launcher.JUnitLauncherCore;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.LogLevelSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.GraftTag;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRole;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRoleSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioGraft;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioOutcomeSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.TxIdSeed;
import io.seedmatic.rke2lab.pulumi.edge.PulumiDeploymentSeed;
import io.seedmatic.rke2lab.pulumi.edge.RunMode;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/**
 * The Pulumi entry point — Layer 1 of the amorce (see
 * docs/architecture/osgi/seed-bdd-module-spec.adoc § the amorce). The whole program runs inside
 * {@code Pulumi.run}: the {@code com.pulumi} graph cannot enter Felix, and the run's facts — the
 * {@link RunMode} (from {@code isDryRun}), the {@link Parcel} (project/stack), the derived {@link
 * BootstrapConfig} (from the Pulumi {@code Config}) — are only knowable here. {@code Main} captures
 * them into a {@link SeedRun} and plays {@link ClusterSeedScenario} on the launcher, seeding the
 * {@code SeedRun} through the session store; the scenario's GIVEN (Layer 2) bootstraps the open
 * gardening from it.
 *
 * <p>Nothing else lives here: opening the gardening, publishing the RunGate, building the Cellar
 * are the GIVEN's narrated work. {@code Main} is the thin Pulumi envelope that captures what only
 * it can know and hands it across.
 */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    // Operator subcommand: drive the out-of-band GitHub App declaration ceremony (create / install
    // / seed .secrets). Kept OUT of the grow because seed-master runs under pulumi up, whose gRPC
    // engine captures the console — so this runs before the Pulumi envelope, on the real console.
    if (args.length > 0 && "ghapp".equals(args[0])) {
      GithubAppCli.run(args);
      return;
    }
    // Route the process's raw console to the boot file, NEVER the real console: under a remote
    // debugger the console is not drained, so a native write to System.out/err blocks the
    // FelixStartLevel thread and the boot deadlocks (proven by jstack). This catches stray direct
    // writes (printStackTrace) AND the early host logs before pax boots (JUL's default
    // ConsoleHandler
    // writes to System.err, now this file). Once pax is up its logback drains everything to
    // .local.d/seed-master.log. seed-master is headless (Pulumi reads the engine gRPC channel, not
    // stdout), so nothing consumes the console and there is nothing to restore — but unlike the
    // former /dev/null sink, sending it to a file PRESERVES the early diagnostics.
    redirectRawConsoleToBootFile();
    Pulumi.run(
        context -> {
          final RunMode runMode = RunMode.detect(true);
          // Two-document view: the Pulumi stack config joined with the worktree's smudged .secrets,
          // so a coordinate's `secret:` meta pulls its provider subtree in. `.secrets` sits at the
          // worktree root, which IS the CWD `pulumi up` runs from — the worktree root is harvested
          // OSGi-side, never derived here (see below).
          final ConfigLoader loader =
              ConfigLoader.of(context.config(), Path.of(".secrets").toAbsolutePath().normalize());
          final Rke2labConfig config = Rke2labConfig.from(loader);
          // worktree.dir is NOT config and NOT derived here: the worktree soil (OSGi-side, played
          // as
          // the first crossing) harvests its own root into the cellar via its Worktree component,
          // and the host fetches it back where it needs it (the GROW mounts). Storing it in config
          // would only collide across worktrees; deriving it here would duplicate the component.
          final BootstrapConfig bootstrap = BootstrapConfig.from(config);
          final Parcel parcel = new Parcel(context.projectName(), context.stackName());
          final SeedRun run =
              SeedRun.builder()
                  .runMode(runMode)
                  .parcel(parcel)
                  .config(bootstrap)
                  .cleanWorktreeRequired(config.entryGate().cleanWorktreeRequired().orElse(false))
                  .toleratedWorktreePaths(config.entryGate().toleratedPaths())
                  .flakeLockRequired(config.entryGate().flakeLockRequired().orElse(false))
                  .txId(UUID.randomUUID().toString())
                  // The FACET payload sourced from the config DTO (the single source of truth): a
                  // Facet re-serialises its bound schema back to the JSON the host contributes
                  // verbatim, blind — the scion owns the decode.
                  .facet("manifests", config.manifests().facetJson())
                  .build();

          // The run's VERDICT, built by the engine's closing gate
          // (ScenarioGraft.assertNoCrossingFailed
          // / assertPassed): a root-anchored throwable carrying the crossings as suppressed. jGiven
          // captures it, the SummaryGeneratingListener reports it — this holder carries it out of
          // the
          // launcher so the driver RE-SURFACES it (pulumi exits non-zero) without re-constructing
          // it.
          final AtomicReference<Throwable> verdict = new AtomicReference<>();
          try {
            final ReportModel runbook =
                new JUnitLauncherCore<ReportModel>()
                    .run(
                        Main.class.getClassLoader(),
                        JupiterTestEngine.class,
                        wiring ->
                            List.of(DiscoverySelectors.selectClass(ClusterSeedScenario.class)),
                        (launcher, request, sessionStore) -> {
                          final var listener = new SummaryGeneratingListener();
                          launcher.execute(request, listener);
                          final var summary = listener.getSummary();
                          summary.getFailures().stream()
                              .findFirst()
                              .ifPresent(f -> verdict.set(f.getException()));
                          // A FAILED scenario still seeds its outcome: ScenarioOutcomeExtension
                          // runs
                          // on AfterTestExecutionCallback, which fires even when the body threw
                          // (the
                          // closing gate's assertNoCrossingFailed). So read the runbook back and
                          // RETURN it — the driver renders it (the diagnosis) and only THEN raises
                          // the honest verdict on the runbook's FAILED status below. This is the
                          // documented contract of the outcome extension; throwing here instead
                          // would discard exactly the runbook the failure needs.
                          final var played = new ScenarioOutcomeSeed().find(sessionStore);
                          if (played.isPresent()) {
                            return played.get().runbook();
                          }
                          // No outcome AND a failure: a genuine before-body failure (setup /
                          // container crash) never reached the scenario body — surface it loud,
                          // with
                          // the real cause, since there is no runbook to render.
                          if (summary.getTotalFailureCount() > 0) {
                            final var first = summary.getFailures().get(0);
                            throw new IllegalStateException(
                                "scenario failed before it could seed an outcome: "
                                    + first.getTestIdentifier().getDisplayName(),
                                first.getException());
                          }
                          // No outcome and no failure: a @SeedScenario always seeds via the
                          // extension, so this is a wiring bug — read() surfaces it clearly.
                          return new ScenarioOutcomeSeed().read(sessionStore).runbook();
                        },
                        ClusterSeedScenario.SEED
                            .into(run)
                            .andThen(RunRoleSeed.into(RunRole.ROOT))
                            .andThen(TxIdSeed.into(run.txId()))
                            // The live Pulumi deployment, seeded onto the launcher worker so the
                            // GROW beat's com.pulumi resources resolve there (the deployment is a
                            // plain ThreadLocal, not inherited by the worker) — §
                            // PulumiDeploymentSeed.
                            .andThen(PulumiDeploymentSeed.into(Deployment.getInstance()))
                            // Plane A of the one log-level knob: the framework's own log verbosity,
                            // seeded onto the launcher so BaseWorldExtension raises felix.log.level
                            // before booting Felix (the only lever that explains a failed resolve).
                            // Absent ⇒ a no-op seed, so the boot keeps the Felix default.
                            .andThen(
                                bootstrap.logLevel().map(LogLevelSeed::into).orElse(store -> {})));
            // Render the runbook (adoc + json) into host.live.d AFTER the play — the two-channel
            // rule: the runbook is narration, materialised post-run. It cannot travel through the
            // promotion (a mid-scenario beat), so the host writes it into the live tree directly, a
            // live mutation seen as drift at the next rotation (§ host-cellar-realisation). The
            // live
            // root is a WITHIN-RUN fact the incus scion posed on the runbook (the ephemeral cellar,
            // § seed-broker-spec two cellars): the host reads it back through the graft mechanism,
            // NOT by re-deriving the layout convention (which lives only in incus-core).
            // Best-effort
            // inside the renderer, so it never fails the provisioning; a run with no live root (a
            // scion that did not pose it) simply renders nothing.
            final var graft = new ScenarioGraft();
            // The crossing REASONS were already folded onto the runbook's case by
            // ScenarioOutcomeExtension (the universal @SeedScenario chokepoint), so the render
            // below
            // shows the real per-crossing stacks, not the closing-gate boilerplate. Here the driver
            // only RENDERS the already-corrected runbook, then attaches the same reasons as
            // suppressed on the console verdict.
            graft
                .graftedValue(runbook, GraftTag.LIVE_ROOT)
                .ifPresent(
                    live ->
                        new RunbookRenderer(Path.of(live), line -> context.log().info(line))
                            .render(runbook));
            // Only AFTER the diagnostic runbook is rendered does the run's verdict surface: the
            // ReportModel is the fail-at-end collector (jGiven plays to completion, marking each
            // failed step), so a FAILED or ABORTED scenario means the seed did not complete — fail
            // the Pulumi run so the operator sees a non-zero exit, the runbook already written for
            // the diagnosis. A clean run raises nothing. The verdict itself is the engine's (a
            // root-anchored throwable with the crossings suppressed); the driver only RE-SURFACES
            // it
            // — Error/RuntimeException rethrown as-is (keeping the root at the top of the stack),
            // any other Throwable wrapped only because the signature demands unchecked.
            final List<?> broken =
                runbook.getScenariosWithStatus(ExecutionStatus.FAILED, ExecutionStatus.ABORTED);
            if (!broken.isEmpty()) {
              Throwable reason = verdict.get();
              // jGiven's fail-at-end rethrows the scenario failure wrapped in a BARE
              // RuntimeException whose message is the cause's toString — unwrap it so the operator
              // sees the ROOT verdict once, not doubled (the wrapper's message AND its Caused-by
              // both printed the folded reason). Only a plain RuntimeException that merely restates
              // its cause is peeled; a real domain RuntimeException is left intact.
              while (reason != null
                  && reason.getClass() == RuntimeException.class
                  && reason.getCause() != null
                  && reason.getCause().toString().equals(reason.getMessage())) {
                reason = reason.getCause();
              }
              switch (reason) {
                case RuntimeException re -> throw re;
                case Error err -> throw err;
                case null ->
                    throw new IllegalStateException(
                        "the cluster-seed scenario did not complete — see the rendered runbook ("
                            + broken.size()
                            + " failed/aborted)");
                default ->
                    throw new IllegalStateException(
                        "the cluster-seed scenario did not complete — see the rendered runbook",
                        reason);
              }
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the cluster-seed run was interrupted", interrupted);
          }
        });
  }

  /**
   * Redirect {@code System.out}/{@code err} to a dedicated boot file (truncated at startup). A file
   * on disk never blocks on a full console pipe, so raw-stream writes during boot cannot deadlock
   * the FelixStartLevel thread under a remote debugger. Not restored: seed-master is headless (see
   * the call site), so nothing reads the console; and unlike the former {@code nullOutputStream}
   * sink, a file preserves the early diagnostics until pax's logback takes over.
   */
  private static void redirectRawConsoleToBootFile() {
    try {
      final Path bootLog = Path.of(BootstrapPaths.STATE_DIR, "rke2lab-boot-early.log");
      Files.createDirectories(bootLog.getParent());
      final PrintStream bootFile =
          new PrintStream(Files.newOutputStream(bootLog), true, StandardCharsets.UTF_8);
      System.setOut(bootFile);
      System.setErr(bootFile);
      // Same policy at the logback level: seed-master is headless under Pulumi, so SUPPRESS the
      // logback console appender PaxLogbackConfigurer adds by default (a standalone CLI keeps it,
      // to
      // narrate live). Property literal (seed-master does not depend on the pax fragment) —
      // PaxLogbackConfigurer.CONSOLE_PROPERTY. Without this the console appender would just
      // duplicate
      // into this redirected boot file, beside logback's own .local.d/seed-master.log.
      System.setProperty("rke2lab.log.console", "false");
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to redirect the raw console to the boot file", ex);
    }
  }
}
