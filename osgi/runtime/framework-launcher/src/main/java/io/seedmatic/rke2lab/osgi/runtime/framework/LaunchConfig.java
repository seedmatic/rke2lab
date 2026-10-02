package io.seedmatic.rke2lab.osgi.runtime.framework;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.osgi.service.log.LogLevel;

/**
 * The launch KNOBS that genuinely differ between executors — everything else the {@link
 * FrameworkLauncher} derives from the {@link io.seedmatic.rke2lab.osgi.boot.discovery.BootPlan}
 * (e.g. pax-presence from whether the plan carries pax bundles). Two honest knobs:
 *
 * <ul>
 *   <li>{@link #bootDelegation} — packages every bundle loads from the parent (app) classloader,
 *       bypassing import/export wiring. For JDK-internal packages a library reaches reflectively
 *       without importing them — notably {@code sun.misc} for byte-buddy's {@code
 *       ClassInjector.UsingReflection}. A system-bundle EXPORT cannot serve these (the consumer
 *       never declares the import to wire to). BOTH executors set {@code sun.misc}: the live boot
 *       ({@code FrameworkLaunch.embedded}) and the test executor ({@code ScenarioTestkit.felix})
 *       both play jGiven scenarios, so byte-buddy needs it in both. {@link #defaults} leaves it
 *       empty — each executor adds what it needs via {@link #withBootDelegation}.
 *   <li>{@link #frameworkLogLevel} — the framework's own internal log verbosity (the resolver's
 *       internal trace, the only place a failed resolve explains WHICH requirement could not be
 *       wired). The currency is the OSGi {@link LogLevel} — the single vocabulary the operator knob
 *       ({@code logging:level}) and the {@code @FrameworkLog} test annotation both speak, so no
 *       second enum exists. Empty leaves the Felix default; both the config knob and the test
 *       executor raise it. This record is felix-owner: {@link #felixLevelOf(LogLevel)} is the ONE
 *       place the OSGi level collapses to Felix's proprietary {@code felix.log.level} int (Felix
 *       has no AUDIT/TRACE — they pin to the nearest step), used by both boot paths.
 * </ul>
 */
public record LaunchConfig(
    List<String> bootDelegation, Optional<LogLevel> frameworkLogLevel, String logFile) {

  /**
   * Default log file for the generated pax logback config — seed-outcluster's established path
   * ({@code .local.d/seed-outcluster.log}, relative to the launch CWD, the {@code
   * BootstrapPaths.STATE_DIR} ".local.d" convention). An executor booting for another purpose
   * overrides it via {@link #withLogFile}. A field, not a system property: {@code
   * FrameworkLauncher} bakes it straight into the config it generates, knowing nothing of the
   * host/Pulumi.
   */
  public static final String DEFAULT_LOG_FILE = ".local.d/seed-outcluster.log";

  /**
   * The boot-delegation every jGiven-PLAYING boot needs — the single source both executors derive
   * from, so the requirement can never be set in one and forgotten in the other (the live boot once
   * was: byte-buddy failed in-container while the tests, which had it, stayed green). jGiven's
   * {@code beforeEach} generates each stage subclass via byte-buddy's {@code
   * ClassInjector.UsingReflection}, which reaches {@code sun.misc.Unsafe} reflectively — a
   * JDK-internal package no system-bundle export can serve. The live boot ({@code
   * FrameworkLaunch.embedded}) and the test executor ({@code ScenarioTestkit.felix}) both reference
   * THIS, never the bare {@code "sun.misc"} literal.
   */
  public static final List<String> SCENARIO_PLAY_BOOT_DELEGATION = List.of("sun.misc");

  /**
   * Stamp the Felix properties that must be IDENTICAL in every boot — the single source both
   * executors derive from, the twin discipline of {@link #SCENARIO_PLAY_BOOT_DELEGATION} (a
   * requirement set in one boot and forgotten in the other is exactly the class of bug that let the
   * live boot diverge). Today one invariant: {@code felix.bootdelegation.implicit=false} — Felix
   * defaults it TRUE, guessing by stack inspection when an outside-a-bundle load should fall
   * through to the parent classloader; that silent escape hatch would let a non-wired (seam)
   * package resolve by accident, so a typed-seam proof could pass for the wrong reason. Off = every
   * load not satisfied by a bundle's imports / Bundle-ClassPath / the system bundle fails loudly.
   * Both the live {@code FrameworkLauncher} and the test {@code OutOfContainerFrameworkExtension}
   * apply this.
   *
   * @param config the mutable Felix property map each executor is building (values are {@code
   *     String} in the test, {@code Object} in the live boot — {@code ? super String} accepts both)
   */
  public static void applyFrameworkInvariants(Map<String, ? super String> config) {
    config.put("felix.bootdelegation.implicit", "false");
  }

  public LaunchConfig {
    bootDelegation = List.copyOf(bootDelegation);
  }

  /**
   * The prod default: no boot-delegation, Felix's default log level, seed-outcluster's log file.
   */
  public static LaunchConfig defaults() {
    return new LaunchConfig(List.of(), Optional.empty(), DEFAULT_LOG_FILE);
  }

  public LaunchConfig withBootDelegation(String... packages) {
    return withBootDelegation(List.of(packages));
  }

  public LaunchConfig withBootDelegation(List<String> packages) {
    return new LaunchConfig(packages, frameworkLogLevel, logFile);
  }

  public LaunchConfig withFrameworkLogLevel(LogLevel level) {
    return new LaunchConfig(bootDelegation, Optional.of(level), logFile);
  }

  /** The file the generated pax logback config writes to (an executor-specific knob). */
  public LaunchConfig withLogFile(String logFile) {
    return new LaunchConfig(bootDelegation, frameworkLogLevel, logFile);
  }

  /**
   * Collapse the OSGi {@link LogLevel} to Felix's proprietary {@code felix.log.level} int (1=error,
   * 2=warning, 3=info, 4=debug). The ONE place the two vocabularies meet — reached by the live boot
   * (via {@link #frameworkLogLevel}) and the test executor (reading {@code @FrameworkLog}) alike,
   * so the mapping can never diverge. Felix has no AUDIT or TRACE step: AUDIT pins to error (least
   * verbose), TRACE to debug (most verbose).
   */
  public static int felixLevelOf(LogLevel level) {
    return switch (level) {
      case AUDIT, ERROR -> 1;
      case WARN -> 2;
      case INFO -> 3;
      case DEBUG, TRACE -> 4;
    };
  }

  /**
   * Collapse the OSGi {@link LogLevel} to a logback level NAME (a String) for the generated pax
   * logback root — the Plane-B twin of {@link #felixLevelOf} (Plane A). Both read the SAME {@link
   * #frameworkLogLevel} knob; logback has no AUDIT (pins to ERROR), the rest map by name.
   */
  public static String logbackLevelOf(LogLevel level) {
    return switch (level) {
      case AUDIT, ERROR -> "ERROR";
      case WARN -> "WARN";
      case INFO -> "INFO";
      case DEBUG -> "DEBUG";
      case TRACE -> "TRACE";
    };
  }
}
