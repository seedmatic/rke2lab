package io.seedmatic.rke2lab.sops.process;

import io.seedmatic.rke2lab.clusterpki.contract.SopsEncryptionException;
import io.seedmatic.rke2lab.seed.broker.port.EnclosureGate;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * One run of {@code sops} over stdin, the ONE runner both sops edges drive — {@code sops-edge} in
 * the OSGi world, {@code SopsAtRest} in the host realm. The document goes in on stdin, the result
 * comes back on stdout, and nothing touches disk. stdout and stderr stay SEPARATE — stdout carries
 * the document, stderr only diagnostics — and stderr is drained concurrently, so a tool that fills
 * its pipe while we block on stdout still exits.
 *
 * <p>{@code env} is laid over the inherited environment: a key reaches the tool through its
 * environment, never through its command line, which the process table shows.
 *
 * <p>The binary is resolved ONCE, at construction: {@value #BINARY_ENV} when set (absolute and
 * executable), else the first {@code sops} on {@code PATH}, whose real path must lie in {@code
 * /nix/store} — a shadowing copy elsewhere on {@code PATH} is refused, by name. A refused or
 * missing binary is kept as the refusal and raised by {@link #run}, so constructing an edge never
 * fails on a machine that does not seal.
 *
 * <p>⚠️ A failure NEVER carries sops' stderr: its parsers quote what they failed to read — {@code
 * cannot unmarshal !!str `<value>`}, the value whole when it is short — and what an edge feeds sops
 * is a plaintext to seal or a blob that opens to one. A {@link SopsEncryptionException} carries the
 * operation, the exit code, a fixed classification of the stderr and its line count, so a caller
 * may lend its message to a failure reason. The stderr itself goes to a separate DEBUG line only on
 * the operator's opt-in ({@value #STDERR_OPT_IN}{@code =true}), and never IN_CLUSTER: there the
 * render Task's logs are kept by Pipelines-as-Code and readable in rke2lab-system, so the opt-in is
 * ignored with one warning.
 */
public final class SopsProcess {

  /** The system property an operator sets for one debugging run, to see sops' stderr. */
  public static final String STDERR_OPT_IN = "rke2lab.sops.stderr";

  /** The environment variable that names the sops binary explicitly. */
  public static final String BINARY_ENV = "RKE2LAB_SOPS";

  private static final Path NIX_STORE = Path.of("/nix/store");

  private final Map<String, String> env;
  private final Binary binary;
  private final boolean stderrToDebug;
  private final System.Logger log;

  /**
   * A runner with {@code env} laid over the inherited environment, the enclosure deciding whether
   * the stderr opt-in may hold. An absent enclosure is the OPERATOR one — a test or a standalone
   * CLI, no host having published the gate — exactly as the manifests scenario reads it.
   */
  public SopsProcess(Map<String, String> env, Optional<EnclosureGate> enclosure) {
    this(env, enclosure, System.getenv(), System.getLogger(SopsProcess.class.getName()));
  }

  SopsProcess(
      Map<String, String> env,
      Optional<EnclosureGate> enclosure,
      Map<String, String> inherited,
      System.Logger log) {
    this.env = Map.copyOf(env);
    this.log = log;
    final Map<String, String> effective = new HashMap<>(inherited);
    effective.putAll(this.env);
    this.binary = Binary.resolve(effective);
    binary
        .path()
        .ifPresent(path -> log.log(System.Logger.Level.DEBUG, "sops resolved to {0}", path));
    final boolean optedIn = Boolean.getBoolean(STDERR_OPT_IN);
    final boolean inCluster = enclosure.map(EnclosureGate::inCluster).orElse(false);
    if (optedIn && inCluster) {
      log.log(
          System.Logger.Level.WARNING,
          "sops stderr opt-in ("
              + STDERR_OPT_IN
              + ") ignored in-cluster: the Task logs are readable in rke2lab-system");
    }
    this.stderrToDebug = optedIn && !inCluster;
  }

