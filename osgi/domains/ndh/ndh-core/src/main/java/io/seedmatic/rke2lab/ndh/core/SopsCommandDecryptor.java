package io.seedmatic.rke2lab.ndh.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;

/**
 * Opens the inventory in memory by piping it to {@code sops --decrypt}, with sops' own key lookup —
 * the operator identity the git smudge filter used to open it with. The plaintext never touches the
 * disk: a plaintext copy in the worktree is what a dirty-tree flake evaluation copies into {@code
 * /nix/store}, and on 2026-10-10 those copies reached a public cache.
 *
 * <p>Not {@code sops-edge}'s decryptor: that one opens a sealed bundle with an identity the caller
 * holds, this one needs none.
 */
final class SopsCommandDecryptor implements UnaryOperator<String> {

  @Override
  public String apply(final String sopsYaml) {
    final ProcessBuilder pb =
        new ProcessBuilder(
            "sops",
            "--decrypt",
            "--config",
            "/dev/null",
            "--input-type",
            "yaml",
            "--output-type",
            "yaml",
            "/dev/stdin");
    final Process process;
    try {
      process = pb.start();
    } catch (IOException ex) {
      throw new IllegalStateException(
          "sops could not be started — is it on PATH? (flox activate -- ...)", ex);
    }
    try {
      try (var stdin = process.getOutputStream()) {
        stdin.write(sopsYaml.getBytes(StandardCharsets.UTF_8));
      }
      final String plaintext;
      try (var stdout = process.getInputStream()) {
        plaintext = new String(stdout.readAllBytes(), StandardCharsets.UTF_8);
      }
      final String diagnostics;
      try (var stderr = process.getErrorStream()) {
        diagnostics = new String(stderr.readAllBytes(), StandardCharsets.UTF_8);
      }
      final int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new IllegalStateException(
            "sops --decrypt exited with code " + exitCode + ": " + diagnostics);
      }
      return plaintext;
    } catch (IOException ex) {
      throw new IllegalStateException("sops I/O failed", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting for sops", ex);
    }
  }
}
