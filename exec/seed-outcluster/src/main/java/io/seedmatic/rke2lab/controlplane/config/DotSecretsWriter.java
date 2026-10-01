package io.seedmatic.rke2lab.controlplane.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Surgical top-level-block upsert for the sops-governed {@code .secrets} YAML — the host-side
 * writer behind the {@code ghapp seed} operator command ({@link GithubAppCli}). It rewrites only
 * the named block, preserving every other block and its {@code # sops:encrypted} comments verbatim
 * (a whole-file YAML round-trip would strip those comments and silently un-encrypt the other
 * secrets). Leaves named in {@code encryptedLeaves} get a {@code # sops:encrypted} comment (the git
 * sops filter encrypts them at commit); a multi-line value (a PEM) is emitted as a block scalar.
 *
 * <p>This is a WRITE tool the operator invokes by hand — distinct from the {@code SecretsGateway}
 * seam the in-container scion reads through, which stays read-only.
 */
public final class DotSecretsWriter {

  private DotSecretsWriter() {}

  /** Upsert the top-level {@code key} block from ordered {@code leaves} (insertion order kept). */
  public static void upsert(
      Path secretsFile, String key, Map<String, String> leaves, Set<String> encryptedLeaves) {
    try {
      final String existing =
          Files.isReadable(secretsFile)
              ? Files.readString(secretsFile, StandardCharsets.UTF_8)
              : "";
      final String spliced = splice(existing, key, renderBlock(key, leaves, encryptedLeaves));
      Files.writeString(secretsFile, spliced, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to write the secrets file at " + secretsFile, ex);
    }
  }

  private static List<String> renderBlock(
      String key, Map<String, String> leaves, Set<String> encryptedLeaves) {
    final List<String> lines = new ArrayList<>();
    lines.add(key + ":");
    leaves.forEach(
        (name, value) -> {
          if (encryptedLeaves.contains(name)) {
            lines.add("  # sops:encrypted");
          }
          if (value.contains("\n")) {
            lines.add("  " + name + ": |");
            for (final String line : value.stripTrailing().split("\n", -1)) {
              lines.add("    " + line);
            }
          } else {
            lines.add("  " + name + ": " + quote(value));
          }
        });
    return lines;
  }

  /**
   * Replace the existing top-level {@code key:} block (or append it) — every other line verbatim.
   */
  private static String splice(String existing, String key, List<String> block) {
    final List<String> in =
        existing.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(existing.split("\n", -1)));
    final List<String> out = new ArrayList<>();
    boolean replaced = false;
    int i = 0;
    while (i < in.size()) {
      final String line = in.get(i);
      if (line.equals(key + ":") || line.startsWith(key + ":")) {
        out.addAll(block);
        replaced = true;
        i++;
        while (i < in.size()
            && (in.get(i).isBlank() || Character.isWhitespace(in.get(i).charAt(0)))) {
          i++;
        }
        continue;
      }
      out.add(line);
      i++;
    }
    if (!replaced) {
      while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) {
        out.remove(out.size() - 1);
      }
      out.addAll(block);
      out.add("");
    }
    return String.join("\n", out);
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
