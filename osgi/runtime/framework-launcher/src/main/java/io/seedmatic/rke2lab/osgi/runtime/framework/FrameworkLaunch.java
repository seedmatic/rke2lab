package io.seedmatic.rke2lab.osgi.runtime.framework;

import io.seedmatic.rke2lab.osgi.bnd.BootStackJar;
import io.seedmatic.rke2lab.osgi.boot.discovery.BootPlan;
import io.seedmatic.rke2lab.osgi.boot.discovery.BootPlanner;
import io.seedmatic.rke2lab.osgi.boot.discovery.BootRequest;
import io.seedmatic.rke2lab.osgi.boot.discovery.HostClassRealm;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import org.osgi.service.log.LogLevel;

/**
 * The embedded-OSGi boot: plan then launch (via {@link BootPlanner} + {@link FrameworkLauncher})
 * the stack staged under {@code META-INF/bundles/}, handing the live {@link BootedFramework} to the
 * caller. NOT a pipeline step — the boot is enacted as a plain launch operation; a scenario that
 * needs a live world invokes it as its first jGiven step ("the OSGi world is connected", via {@code
 * OsgiConnection.embedded()}), the caller owning the framework's lifecycle.
 *
 * <p>Plan and launch are NOT separate operations: no caller inspects a {@code BootPlan} without
 * booting (verified across all entrypoints and {@code OutOfContainerFrameworkExtension}, which does
 * not use {@code BootPlanner} at all), so {@link #bootEmbedded()} does plan+launch in one step.
 *
 * <p>Callers use {@link #embedded()}, a preset that pre-fills the fixed prod topology (the staged
 * index, install-everything) and exposes {@link Embedded#launch()} — the booted framework is handed
 * out and the caller closes it (typically {@code try (var f = FrameworkLaunch.embedded().launch())
 * { … }}); the embedded prod topology is exercised exactly as a deployed exec-jar would.
 */
public final class FrameworkLaunch {

  private FrameworkLaunch() {}

  /**
   * The prod preset: boot the embedded stack staged under {@code META-INF/bundles/} (the staged
   * index, {@code DiscoveryPolicy.all()}). Fails fast if the artifact carries no embedded bundles
   * (a packaging defect, not a degraded run mode). The shape every entrypoint uses.
   */
  public static Embedded embedded() {
    return new Embedded(Optional.empty(), Optional.empty());
  }

  /**
   * The prod preset raised to a chosen framework {@link LogLevel} — the operator's {@code
   * logging:level} knob threaded to the live boot so a failed resolve explains itself. Empty leaves
   * the Felix default (what the two CLIs, which never thread a level, get from {@link
   * #embedded()}).
   */
  public static Embedded embedded(LogLevel frameworkLogLevel) {
    return new Embedded(Optional.of(frameworkLogLevel), Optional.empty());
  }

  /**
   * The prod preset writing the framework log to {@code logFile} instead of the shared {@link
   * LaunchConfig#DEFAULT_LOG_FILE} — so each exec-jar keeps its own boot trace ({@code
   * manifests-cli.log}, {@code netplan-cli.log}), never a single {@code seed-outcluster.log} they
   * all clobber. The name is the whole relative path the caller chooses.
   */
  public static Embedded embedded(String logFile) {
    return new Embedded(Optional.empty(), Optional.of(logFile));
  }

  /** The prod preset with BOTH a chosen framework {@link LogLevel} and its own {@code logFile}. */
  public static Embedded embedded(LogLevel frameworkLogLevel, String logFile) {
    return new Embedded(Optional.of(frameworkLogLevel), Optional.of(logFile));
  }

  /** The boot preset over the fixed embedded topology; {@link #launch()} hands the world out. */
  public static final class Embedded {

    private final Optional<LogLevel> frameworkLogLevel;
    private final Optional<String> logFile;

    private Embedded(Optional<LogLevel> frameworkLogLevel, Optional<String> logFile) {
      this.frameworkLogLevel = frameworkLogLevel;
      this.logFile = logFile;
    }

    /**
     * Boot and HAND the live {@link BootedFramework} OUT — the caller owns the lifecycle (closes it
     * itself). Fails fast if the artifact carries no embedded bundles (a packaging defect, not a
     * degraded run mode).
     */
    public BootedFramework launch() {
      try {
        return bootEmbedded();
      } catch (IOException ex) {
        throw new UncheckedIOException("failed to boot the embedded OSGi runtime", ex);
      }
    }

    private BootedFramework bootEmbedded() throws IOException {
      final BootPlan plan =
          new BootPlanner(HOST.stagedBundles(), HOST::resolves)
              .plan(BootRequest.create().embedBootStack());
      // The live boot PLAYS jGiven scenarios (the seeding IS jGiven), so it needs the same
      // byte-buddy boot-delegation the test executor does — from the ONE shared source, so it can
      // never be set in one and forgotten in the other (§
      // LaunchConfig.SCENARIO_PLAY_BOOT_DELEGATION).
      LaunchConfig config =
          LaunchConfig.defaults().withBootDelegation(LaunchConfig.SCENARIO_PLAY_BOOT_DELEGATION);
      if (frameworkLogLevel.isPresent()) {
        config = config.withFrameworkLogLevel(frameworkLogLevel.get());
      }
      if (logFile.isPresent()) {
        config = config.withLogFile(logFile.get());
      }
      return new FrameworkLauncher(config).launch(plan, true);
    }
  }

  /**
   * This exec-jar's host-world view (the flat JCL the boot runs on) — the one collaborator the
   * staged-bundle index and the host-resolution predicate both derive from, passed into {@link
   * BootPlanner} rather than reached through static helpers.
   */
  private static final HostClassRealm HOST =
      HostClassRealm.of(FrameworkLaunch.class.getClassLoader());

  /**
   * Whether the running process carries the embedded boot stack — true in a deployed exec-jar,
   * false on a reactor/test classpath. Probes felix.scr (by its symbolic name), the boot-stack
   * bundle common to every entrypoint.
   */
  public static boolean hasEmbeddedBundles() {
    return HOST.stagedBundles().contains(BootStackJar.FELIX_SCR.symbolicName());
  }
}
