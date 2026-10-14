package io.seedmatic.rke2lab.sops.edge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * One run of {@code sops} over stdin: the document goes in on stdin, the result comes back on
 * stdout, and nothing touches disk. stdout and stderr stay SEPARATE — stdout carries the document,
 * stderr only diagnostics, and merging them would corrupt it. stderr is drained concurrently: a
 * tool that fills the stderr pipe while we block on stdout would otherwise never exit.
 *
 * <p>{@code env} is laid over the inherited environment, so a key reaches the tool through its
 * environment and never through its command line, which the process table shows.
 *
 * <p>⚠️ A failure NEVER carries sops' stderr: its parsers quote what they failed to read — {@code
 * cannot unmarshal !!str `<value>`} — and what this edge feeds sops is a plaintext to seal or a
 * blob that opens to one. It carries the operation, the exit code, a fixed classification of the
 * stderr and its line count, so every caller is safe by construction. The stderr itself joins the
 * message only when the operator opts in for a debugging run ({@value #STDERR_OPT_IN}{@code
 * =true}).
 *
 * <p><b>Runtime dependency:</b> {@code sops} on {@code PATH}, provided by the rke2lab flox
 * manifest. Always run via {@code flox activate -- ...}.
 */
final class SopsProcess {

  static final String STDERR_OPT_IN = "rke2lab.sops.stderr";

  private final Map<String, String> env;
  private final boolean stderrOptIn = Boolean.getBoolean(STDERR_OPT_IN);

  SopsProcess() {
    this(Map.of());
  }

  SopsProcess(Map<String, String> env) {
    this.env = Map.copyOf(env);
  }

  String run(List<String> arguments, String stdinDocument) {
    final List<String> command = new ArrayList<>();
    command.add("sops");
    command.addAll(arguments);
    final ProcessBuilder pb = new ProcessBuilder(command);
    pb.environment().putAll(env);

    final Process process;
    try {
      process = pb.start();
    } catch (IOException ex) {
      throw new SopsEncryptionException(
          "sops could not be started — is it on PATH? (flox activate -- ...)", ex);
    }
    final CompletableFuture<String> diagnostics =
        CompletableFuture.supplyAsync(() -> drain(process.getErrorStream()));
    try {
      try (var stdin = process.getOutputStream()) {
        stdin.write(stdinDocument.getBytes(StandardCharsets.UTF_8));
      }
      final String document = drain(process.getInputStream());
      final int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new SopsEncryptionException(
            failure(arguments.get(0), "exited with code " + exitCode, diagnostics.join()));
      }
      return document;
    } catch (IOException ex) {
      // A sops that exits early closes its stdin under us: its reason is on stderr, not in ex.
      throw new SopsEncryptionException(
          failure(
              arguments.get(0),
              "I/O failed",
              diagnostics.completeOnTimeout("", 5, TimeUnit.SECONDS).join()),
          ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new SopsEncryptionException("interrupted waiting for sops", ex);
    }
  }

  private String failure(String operation, String outcome, String stderr) {
    return "sops "
        + operation
        + " "
        + outcome
        + " ("
        + classify(stderr)
        + "; "
        + stderr.lines().count()
        + " stderr lines)"
        + (stderrOptIn ? ": " + stderr : "");
  }

  /** A fixed label for sops' stderr — words of this class only, never a fragment of the stderr. */
  private static String classify(String stderr) {
    if (stderr.contains("Failed to get the data key")) {
      return "no key opens the data key";
    }
    if (stderr.contains("sops metadata not found")) {
      return "not a sops document";
    }
    if (stderr.contains("no matching creation rules")) {
      return "no creation rule matches";
    }
    if (stderr.contains("error loading config")) {
      return "unreadable policy";
    }
    if (stderr.contains("Error unmarshalling")) {
      return "unparseable input";
    }
    return "unclassified";
  }

  private static String drain(InputStream stream) {
    try (stream) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