  /** Run {@code sops arguments…} with {@code stdinDocument} on stdin, returning its stdout. */
  public String run(List<String> arguments, String stdinDocument) {
    final List<String> command = new ArrayList<>();
    command.add(binary.orRefuse().toString());
    command.addAll(arguments);
    final ProcessBuilder pb = new ProcessBuilder(command);
    pb.environment().putAll(env);

    final Process process;
    try {
      process = pb.start();
    } catch (IOException ex) {
      throw new SopsEncryptionException("sops could not be started", ex);
    }
    final CompletableFuture<String> diagnostics =
        CompletableFuture.supplyAsync(() -> drain(process.getErrorStream()));
    final String operation = arguments.isEmpty() ? "" : arguments.get(0);
    try {
      try (var stdin = process.getOutputStream()) {
        stdin.write(stdinDocument.getBytes(StandardCharsets.UTF_8));
      }
      final String document = drain(process.getInputStream());
      final int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new SopsEncryptionException(
            failure(operation, "exited with code " + exitCode, diagnostics.join()));
      }
      return document;
    } catch (IOException ex) {
      // A sops that exits early closes its stdin under us: its reason is on stderr, not in ex.
      throw new SopsEncryptionException(
          failure(
              operation,
              "I/O failed",
              diagnostics.completeOnTimeout("", 5, TimeUnit.SECONDS).join()),
          ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new SopsEncryptionException("interrupted waiting for sops", ex);
    }
  }

  private String failure(String operation, String outcome, String stderr) {
    if (stderrToDebug) {
      log.log(
          System.Logger.Level.DEBUG,
          "sops " + operation + " stderr (" + STDERR_OPT_IN + "): " + stderr);
    }
    return "sops "
        + operation
        + " "
        + outcome
        + " ("
        + StderrClass.of(stderr).label()
        + "; "
        + stderr.lines().count()
        + " stderr lines)";
  }

  private static String drain(InputStream stream) {
    try (stream) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to read sops output", ex);
    }
  }

  /** The sops binary, resolved once — or the refusal {@link #run} raises. */
  private record Binary(Optional<Path> path, String refusal) {

    static Binary resolve(Map<String, String> env) {
      final String configured = env.get(BINARY_ENV);
      if (configured != null && !configured.isBlank()) {
        final Path explicit = Path.of(configured);
        return explicit.isAbsolute() && Files.isExecutable(explicit)
            ? found(explicit)
            : refused(BINARY_ENV + " must name an absolute, executable sops: " + configured);
      }
      for (String dir : env.getOrDefault("PATH", "").split(File.pathSeparator)) {
        if (dir.isBlank()) {
          continue;
        }
        final Path candidate = Path.of(dir, "sops");
        if (!Files.isExecutable(candidate)) {
          continue;
        }
        final Path real;
        try {
          real = candidate.toRealPath();
        } catch (IOException ex) {
          return refused("the first sops on PATH, " + candidate + ", does not resolve");
        }
        return real.startsWith(NIX_STORE)
            ? found(real)
            : refused(
                "the first sops on PATH, "
                    + candidate
                    + ", lies outside /nix/store ("
                    + real
                    + "); refusing a shadowing copy — set "
                    + BINARY_ENV
                    + " to run another one");
      }
      return refused("no sops on PATH (flox activate -- ...), and " + BINARY_ENV + " is not set");
    }

    private static Binary found(Path path) {
      return new Binary(Optional.of(path), "");
    }

    private static Binary refused(String refusal) {
      return new Binary(Optional.empty(), refusal);
    }

    Path orRefuse() {
      return path.orElseThrow(() -> new SopsEncryptionException(refusal));
    }
  }

  /** A fixed label for sops' stderr — words of this class only, never a fragment of the stderr. */
  private enum StderrClass {
    NO_KEY("Failed to get the data key", "no key opens the data key"),
    NOT_SOPS("sops metadata not found", "not a sops document"),
    NO_RULE("no matching creation rules", "no creation rule matches"),
    BAD_POLICY("error loading config", "unreadable policy"),
    UNPARSEABLE("Error unmarshalling", "unparseable input"),
    UNCLASSIFIED("", "unclassified");

    private final String marker;
    private final String label;

    StderrClass(String marker, String label) {
      this.marker = marker;
      this.label = label;
    }

    static StderrClass of(String stderr) {
      for (StderrClass candidate : values()) {
        if (!candidate.marker.isEmpty() && stderr.contains(candidate.marker)) {
          return candidate;
        }
      }
      return UNCLASSIFIED;
    }

    String label() {
      return label;
    }
  }
}
