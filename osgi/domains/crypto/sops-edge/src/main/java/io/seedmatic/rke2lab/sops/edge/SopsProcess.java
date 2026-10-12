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
 * <p><b>Runtime dependency:</b> {@code sops} on {@code PATH}, provided by the rke2lab flox
 * manifest. Always run via {@code flox activate -- ...}.
 */
final class SopsProcess {

  private final Map<String, String> env;

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
            "sops "
                + arguments.get(0)
                + " exited with code "
                + exitCode
                + ": "
                + diagnostics.join());
      }
      return document;
    } catch (IOException ex) {
      // A sops that exits early closes its stdin under us: its reason is on stderr, not in ex.
      throw new SopsEncryptionException(
          "sops I/O failed: " + diagnostics.completeOnTimeout("", 5, TimeUnit.SECONDS).join(), ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new SopsEncryptionException("interrupted waiting for sops", ex);
    }
  }

  private static String drain(InputStream stream) {
    try (stream) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
