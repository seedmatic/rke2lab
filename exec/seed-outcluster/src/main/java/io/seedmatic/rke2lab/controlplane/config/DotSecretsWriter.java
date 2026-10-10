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
 * Surgical block upsert for the sops-governed {@code .secrets} YAML — the host-side writer behind
 * the {@code ghapp seed} operator command ({@link GithubAppCli}). It rewrites only the block at the
 * given path, preserving every other block and its {@code # sops:encrypted} comments verbatim (a
 * whole-file YAML round-trip would strip those comments and silently un-encrypt the other secrets).
 * Leaves named in {@code encryptedLeaves} get a {@code # sops:encrypted} comment (the git sops
 * filter encrypts them at commit); a multi-line value (a PEM) is emitted as a block scalar.
 *
 * <p>This is a WRITE tool the operator invokes by hand — distinct from the {@code SecretsGateway}
 * seam the in-container scion reads through, which stays read-only.
 */
public final class DotSecretsWriter {

  private DotSecretsWriter() {}

  /**
   * Upsert the block at {@code path} (e.g. {@code github}, {@code app}) from ordered {@code
   * leaves}, insertion order kept. Missing parents are created; siblings stay verbatim.
   */
  public static void upsert(
      Path secretsFile,
      List<String> path,
      Map<String, String> leaves,
      Set<String> encryptedLeaves) {
    try {
      final String existing =
          Files.isReadable(secretsFile)
              ? Files.readString(secretsFile, StandardCharsets.UTF_8)
              : "";
      Files.writeString(
          secretsFile, splice(existing, path, leaves, encryptedLeaves), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to write the secrets file at " + secretsFile, ex);
    }
  }

  private static List<String> renderBlock(
      String key, int depth, Map<String, String> leaves, Set<String> encryptedLeaves) {
    final String indent = "  ".repeat(depth);
    final List<String> lines = new ArrayList<>();
    lines.add(indent + key + ":");
    leaves.forEach(
        (name, value) -> {
          if (encryptedLeaves.contains(name)) {
            lines.add(indent + "  # sops:encrypted");
          }
          if (value.contains("\n")) {
            lines.add(indent + "  " + name + ": |");
            for (final String line : value.stripTrailing().split("\n", -1)) {
              lines.add(indent + "    " + line);
            }
          } else {
            lines.add(indent + "  " + name + ": " + quote(value));
          }
        });
    return lines;
  }

  /**
   * Replace the block at {@code path} (or create it, with its missing parents, at the end of its
   * parent) — every other line verbatim. A block runs from its {@code key:} line to the next line
   * indented at most as deep, blank lines included.
   */
  private static String splice(
      String existing, List<String> path, Map<String, String> leaves, Set<String> encryptedLeaves) {
    final List<String> lines =
        existing.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(existing.split("\n", -1)));
    int from = 0;
    int to = lines.size();
    for (int depth = 0; depth < path.size(); depth++) {
      final String head = "  ".repeat(depth) + path.get(depth) + ":";
      int at = -1;
      for (int i = from; i < to; i++) {
        if (lines.get(i).equals(head) || lines.get(i).startsWith(head + " ")) {
          at = i;
          break;
        }
      }
      if (at < 0) {
        int insert = to;
        while (insert > from && lines.get(insert - 1).isBlank()) {
          insert--;
        }
        final List<String> created = new ArrayList<>();
        for (int d = depth; d < path.size() - 1; d++) {
          created.add("  ".repeat(d) + path.get(d) + ":");
        }
        created.addAll(
            renderBlock(path.get(path.size() - 1), path.size() - 1, leaves, encryptedLeaves));
        lines.addAll(insert, created);
        return String.join("\n", lines);
      }
      int end = at + 1;
      while (end < to && (lines.get(end).isBlank() || indentOf(lines.get(end)) > 2 * depth)) {
        end++;
      }
      from = at;
      to = end;
    }
    final List<String> block =
        renderBlock(path.get(path.size() - 1), path.size() - 1, leaves, encryptedLeaves);
    int end = to;
    while (end > from + 1 && lines.get(end - 1).isBlank()) {
      end--;
    }
    lines.subList(from, end).clear();
    lines.addAll(from, block);
    return String.join("\n", lines);
  }

  private static int indentOf(String line) {
    int n = 0;
    while (n < line.length() && line.charAt(n) == ' ') {
      n++;
    }
    return n;
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
