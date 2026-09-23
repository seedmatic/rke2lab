package io.seedmatic.rke2lab.osgi.runtime.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * The pax-logging-logback backend's logback policy, coded in Java rather than an inlined XML
 * string.
 *
 * <p>This class ships in a FRAGMENT attached to {@code org.ops4j.pax.logging.pax-logging-logback}
 * (see this module's {@code bnd.bnd}), so at runtime it is loaded by pax's bundle classloader and
 * links directly against pax's embedded — and unexported — logback. The flat host cannot reach
 * logback (pax exports no package), so {@code FrameworkLauncher} invokes {@link #configure}
 * reflectively across the realm seam via {@code paxBundle.loadClass(...)}, once, right after {@code
 * framework.start()} returns: pax is ACTIVE and its minimal bootstrap XML is already applied, and
 * nothing reconfigures the context afterwards, so this holds the last word.
 *
 * <p>Split of concerns with the bootstrap XML the launcher still writes: that XML wires only the
 * FILE appender + root, so boot noise reaches the file and never the console (whose native stdout
 * write also wedges the boot under a remote debugger). THIS enforces the noise-suppression POLICY —
 * the per-tree levels and the root level from the launch config — the single source that was the
 * old inlined {@code logback.xml}.
 */
public final class PaxLogbackConfigurer {

  /**
   * The one configurer the launcher reaches across the realm seam — loaded through the pax bundle's
   * classloader and invoked on THIS instance ({@code instance().configure(...)}), never as a static
   * call (the project's instance-passing discipline).
   */
  public static final PaxLogbackConfigurer INSTANCE = new PaxLogbackConfigurer();

  /**
   * The noisy third-party trees, quieted so an INFO root never drowns the file. Coded here, in
   * Java: the policy the old inlined logback config carried, now the one source. Insertion order
   * (broadest first) is irrelevant to logback but reads as intent.
   */
  protected final Map<String, Level> treeLevels = treeLevels();

  protected PaxLogbackConfigurer() {}

  /** The singleton the launcher invokes reflectively across the host↔pax realm seam. */
  public static PaxLogbackConfigurer instance() {
    return INSTANCE;
  }

  protected Map<String, Level> treeLevels() {
    final Map<String, Level> levels = new LinkedHashMap<>();
    levels.put("io.netty", Level.ERROR);
    levels.put("io.netty.util.internal", Level.ERROR);
    levels.put("io.netty.buffer", Level.ERROR);
    levels.put("io.grpc.netty", Level.ERROR);
    levels.put("org.apache.http", Level.WARN);
    levels.put("org.apache.http.wire", Level.ERROR);
    return levels;
  }

  /**
   * Apply the logback policy to pax's live context. Invoked reflectively by {@code
   * FrameworkLauncher} across the host↔pax realm seam with {@code paxBundle} = the resolved
   * pax-logging-logback bundle and {@code rootLevelName} = the launch config's framework log level
   * as a logback level name. Lets a failure propagate: the launcher's reflective call wraps and
   * logs it as a WARN (to the file), so a logging-config slip degrades to pax's bootstrap config
   * rather than felling the boot.
   */
  public void configure(Bundle paxBundle, String rootLevelName) {
    final LoggerContext context = resolveContext(paxBundle);
    treeLevels.forEach((name, level) -> context.getLogger(name).setLevel(level));
    context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.toLevel(rootLevelName, Level.INFO));
    attachConsoleUnlessSuppressed(context);
  }

  /** The system property a host sets to {@code false} to SUPPRESS the console appender. */
  protected static final String CONSOLE_PROPERTY = "rke2lab.log.console";

  /**
   * Attach a STDERR {@link ConsoleAppender} to root — the DEFAULT — so a STANDALONE CLI (manifests
   * / plan) shows its narration live.
   *
   * <p>stderr, not stdout, because several of these CLIs emit a DOCUMENT on stdout ({@code plan-cli
   * network export} → the blueprint YAML, {@code dataset export} → dataplan JSON) and a diagnostic
   * stream has no business in a product stream. It went unnoticed for a release because YAML is
   * permissive enough to swallow the damage silently: {@code 21:27:55.473 INFO … FrameworkLauncher
   * - OSGi runtime booted: 45 bundle(s) installed and started} parses as a MAPPING — everything up
   * to the colon becoming a key — so the export stayed syntactically valid and the junk key rode
   * all the way into the committed {@code network-blueprint.json}. The JSON export was luckier only
   * by accident: a stray line there breaks the parse outright.
   *
   * <p>Suppressed entirely when {@code -Drke2lab.log.console=false}: that is seed-master's case,
   * where a native console write wedges the boot under a remote debugger. (Stream pollution is no
   * longer a reason to suppress — stderr already answers it.)
   *
   * <p>This runs POST-{@code framework.start()}, past the boot-sensitive window the FILE-only
   * bootstrap XML guards, so the appender is safe to add here. Idempotent name so a
   * double-configure never double-attaches.
   */
  protected void attachConsoleUnlessSuppressed(LoggerContext context) {
    if ("false".equalsIgnoreCase(System.getProperty(CONSOLE_PROPERTY, "true"))) {
      return;
    }
    final Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
    if (root.getAppender("CONSOLE") != null) {
      return;
    }
    final PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(context);
    encoder.setPattern("%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n");
    encoder.start();
    final ConsoleAppender<ILoggingEvent> console = new ConsoleAppender<>();
    console.setContext(context);
    console.setName("CONSOLE");
    console.setTarget("System.err");
    console.setEncoder(encoder);
    console.start();
    root.addAppender(console);
  }

  /**
   * Reach the ONE {@code LoggerContext} pax logs through. pax's {@code PaxLoggingServiceImpl} holds
   * it in its protected instance field {@code m_logbackContext}, and its {@code Activator}
   * publishes that impl — wrapped in a local {@code $1ManagedPaxLoggingService} — under its
   * logservice names. So scan the services pax registered and let {@link #contextFieldOf} pull the
   * field off the impl, unwrapping the wrapper's synthetic enclosing reference. This handles both
   * pax context modes: the instance field carries the live context whether or not {@code
   * StaticLogbackContext} is set.
   */
  protected LoggerContext resolveContext(Bundle paxBundle) {
    final ServiceReference<?>[] registered = paxBundle.getRegisteredServices();
    final BundleContext bundleContext = paxBundle.getBundleContext();
    if (registered != null && bundleContext != null) {
      for (ServiceReference<?> ref : registered) {
        final Optional<LoggerContext> context =
            Optional.ofNullable(bundleContext.getService(ref)).flatMap(this::contextFieldOf);
        if (context.isPresent()) {
          return context.orElseThrow();
        }
      }
    }
    throw new IllegalStateException(
        "pax-logging-logback LoggerContext not reachable via its registered services");
  }

  /**
   * The {@code m_logbackContext} pax holds, reached from a registered service object. The object
   * registered under {@code PaxLoggingService} is not the impl itself but its local wrapper {@code
   * PaxLoggingServiceImpl$1ManagedPaxLoggingService}, whose synthetic enclosing-instance field
   * ({@code this$0}) IS the {@code PaxLoggingServiceImpl} that owns the context — so try the field
   * directly, then through that enclosing instance.
   */
  protected Optional<LoggerContext> contextFieldOf(Object service) {
    return loggerContextOf(service)
        .or(() -> readField(service, "this$0").flatMap(this::loggerContextOf));
  }

  protected Optional<LoggerContext> loggerContextOf(Object target) {
    return readField(target, "m_logbackContext")
        .filter(LoggerContext.class::isInstance)
        .map(LoggerContext.class::cast);
  }

  /** The value of field {@code name} on {@code target} (walking its supertypes), if present. */
  protected Optional<Object> readField(Object target, String name) {
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        final Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return Optional.ofNullable(field.get(target));
      } catch (NoSuchFieldException walkUp) {
        // declared higher up — keep walking
      } catch (IllegalAccessException blocked) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }
}
