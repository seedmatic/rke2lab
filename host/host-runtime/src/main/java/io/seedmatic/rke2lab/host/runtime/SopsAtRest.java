package io.seedmatic.rke2lab.host.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The host's one door to a sops YAML file that stays ENCRYPTED AT REST: {@link #open} decrypts it
 * in memory, {@link #seal} encrypts a plaintext document for it. The plaintext never touches the
 * disk — a decrypted copy in a checkout is what a working-tree evaluation copies into {@code
 * /nix/store}, and on 2026-10-10 such copies reached a public cache. A plaintext file is refused:
 * sops finds no metadata in it, and this class never falls back to reading it raw.
 *
 * <p>Keys come from sops' own lookup ({@code SOPS_AGE_KEY}, {@code SOPS_AGE_KEY_FILE}, the default
 * keys file) in the inherited environment plus {@code env}; the creation rule from the nearest
 * {@code .sops.yaml} above the file.
 */
public final class SopsAtRest {

  private final Map<String, String> env;

  public SopsAtRest() {
    this(Map.of());
  }

  public SopsAtRest(Map<String, String> env) {
    this.env = Map.copyOf(env);
  }

  /**
   * The decrypted document of {@code file}, indented by two spaces. Read from stdin, sops indents
   * by four by default, and a line-based editor of the document (DotSecretsWriter's splice) then no
   * longer finds the block it rewrites.
   */
  public String open(Path file) {
    try {
      return run(
          List.of(
              "--decrypt",
              "--indent",
              "2",
              "--config",
              "/dev/null",
              "--input-type",
              "yaml",
              "--output-type",
              "yaml",
              "/dev/stdin"),
          Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to read " + file, ex);
    }
  }

  /** {@code plaintextYaml} encrypted for {@code file}, under its repository's creation rule. */
  public String seal(Path file, String plaintextYaml) {
    final Path absolute = file.toAbsolutePath().normalize();
    return run(
        List.of(
            "--encrypt",
            "--config",
            nearestConfig(absolute).toString(),
            "--filename-override",
            absolute.toString(),
            "--input-type",
            "yaml",
            "--output-type",
            "yaml",
            "/dev/stdin"),
        plaintextYaml);
  }

  /**
   * The {@code .sops.yaml} of the file's own repository: the search stops at the first directory
   * holding {@code .git}, so a worktree without one never seals under a parent's recipients.
   */
  private static Path nearestConfig(Path file) {
    for (Path dir = file.getParent(); dir != null; dir = dir.getParent()) {
      final Path candidate = dir.resolve(".sops.yaml");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      if (Files.exists(dir.resolve(".git"))) {
        break;
      }
    }
    throw new IllegalStateException(
        "no .sops.yaml in the repository of " + file + ": no creation rule to seal it");
  }

  private String run(List<String> args, String stdin) {
    final List<String> command = new ArrayList<>(List.of("sops"));
    command.addAll(args);
    final ProcessBuilder builder = new ProcessBuilder(command);
    builder.environment().putAll(env);
    final Process process;
    try {
      process = builder.start();
    } catch (IOException ex) {
      throw new IllegalStateException(
          "sops could not be started — is it on PATH? (flox activate -- ...)", ex);
    }
    // stderr is drained concurrently: a sops that fills the stderr pipe while we block on stdout
    // would otherwise deadlock.
    final CompletableFuture<String> diagnostics =
        CompletableFuture.supplyAsync(() -> drain(process.getErrorStream()));
    try {
      try (var in = process.getOutputStream()) {
        in.write(stdin.getBytes(StandardCharsets.UTF_8));
      }
      final String out = drain(process.getInputStream());
      final int exit = process.waitFor();
      if (exit != 0) {
        throw new IllegalStateException(
            "sops " + args.get(0) + " exited with code " + exit + ": " + diagnostics.join());
      }
      return out;
    } catch (IOException ex) {
      throw new UncheckedIOException("sops I/O failed", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting for sops", ex);
    }
  }

  private static String drain(InputStream stream) {
    try (stream) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to read sops output", ex);
    }
  }
}
