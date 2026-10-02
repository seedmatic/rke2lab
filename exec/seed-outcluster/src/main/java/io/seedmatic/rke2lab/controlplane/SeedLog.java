package io.seedmatic.rke2lab.controlplane;

import java.util.Locale;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Shared seed-outcluster logger with Pulumi verbosity-aware level mapping. */
public final class SeedLog {

  @FunctionalInterface
  public interface PulumiLogSink {
    void emit(LogEvent event, String message);
  }

  public enum LogEvent {
    ERROR,
    WARN,
    INFO,
    DEBUG,
    TRACE
  }

  private enum LogLevel {
    ERROR,
    WARN,
    INFO,
    DEBUG,
    TRACE
  }

  private static final Logger LOGGER = Logger.getLogger("io.seedmatic.rke2lab.controlplane");

  private static final LogLevel THRESHOLD = resolveThreshold();

  private static volatile Optional<PulumiLogSink> optSink = Optional.empty();

  static {
    configureJavaUtilLogging();
  }

  private SeedLog() {
    // Utility class
  }

  public static void error(String scope, String message) {
    log(scope, LogLevel.ERROR, message);
  }

  public static void warn(String scope, String message) {
    log(scope, LogLevel.WARN, message);
  }

  public static void info(String scope, String message) {
    log(scope, LogLevel.INFO, message);
  }

  public static void debug(String scope, String message) {
    log(scope, LogLevel.DEBUG, message);
  }

  public static void trace(String scope, String message) {
    log(scope, LogLevel.TRACE, message);
  }

  public static AutoCloseable open(PulumiLogSink sink) {
    optSink = Optional.of(sink);
    return SeedLog::close;
  }

  public static void close() {
    optSink = Optional.empty();
  }

  /** The installed Pulumi log sink, or empty when logs route through java.util.logging. */
  private static Optional<PulumiLogSink> currentSink() {
    return optSink;
  }

  private static void log(String scope, LogLevel level, String message) {
    if (!isEnabled(level)) {
      return;
    }

    final String prefix = "[" + normalize(scope, "seed") + "] ";
    final String payload = prefix + normalize(message, "");
    final Optional<PulumiLogSink> sink = currentSink();
    if (sink.isPresent()) {
      sink.orElseThrow().emit(toEvent(level), payload);
      return;
    }
    LOGGER.log(toJavaLevel(level), payload);
  }

  private static LogEvent toEvent(LogLevel level) {
    return switch (level) {
      case ERROR -> LogEvent.ERROR;
      case WARN -> LogEvent.WARN;
      case INFO -> LogEvent.INFO;
      case DEBUG -> LogEvent.DEBUG;
      case TRACE -> LogEvent.TRACE;
    };
  }

  private static boolean isEnabled(LogLevel level) {
    if (level.ordinal() <= LogLevel.INFO.ordinal()) {
      return true;
    }
    if (currentSink().isPresent()) {
      return true;
    }
    return level.ordinal() <= THRESHOLD.ordinal();
  }

  private static Level toJavaLevel(LogLevel level) {
    return switch (level) {
      case ERROR -> Level.SEVERE;
      case WARN -> Level.WARNING;
      case INFO -> Level.INFO;
      case DEBUG -> Level.FINE;
      case TRACE -> Level.FINER;
    };
  }

  private static void configureJavaUtilLogging() {
    final Level javaThreshold = toJavaLevel(THRESHOLD);

    LOGGER.setLevel(javaThreshold);

    final Logger rootLogger = Logger.getLogger("");
    if (rootLogger != null) {
      if (rootLogger.getLevel() == null
          || rootLogger.getLevel().intValue() > javaThreshold.intValue()) {
        rootLogger.setLevel(javaThreshold);
      }
      for (Handler handler : rootLogger.getHandlers()) {
        if (handler.getLevel() == null
            || handler.getLevel().intValue() > javaThreshold.intValue()) {
          handler.setLevel(javaThreshold);
        }
      }
    }
  }

  private static LogLevel resolveThreshold() {
    final String explicit =
        firstNonBlank(System.getenv("RKE2LAB_LOG_LEVEL"), System.getenv("PULUMI_LOG_LEVEL"));
    if (!explicit.isBlank()) {
      final Optional<LogLevel> parsed = parseNamedLevel(explicit);
      if (parsed.isPresent()) {
        return parsed.orElseThrow();
      }
    }

    if (isTruthy(System.getenv("PULUMI_DEBUG_COMMANDS"))) {
      return LogLevel.DEBUG;
    }

    final int verbosity =
        max(
            parseInt(System.getenv("RKE2LAB_LOG_VERBOSITY")),
            parseInt(System.getenv("PULUMI_LOG_VERBOSITY")),
            parseInt(System.getenv("PULUMI_VERBOSE")));

    if (verbosity >= 9) {
      return LogLevel.TRACE;
    }
    if (verbosity >= 3) {
      return LogLevel.DEBUG;
    }
    return LogLevel.INFO;
  }

  private static Optional<LogLevel> parseNamedLevel(String value) {
    final String normalized = value.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "error", "err", "severe" -> Optional.of(LogLevel.ERROR);
      case "warn", "warning" -> Optional.of(LogLevel.WARN);
      case "info", "information" -> Optional.of(LogLevel.INFO);
      case "debug", "fine" -> Optional.of(LogLevel.DEBUG);
      case "trace", "finer", "finest" -> Optional.of(LogLevel.TRACE);
      default -> Optional.empty();
    };
  }

  private static boolean isTruthy(String value) {
    if (value == null) {
      return false;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "1", "true", "yes", "on" -> true;
      default -> false;
    };
  }

  private static int parseInt(String value) {
    if (value == null || value.isBlank()) {
      return -1;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  private static int max(int... values) {
    int max = -1;
    for (int value : values) {
      if (value > max) {
        max = value;
      }
    }
    return max;
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private static String normalize(String value, String fallback) {
    if (value == null) {
      return fallback;
    }
    final String trimmed = value.trim();
    return trimmed.isEmpty() ? fallback : trimmed;
  }
}
